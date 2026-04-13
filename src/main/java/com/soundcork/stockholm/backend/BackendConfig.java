package com.soundcork.stockholm.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BackendConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendConfig.class);
    private static final int DEFAULT_FRONTEND_LOGGING_LEVEL = 0;
    private static final String DEFAULT_HTTP_CAPTURE_DIRECTORY = "backend/state/http-capture";
    private static final int DEFAULT_HTTP_CAPTURE_MAX_TEXT_BODY_BYTES = 65_536;
    private static final int DEFAULT_HTTP_CAPTURE_MAX_BINARY_BODY_BYTES = 8_192;

    private final int frontendLoggingLevel;
    private final HttpCaptureConfig httpCapture;

    private BackendConfig(int frontendLoggingLevel, HttpCaptureConfig httpCapture) {
        this.frontendLoggingLevel = Math.max(0, frontendLoggingLevel);
        this.httpCapture = httpCapture;
    }

    static BackendConfig load(Path workspaceRoot) {
        Path configFile = workspaceRoot.resolve("backend").resolve("config").resolve("backend-config.json").normalize();
        if (!Files.exists(configFile)) {
            LOGGER.debug("No backend config found at {}, using defaults", configFile);
            return defaults(workspaceRoot);
        }

        try {
            Object parsed = SimpleJson.parse(Files.readString(configFile, StandardCharsets.UTF_8));
            Map<String, Object> object = SimpleJson.asObject(parsed);
            int loggingLevel = parseFrontendLoggingLevel(object.get("frontendLoggingLevel"));
            HttpCaptureConfig httpCapture = parseHttpCaptureConfig(workspaceRoot, object.get("httpCapture"));
            LOGGER.debug(
                    "Loaded backend config from {} with frontendLoggingLevel={} and httpCaptureEnabled={} mode={}",
                    configFile,
                    loggingLevel,
                    httpCapture.enabled(),
                    httpCapture.mode());
            return new BackendConfig(loggingLevel, httpCapture);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to load backend config from {}, using defaults", configFile, exception);
            return defaults(workspaceRoot);
        }
    }

    int frontendLoggingLevel() {
        return frontendLoggingLevel;
    }

    boolean shouldEnableFrontendDebug() {
        return frontendLoggingLevel > 0;
    }

    HttpCaptureConfig httpCapture() {
        return httpCapture;
    }

    private static BackendConfig defaults(Path workspaceRoot) {
        return new BackendConfig(
                DEFAULT_FRONTEND_LOGGING_LEVEL,
                parseHttpCaptureConfig(workspaceRoot, null));
    }

    private static int parseFrontendLoggingLevel(Object value) {
        if (value == null) {
            return DEFAULT_FRONTEND_LOGGING_LEVEL;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String stringValue = String.valueOf(value).trim();
        if (stringValue.isEmpty()) {
            return DEFAULT_FRONTEND_LOGGING_LEVEL;
        }
        return Integer.parseInt(stringValue);
    }

    private static HttpCaptureConfig parseHttpCaptureConfig(Path workspaceRoot, Object value) {
        Path defaultDirectory = workspaceRoot.resolve(DEFAULT_HTTP_CAPTURE_DIRECTORY).normalize();
        if (!(value instanceof Map<?, ?> captureObject)) {
            return new HttpCaptureConfig(
                    false,
                    HttpCaptureMode.PSEUDONYMIZE,
                    defaultDirectory,
                    DEFAULT_HTTP_CAPTURE_MAX_TEXT_BODY_BYTES,
                    DEFAULT_HTTP_CAPTURE_MAX_BINARY_BODY_BYTES,
                    true);
        }

        Map<String, Object> object = SimpleJson.asObject(captureObject);
        boolean enabled = parseBoolean(object.get("enabled"), false);
        HttpCaptureMode mode = parseHttpCaptureMode(object.get("mode"));
        Path directory = parseDirectory(workspaceRoot, object.get("directory"), defaultDirectory);
        int maxTextBodyBytes = parseNonNegativeInt(
                object.get("maxTextBodyBytes"),
                DEFAULT_HTTP_CAPTURE_MAX_TEXT_BODY_BYTES);
        int maxBinaryBodyBytes = parseNonNegativeInt(
                object.get("maxBinaryBodyBytes"),
                DEFAULT_HTTP_CAPTURE_MAX_BINARY_BODY_BYTES);
        boolean includeProxyErrors = parseBoolean(object.get("includeProxyErrors"), true);
        return new HttpCaptureConfig(
                enabled,
                mode,
                directory,
                maxTextBodyBytes,
                maxBinaryBodyBytes,
                includeProxyErrors);
    }

    private static HttpCaptureMode parseHttpCaptureMode(Object value) {
        if (value == null) {
            return HttpCaptureMode.PSEUDONYMIZE;
        }
        String stringValue = String.valueOf(value).trim();
        if (stringValue.isEmpty()) {
            return HttpCaptureMode.PSEUDONYMIZE;
        }
        try {
            return HttpCaptureMode.valueOf(stringValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return HttpCaptureMode.PSEUDONYMIZE;
        }
    }

    private static Path parseDirectory(Path workspaceRoot, Object value, Path defaultDirectory) {
        if (value == null) {
            return defaultDirectory;
        }
        String stringValue = String.valueOf(value).trim();
        if (stringValue.isEmpty()) {
            return defaultDirectory;
        }
        Path path = Path.of(stringValue);
        if (!path.isAbsolute()) {
            path = workspaceRoot.resolve(path);
        }
        return path.normalize();
    }

    private static int parseNonNegativeInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        String stringValue = String.valueOf(value).trim();
        if (stringValue.isEmpty()) {
            return defaultValue;
        }
        return Math.max(0, Integer.parseInt(stringValue));
    }

    private static boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String stringValue = String.valueOf(value).trim();
        if (stringValue.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(stringValue);
    }

    enum HttpCaptureMode {
        RAW,
        REDACT,
        PSEUDONYMIZE
    }

    record HttpCaptureConfig(
            boolean enabled,
            HttpCaptureMode mode,
            Path directory,
            int maxTextBodyBytes,
            int maxBinaryBodyBytes,
            boolean includeProxyErrors) {
    }
}
