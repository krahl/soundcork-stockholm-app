package com.soundcork.stockholm.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NativeBridgeService implements AutoCloseable {
    private static final String DEFAULT_CLIENT_ID = "default";
    private static final String UNSUPPORTED = "unsupported";
    private static final String MARGE_AUTH_TOKEN_KEY = "margeAuthToken";
    private static final String MARGE_ACCOUNT_ID_KEY = "margeAccountID";
    private static final Map<String, String> DEFAULT_CONSTANTS = Map.of(
            "kilo", "a7928d7b43dcd49f0af31e5aeed26458"
    );
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeBridgeService.class);

    private final Path stateFile;
    private final SsdpDiscoveryService discoveryService;
    private final ConcurrentMap<String, String> state = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ArrayDeque<Map<String, Object>>> queues = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    NativeBridgeService(Path stateFile) {
        this(stateFile, System.getenv());
    }

    NativeBridgeService(Path stateFile, Map<String, String> environment) {
        this.stateFile = stateFile;
        this.discoveryService = new SsdpDiscoveryService();
        loadState();
        seedDefaultConstants();
        seedMargeSessionFromEnvironment(environment);
        LOGGER.debug("NativeBridgeService initialized with state file {}", stateFile);
    }

    void handleAppSend(String clientId, String payload) {
        String queueId = normalizeClientId(clientId);
        Object id = null;

        try {
            Map<String, Object> request = SimpleJson.asObject(SimpleJson.parse(payload));
            String method = stringValue(request.get("method"));
            id = request.get("id");
            Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
                    ? SimpleJson.asObject(map)
                    : Map.of();
            LOGGER.debug("Handling method '{}' for client '{}'", method, queueId);

            if (method == null || method.isBlank()) {
                LOGGER.debug("Rejecting empty method for client '{}'", queueId);
                enqueueCallbackError(queueId, id, "invalid_method");
                return;
            }

            switch (method) {
                case "locale", "htmlReady", "stopHrmsUpdates" -> {
                    LOGGER.debug("No backend action required for method '{}' on client '{}'", method, queueId);
                }
                case "log" -> {
                    if (params.containsKey("msg")) {
                        LOGGER.debug("[Stockholm:{}] {}", queueId, stringValue(params.get("msg")));
                    }
                }
                case "setData" -> {
                    String name = stringValue(params.get("name"));
                    String value = params.containsKey("value") ? stringifyScalar(params.get("value")) : "";
                    if (name != null && !name.isBlank()) {
                        state.put(name, value);
                        LOGGER.debug("Updated persisted state key '{}' for client '{}'", name, queueId);
                        persistState();
                    }
                }
                case "getData" -> enqueueCallbackResult(queueId, id, state.getOrDefault(stringValue(params.get("name")), ""), "");
                case "getLanStatus" -> enqueueCallbackResult(queueId, id, Boolean.TRUE, null);
                case "getTimeZone" -> enqueueCallbackResult(queueId, id, createTimeZoneInfo(), "");
                case "getLegalDocPath" -> enqueueCallbackResult(queueId, id, getLegalDocPath(params), null);
                case "getConstant" -> enqueueCallbackResult(queueId, id, getConstant(params), "");
                case "canPerformAutoAPSetup" -> enqueueCallbackResult(queueId, id, createAutoApSetupInfo(), "");
                case "getDeviceList" -> submitAsync(queueId, id, () -> {
                    String accountId = blankToNull(state.get("margeAccountID"));
                    LOGGER.debug("Starting renderer discovery for client '{}'", queueId);
                    List<Map<String, Object>> devices = discoveryService.discoverRenderers(
                            accountId,
                            partial -> enqueueMethod(queueId, "devices", partial));
                    LOGGER.debug("Renderer discovery finished with {} device(s) for client '{}'",
                            devices.size(), queueId);
                    if (devices.isEmpty()) {
                        enqueueMethod(queueId, "devices", devices);
                    }
                });
                case "getHrmsList" -> submitAsync(queueId, id, () -> {
                    LOGGER.debug("Starting media-server discovery for client '{}'", queueId);
                    List<Map<String, Object>> servers = discoveryService.discoverServers();
                    LOGGER.debug("Media-server discovery finished with {} server(s) for client '{}'",
                            servers.size(), queueId);
                    enqueueMethod(queueId, "servers", servers);
                });
                case "getNetStats", "getSSIDList", "setSSID", "updateSetting", "oauth", "downloadNewGui",
                        "installNewGui", "sendLogs", "socketCreate", "socketSend", "socketClose" ->
                        enqueueUnsupportedMethod(queueId, id, method);
                default -> enqueueUnsupportedMethod(queueId, id, method);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to handle appSend payload for client '{}'", queueId, exception);
            enqueueCallbackError(queueId, id, exception.getMessage() == null ? "bridge_error" : exception.getMessage());
        }
    }

    String runQueue(String clientId) {
        String queueId = normalizeClientId(clientId);
        ArrayDeque<Map<String, Object>> queue = queues.computeIfAbsent(queueId, ignored -> new ArrayDeque<>());
        List<Map<String, Object>> messages = new ArrayList<>();
        synchronized (queue) {
            while (!queue.isEmpty()) {
                messages.add(queue.removeFirst());
            }
        }
        if (!messages.isEmpty()) {
            LOGGER.debug("Drained {} queued message(s) for client '{}'", messages.size(), queueId);
        }
        LinkedHashMap<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("messages", messages.isEmpty() ? null : messages);
        return SimpleJson.stringify(wrapper);
    }

    String getStateValue(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return state.get(name);
    }

    void putStateValue(String name, String value) {
        if (name == null || name.isBlank()) {
            return;
        }
        putStateValues(Map.of(name, value == null ? "" : value));
    }

    void putStateValues(Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            String previous = state.put(name, value);
            if (!value.equals(previous)) {
                changed = true;
            }
        }
        if (changed) {
            persistState();
        }
    }

    private void enqueueMethod(String clientId, String method, Object params) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", method);
        payload.put("params", params);
        payload.put("id", null);
        enqueue(clientId, payload);
    }

    private void enqueueCallbackResult(String clientId, Object id, Object result, Object error) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", result);
        payload.put("error", error);
        payload.put("id", id);
        enqueue(clientId, payload);
    }

    private void enqueueCallbackError(String clientId, Object id, String error) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", null);
        payload.put("error", error);
        payload.put("id", id);
        enqueue(clientId, payload);
    }

    private void enqueue(String clientId, Map<String, Object> payload) {
        ArrayDeque<Map<String, Object>> queue = queues.computeIfAbsent(clientId, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(payload);
        }
    }

    private void submitAsync(String clientId, Object id, Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException exception) {
                LOGGER.warn("Asynchronous backend task failed for client '{}'", clientId, exception);
                enqueueCallbackError(clientId, id, exception.getMessage() == null ? "bridge_error" : exception.getMessage());
            }
        });
    }

    private void enqueueUnsupportedMethod(String clientId, Object id, String method) {
        LOGGER.debug("Method '{}' is unsupported for client '{}'", method, clientId);
        enqueueCallbackError(clientId, id, UNSUPPORTED);
    }

    private Map<String, Object> createTimeZoneInfo() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("timezoneInfo", ZoneId.systemDefault().getId());
        result.put("timeFormat", "TIME_FORMAT_24HOUR_ID");
        return result;
    }

    private Map<String, Object> createAutoApSetupInfo() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("permission", Boolean.FALSE);
        result.put("location", Boolean.FALSE);
        return result;
    }

    private Object getConstant(Map<String, Object> params) {
        String name = stringValue(params.get("name"));
        if (name == null) {
            return "";
        }
        return state.getOrDefault("constant." + name, DEFAULT_CONSTANTS.getOrDefault(name, ""));
    }

    private void seedDefaultConstants() {
        boolean updated = false;
        for (Map.Entry<String, String> entry : DEFAULT_CONSTANTS.entrySet()) {
            String key = "constant." + entry.getKey();
            if (state.putIfAbsent(key, entry.getValue()) == null) {
                updated = true;
            }
        }
        if (updated) {
            persistState();
        }
    }

    private void seedMargeSessionFromEnvironment(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        String authToken = firstNonBlank(
                environment.get(MARGE_AUTH_TOKEN_KEY),
                environment.get("MARGE_AUTH_TOKEN"));
        String accountId = firstNonBlank(
                environment.get(MARGE_ACCOUNT_ID_KEY),
                environment.get("MARGE_ACCOUNT_ID"));
        if (authToken != null) {
            updates.put(MARGE_AUTH_TOKEN_KEY, authToken);
        }
        if (accountId != null) {
            updates.put(MARGE_ACCOUNT_ID_KEY, accountId);
        }
        if (!updates.isEmpty()) {
            LOGGER.info("Seeding Marge session value(s) from environment: {}", updates.keySet());
            putStateValues(updates);
        }
    }

    private String getLegalDocPath(Map<String, Object> params) {
        String type = stringValue(params.get("type"));
        String lang = stringValue(params.get("lang"));
        if ("lcns".equals(type)) {
            return "legal/platform_license.txt";
        }
        if (type == null || type.isBlank()) {
            return "legal/eula_en.txt";
        }
        String safeLang = (lang == null || lang.isBlank()) ? "en" : lang;
        return "legal/" + type + "_" + safeLang + ".txt";
    }

    private void loadState() {
        if (!Files.exists(stateFile)) {
            LOGGER.debug("No persisted native bridge state found at {}", stateFile);
            return;
        }
        try {
            Object parsed = SimpleJson.parse(Files.readString(stateFile, StandardCharsets.UTF_8));
            Map<String, Object> object = SimpleJson.asObject(parsed);
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                state.put(entry.getKey(), stringifyScalar(entry.getValue()));
            }
            LOGGER.debug("Loaded {} persisted state entries from {}", state.size(), stateFile);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to load persisted native bridge state from {}", stateFile, exception);
        }
    }

    private void persistState() {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, SimpleJson.stringify(new LinkedHashMap<>(state)), StandardCharsets.UTF_8);
            LOGGER.debug("Persisted {} state entries to {}", state.size(), stateFile);
        } catch (IOException exception) {
            LOGGER.warn("Failed to persist native bridge state to {}", stateFile, exception);
        }
    }

    private String normalizeClientId(String clientId) {
        return clientId == null || clientId.isBlank() ? DEFAULT_CLIENT_ID : clientId;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String stringifyScalar(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return SimpleJson.stringify(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @Override
    public void close() {
        LOGGER.debug("Shutting down NativeBridgeService executor");
        executor.shutdownNow();
    }
}
