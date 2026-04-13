package com.soundcork.stockholm.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsHttpCaptureSettingsWhenConfigIsMissing() {
        Path workspaceRoot = tempDir.resolve("workspace");
        BackendConfig config = BackendConfig.load(workspaceRoot);

        assertEquals(0, config.frontendLoggingLevel());
        assertFalse(config.shouldEnableFrontendDebug());
        assertFalse(config.httpCapture().enabled());
        assertEquals(BackendConfig.HttpCaptureMode.PSEUDONYMIZE, config.httpCapture().mode());
        assertEquals(
                workspaceRoot.resolve("backend").resolve("state").resolve("http-capture").normalize(),
                config.httpCapture().directory());
        assertEquals(65_536, config.httpCapture().maxTextBodyBytes());
        assertEquals(8_192, config.httpCapture().maxBinaryBodyBytes());
        assertTrue(config.httpCapture().includeProxyErrors());
    }

    @Test
    void parsesCustomHttpCaptureSettings() throws Exception {
        Path workspaceRoot = tempDir.resolve("workspace");
        Path configDir = workspaceRoot.resolve("backend").resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("backend-config.json"), """
                {
                  "frontendLoggingLevel": 3,
                  "httpCapture": {
                    "enabled": true,
                    "mode": "raw",
                    "directory": "captures/http",
                    "maxTextBodyBytes": 1234,
                    "maxBinaryBodyBytes": 456,
                    "includeProxyErrors": false
                  }
                }
                """, StandardCharsets.UTF_8);

        BackendConfig config = BackendConfig.load(workspaceRoot);

        assertEquals(3, config.frontendLoggingLevel());
        assertTrue(config.shouldEnableFrontendDebug());
        assertTrue(config.httpCapture().enabled());
        assertEquals(BackendConfig.HttpCaptureMode.RAW, config.httpCapture().mode());
        assertEquals(workspaceRoot.resolve("captures").resolve("http").normalize(), config.httpCapture().directory());
        assertEquals(1234, config.httpCapture().maxTextBodyBytes());
        assertEquals(456, config.httpCapture().maxBinaryBodyBytes());
        assertFalse(config.httpCapture().includeProxyErrors());
    }
}
