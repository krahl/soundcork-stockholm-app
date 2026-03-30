package com.soundcork.stockholm.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BackendConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendConfig.class);
    private static final int DEFAULT_FRONTEND_LOGGING_LEVEL = 0;

    private final int frontendLoggingLevel;

    private BackendConfig(int frontendLoggingLevel) {
        this.frontendLoggingLevel = Math.max(0, frontendLoggingLevel);
    }

    static BackendConfig load(Path workspaceRoot) {
        Path configFile = workspaceRoot.resolve("backend").resolve("config").resolve("backend-config.json").normalize();
        if (!Files.exists(configFile)) {
            LOGGER.debug("No backend config found at {}, using defaults", configFile);
            return new BackendConfig(DEFAULT_FRONTEND_LOGGING_LEVEL);
        }

        try {
            Object parsed = SimpleJson.parse(Files.readString(configFile, StandardCharsets.UTF_8));
            Map<String, Object> object = SimpleJson.asObject(parsed);
            int loggingLevel = parseFrontendLoggingLevel(object.get("frontendLoggingLevel"));
            LOGGER.debug("Loaded backend config from {} with frontendLoggingLevel={}", configFile, loggingLevel);
            return new BackendConfig(loggingLevel);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to load backend config from {}, using defaults", configFile, exception);
            return new BackendConfig(DEFAULT_FRONTEND_LOGGING_LEVEL);
        }
    }

    int frontendLoggingLevel() {
        return frontendLoggingLevel;
    }

    boolean shouldEnableFrontendDebug() {
        return frontendLoggingLevel > 0;
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
}
