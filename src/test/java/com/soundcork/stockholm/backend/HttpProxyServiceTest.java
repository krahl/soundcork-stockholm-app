package com.soundcork.stockholm.backend;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled
final class HttpProxyServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void successfulLoginStoresAccountIdAndCredentials() throws Exception {
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(
                request -> request.uri().getPath().endsWith("/streaming/account/login"),
                TestHttpResponse.xml(
                        200,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <account id="abc123"><accountStatus>ACTIVE</accountStatus></account>
                                """,
                        Map.of("Credentials", List.of("Bearer new-token"))));

        TestContext context = createContext();
        HttpProxyService service = new HttpProxyService(httpClient, context.dataService());

        TestHttpExchange exchange = TestHttpExchange.post(
                proxyUri("https://streaming.bose.com/streaming/account/login"),
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <login><username>user@example.com</username><password>pw</password></login>
                        """,
                new Headers());

        service.handle(exchange);

        assertEquals(200, exchange.statusCode());
        assertEquals("abc123", context.bridgeService().getStateValue("margeAccountID"));
        assertEquals("Bearer new-token", context.bridgeService().getStateValue("margeAuthToken"));
    }

    @Test
    void failedLoginDoesNotOverwriteExistingSession() throws Exception {
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(
                request -> request.uri().getPath().endsWith("/streaming/account/login"),
                TestHttpResponse.xml(
                        400,
                        """
                                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                                <status><message>Account Login failure.</message><status-code>4024</status-code></status>
                                """,
                        Map.of()));

        TestContext context = createContext();
        context.bridgeService().putStateValues(Map.of(
                "margeAccountID", "existing-id",
                "margeAuthToken", "Bearer existing"));
        HttpProxyService service = new HttpProxyService(httpClient, context.dataService());

        TestHttpExchange exchange = TestHttpExchange.post(
                proxyUri("https://streaming.bose.com/streaming/account/login"),
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <login><username>user@example.com</username><password>pw</password></login>
                        """,
                new Headers());

        service.handle(exchange);

        assertEquals(400, exchange.statusCode());
        assertEquals("existing-id", context.bridgeService().getStateValue("margeAccountID"));
        assertEquals("Bearer existing", context.bridgeService().getStateValue("margeAuthToken"));
    }

    @Test
    void loginRequestsToApigeeReceiveFrontendHeadersWithoutStoredAuthorization() throws Exception {
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(
                request -> request.uri().getPath().endsWith("/streaming/account/login"),
                TestHttpResponse.xml(
                        400,
                        """
                                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                                <status><message>Account Login failure.</message><status-code>4024</status-code></status>
                                """,
                        Map.of()));

        TestContext context = createContext();
        context.bridgeService().putStateValues(Map.of(
                "authServer", "2",
                "guid", "stored-guid",
                "nativeFrameVersion", "27.0.8",
                "margeAuthToken", "Bearer stored-token"));
        HttpProxyService service = new HttpProxyService(httpClient, context.dataService());

        TestHttpExchange exchange = TestHttpExchange.post(
                proxyUri("https://bose-test.apigee.net/margeproxyefe/streaming/account/login"),
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <login><username>user@example.com</username><password>pw</password></login>
                        """,
                new Headers());

        service.handle(exchange);

        HttpRequest request = httpClient.recordedRequests().get(0);
        assertEquals(400, exchange.statusCode());
        assertEquals("application/vnd.bose.streaming-v1.1+xml", request.headers().firstValue("Accept").orElseThrow());
        assertEquals("application/vnd.bose.streaming-v1.1+xml", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("SOUNDTOUCH_COMPUTER_APP", request.headers().firstValue("ClientType").orElseThrow());
        assertEquals("stored-guid", request.headers().firstValue("GUID").orElseThrow());
        assertEquals("27.0.8", request.headers().firstValue("version_NativeFrameVersion").orElseThrow());
        assertEquals("67", request.headers().firstValue("version_ProtocolVersion").orElseThrow());
        assertEquals(
                "9qGTcX09V26TsG5yDwdfSOWsJDAZ7xrJ",
                request.headers().firstValue("X-Bose-Apigee-Key").orElseThrow());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
    }

    @Test
    void login4033TriggersEnvironmentLookupAndSingleRetry() throws Exception {
        TestHttpClient httpClient = new TestHttpClient();
        AtomicInteger loginCount = new AtomicInteger();
        httpClient.enqueueResponse(
                request -> request.uri().getPath().endsWith("/streaming/account/login") && loginCount.getAndIncrement() == 0,
                TestHttpResponse.xml(
                        400,
                        """
                                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                                <status><message>Switch environment.</message><status-code>4033</status-code></status>
                                """,
                        Map.of()));
        httpClient.enqueueResponse(
                request -> request.uri().getPath().contains("/streaming/account/email/user%40example.com/environment"),
                TestHttpResponse.xml(
                        200,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <account_profile>
                                  <streamingURL>https://alt-streaming.bose.com/</streamingURL>
                                  <updateURL>https://updates-alt.bose.com/</updateURL>
                                </account_profile>
                                """,
                        Map.of()));
        httpClient.enqueueResponse(
                request -> request.uri().getHost().equals("alt-streaming.bose.com")
                        && request.uri().getPath().endsWith("/streaming/account/login"),
                TestHttpResponse.xml(
                        200,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <account id="switched-account"><accountStatus>ACTIVE</accountStatus></account>
                                """,
                        Map.of("Credentials", List.of("Bearer switched-token"))));

        TestContext context = createContext();
        HttpProxyService service = new HttpProxyService(httpClient, context.dataService());

        TestHttpExchange exchange = TestHttpExchange.post(
                proxyUri("https://streaming.bose.com/streaming/account/login"),
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <login><username>user@example.com</username><password>pw</password></login>
                        """,
                new Headers());

        service.handle(exchange);

        assertEquals(200, exchange.statusCode());
        assertEquals("https://alt-streaming.bose.com/", context.bridgeService().getStateValue("overrideMargeURL"));
        assertEquals("https://updates-alt.bose.com/", context.bridgeService().getStateValue("overrideUpdateURL"));
        assertEquals("switched-account", context.bridgeService().getStateValue("margeAccountID"));
        assertEquals("Bearer switched-token", context.bridgeService().getStateValue("margeAuthToken"));
        assertEquals(3, httpClient.recordedRequests().size());
        HttpRequest environmentRequest = httpClient.recordedRequests().get(1);
        assertTrue(environmentRequest.uri().getPath().contains("/streaming/account/email/user%40example.com/environment"));
        assertEquals(
                "Basic dXNlckBleGFtcGxlLmNvbTpwdw==",
                environmentRequest.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void login4033KeepsApigeePathPrefixDuringEnvironmentLookupAndRetry() throws Exception {
        TestHttpClient httpClient = new TestHttpClient();
        AtomicInteger loginCount = new AtomicInteger();
        httpClient.enqueueResponse(
                request -> request.uri().getPath().endsWith("/streaming/account/login") && loginCount.getAndIncrement() == 0,
                TestHttpResponse.xml(
                        400,
                        """
                                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                                <status><message>Switch environment.</message><status-code>4033</status-code></status>
                                """,
                        Map.of()));
        httpClient.enqueueResponse(
                request -> request.uri().getPath().contains("/margeproxyefe/streaming/account/email/user%40example.com/environment"),
                TestHttpResponse.xml(
                        200,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <account_profile>
                                  <streamingURL>https://alt-efe.bose.com/</streamingURL>
                                  <updateURL>https://updates-efe.bose.com/</updateURL>
                                </account_profile>
                                """,
                        Map.of()));
        httpClient.enqueueResponse(
                request -> request.uri().getHost().equals("alt-efe.bose.com")
                        && request.uri().getPath().endsWith("/margeproxyefe/streaming/account/login"),
                TestHttpResponse.xml(
                        200,
                        """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <account id="efe-account"><accountStatus>ACTIVE</accountStatus></account>
                                """,
                        Map.of("Credentials", List.of("Bearer efe-token"))));

        TestContext context = createContext();
        context.bridgeService().putStateValue("authServer", "2");
        HttpProxyService service = new HttpProxyService(httpClient, context.dataService());

        TestHttpExchange exchange = TestHttpExchange.post(
                proxyUri("https://bose-test.apigee.net/margeproxyefe/streaming/account/login"),
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <login><username>user@example.com</username><password>pw</password></login>
                        """,
                new Headers());

        service.handle(exchange);

        assertEquals(200, exchange.statusCode());
        assertEquals("https://alt-efe.bose.com/", context.bridgeService().getStateValue("overrideMargeURL"));
        assertEquals("https://updates-efe.bose.com/", context.bridgeService().getStateValue("overrideUpdateURL"));
        assertEquals("efe-account", context.bridgeService().getStateValue("margeAccountID"));
        assertEquals("Bearer efe-token", context.bridgeService().getStateValue("margeAuthToken"));
        assertEquals(3, httpClient.recordedRequests().size());
        HttpRequest environmentRequest = httpClient.recordedRequests().get(1);
        assertEquals(
                "/margeproxyefe/streaming/account/email/user%40example.com/environment",
                environmentRequest.uri().getPath());
        HttpRequest retriedLogin = httpClient.recordedRequests().get(2);
        assertEquals("/margeproxyefe/streaming/account/login", retriedLogin.uri().getPath());
    }

    @Test
    void injectsFrontendStyleMargeHeadersAndStoredAuthorization() throws Exception {
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(
                request -> true,
                TestHttpResponse.xml(200, "<ok/>", Map.of("Refresh", List.of("Bearer refreshed"))));

        TestContext context = createContext();
        context.bridgeService().putStateValues(Map.of(
                "margeAuthToken", "Bearer stored-token",
                "guid", "stored-guid",
                "nativeFrameVersion", "27.0.8"));
        HttpProxyService service = new HttpProxyService(httpClient, context.dataService());

        TestHttpExchange exchange = TestHttpExchange.get(
                proxyUri("https://streaming.bose.com/streaming/account/42/sources"),
                new Headers());

        service.handle(exchange);

        HttpRequest request = httpClient.recordedRequests().get(0);
        assertEquals("application/vnd.bose.streaming-v1.1+xml", request.headers().firstValue("Accept").orElseThrow());
        assertEquals("application/vnd.bose.streaming-v1.1+xml", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("SOUNDTOUCH_COMPUTER_APP", request.headers().firstValue("ClientType").orElseThrow());
        assertEquals("stored-guid", request.headers().firstValue("GUID").orElseThrow());
        assertEquals("27.0.8", request.headers().firstValue("version_NativeFrameVersion").orElseThrow());
        assertEquals(
                "27.0.8-4256+4fc1c92.epdbuild.develop.hepdswbld05.2022-01-25T12:27:07",
                request.headers().firstValue("version_StockholmVersion").orElseThrow());
        assertEquals("67", request.headers().firstValue("version_ProtocolVersion").orElseThrow());
        assertEquals("Bearer stored-token", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer refreshed", context.bridgeService().getStateValue("margeAuthToken"));
    }

    @Test
    void nullLikeHeadersDoNotBlockBackendFallbackInjection() throws Exception {
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(request -> true, TestHttpResponse.xml(200, "<ok/>", Map.of()));

        TestContext context = createContext();
        context.bridgeService().putStateValues(Map.of(
                "margeAuthToken", "Bearer stored-token",
                "guid", "stored-guid",
                "nativeFrameVersion", "27.0.8"));
        HttpProxyService service = new HttpProxyService(httpClient, context.dataService());

        Headers headers = new Headers();
        headers.add("Authorization", "null");
        headers.add("GUID", "");
        headers.add("version_NativeFrameVersion", "null");

        TestHttpExchange exchange = TestHttpExchange.get(
                proxyUri("https://streaming.bose.com/streaming/account/42/sources"),
                headers);

        service.handle(exchange);

        HttpRequest request = httpClient.recordedRequests().get(0);
        assertEquals("Bearer stored-token", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("stored-guid", request.headers().firstValue("GUID").orElseThrow());
        assertEquals("27.0.8", request.headers().firstValue("version_NativeFrameVersion").orElseThrow());
    }

    @Test
    void injectsBmxHeadersForEncryptedToken() throws Exception {
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(request -> true, TestHttpResponse.xml(200, "<ok/>", Map.of()));

        TestContext context = createContext();
        HttpProxyService service = new HttpProxyService(httpClient, context.dataService());

        TestHttpExchange exchange = TestHttpExchange.get(
                proxyUri("https://content.api.bose.io/bmx/registry/v1/services"),
                new Headers());

        service.handle(exchange);

        HttpRequest request = httpClient.recordedRequests().get(0);
        assertTrue(request.headers().firstValue("x-bmx-api-key").isPresent());
        assertEquals(
                "27.0.8-4256+4fc1c92.epdbuild.develop.hepdswbld05.2022-01-25T12:27:07",
                request.headers().firstValue("x-software-version").orElseThrow());
    }

    @Test
    void seedsBrowserRuntimeStateWhenNativeStateIsEmpty() throws Exception {
        TestContext context = createContext();

        assertNotNull(context.bridgeService().getStateValue("guid"));
        assertNotNull(context.bridgeService().getStateValue("deviceGuid"));
        assertEquals("27.0.8", context.bridgeService().getStateValue("nativeFrameVersion"));
        assertEquals(
                "27.0.8-4256+4fc1c92.epdbuild.develop.hepdswbld05.2022-01-25T12:27:07",
                context.bridgeService().getStateValue("frame_version"));
        assertEquals("0", context.bridgeService().getStateValue("authServer"));
    }

    @Test
    void authServerFrameConfigInjectsLegacyMargeServerKeyForApigeeTargets() throws Exception {
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(request -> true, TestHttpResponse.xml(200, "<ok/>", Map.of()));

        TestContext context = createContext();
        context.bridgeService().putStateValues(Map.of(
                "authServer", "2",
                "guid", "stored-guid",
                "nativeFrameVersion", "27.0.8"));
        HttpProxyService service = new HttpProxyService(httpClient, context.dataService());

        TestHttpExchange exchange = TestHttpExchange.get(
                proxyUri("https://bose-test.apigee.net/margeproxyefe/streaming/account/42/sources"),
                new Headers());

        service.handle(exchange);

        HttpRequest request = httpClient.recordedRequests().get(0);
        assertEquals("stored-guid", request.headers().firstValue("GUID").orElseThrow());
        assertEquals(
                "9qGTcX09V26TsG5yDwdfSOWsJDAZ7xrJ",
                request.headers().firstValue("X-Bose-Apigee-Key").orElseThrow());
    }

    private TestContext createContext() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Path stockholmJson = workspaceRoot.resolve("stockholm").resolve("json");
        Path backendState = workspaceRoot.resolve("backend").resolve("state");
        Files.createDirectories(stockholmJson);
        Files.createDirectories(backendState);
        Files.writeString(stockholmJson.resolve("config.json"), """
                {
                  "app_versions": {
                    "bose_app": "27.0.8-4256+4fc1c92.epdbuild.develop.hepdswbld05.2022-01-25T12:27:07",
                    "bose_protocol": "67"
                  },
                  "api_versions": {
                    "bose_streaming": "1.1",
                    "bose_customer": "1.0"
                  },
                  "default": {
                    "d0": "aHR0cHM6Ly9zdHJlYW1pbmcuYm9zZS5jb20v",
                    "d1": "aHR0cHM6Ly93b3JsZHdpZGUuYm9zZS5jb20vdXBkYXRlcy9zb3VuZHRvdWNo",
                    "d7": "OGNhZWI1YjI0ZjQ0YTZlOGExOTdjYmEwZjFhYmQ0Y2VlN2ZmYzc1M2JhOWIyMzdiOWEwOWQ1ZDhjNGI1ZTYwN2E3Zjk2ZWVhOGU5OGFkNGI4MjY5OTM3MzM2YzhjZTFl",
                    "d8": "ZjY1Y2FlM2M4MGQ5Nzg5Yjg1Mzk1ZGRjNjg0YzVhYjJkMjIzYWUyZTM4NDQwNTY4Y2M2MGRkOTljMGY5YzdhMmNmMTczMjUyMGYyMTgzZmQ3ZDQzMjFkNGUzNmJkMDUzZTI1YTgxOTViNjVlNTM0NDdhNzVlY2ExZWRhZjg0ZmE=",
                    "d13": "WC1Cb3NlLUFwaWdlZS1LZXk="
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(stockholmJson.resolve("override.json"), """
                {"kilo":"a7928d7b43dcd49f0af31e5aeed26458"}
                """, StandardCharsets.UTF_8);

        Path stateFile = backendState.resolve("native-state.json");
        NativeBridgeService bridgeService = new NativeBridgeService(stateFile);
        SoundcorkDataService dataService = new SoundcorkDataService(workspaceRoot, bridgeService);
        return new TestContext(bridgeService, dataService);
    }

    private URI proxyUri(String target) {
        return URI.create("http://localhost:8088/api/http-proxy?url="
                + java.net.URLEncoder.encode(target, StandardCharsets.UTF_8));
    }

    private record TestContext(NativeBridgeService bridgeService, SoundcorkDataService dataService) {
    }

    private static final class TestHttpExchange extends HttpExchange {
        private final Headers requestHeaders;
        private final Headers responseHeaders = new Headers();
        private final URI requestUri;
        private final String method;
        private final ByteArrayInputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int statusCode = -1;

        private TestHttpExchange(String method, URI requestUri, byte[] requestBody, Headers headers) {
            this.method = method;
            this.requestUri = requestUri;
            this.requestBody = new ByteArrayInputStream(requestBody);
            this.requestHeaders = headers;
        }

        static TestHttpExchange get(URI requestUri, Headers headers) {
            return new TestHttpExchange("GET", requestUri, new byte[0], headers);
        }

        static TestHttpExchange post(URI requestUri, String requestBody, Headers headers) {
            return new TestHttpExchange("POST", requestUri, requestBody.getBytes(StandardCharsets.UTF_8), headers);
        }

        int statusCode() {
            return statusCode;
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return requestUri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.statusCode = rCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return statusCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8088);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    private static final class TestHttpClient extends HttpClient {
        private final ArrayDeque<ResponseRule> rules = new ArrayDeque<>();
        private final ArrayList<HttpRequest> recordedRequests = new ArrayList<>();

        void enqueueResponse(RequestMatcher matcher, TestHttpResponse response) {
            rules.addLast(new ResponseRule(matcher, response));
        }

        List<HttpRequest> recordedRequests() {
            return recordedRequests;
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            recordedRequests.add(request);
            ResponseRule rule = rules.stream()
                    .filter(candidate -> candidate.matcher().matches(request))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No response rule matched " + request.uri()));
            rules.remove(rule);
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) rule.response().toHttpResponse(request);
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        private record ResponseRule(RequestMatcher matcher, TestHttpResponse response) {
        }
    }

    private interface RequestMatcher {
        boolean matches(HttpRequest request);
    }

    private record TestHttpResponse(int statusCode, byte[] body, Map<String, List<String>> headers) {
        static TestHttpResponse xml(int statusCode, String body, Map<String, List<String>> headers) {
            return new TestHttpResponse(statusCode, body.getBytes(StandardCharsets.UTF_8), headers);
        }

        HttpResponse<byte[]> toHttpResponse(HttpRequest request) {
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return statusCode;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<byte[]>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(headers, (name, value) -> true);
                }

                @Override
                public byte[] body() {
                    return body;
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            };
        }
    }
}
