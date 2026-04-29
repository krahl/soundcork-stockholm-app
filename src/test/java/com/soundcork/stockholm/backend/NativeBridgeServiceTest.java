package com.soundcork.stockholm.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

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

    @Test
    void getDeviceListEmitsCachedDevicesBeforeDiscoveryByDefault() throws IOException {
        Path stateFile = tempDir.resolve("native-state.json");
        Files.writeString(stateFile, """
                {"deviceCache":"{\\"array\\":[{\\"ip\\":\\"192.168.1.10\\",\\"uID\\":\\"cached-device\\",\\"name\\":\\"Kitchen\\",\\"type\\":\\"ST10\\"}]}"}
                """, StandardCharsets.UTF_8);

        try (NativeBridgeService bridgeService = new NativeBridgeService(
                stateFile,
                Map.of(),
                new FakeDiscoveryService(List.of(device("discovered-device", "192.168.1.11"))))) {
            requestDeviceList(bridgeService);

            List<Map<String, Object>> messages = waitForMessages(bridgeService, 2);
            assertDeviceMessage(messages.get(0), "cached-device", "192.168.1.10");
            assertDeviceMessage(messages.get(1), "discovered-device", "192.168.1.11");
        }
    }

    @Test
    void getDeviceListSkipsCachedDevicesWhenDisabled() throws IOException {
        Path stateFile = tempDir.resolve("native-state.json");
        Files.writeString(stateFile, """
                {"deviceCache":"{\\"array\\":[{\\"ip\\":\\"192.168.1.10\\",\\"uID\\":\\"cached-device\\"}]}"}
                """, StandardCharsets.UTF_8);

        try (NativeBridgeService bridgeService = new NativeBridgeService(
                stateFile,
                Map.of("BACKEND_DEVICE_CACHE_ENABLED", "false"),
                new FakeDiscoveryService(List.of(device("discovered-device", "192.168.1.11"))))) {
            requestDeviceList(bridgeService);

            List<Map<String, Object>> messages = waitForMessages(bridgeService, 1);
            assertDeviceMessage(messages.get(0), "discovered-device", "192.168.1.11");
            assertFalse(hasMoreMessages(bridgeService));
        }
    }

    @Test
    void malformedDeviceCacheDoesNotFailDiscovery() throws IOException {
        Path stateFile = tempDir.resolve("native-state.json");
        Files.writeString(stateFile, """
                {"deviceCache":"not-json"}
                """, StandardCharsets.UTF_8);

        try (NativeBridgeService bridgeService = new NativeBridgeService(
                stateFile,
                Map.of(),
                new FakeDiscoveryService(List.of()))) {
            requestDeviceList(bridgeService);

            List<Map<String, Object>> messages = waitForMessages(bridgeService, 1);
            assertEquals("devices", messages.get(0).get("method"));
            assertEquals(List.of(), messages.get(0).get("params"));
        }
    }

    @Test
    void getDeviceListDoesNotDuplicateCachedDiscoveryMatch() throws IOException {
        Path stateFile = tempDir.resolve("native-state.json");
        Files.writeString(stateFile, """
                {"deviceCache":"{\\"array\\":[{\\"ip\\":\\"192.168.1.10\\",\\"uID\\":\\"cached-device\\"}]}"}
                """, StandardCharsets.UTF_8);

        try (NativeBridgeService bridgeService = new NativeBridgeService(
                stateFile,
                Map.of(),
                new FakeDiscoveryService(List.of(device("cached-device", "192.168.1.10"))))) {
            requestDeviceList(bridgeService);

            List<Map<String, Object>> messages = waitForMessages(bridgeService, 1);
            assertDeviceMessage(messages.get(0), "cached-device", "192.168.1.10");
            assertFalse(hasMoreMessages(bridgeService));
        }
    }

    private void requestDeviceList(NativeBridgeService bridgeService) {
        bridgeService.handleAppSend("test-client", """
                {"method":"getDeviceList","params":null,"id":1}
                """);
    }

    private List<Map<String, Object>> waitForMessages(NativeBridgeService bridgeService, int count) {
        ArrayList<Map<String, Object>> messages = new ArrayList<>();
        for (int attempt = 0; attempt < 100; attempt++) {
            messages.addAll(drainMessages(bridgeService));
            if (messages.size() >= count) {
                return messages;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for bridge messages");
            }
        }
        fail("Timed out waiting for " + count + " bridge message(s), got " + messages.size());
        return messages;
    }

    private boolean hasMoreMessages(NativeBridgeService bridgeService) {
        try {
            Thread.sleep(50);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("Interrupted while checking bridge messages");
        }
        return !drainMessages(bridgeService).isEmpty();
    }

    private List<Map<String, Object>> drainMessages(NativeBridgeService bridgeService) {
        Map<String, Object> wrapper = SimpleJson.asObject(SimpleJson.parse(bridgeService.runQueue("test-client")));
        Object rawMessages = wrapper.get("messages");
        if (!(rawMessages instanceof List<?> list)) {
            return List.of();
        }
        ArrayList<Map<String, Object>> messages = new ArrayList<>();
        for (Object message : list) {
            messages.add(SimpleJson.asObject(message));
        }
        return messages;
    }

    private void assertDeviceMessage(Map<String, Object> message, String uid, String ip) {
        assertEquals("devices", message.get("method"));
        @SuppressWarnings("unchecked")
        List<Object> params = (List<Object>) message.get("params");
        assertEquals(1, params.size());
        Map<String, Object> device = SimpleJson.asObject(params.get(0));
        assertEquals(uid, device.get("uID"));
        assertEquals(ip, device.get("ip"));
    }

    private Map<String, Object> device(String uid, String ip) {
        LinkedHashMap<String, Object> device = new LinkedHashMap<>();
        device.put("uID", uid);
        device.put("ip", ip);
        return device;
    }

    private static final class FakeDiscoveryService implements NativeBridgeService.DiscoveryService {
        private final List<Map<String, Object>> devices;

        private FakeDiscoveryService(List<Map<String, Object>> devices) {
            this.devices = devices;
        }

        @Override
        public List<Map<String, Object>> discoverRenderers(String expectedAccountId,
                Consumer<List<Map<String, Object>>> onDiscovered) {
            for (Map<String, Object> device : devices) {
                onDiscovered.accept(List.of(device));
            }
            return devices;
        }

        @Override
        public List<Map<String, Object>> discoverServers() {
            return List.of();
        }
    }
}
