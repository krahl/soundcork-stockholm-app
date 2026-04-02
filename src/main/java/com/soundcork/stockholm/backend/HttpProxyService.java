package com.soundcork.stockholm.backend;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HttpProxyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpProxyService.class);

    private static final Set<String> BLOCKED_REQUEST_HEADERS = Set.of(
            "access-control-request-headers",
            "access-control-request-method",
            "connection",
            "content-length",
            "cookie",
            "forwarded",
            "host",
            "http2-settings",
            "keep-alive",
            "origin",
            "proxy-authenticate",
            "proxy-authorization",
            "referer",
            "sec-ch-ua",
            "sec-ch-ua-mobile",
            "sec-ch-ua-platform",
            "sec-fetch-dest",
            "sec-fetch-mode",
            "sec-fetch-site",
            "sec-fetch-user",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-port",
            "x-forwarded-proto",
            "x-real-ip",
            "x-requested-with");

    private static final Set<String> BLOCKED_RESPONSE_HEADERS = Set.of(
            "access-control-allow-credentials",
            "access-control-allow-headers",
            "access-control-allow-methods",
            "access-control-allow-origin",
            "access-control-expose-headers",
            "access-control-max-age",
            "connection",
            "content-length",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "set-cookie",
            "set-cookie2",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final SoundcorkDataService soundcorkDataService;
    private final HttpClient httpClient;

    HttpProxyService(SoundcorkDataService soundcorkDataService) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build(), soundcorkDataService);
    }

    HttpProxyService(HttpClient httpClient, SoundcorkDataService soundcorkDataService) {
        this.httpClient = httpClient;
        this.soundcorkDataService = soundcorkDataService;
    }

    void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String encodedTarget = queryParam(exchange, "url");
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(
                    "Proxy request received from frontend: method={}, requestUri={}, encodedTarget={}, remoteAddress={}, headers={}",
                    method,
                    exchange.getRequestURI(),
                    encodedTarget,
                    exchange.getRemoteAddress(),
                    formatHeaders(exchange.getRequestHeaders()));
        }
        if (encodedTarget == null || encodedTarget.isBlank()) {
            BackendApplication.sendText(exchange, 400, "Missing url query parameter", "text/plain; charset=UTF-8");
            return;
        }

        URI target;
        try {
            target = URI.create(URLDecoder.decode(encodedTarget, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            LOGGER.debug("Rejected malformed proxy target '{}'", encodedTarget, exception);
            BackendApplication.sendText(exchange, 400, "Invalid proxy target", "text/plain; charset=UTF-8");
            return;
        }

        if (!isSupportedTarget(target)) {
            LOGGER.debug("Rejected unsupported proxy target {}", target);
            BackendApplication.sendText(exchange, 400, "Unsupported proxy target", "text/plain; charset=UTF-8");
            return;
        }

        if (isProxyLoop(exchange, target)) {
            LOGGER.debug("Rejected proxy loop for target {}", target);
            BackendApplication.sendText(exchange, 400, "Refusing to proxy proxy endpoint", "text/plain; charset=UTF-8");
            return;
        }

        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        try {
            RequestOutcome outcome = executeWithCloudHandling(method, exchange.getRequestHeaders(), target, requestBody);
            relayResponse(exchange, method, outcome.response());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while proxying {} to {}", method, target, exception);
            BackendApplication.sendText(exchange, 502, "Proxy request interrupted", "text/plain; charset=UTF-8");
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed proxy request {} {}", method, target, exception);
            BackendApplication.sendText(exchange, 502, "Proxy request failed", "text/plain; charset=UTF-8");
        }
    }

    private boolean isSupportedTarget(URI target) {
        if (target == null || target.getHost() == null) {
            return false;
        }
        String scheme = target.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private boolean isProxyLoop(HttpExchange exchange, URI target) {
        String targetPath = target.getPath();
        if (targetPath == null || !targetPath.startsWith("/api/http-proxy")) {
            return false;
        }

        String host = target.getHost();
        InetSocketAddress localAddress = exchange.getLocalAddress();
        String localHost = localAddress.getAddress() == null ? null : localAddress.getAddress().getHostAddress();
        String localName = localAddress.getHostString();
        int localPort = localAddress.getPort();
        int targetPort = target.getPort() >= 0 ? target.getPort() : defaultPort(target);

        // Direct match: target points at the server's own bound address/port
        if (targetPort == localPort) {
            if (host.equalsIgnoreCase(localName)
                    || host.equalsIgnoreCase("localhost")
                    || (localHost != null && host.equalsIgnoreCase(localHost))
                    || host.equals("127.0.0.1")
                    || host.equals("::1")) {
                return true;
            }
        }

        // Reverse-proxy match: target points at the externally visible host/port
        // (e.g. the app sits behind nginx at https://stapp.example.com:443 but binds on :8080)
        String externalHost = resolveExternalHost(exchange.getRequestHeaders());
        int externalPort = resolveExternalPort(exchange.getRequestHeaders());
        if (externalHost != null && !externalHost.isBlank()
                && host.equalsIgnoreCase(externalHost)
                && targetPort == externalPort) {
            return true;
        }

        return false;
    }

    private String resolveExternalHost(Headers requestHeaders) {
        // X-Forwarded-Host is set by reverse proxies and reflects the original Host
        String forwardedHost = firstRequestHeader(requestHeaders, "x-forwarded-host");
        if (forwardedHost != null && !forwardedHost.isBlank()) {
            // May contain a port (e.g. "example.com:8443") — strip it
            int colonIdx = forwardedHost.lastIndexOf(':');
            return colonIdx >= 0 ? forwardedHost.substring(0, colonIdx) : forwardedHost;
        }
        // Fall back to the Host header (also reflects the public hostname when behind a proxy)
        String hostHeader = firstRequestHeader(requestHeaders, "host");
        if (hostHeader != null && !hostHeader.isBlank()) {
            int colonIdx = hostHeader.lastIndexOf(':');
            return colonIdx >= 0 ? hostHeader.substring(0, colonIdx) : hostHeader;
        }
        return null;
    }

    private int resolveExternalPort(Headers requestHeaders) {
        // Explicit forwarded-port header takes highest priority
        String forwardedPort = firstRequestHeader(requestHeaders, "x-forwarded-port");
        if (forwardedPort != null && !forwardedPort.isBlank()) {
            try {
                return Integer.parseInt(forwardedPort.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        // Port embedded in X-Forwarded-Host (e.g. "example.com:8443")
        String forwardedHost = firstRequestHeader(requestHeaders, "x-forwarded-host");
        if (forwardedHost != null) {
            int colonIdx = forwardedHost.lastIndexOf(':');
            if (colonIdx >= 0) {
                try {
                    return Integer.parseInt(forwardedHost.substring(colonIdx + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // Port embedded in Host header
        String hostHeader = firstRequestHeader(requestHeaders, "host");
        if (hostHeader != null) {
            int colonIdx = hostHeader.lastIndexOf(':');
            if (colonIdx >= 0) {
                try {
                    return Integer.parseInt(hostHeader.substring(colonIdx + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // Derive from the forwarded protocol
        String proto = firstRequestHeader(requestHeaders, "x-forwarded-proto");
        return "https".equalsIgnoreCase(proto) ? 443 : 80;
    }

    private String firstRequestHeader(Headers headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = sanitizeHeaderValues(entry.getValue());
                if (!values.isEmpty()) {
                    return values.get(0);
                }
            }
        }
        return null;
    }

    private int defaultPort(URI target) {
        return "https".equalsIgnoreCase(target.getScheme()) ? 443 : 80;
    }

    private HttpRequest.BodyPublisher bodyPublisher(String method, byte[] requestBody) {
        if ("GET".equals(method) || "HEAD".equals(method) || requestBody.length == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofByteArray(requestBody);
    }

    private RequestOutcome executeWithCloudHandling(String method, Headers requestHeaders, URI target, byte[] requestBody)
            throws IOException, InterruptedException {
        RequestOutcome outcome = executeRequest(method, requestHeaders, target, requestBody);

        if (isLoginRequest(method, outcome.target())) {
            outcome = retryLoginWithEnvironmentIfNeeded(method, requestHeaders, outcome, requestBody);
            captureSuccessfulLogin(outcome.response());
        }

        captureRefreshedAuthToken(outcome.target(), outcome.response());
        return outcome;
    }

    private RequestOutcome retryLoginWithEnvironmentIfNeeded(
            String method,
            Headers requestHeaders,
            RequestOutcome outcome,
            byte[] requestBody) throws IOException, InterruptedException {
        if (!"4033".equals(SoundcorkCloudXml.extractStatusCode(outcome.response().body()))) {
            return outcome;
        }

        SoundcorkCloudXml.LoginCredentials credentials = SoundcorkCloudXml.parseLoginRequest(requestBody);
        if (credentials == null) {
            LOGGER.debug("Cannot retry login after 4033 because login credentials could not be parsed");
            return outcome;
        }

        SoundcorkCloudXml.EnvironmentInfo environment = fetchEnvironment(requestHeaders, outcome.target(), credentials);
        if (environment == null || environment.streamingUrl() == null || environment.streamingUrl().isBlank()) {
            LOGGER.debug("Cannot retry login after 4033 because no valid environment payload was returned");
            return outcome;
        }

        soundcorkDataService.storeOverrideUrls(environment.streamingUrl(), environment.updateUrl());
        URI retryTarget = soundcorkDataService.buildUriFromBase(environment.streamingUrl(), outcome.target().getPath(), outcome.target().getQuery());
        if (retryTarget == null) {
            LOGGER.debug("Cannot retry login after 4033 because retry target could not be built");
            return outcome;
        }

        LOGGER.info("Retrying login against switched environment {}", retryTarget);
        return executeRequest(method, requestHeaders, retryTarget, requestBody);
    }

    private SoundcorkCloudXml.EnvironmentInfo fetchEnvironment(
            Headers requestHeaders,
            URI loginTarget,
            SoundcorkCloudXml.LoginCredentials credentials) throws IOException, InterruptedException {
        String encodedEmail = URLEncoder.encode(credentials.email(), StandardCharsets.UTF_8);
        String pathPrefix = margePathPrefix(loginTarget);
        URI environmentTarget = soundcorkDataService.buildUriFromBase(
                loginTarget.getScheme() + "://" + loginTarget.getAuthority() + "/",
                pathPrefix + "/streaming/account/email/" + encodedEmail + "/environment",
                null);
        if (environmentTarget == null) {
            return null;
        }

        Headers environmentHeaders = cloneHeaders(requestHeaders);
        environmentHeaders.set("Authorization", basicAuth(credentials));
        RequestOutcome environmentOutcome = executeRequest("GET", environmentHeaders, environmentTarget, new byte[0]);
        if (environmentOutcome.response().statusCode() != 200) {
            LOGGER.debug("Environment lookup for login returned HTTP {}", environmentOutcome.response().statusCode());
            return null;
        }
        return SoundcorkCloudXml.extractEnvironment(environmentOutcome.response().body());
    }

    private String margePathPrefix(URI target) {
        if (target == null || target.getPath() == null) {
            return "";
        }
        String path = target.getPath();
        int streamingIndex = path.toLowerCase(Locale.ROOT).indexOf("/streaming/");
        if (streamingIndex <= 0) {
            return "";
        }
        return path.substring(0, streamingIndex);
    }

    private RequestOutcome executeRequest(String method, Headers requestHeaders, URI target, byte[] requestBody)
            throws IOException, InterruptedException {
        URI effectiveTarget = soundcorkDataService.overrideTarget(target);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(effectiveTarget)
                .timeout(REQUEST_TIMEOUT)
                .method(method, bodyPublisher(method, requestBody));
        HeaderCopy requestHeaderCopy = copyRequestHeaders(requestHeaders, requestBuilder);
        Map<String, List<String>> backendInjectedHeaders = applyBackendHeaders(requestHeaders, effectiveTarget, requestBuilder);

        LOGGER.debug("Proxying {} request to {}", method, effectiveTarget);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(
                    "Proxy request forwarded to target: method={}, target={}, requestBodyBytes={}, forwardedHeaders={}, backendInjectedHeaders={}, droppedHeaders={}",
                    method,
                    effectiveTarget,
                    requestBody.length,
                    formatHeaders(requestHeaderCopy.forwarded()),
                    formatHeaders(backendInjectedHeaders),
                    formatHeaders(requestHeaderCopy.blocked()));
        }

        HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            String bodySnippet = response.body() != null
                    ? new String(response.body(), 0, Math.min(response.body().length, 2048), StandardCharsets.UTF_8)
                    : "<empty>";
            LOGGER.warn("Server returned HTTP {} for {} {} — response body: {}",
                    response.statusCode(), method, effectiveTarget, bodySnippet);
        } else if (response.statusCode() >= 500) {
            LOGGER.warn("Server returned HTTP {} for {} {}", response.statusCode(), method, effectiveTarget);
        }
        return new RequestOutcome(effectiveTarget, response);
    }

    private HeaderCopy copyRequestHeaders(Headers requestHeaders, HttpRequest.Builder requestBuilder) {
        LinkedHashMap<String, List<String>> forwarded = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> blocked = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : requestHeaders.entrySet()) {
            String name = entry.getKey();
            List<String> values = sanitizeHeaderValues(entry.getValue());
            if (name == null || BLOCKED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                if (name != null) {
                    blocked.put(name, values);
                }
                continue;
            }
            if (values.isEmpty()) {
                continue;
            }
            forwarded.put(name, values);
            for (String value : values) {
                requestBuilder.header(name, value);
            }
        }
        return new HeaderCopy(forwarded, blocked);
    }

    private Map<String, List<String>> applyBackendHeaders(
            Headers requestHeaders,
            URI target,
            HttpRequest.Builder requestBuilder) {
        LinkedHashMap<String, List<String>> injected = new LinkedHashMap<>();
        String host = target.getHost();
        String path = target.getPath();

        if (soundcorkDataService.isBmxTarget(host)) {
            injectIfMissing(requestHeaders, requestBuilder, injected, "x-bmx-api-key", soundcorkDataService.bmxApiKey());
            injectIfMissing(requestHeaders, requestBuilder, injected, "x-software-version", soundcorkDataService.soundcorkAppVersion());
        }

        if (soundcorkDataService.isMargeTarget(host, path)) {
            String mediaType = soundcorkDataService.mediaTypeForPath(path);
            injectIfMissing(requestHeaders, requestBuilder, injected, "Accept", mediaType);
            injectIfMissing(requestHeaders, requestBuilder, injected, "Content-Type", mediaType);
            injectIfMissing(requestHeaders, requestBuilder, injected, "ClientType", soundcorkDataService.defaultClientType());
            injectIfMissing(requestHeaders, requestBuilder, injected, "GUID", soundcorkDataService.guid());
            injectIfMissing(requestHeaders, requestBuilder, injected, "version_NativeFrameVersion", soundcorkDataService.nativeFrameVersion());
            injectIfMissing(requestHeaders, requestBuilder, injected, "version_StockholmVersion", soundcorkDataService.soundcorkAppVersion());
            injectIfMissing(requestHeaders, requestBuilder, injected, "version_ProtocolVersion", soundcorkDataService.protocolVersion());
            if (soundcorkDataService.margeServerKeyHeader() != null) {
                injectIfMissing(
                        requestHeaders,
                        requestBuilder,
                        injected,
                        soundcorkDataService.margeServerKeyHeader(),
                        soundcorkDataService.margeServerKey());
            }
            if (shouldInjectStoredAuth(path)) {
                injectIfMissing(requestHeaders, requestBuilder, injected, "Authorization", soundcorkDataService.margeAuthToken());
            }
        }

        return injected;
    }

    private boolean shouldInjectStoredAuth(String path) {
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (normalizedPath.endsWith("/streaming/account/login")) {
            return false;
        }
        if (normalizedPath.equals("/streaming/account") || normalizedPath.equals("/streaming/account/")) {
            return false;
        }
        if (normalizedPath.contains("/streaming/account/email/") && normalizedPath.endsWith("/environment")) {
            return false;
        }
        return !normalizedPath.startsWith("/customer/account/password/email/");
    }

    private void injectIfMissing(
            Headers requestHeaders,
            HttpRequest.Builder requestBuilder,
            Map<String, List<String>> injected,
            String headerName,
            String headerValue) {
        if (headerValue == null || headerValue.isBlank() || hasHeader(requestHeaders, headerName)) {
            return;
        }
        requestBuilder.header(headerName, headerValue);
        injected.put(headerName, List.of("<backend>"));
    }

    private boolean hasHeader(Headers headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().equalsIgnoreCase(name)
                    && !sanitizeHeaderValues(entry.getValue()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<String> sanitizeHeaderValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<String> sanitized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()
                    || "null".equalsIgnoreCase(trimmed)
                    || "undefined".equalsIgnoreCase(trimmed)) {
                continue;
            }
            sanitized.add(trimmed);
        }
        return sanitized;
    }

    private boolean isLoginRequest(String method, URI target) {
        return "POST".equals(method) && target != null && target.getPath() != null
                && target.getPath().toLowerCase(Locale.ROOT).endsWith("/streaming/account/login");
    }

    private void captureSuccessfulLogin(HttpResponse<byte[]> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return;
        }
        String accountId = SoundcorkCloudXml.extractAccountId(response.body());
        String credentials = firstHeaderValue(response.headers(), "Credentials");
        if ((accountId == null || accountId.isBlank()) && (credentials == null || credentials.isBlank())) {
            return;
        }
        soundcorkDataService.storeMargeSession(accountId, credentials);
    }

    private void captureRefreshedAuthToken(URI target, HttpResponse<byte[]> response) {
        if (!soundcorkDataService.isMargeTarget(target.getHost(), target.getPath())) {
            return;
        }
        String refreshedToken = firstHeaderValue(response.headers(), "Refresh");
        if (refreshedToken != null && !refreshedToken.isBlank()) {
            soundcorkDataService.storeMargeAuthToken(refreshedToken);
        }
    }

    private String firstHeaderValue(HttpHeaders headers, String name) {
        return headers.firstValue(name).orElseGet(() ->
                headers.firstValue(name.toLowerCase(Locale.ROOT)).orElse(null));
    }

    private String basicAuth(SoundcorkCloudXml.LoginCredentials credentials) {
        String raw = credentials.email() + ":" + credentials.password();
        return "Basic " + java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Headers cloneHeaders(Headers original) {
        Headers copy = new Headers();
        for (Map.Entry<String, List<String>> entry : original.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    private void relayResponse(HttpExchange exchange, String method, HttpResponse<byte[]> response) throws IOException {
        byte[] body = response.body() == null ? new byte[0] : response.body();
        Headers responseHeaders = exchange.getResponseHeaders();
        HeaderCopy responseHeaderCopy = copyResponseHeaders(response.headers(), responseHeaders);
        responseHeaders.set("Cache-Control", "no-store");
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(
                    "Proxy response received from target: method={}, status={}, responseBodyBytes={}, targetHeaders={}",
                    method,
                    response.statusCode(),
                    body.length,
                    formatHeaders(response.headers().map()));
            LOGGER.trace(
                    "Proxy response forwarded to frontend: relayedHeaders={}, backendAddedHeaders={}, droppedHeaders={}",
                    formatHeaders(responseHeaderCopy.forwarded()),
                    formatHeaders(Map.of("Cache-Control", List.of("no-store"))),
                    formatHeaders(responseHeaderCopy.blocked()));
        }

        boolean bodyAllowed = !"HEAD".equals(method)
                && response.statusCode() != 204
                && response.statusCode() != 304;
        exchange.sendResponseHeaders(response.statusCode(), bodyAllowed ? body.length : -1);
        if (!bodyAllowed) {
            exchange.close();
            return;
        }
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private HeaderCopy copyResponseHeaders(HttpHeaders httpHeaders, Headers responseHeaders) {
        LinkedHashMap<String, List<String>> forwarded = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> blocked = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : httpHeaders.map().entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue() == null ? List.of() : List.copyOf(entry.getValue());
            // HTTP/2 pseudo-headers (e.g. :status, :method) must never be forwarded as
            // real HTTP/1.1 headers — doing so produces a malformed response that causes
            // reverse proxies (nginx, etc.) to return 502.
            if (name == null || name.startsWith(":") || BLOCKED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                if (name != null) {
                    blocked.put(name, values);
                }
                continue;
            }
            forwarded.put(name, values);
            responseHeaders.put(name, values);
        }
        return new HeaderCopy(forwarded, blocked);
    }

    private String queryParam(HttpExchange exchange, String name) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            if (separator > 0 && name.equals(part.substring(0, separator))) {
                return part.substring(separator + 1);
            }
        }
        return null;
    }

    private String formatHeaders(Map<String, ? extends List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        ArrayList<Map.Entry<String, ? extends List<String>>> entries = new ArrayList<>(headers.entrySet());
        entries.sort(Comparator.comparing((Map.Entry<String, ? extends List<String>> entry) ->
                entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT))
                .thenComparing(entry -> entry.getKey() == null ? "" : entry.getKey()));

        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, ? extends List<String>> entry = entries.get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append("=").append(entry.getValue());
        }
        builder.append("}");
        return builder.toString();
    }

    private record HeaderCopy(Map<String, List<String>> forwarded, Map<String, List<String>> blocked) {
    }

    private record RequestOutcome(URI target, HttpResponse<byte[]> response) {
    }
}
