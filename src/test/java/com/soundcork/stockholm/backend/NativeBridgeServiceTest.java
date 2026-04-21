package com.soundcork.stockholm.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NativeBridgeServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void envMargeSessionPopulatesEmptyNativeState() throws IOException {
        Path stateFile = tempDir.resolve("native-state.json");

        try (NativeBridgeService bridgeService = new NativeBridgeService(stateFile, Map.of(
                "margeAuthToken", "token-from-env",
                "margeAccountID", "1234567"))) {
            assertEquals("token-from-env", bridgeService.getStateValue("margeAuthToken"));
            assertEquals("1234567", bridgeService.getStateValue("margeAccountID"));
        }

        Map<String, Object> persisted = SimpleJson.asObject(SimpleJson.parse(Files.readString(stateFile, StandardCharsets.UTF_8)));
        assertEquals("token-from-env", persisted.get("margeAuthToken"));
        assertEquals("1234567", persisted.get("margeAccountID"));
    }

    @Test
    void envMargeSessionOverridesPersistedNativeState() throws IOException {
        Path stateFile = tempDir.resolve("native-state.json");
        Files.writeString(stateFile, """
                {"margeAuthToken":"old-token","margeAccountID":"7654321"}
                """, StandardCharsets.UTF_8);

        try (NativeBridgeService bridgeService = new NativeBridgeService(stateFile, Map.of(
                "MARGE_AUTH_TOKEN", "token-from-alias",
                "MARGE_ACCOUNT_ID", "2345678"))) {
            assertEquals("token-from-alias", bridgeService.getStateValue("margeAuthToken"));
            assertEquals("2345678", bridgeService.getStateValue("margeAccountID"));
        }
    }

    @Test
    void absentEnvPreservesPersistedNativeState() throws IOException {
        Path stateFile = tempDir.resolve("native-state.json");
        Files.writeString(stateFile, """
                {"margeAuthToken":"persisted-token","margeAccountID":"3456789"}
                """, StandardCharsets.UTF_8);

        try (NativeBridgeService bridgeService = new NativeBridgeService(stateFile, Map.of())) {
            assertEquals("persisted-token", bridgeService.getStateValue("margeAuthToken"));
            assertEquals("3456789", bridgeService.getStateValue("margeAccountID"));
        }
    }

    @Test
    void getDataReturnsEnvSeededMargeValues() {
        Path stateFile = tempDir.resolve("native-state.json");

        try (NativeBridgeService bridgeService = new NativeBridgeService(stateFile, Map.of(
                "margeAuthToken", "token-for-get-data",
                "margeAccountID", "4567890"))) {
            bridgeService.handleAppSend("test-client", """
                    {"method":"getData","params":{"name":"margeAuthToken"},"id":7}
                    """);

            Map<String, Object> wrapper = SimpleJson.asObject(SimpleJson.parse(bridgeService.runQueue("test-client")));
            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) wrapper.get("messages");
            Map<String, Object> message = SimpleJson.asObject(messages.get(0));

            assertEquals("token-for-get-data", message.get("result"));
            assertEquals(Long.valueOf(7), message.get("id"));
        }
    }

    @Test
    void exactEnvNamesTakePrecedenceOverAliases() {
        Path stateFile = tempDir.resolve("native-state.json");

        try (NativeBridgeService bridgeService = new NativeBridgeService(stateFile, Map.of(
                "margeAuthToken", "exact-token",
                "MARGE_AUTH_TOKEN", "alias-token",
                "margeAccountID", "5678901",
                "MARGE_ACCOUNT_ID", "6789012"))) {
            assertEquals("exact-token", bridgeService.getStateValue("margeAuthToken"));
            assertEquals("5678901", bridgeService.getStateValue("margeAccountID"));
        }
    }
}
