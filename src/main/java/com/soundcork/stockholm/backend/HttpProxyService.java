package com.soundcork.stockholm.backend;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final HttpTrafficCaptureService captureService;

    HttpProxyService(SoundcorkDataService soundcorkDataService) {
        this(soundcorkDataService, HttpTrafficCaptureService.disabled());
    }

    HttpProxyService(SoundcorkDataService soundcorkDataService, HttpTrafficCaptureService captureService) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build(), soundcorkDataService, captureService);
    }

    HttpProxyService(HttpClient httpClient, SoundcorkDataService soundcorkDataService) {
        this(httpClient, soundcorkDataService, HttpTrafficCaptureService.disabled());
    }

    HttpProxyService(HttpClient httpClient, SoundcorkDataService soundcorkDataService, HttpTrafficCaptureService captureService) {
        this.httpClient = httpClient;
        this.soundcorkDataService = soundcorkDataService;
        this.captureService = captureService == null ? HttpTrafficCaptureService.disabled() : captureService;
    }

    void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String encodedTarget = queryParam(exchange, "url");
        HttpTrafficCaptureService.CaptureContext captureContext = captureService.beginRequest(exchange, method, encodedTarget);
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
            captureService.captureProxyError(captureContext, null, "missing_url", 400, "Missing url query parameter", null);
            BackendApplication.sendText(exchange, 400, "Missing url query parameter", "text/plain; charset=UTF-8");
            return;
        }

        URI target;
        try {
            target = URI.create(URLDecoder.decode(encodedTarget, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            LOGGER.debug("Rejected malformed proxy target '{}'", encodedTarget, exception);
            captureService.captureProxyError(captureContext, null, "invalid_target", 400, "Invalid proxy target", exception);
            BackendApplication.sendText(exchange, 400, "Invalid proxy target", "text/plain; charset=UTF-8");
            return;
        }

        if (!isSupportedTarget(target)) {
            LOGGER.debug("Rejected unsupported proxy target {}", target);
            captureService.captureProxyError(captureContext, target, "unsupported_target", 400, "Unsupported proxy target", null);
            BackendApplication.sendText(exchange, 400, "Unsupported proxy target", "text/plain; charset=UTF-8");
            return;
        }

        if (isProxyLoop(exchange, target)) {
            LOGGER.debug("Rejected proxy loop for target {}", target);
            captureService.captureProxyError(captureContext, target, "proxy_loop", 400, "Refusing to proxy proxy endpoint", null);
            BackendApplication.sendText(exchange, 400, "Refusing to proxy proxy endpoint", "text/plain; charset=UTF-8");
            return;
        }

        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        try {
            RequestOutcome outcome = executeWithCloudHandling(
                    method,
                    exchange.getRequestHeaders(),
                    target,
                    requestBody,
                    captureContext);
            relayResponse(exchange, method, outcome.response());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while proxying {} to {}", method, target, exception);
            captureService.captureProxyError(captureContext, target, "interrupted", 502, "Proxy request interrupted", exception);
            BackendApplication.sendText(exchange, 502, "Proxy request interrupted", "text/plain; charset=UTF-8");
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed proxy request {} {}", method, target, exception);
            captureService.captureProxyError(captureContext, target, "proxy_failure", 502, "Proxy request failed", exception);
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

        if (targetPort == localPort) {
            if (host.equalsIgnoreCase(localName)
                    || host.equalsIgnoreCase("localhost")
                    || (localHost != null && host.equalsIgnoreCase(localHost))
                    || host.equals("127.0.0.1")
                    || host.equals("::1")) {
                return true;
            }
        }

        String externalHost = resolveExternalHost(exchange.getRequestHeaders());
        int externalPort = resolveExternalPort(exchange.getRequestHeaders());
        return externalHost != null && !externalHost.isBlank()
                && host.equalsIgnoreCase(externalHost)
                && targetPort == externalPort;
    }

    private String resolveExternalHost(Headers requestHeaders) {
        String forwardedHost = firstRequestHeader(requestHeaders, "x-forwarded-host");
        if (forwardedHost != null && !forwardedHost.isBlank()) {
            int colonIdx = forwardedHost.lastIndexOf(':');
            return colonIdx >= 0 ? forwardedHost.substring(0, colonIdx) : forwardedHost;
        }
        String hostHeader = firstRequestHeader(requestHeaders, "host");
        if (hostHeader != null && !hostHeader.isBlank()) {
            int colonIdx = hostHeader.lastIndexOf(':');
            return colonIdx >= 0 ? hostHeader.substring(0, colonIdx) : hostHeader;
        }
        return null;
    }

    private int resolveExternalPort(Headers requestHeaders) {
        String forwardedPort = firstRequestHeader(requestHeaders, "x-forwarded-port");
        if (forwardedPort != null && !forwardedPort.isBlank()) {
            try {
                return Integer.parseInt(forwardedPort.trim());
            } catch (NumberFormatException ignored) {
            }
        }

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

    private RequestOutcome executeWithCloudHandling(
            String method,
            Headers requestHeaders,
            URI target,
            byte[] requestBody,
            HttpTrafficCaptureService.CaptureContext captureContext) throws IOException, InterruptedException {
        RequestOutcome outcome = executeRequest(
                method,
                requestHeaders,
                target,
                requestBody,
                captureContext,
                "initial",
                Map.of());

        if (isLoginRequest(method, outcome.target())) {
            outcome = retryLoginWithEnvironmentIfNeeded(method, requestHeaders, outcome, requestBody, captureContext);
            captureSuccessfulLogin(outcome.response());
        }

        captureRefreshedAuthToken(outcome.target(), outcome.response());
        return outcome;
    }

    private RequestOutcome retryLoginWithEnvironmentIfNeeded(
            String method,
            Headers requestHeaders,
            RequestOutcome outcome,
            byte[] requestBody,
            HttpTrafficCaptureService.CaptureContext captureContext) throws IOException, InterruptedException {
        if (!"4033".equals(SoundcorkCloudXml.extractStatusCode(outcome.response().body()))) {
            return outcome;
        }

        SoundcorkCloudXml.LoginCredentials credentials = SoundcorkCloudXml.parseLoginRequest(requestBody);
        if (credentials == null) {
            LOGGER.debug("Cannot retry login after 4033 because login credentials could not be parsed");
            return outcome;
        }

        SoundcorkCloudXml.EnvironmentInfo environment = fetchEnvironment(requestHeaders, outcome.target(), credentials, captureContext);
        if (environment == null || environment.streamingUrl() == null || environment.streamingUrl().isBlank()) {
            LOGGER.debug("Cannot retry login after 4033 because no valid environment payload was returned");
            return outcome;
        }

        soundcorkDataService.storeOverrideUrls(environment.streamingUrl(), environment.updateUrl());
        URI retryTarget = soundcorkDataService.buildUriFromBase(
                environment.streamingUrl(),
                outcome.target().getPath(),
                outcome.target().getQuery());
        if (retryTarget == null) {
            LOGGER.debug("Cannot retry login after 4033 because retry target could not be built");
            return outcome;
        }

        LOGGER.info("Retrying login against switched environment {}", retryTarget);
        return executeRequest(
                method,
                requestHeaders,
                retryTarget,
                requestBody,
                captureContext,
                "login_retry",
                Map.of());
    }

    private SoundcorkCloudXml.EnvironmentInfo fetchEnvironment(
            Headers requestHeaders,
            URI loginTarget,
            SoundcorkCloudXml.LoginCredentials credentials,
            HttpTrafficCaptureService.CaptureContext captureContext) throws IOException, InterruptedException {
        String encodedEmail = URLEncoder.encode(credentials.email(), StandardCharsets.UTF_8);
        String pathPrefix = margePathPrefix(loginTarget);
        URI environmentTarget = soundcorkDataService.buildUriFromBase(
                loginTarget.getScheme() + "://" + loginTarget.getAuthority() + "/",
                pathPrefix + "/streaming/account/email/" + encodedEmail + "/environment",
                null);
        if (environmentTarget == null) {
            return null;
        }

        RequestOutcome environmentOutcome = executeRequest(
                "GET",
                requestHeaders,
                environmentTarget,
                new byte[0],
                captureContext,
                "environment_lookup",
                Map.of("Authorization", List.of(basicAuth(credentials))));
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

    private RequestOutcome executeRequest(
            String method,
            Headers requestHeaders,
            URI target,
            byte[] requestBody,
            HttpTrafficCaptureService.CaptureContext captureContext,
            String stepLabel,
            Map<String, List<String>> explicitInjectedHeaders) throws IOException, InterruptedException {
        long startNanos = System.nanoTime();
        URI effectiveTarget = soundcorkDataService.overrideTarget(target);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(effectiveTarget)
                .timeout(REQUEST_TIMEOUT)
                .method(method, bodyPublisher(method, requestBody));
        HeaderCopy requestHeaderCopy = copyRequestHeaders(requestHeaders, requestBuilder);
        Map<String, List<String>> backendInjectedHeaders = applyInjectedHeaders(
                requestHeaders,
                explicitInjectedHeaders,
                effectiveTarget,
                requestBuilder);
        Map<String, List<String>> finalSentHeaders = mergeHeaders(
                requestHeaderCopy.forwarded(),
                backendInjectedHeaders);

        LOGGER.debug("Proxying {} request to {}", method, effectiveTarget);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(
                    "Proxy request forwarded to target: method={}, target={}, requestBodyBytes={}, forwardedHeaders={}, backendInjectedHeaders={}, droppedHeaders={}",
                    method,
                    effectiveTarget,
                    requestBody.length,
                    formatHeaders(requestHeaderCopy.forwarded()),
                    formatHeaders(maskHeadersForTrace(backendInjectedHeaders)),
                    formatHeaders(requestHeaderCopy.blocked()));
        }

        HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        ResponseHeaderPlan responseHeaderPlan = planResponseHeaders(response.headers());
        captureService.captureUpstreamExchange(
                captureContext,
                stepLabel,
                target,
                effectiveTarget,
                requestBody,
                requestHeaderCopy.forwarded(),
                requestHeaderCopy.blocked(),
                backendInjectedHeaders,
                finalSentHeaders,
                response,
                responseHeaderPlan.forwarded(),
                responseHeaderPlan.blocked(),
                System.nanoTime() - startNanos);

        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            String bodySnippet = response.body() != null
                    ? new String(response.body(), 0, Math.min(response.body().length, 2048), StandardCharsets.UTF_8)
                    : "<empty>";
            LOGGER.warn("Server returned HTTP {} for {} {} — response body: {}",
                    response.statusCode(), method, effectiveTarget, bodySnippet);
        } else if (response.statusCode() >= 500) {
            LOGGER.warn("Server returned HTTP {} for {} {}", response.statusCode(), method, effectiveTarget);
        }
        return new RequestOutcome(target, effectiveTarget, response);
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

    private Map<String, List<String>> applyInjectedHeaders(
            Headers requestHeaders,
            Map<String, List<String>> explicitInjectedHeaders,
            URI target,
            HttpRequest.Builder requestBuilder) {
        LinkedHashMap<String, List<String>> injected = new LinkedHashMap<>();
        applyExplicitHeaders(requestHeaders, explicitInjectedHeaders, requestBuilder, injected);

        String host = target.getHost();
        String path = target.getPath();
        if (soundcorkDataService.isBmxTarget(host)) {
            injectIfMissing(requestHeaders, injected, requestBuilder, "x-bmx-api-key", soundcorkDataService.bmxApiKey());
            injectIfMissing(requestHeaders, injected, requestBuilder, "x-software-version", soundcorkDataService.soundcorkAppVersion());
        }

        if (soundcorkDataService.isMargeTarget(host, path)) {
            String mediaType = soundcorkDataService.mediaTypeForPath(path);
            injectIfMissing(requestHeaders, injected, requestBuilder, "Accept", mediaType);
            injectIfMissing(requestHeaders, injected, requestBuilder, "Content-Type", mediaType);
            injectIfMissing(requestHeaders, injected, requestBuilder, "ClientType", soundcorkDataService.defaultClientType());
            injectIfMissing(requestHeaders, injected, requestBuilder, "GUID", soundcorkDataService.guid());
            injectIfMissing(requestHeaders, injected, requestBuilder, "version_NativeFrameVersion", soundcorkDataService.nativeFrameVersion());
            injectIfMissing(requestHeaders, injected, requestBuilder, "version_StockholmVersion", soundcorkDataService.soundcorkAppVersion());
            injectIfMissing(requestHeaders, injected, requestBuilder, "version_ProtocolVersion", soundcorkDataService.protocolVersion());
            if (soundcorkDataService.margeServerKeyHeader() != null) {
                injectIfMissing(
                        requestHeaders,
                        injected,
                        requestBuilder,
                        soundcorkDataService.margeServerKeyHeader(),
                        soundcorkDataService.margeServerKey());
            }
            if (shouldInjectStoredAuth(path)) {
                injectIfMissing(requestHeaders, injected, requestBuilder, "Authorization", soundcorkDataService.margeAuthToken());
            }
        }

        return injected;
    }

    private void applyExplicitHeaders(
            Headers requestHeaders,
            Map<String, List<String>> explicitInjectedHeaders,
            HttpRequest.Builder requestBuilder,
            Map<String, List<String>> injected) {
        if (explicitInjectedHeaders == null || explicitInjectedHeaders.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : explicitInjectedHeaders.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            for (String value : sanitizeHeaderValues(entry.getValue())) {
                injectIfMissing(requestHeaders, injected, requestBuilder, name, value);
            }
        }
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
            Map<String, List<String>> injected,
            HttpRequest.Builder requestBuilder,
            String headerName,
            String headerValue) {
        if (headerValue == null || headerValue.isBlank() || hasHeader(requestHeaders, headerName) || hasHeader(injected, headerName)) {
            return;
        }
        requestBuilder.header(headerName, headerValue);
        injected.put(headerName, List.of(headerValue));
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

    private boolean hasHeader(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().equalsIgnoreCase(name)
                    && !sanitizeHeaderValues(entry.getValue()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, List<String>> mergeHeaders(
            Map<String, List<String>> forwardedHeaders,
            Map<String, List<String>> injectedHeaders) {
        LinkedHashMap<String, List<String>> merged = new LinkedHashMap<>();
        putHeaders(merged, forwardedHeaders);
        putHeaders(merged, injectedHeaders);
        return merged;
    }

    private void putHeaders(Map<String, List<String>> target, Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            target.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
    }

    private Map<String, List<String>> maskHeadersForTrace(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> masked = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            masked.put(entry.getKey(), List.of("<backend>"));
        }
        return masked;
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

    private void relayResponse(HttpExchange exchange, String method, HttpResponse<byte[]> response) throws IOException {
        byte[] body = response.body() == null ? new byte[0] : response.body();
        Headers responseHeaders = exchange.getResponseHeaders();
        ResponseHeaderPlan responseHeaderPlan = planResponseHeaders(response.headers());
        applyResponseHeaders(responseHeaders, responseHeaderPlan.forwarded());
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
                    formatHeaders(responseHeaderPlan.forwarded()),
                    formatHeaders(Map.of("Cache-Control", List.of("no-store"))),
                    formatHeaders(responseHeaderPlan.blocked()));
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

    private ResponseHeaderPlan planResponseHeaders(HttpHeaders httpHeaders) {
        LinkedHashMap<String, List<String>> forwarded = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> blocked = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : httpHeaders.map().entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue() == null ? List.of() : List.copyOf(entry.getValue());
            if (name == null || name.startsWith(":") || BLOCKED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                if (name != null) {
                    blocked.put(name, values);
                }
                continue;
            }
            forwarded.put(name, values);
        }
        return new ResponseHeaderPlan(forwarded, blocked);
    }

    private void applyResponseHeaders(Headers responseHeaders, Map<String, List<String>> forwardedHeaders) {
        for (Map.Entry<String, List<String>> entry : forwardedHeaders.entrySet()) {
            responseHeaders.put(entry.getKey(), entry.getValue());
        }
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

    private record ResponseHeaderPlan(Map<String, List<String>> forwarded, Map<String, List<String>> blocked) {
    }

    private record RequestOutcome(URI requestedTarget, URI target, HttpResponse<byte[]> response) {
    }
}
