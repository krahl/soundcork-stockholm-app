package com.soundcork.stockholm.backend;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HttpTrafficCaptureService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpTrafficCaptureService.class);
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "credentials",
            "refresh",
            "cookie",
            "set-cookie",
            "set-cookie2",
            "proxy-authorization",
            "x-bmx-api-key",
            "x-bose-apigee-key",
            "guid");
    private static final String SENSITIVE_FIELD_FRAGMENT =
            "password|passphrase|token|secret|credential|guid|email|username|authorization|api(?:_|-)?key|session";
    private static final Pattern JSON_SENSITIVE_FIELD_PATTERN = Pattern.compile(
            "(\"([^\"]*(?:" + SENSITIVE_FIELD_FRAGMENT + ")[^\"]*)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_SENSITIVE_ELEMENT_PATTERN = Pattern.compile(
            "(<([A-Za-z0-9_:\\-.]*?(?:" + SENSITIVE_FIELD_FRAGMENT + ")[A-Za-z0-9_:\\-.]*)>)([^<]*)(</\\2>)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_SENSITIVE_ATTRIBUTE_PATTERN = Pattern.compile(
            "((?:^|\\s)([A-Za-z0-9_:\\-.]*?(?:" + SENSITIVE_FIELD_FRAGMENT + ")[A-Za-z0-9_:\\-.]*)=\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM_SENSITIVE_FIELD_PATTERN = Pattern.compile(
            "((?:^|[&?])([A-Za-z0-9_\\-.]*?(?:" + SENSITIVE_FIELD_FRAGMENT + ")[A-Za-z0-9_\\-.]*)=)([^&]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)\\b(Bearer\\s+)([A-Za-z0-9._~+/=-]+)");
    private static final Pattern BASIC_PATTERN = Pattern.compile(
            "(?i)\\b(Basic\\s+)([A-Za-z0-9+/=]+)");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern LONG_HEX_PATTERN = Pattern.compile("(?i)^[0-9a-f]{16,}$");
    private static final Pattern TOKEN_SEGMENT_PATTERN = Pattern.compile("^[A-Za-z0-9._~+=%-]{16,}$");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset=([^;]+)", Pattern.CASE_INSENSITIVE);

    private final BackendConfig.HttpCaptureConfig config;
    private final boolean enabled;
    private final Object writeLock = new Object();
    private volatile byte[] pseudonymKey;

    private HttpTrafficCaptureService() {
        this.config = new BackendConfig.HttpCaptureConfig(
                false,
                BackendConfig.HttpCaptureMode.PSEUDONYMIZE,
                Path.of("backend", "state", "http-capture"),
                65_536,
                8_192,
                false);
        this.enabled = false;
    }

    HttpTrafficCaptureService(BackendConfig.HttpCaptureConfig config) {
        this.config = config;
        this.enabled = config != null && config.enabled();
    }

    static HttpTrafficCaptureService disabled() {
        return new HttpTrafficCaptureService();
    }

    CaptureContext beginRequest(HttpExchange exchange, String method, String encodedTarget) {
        String flowId = UUID.randomUUID().toString();
        return new CaptureContext(
                flowId,
                flowId,
                method,
                exchange.getRequestURI(),
                encodedTarget,
                describeRemoteAddress(exchange),
                copyHeaders(exchange.getRequestHeaders()));
    }

    void captureProxyError(
            CaptureContext context,
            URI decodedOriginalTarget,
            String reason,
            int status,
            String message,
            Throwable failure) {
        if (!enabled || !config.includeProxyErrors() || context == null) {
            return;
        }

        LinkedHashMap<String, Object> record = baseRecord(context, "proxy_error", "proxy_error", 0L);
        record.put("frontend", frontendSection(context, decodedOriginalTarget));

        LinkedHashMap<String, Object> proxyResult = new LinkedHashMap<>();
        proxyResult.put("reason", reason);
        proxyResult.put("status", status);
        proxyResult.put("message", sanitizeStructuredText(message == null ? "" : message));
        if (failure != null) {
            proxyResult.put("errorClass", failure.getClass().getName());
            proxyResult.put("errorMessage", sanitizeStructuredText(
                    failure.getMessage() == null ? "" : failure.getMessage()));
        }
        record.put("proxyResult", proxyResult);
        writeRecord(record);
    }

    void captureUpstreamExchange(
            CaptureContext context,
            String stepLabel,
            URI intendedTarget,
            URI effectiveTarget,
            byte[] requestBody,
            Map<String, List<String>> forwardedRequestHeaders,
            Map<String, List<String>> blockedRequestHeaders,
            Map<String, List<String>> backendInjectedHeaders,
            Map<String, List<String>> finalSentHeaders,
            HttpResponse<byte[]> response,
            Map<String, List<String>> forwardedResponseHeaders,
            Map<String, List<String>> blockedResponseHeaders,
            long durationNanos) {
        if (!enabled || context == null || response == null) {
            return;
        }

        LinkedHashMap<String, Object> record = baseRecord(context, "upstream_exchange", stepLabel, durationNanos);
        record.put("frontend", frontendSection(context, intendedTarget));

        LinkedHashMap<String, Object> upstreamRequest = new LinkedHashMap<>();
        upstreamRequest.put("method", context.method());
        upstreamRequest.put("intendedUrl", sanitizeUri(intendedTarget));
        upstreamRequest.put("effectiveUrl", sanitizeUri(effectiveTarget));
        upstreamRequest.put("host", effectiveTarget == null ? null : effectiveTarget.getHost());
        upstreamRequest.put("path", sanitizePath(effectiveTarget));
        upstreamRequest.put("queryKeys", queryKeys(effectiveTarget));
        upstreamRequest.put("endpointTemplateCandidate", endpointTemplateCandidate(effectiveTarget));

        LinkedHashMap<String, Object> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("frontendProvided", sanitizeHeaders(context.frontendRequestHeaders()));
        requestHeaders.put("forwarded", sanitizeHeaders(forwardedRequestHeaders));
        requestHeaders.put("blocked", sanitizeHeaders(blockedRequestHeaders));
        requestHeaders.put("backendInjected", sanitizeHeaders(backendInjectedHeaders));
        requestHeaders.put("finalSent", sanitizeHeaders(finalSentHeaders));
        upstreamRequest.put("headers", requestHeaders);
        upstreamRequest.put(
                "body",
                describeBody(requestBody, firstHeaderValue(finalSentHeaders, "content-type"), config.maxTextBodyBytes(), config.maxBinaryBodyBytes()));
        record.put("upstreamRequest", upstreamRequest);

        LinkedHashMap<String, Object> upstreamResponse = new LinkedHashMap<>();
        upstreamResponse.put("status", response.statusCode());
        LinkedHashMap<String, Object> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put("forwarded", sanitizeHeaders(forwardedResponseHeaders));
        responseHeaders.put("blocked", sanitizeHeaders(blockedResponseHeaders));
        upstreamResponse.put("headers", responseHeaders);
        upstreamResponse.put(
                "body",
                describeBody(response.body(), firstHeaderValue(response.headers().map(), "content-type"),
                        config.maxTextBodyBytes(), config.maxBinaryBodyBytes()));
        record.put("upstreamResponse", upstreamResponse);

        writeRecord(record);
    }

    private LinkedHashMap<String, Object> baseRecord(
            CaptureContext context,
            String recordType,
            String stepLabel,
            long durationNanos) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("type", recordType);
        record.put("flowId", context.flowId());
        record.put("requestId", context.requestId());
        record.put("exchangeId", context.nextExchangeId());
        record.put("step", stepLabel);
        record.put("timestamp", Instant.now().toString());
        record.put("durationMs", durationNanos <= 0 ? 0L : durationNanos / 1_000_000L);
        return record;
    }

    private Map<String, Object> frontendSection(CaptureContext context, URI decodedOriginalTarget) {
        LinkedHashMap<String, Object> frontend = new LinkedHashMap<>();
        frontend.put("method", context.method());
        frontend.put("proxyUri", context.proxyUri() == null ? null : context.proxyUri().toString());
        frontend.put("encodedTarget", context.encodedTarget());
        frontend.put("decodedOriginalTarget", sanitizeUri(decodedOriginalTarget));
        frontend.put("remoteAddress", context.remoteAddress());
        frontend.put("headers", sanitizeHeaders(context.frontendRequestHeaders()));
        return frontend;
    }

    private Map<String, Object> describeBody(byte[] body, String contentType, int maxTextBodyBytes, int maxBinaryBodyBytes) {
        byte[] safeBody = body == null ? new byte[0] : body;
        LinkedHashMap<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("contentType", contentType);
        descriptor.put("byteCount", safeBody.length);
        descriptor.put("sha256", sha256Hex(safeBody));

        boolean textual = isTextualContentType(contentType);
        int limit = textual ? maxTextBodyBytes : maxBinaryBodyBytes;
        byte[] previewBytes = Arrays.copyOf(safeBody, Math.min(safeBody.length, limit));
        descriptor.put("truncated", safeBody.length > limit);

        if (textual) {
            Charset charset = resolveCharset(contentType);
            String preview = new String(previewBytes, charset);
            descriptor.put("encoding", "text");
            descriptor.put("text", sanitizeStructuredText(preview));
        } else {
            descriptor.put("encoding", "base64");
            descriptor.put("base64", Base64.getEncoder().encodeToString(previewBytes));
        }

        return descriptor;
    }

    private Map<String, List<String>> sanitizeHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            ArrayList<String> values = new ArrayList<>();
            for (String value : normalizeHeaderValues(entry.getValue())) {
                values.add(sanitizeHeaderValue(name, value));
            }
            sanitized.put(name, List.copyOf(values));
        }
        return sanitized;
    }

    private String sanitizeHeaderValue(String name, String value) {
        if (value == null) {
            return null;
        }
        if (config.mode() == BackendConfig.HttpCaptureMode.RAW) {
            return value;
        }
        if (isSensitiveHeader(name)) {
            return privacyTransform("header." + name.toLowerCase(Locale.ROOT), value);
        }
        return sanitizeFreeText(value);
    }

    private boolean isSensitiveHeader(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (SENSITIVE_HEADERS.contains(normalized)) {
            return true;
        }
        return normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("auth")
                || normalized.contains("guid")
                || normalized.contains("cookie")
                || normalized.contains("api-key")
                || normalized.contains("apikey");
    }

    private String sanitizeUri(URI uri) {
        if (uri == null) {
            return null;
        }
        if (config.mode() == BackendConfig.HttpCaptureMode.RAW) {
            return uri.toString();
        }

        StringBuilder builder = new StringBuilder();
        if (uri.getScheme() != null) {
            builder.append(uri.getScheme()).append("://");
        }
        if (uri.getHost() != null) {
            builder.append(uri.getHost());
        } else if (uri.getAuthority() != null) {
            builder.append(uri.getAuthority());
        }
        if (uri.getPort() >= 0) {
            builder.append(":").append(uri.getPort());
        }
        builder.append(sanitizePath(uri));
        String query = sanitizeQuery(uri.getRawQuery());
        if (!query.isEmpty()) {
            builder.append("?").append(query);
        }
        return builder.toString();
    }

    private String sanitizePath(URI uri) {
        if (uri == null || uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
            return "";
        }
        if (config.mode() == BackendConfig.HttpCaptureMode.RAW) {
            return uri.getPath();
        }

        String[] segments = uri.getRawPath().split("/", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            String rawSegment = segments[i];
            if (i > 0) {
                builder.append('/');
            }
            if (rawSegment.isEmpty()) {
                continue;
            }
            String decoded = URLDecoder.decode(rawSegment, StandardCharsets.UTF_8);
            String sanitized = sanitizePathSegment(decoded);
            builder.append(encodeUriComponent(sanitized));
        }
        return builder.isEmpty() ? "/" : builder.toString();
    }

    private String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        if (config.mode() == BackendConfig.HttpCaptureMode.RAW) {
            return rawQuery;
        }

        ArrayList<String> parts = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String rawName = separator >= 0 ? part.substring(0, separator) : part;
            String rawValue = separator >= 0 ? part.substring(separator + 1) : "";
            String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);

            String sanitizedValue = shouldSanitizeQueryValue(name, value)
                    ? privacyTransform("query." + name.toLowerCase(Locale.ROOT), value)
                    : sanitizeFreeText(value);
            parts.add(encodeUriComponent(name) + "=" + encodeUriComponent(sanitizedValue));
        }
        return String.join("&", parts);
    }

    private boolean shouldSanitizeQueryValue(String name, String value) {
        String normalizedName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (normalizedName.matches(".*(?:" + SENSITIVE_FIELD_FRAGMENT + ").*")) {
            return true;
        }
        return looksLikeSensitiveValue(value);
    }

    private String sanitizePathSegment(String value) {
        if (config.mode() == BackendConfig.HttpCaptureMode.RAW) {
            return value;
        }
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (looksLikeEmail(value)) {
            return placeholderOrPseudo("path.email", "{email}", value);
        }
        if (value.matches("^\\d+$")) {
            return "{number}";
        }
        if (UUID_PATTERN.matcher(value).matches()) {
            return "{uuid}";
        }
        if (LONG_HEX_PATTERN.matcher(value).matches()) {
            return "{hex}";
        }
        if (TOKEN_SEGMENT_PATTERN.matcher(value).matches() && looksLikeSensitiveValue(value)) {
            return placeholderOrPseudo("path.token", "{token}", value);
        }
        return value;
    }

    private String endpointTemplateCandidate(URI uri) {
        if (uri == null || uri.getPath() == null || uri.getPath().isEmpty()) {
            return "/";
        }
        String[] segments = uri.getPath().split("/", -1);
        ArrayList<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            normalized.add(normalizeTemplateSegment(segment));
        }
        return "/" + String.join("/", normalized);
    }

    private String normalizeTemplateSegment(String segment) {
        if (segment.matches("^\\d+$")) {
            return "{number}";
        }
        if (looksLikeEmail(segment)) {
            return "{email}";
        }
        if (UUID_PATTERN.matcher(segment).matches()) {
            return "{uuid}";
        }
        if (LONG_HEX_PATTERN.matcher(segment).matches()) {
            return "{hex}";
        }
        if (TOKEN_SEGMENT_PATTERN.matcher(segment).matches() && looksLikeSensitiveValue(segment)) {
            return "{token}";
        }
        return segment;
    }

    private List<String> queryKeys(URI uri) {
        if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return List.of();
        }
        ArrayList<String> keys = new ArrayList<>();
        for (String part : uri.getRawQuery().split("&")) {
            int separator = part.indexOf('=');
            String rawName = separator >= 0 ? part.substring(0, separator) : part;
            keys.add(URLDecoder.decode(rawName, StandardCharsets.UTF_8));
        }
        return List.copyOf(keys);
    }

    private String sanitizeStructuredText(String text) {
        if (text == null || text.isEmpty() || config.mode() == BackendConfig.HttpCaptureMode.RAW) {
            return text;
        }
        String sanitized = replaceSensitiveFieldPattern(text, JSON_SENSITIVE_FIELD_PATTERN, 2, 3, "");
        sanitized = replaceSensitiveFieldPattern(sanitized, XML_SENSITIVE_ELEMENT_PATTERN, 2, 3, "");
        sanitized = replaceSensitiveFieldPattern(sanitized, XML_SENSITIVE_ATTRIBUTE_PATTERN, 2, 3, "");
        sanitized = replaceSensitiveFieldPattern(sanitized, FORM_SENSITIVE_FIELD_PATTERN, 2, 3, "");
        return sanitizeFreeText(sanitized);
    }

    private String sanitizeFreeText(String text) {
        if (text == null || text.isEmpty() || config.mode() == BackendConfig.HttpCaptureMode.RAW) {
            return text;
        }
        String sanitized = replaceMatch(text, EMAIL_PATTERN, "email");
        sanitized = replaceGroupedMatch(sanitized, BEARER_PATTERN, 2, "bearer");
        sanitized = replaceGroupedMatch(sanitized, BASIC_PATTERN, 2, "basic");
        return sanitized;
    }

    private String replaceSensitiveFieldPattern(
            String input,
            Pattern pattern,
            int labelGroup,
            int valueGroup,
            String labelPrefix) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String label = labelPrefix + matcher.group(labelGroup).toLowerCase(Locale.ROOT);
            String replacementValue = privacyTransform("body." + label, matcher.group(valueGroup));
            String suffix = matcher.groupCount() > valueGroup ? matcher.group(matcher.groupCount()) : "";
            String replacement = matcher.group(1)
                    + replacementValue
                    + suffix;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceMatch(String input, Pattern pattern, String label) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(privacyTransform(label, matcher.group())));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceGroupedMatch(String input, Pattern pattern, int sensitiveGroup, String label) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1)
                    + privacyTransform(label, matcher.group(sensitiveGroup));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String placeholderOrPseudo(String label, String placeholder, String value) {
        if (config.mode() == BackendConfig.HttpCaptureMode.REDACT) {
            return placeholder;
        }
        return privacyTransform(label, value);
    }

    private String privacyTransform(String label, String value) {
        if (value == null) {
            return null;
        }
        return switch (config.mode()) {
            case RAW -> value;
            case REDACT -> "<redacted:" + label + ">";
            case PSEUDONYMIZE -> "<pseudo:" + label + ":" + pseudonymHash(label, value) + ">";
        };
    }

    private String pseudonymHash(String label, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(loadOrCreatePseudonymKey(), "HmacSHA256"));
            byte[] digest = mac.doFinal((label + "\u0000" + value).getBytes(StandardCharsets.UTF_8));
            return toHex(digest).substring(0, 16);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to pseudonymize capture value", exception);
        }
    }

    private byte[] loadOrCreatePseudonymKey() {
        byte[] existing = pseudonymKey;
        if (existing != null) {
            return existing;
        }

        synchronized (writeLock) {
            if (pseudonymKey != null) {
                return pseudonymKey;
            }
            try {
                Files.createDirectories(config.directory());
                Path keyFile = config.directory().resolve("pseudonym.key");
                if (Files.exists(keyFile)) {
                    pseudonymKey = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
                    return pseudonymKey;
                }

                byte[] generated = new byte[32];
                new SecureRandom().nextBytes(generated);
                Files.writeString(
                        keyFile,
                        Base64.getEncoder().encodeToString(generated),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
                pseudonymKey = generated;
                return pseudonymKey;
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to initialize HTTP capture pseudonym key", exception);
            }
        }
    }

    private void writeRecord(Map<String, Object> record) {
        try {
            synchronized (writeLock) {
                Files.createDirectories(config.directory());
                Path captureFile = config.directory()
                        .resolve("http-traffic-" + LocalDate.now(ZoneOffset.UTC) + ".ndjson");
                Files.writeString(
                        captureFile,
                        SimpleJson.stringify(record) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (Exception exception) {
            LOGGER.warn("Failed to write HTTP capture record", exception);
        }
    }

    private Charset resolveCharset(String contentType) {
        if (contentType != null) {
            Matcher matcher = CHARSET_PATTERN.matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1).trim());
                } catch (Exception ignored) {
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private boolean isTextualContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("text/")
                || normalized.contains("json")
                || normalized.contains("xml")
                || normalized.contains("javascript")
                || normalized.contains("html")
                || normalized.contains("x-www-form-urlencoded");
    }

    private boolean looksLikeSensitiveValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return looksLikeEmail(value)
                || value.regionMatches(true, 0, "Bearer ", 0, 7)
                || value.regionMatches(true, 0, "Basic ", 0, 6)
                || LONG_HEX_PATTERN.matcher(value).matches()
                || (TOKEN_SEGMENT_PATTERN.matcher(value).matches()
                        && value.chars().anyMatch(Character::isDigit)
                        && value.chars().anyMatch(Character::isLetter));
    }

    private boolean looksLikeEmail(String value) {
        return value != null && EMAIL_PATTERN.matcher(value).matches();
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash HTTP body", exception);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private String firstHeaderValue(Map<String, List<String>> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = normalizeHeaderValues(entry.getValue());
                if (!values.isEmpty()) {
                    return values.get(0);
                }
            }
        }
        return null;
    }

    private Map<String, List<String>> copyHeaders(Headers headers) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        if (headers == null) {
            return copy;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            copy.put(entry.getKey(), normalizeHeaderValues(entry.getValue()));
        }
        return copy;
    }

    private List<String> normalizeHeaderValues(List<String> values) {
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
        return List.copyOf(sanitized);
    }

    private String describeRemoteAddress(HttpExchange exchange) {
        if (exchange == null || exchange.getRemoteAddress() == null) {
            return null;
        }
        return exchange.getRemoteAddress().getHostString() + ":" + exchange.getRemoteAddress().getPort();
    }

    private String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static final class CaptureContext {
        private final String flowId;
        private final String requestId;
        private final String method;
        private final URI proxyUri;
        private final String encodedTarget;
        private final String remoteAddress;
        private final Map<String, List<String>> frontendRequestHeaders;
        private final AtomicInteger exchangeCounter = new AtomicInteger();

        private CaptureContext(
                String flowId,
                String requestId,
                String method,
                URI proxyUri,
                String encodedTarget,
                String remoteAddress,
                Map<String, List<String>> frontendRequestHeaders) {
            this.flowId = flowId;
            this.requestId = requestId;
            this.method = method;
            this.proxyUri = proxyUri;
            this.encodedTarget = encodedTarget;
            this.remoteAddress = remoteAddress;
            this.frontendRequestHeaders = frontendRequestHeaders;
        }

        String flowId() {
            return flowId;
        }

        String requestId() {
            return requestId;
        }

        String method() {
            return method;
        }

        URI proxyUri() {
            return proxyUri;
        }

        String encodedTarget() {
            return encodedTarget;
        }

        String remoteAddress() {
            return remoteAddress;
        }

        Map<String, List<String>> frontendRequestHeaders() {
            return frontendRequestHeaders;
        }

        String nextExchangeId() {
            return flowId + ":" + exchangeCounter.incrementAndGet();
        }
    }
}
