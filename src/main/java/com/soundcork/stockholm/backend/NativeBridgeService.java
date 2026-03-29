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

final class NativeBridgeService implements AutoCloseable {
    private static final String DEFAULT_CLIENT_ID = "default";
    private static final String UNSUPPORTED = "unsupported";

    private final Path stateFile;
    private final SsdpDiscoveryService discoveryService;
    private final ConcurrentMap<String, String> state = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ArrayDeque<Map<String, Object>>> queues = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    NativeBridgeService(Path stateFile) {
        this.stateFile = stateFile;
        this.discoveryService = new SsdpDiscoveryService();
        loadState();
    }

    void handleAppSend(String clientId, String payload) {
        String queueId = normalizeClientId(clientId);
        Map<String, Object> request = SimpleJson.asObject(SimpleJson.parse(payload));
        String method = stringValue(request.get("method"));
        Object id = request.get("id");
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
                ? SimpleJson.asObject(map)
                : Map.of();

        if (method == null || method.isBlank()) {
            enqueueCallbackError(queueId, id, "invalid_method");
            return;
        }

        try {
            switch (method) {
                case "locale", "htmlReady", "stopHrmsUpdates" -> {
                    // No server work is needed here.
                }
                case "log" -> {
                    if (params.containsKey("msg")) {
                        System.out.println("[Stockholm] " + stringValue(params.get("msg")));
                    }
                }
                case "setData" -> {
                    String name = stringValue(params.get("name"));
                    String value = params.containsKey("value") ? stringifyScalar(params.get("value")) : "";
                    if (name != null && !name.isBlank()) {
                        state.put(name, value);
                        persistState();
                    }
                }
                case "getData" -> enqueueCallbackResult(queueId, id, state.getOrDefault(stringValue(params.get("name")), ""), "");
                case "getLanStatus" -> enqueueCallbackResult(queueId, id, Boolean.TRUE, null);
                case "getTimeZone" -> enqueueCallbackResult(queueId, id, createTimeZoneInfo(), "");
                case "getLegalDocPath" -> enqueueCallbackResult(queueId, id, getLegalDocPath(params), null);
                case "getConstant" -> enqueueCallbackResult(queueId, id, getConstant(params), "");
                case "canPerformAutoAPSetup" -> enqueueCallbackResult(queueId, id, createAutoApSetupInfo(), "");
                case "getDeviceList" -> executor.submit(() -> enqueueMethod(queueId, "devices", discoveryService.discoverRenderers()));
                case "getHrmsList" -> executor.submit(() -> enqueueMethod(queueId, "servers", discoveryService.discoverServers()));
                case "getNetStats", "getSSIDList", "setSSID", "updateSetting", "oauth", "downloadNewGui",
                        "installNewGui", "sendLogs", "socketCreate", "socketSend", "socketClose" ->
                        enqueueCallbackError(queueId, id, UNSUPPORTED);
                default -> enqueueCallbackError(queueId, id, UNSUPPORTED);
            }
        } catch (RuntimeException exception) {
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
        LinkedHashMap<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("messages", messages.isEmpty() ? null : messages);
        return SimpleJson.stringify(wrapper);
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
        return state.getOrDefault("constant." + name, "");
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
            return;
        }
        try {
            Object parsed = SimpleJson.parse(Files.readString(stateFile, StandardCharsets.UTF_8));
            Map<String, Object> object = SimpleJson.asObject(parsed);
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                state.put(entry.getKey(), stringifyScalar(entry.getValue()));
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void persistState() {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, SimpleJson.stringify(new LinkedHashMap<>(state)), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
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

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
