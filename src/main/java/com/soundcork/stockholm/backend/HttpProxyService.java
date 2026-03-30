package com.soundcork.stockholm.backend;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
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

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target)
                .timeout(REQUEST_TIMEOUT)
                .method(method, bodyPublisher(method, requestBody));
        HeaderCopy requestHeaderCopy = copyRequestHeaders(exchange.getRequestHeaders(), requestBuilder);

        LOGGER.debug("Proxying {} request to {}", method, target);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(
                    "Proxy request forwarded to target: method={}, target={}, requestBodyBytes={}, forwardedHeaders={}, droppedHeaders={}",
                    method,
                    target,
                    requestBody.length,
                    formatHeaders(requestHeaderCopy.forwarded()),
                    formatHeaders(requestHeaderCopy.blocked()));
        }

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while proxying {} to {}", method, target, exception);
            BackendApplication.sendText(exchange, 502, "Proxy request interrupted", "text/plain; charset=UTF-8");
            return;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed proxy request {} {}", method, target, exception);
            BackendApplication.sendText(exchange, 502, "Proxy request failed", "text/plain; charset=UTF-8");
            return;
        }

        relayResponse(exchange, method, response);
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

        if (targetPort != localPort) {
            return false;
        }

        return host.equalsIgnoreCase(localName)
                || host.equalsIgnoreCase("localhost")
                || (localHost != null && host.equalsIgnoreCase(localHost))
                || host.equals("127.0.0.1")
                || host.equals("::1");
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

    private HeaderCopy copyRequestHeaders(Headers requestHeaders, HttpRequest.Builder requestBuilder) {
        LinkedHashMap<String, List<String>> forwarded = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> blocked = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : requestHeaders.entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue() == null ? List.of() : List.copyOf(entry.getValue());
            if (name == null || BLOCKED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                if (name != null) {
                    blocked.put(name, values);
                }
                continue;
            }
            forwarded.put(name, values);
            for (String value : entry.getValue()) {
                requestBuilder.header(name, value);
            }
        }
        return new HeaderCopy(forwarded, blocked);
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
            if (name == null || BLOCKED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
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
}
