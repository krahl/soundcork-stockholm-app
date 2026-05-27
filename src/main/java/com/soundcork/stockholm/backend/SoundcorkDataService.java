package com.soundcork.stockholm.backend;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SoundcorkDataService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundcorkDataService.class);
    private static final String DEFAULT_CLIENT_TYPE = "SOUNDTOUCH_COMPUTER_APP";
    private static final String MOBILE_CLIENT_TYPE = "SOUNDTOUCH_MOBILE_APP";
    private static final String DEFAULT_STREAMING_VERSION = "1.0";
    private static final String DEFAULT_CUSTOMER_VERSION = "1.0";
    private static final String DEFAULT_AUTH_SERVER = "0";
    private static final Pattern VERSION_PREFIX = Pattern.compile("^(\\d+(?:\\.\\d+)+)");

    private final NativeBridgeService bridgeService;
    private final Path configFile;
    private final Path overrideFile;
    private final String streamingVersion;
    private final String customerVersion;
    private final String protocolVersion;
    private final String soundcorkAppVersion;
    private final String defaultMargeUrl;
    private final String defaultUpdateUrl;
    private final String defaultBmxRegistryUrl;
    private final String encryptedBmxToken;
    private final String encryptedBmxServerUrlAlt;
    private final String overrideKilo;
    private final String margeServerKey;
    private final String margeServerKeyHeader;

    SoundcorkDataService(Path workspaceRoot, NativeBridgeService bridgeService) {
        this.bridgeService = bridgeService;
        this.configFile = workspaceRoot.resolve("stockholm").resolve("json").resolve("config.json").normalize();
        this.overrideFile = workspaceRoot.resolve("stockholm").resolve("json").resolve("override.json").normalize();
        Map<String, Object> config = loadObject(configFile);
        Map<String, Object> appVersions = childObject(config, "app_versions");
        Map<String, Object> apiVersions = childObject(config, "api_versions");
        Map<String, Object> defaults = childObject(config, "default");
        this.streamingVersion = firstNonBlank(stringValue(apiVersions.get("bose_streaming")), DEFAULT_STREAMING_VERSION);
        this.customerVersion = firstNonBlank(stringValue(apiVersions.get("bose_customer")), DEFAULT_CUSTOMER_VERSION);
        this.protocolVersion = stringValue(appVersions.get("bose_protocol"));
        this.soundcorkAppVersion = stringValue(appVersions.get("bose_app"));
        this.defaultMargeUrl = normalizeBaseUrl(SoundcorkCrypto.decodeBase64String(stringValue(defaults.get("d0"))));
        this.defaultUpdateUrl = normalizeBaseUrl(SoundcorkCrypto.decodeBase64String(stringValue(defaults.get("d1"))));
        this.defaultBmxRegistryUrl = blankToNull(SoundcorkCrypto.decodeBase64String(stringValue(defaults.get("d3"))));
        this.encryptedBmxToken = SoundcorkCrypto.decodeBase64String(stringValue(defaults.get("d7")));
        this.encryptedBmxServerUrlAlt = SoundcorkCrypto.decodeBase64String(stringValue(defaults.get("d8")));
        this.overrideKilo = stringValue(loadObject(overrideFile).get("kilo"));
        this.margeServerKey = SoundcorkCrypto.decodeBase64String(stringValue(defaults.get("d10")));
        this.margeServerKeyHeader = SoundcorkCrypto.decodeBase64String(stringValue(defaults.get("d13")));
        seedBrowserRuntimeState(NativeBridgeService.DEFAULT_CLIENT_ID);
        LOGGER.debug("Loaded SoundcorkDataService using config={} override={}", configFile, overrideFile);
    }

    String authServer() {
        return authServer(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    String authServer(String clientId) {
        return normalizeAuthServer(bridgeService.getStateValue(clientId, "authServer"));
    }

    String soundcorkAppVersion() {
        return soundcorkAppVersion;
    }

    String protocolVersion() {
        return protocolVersion;
    }

    String streamingMediaType() {
        return "application/vnd.bose.streaming-v" + streamingVersion + "+xml";
    }

    String customerMediaType() {
        return "application/vnd.bose.customer-v" + customerVersion + "+xml";
    }

    String mediaTypeForPath(String path) {
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (normalizedPath.contains("/customer/")) {
            return customerMediaType();
        }
        if (normalizedPath.contains("/streaming/")) {
            return streamingMediaType();
        }
        return "application/xml";
    }

    String defaultClientType() {
        return DEFAULT_CLIENT_TYPE;
    }

    String mobileClientType() {
        return MOBILE_CLIENT_TYPE;
    }

    String nativeFrameVersion() {
        return nativeFrameVersion(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    String nativeFrameVersion(String clientId) {
        return firstNonBlank(
                blankToNull(bridgeService.getStateValue(clientId, "nativeFrameVersion")),
                extractVersionPrefix(bridgeService.getStateValue(clientId, "frame_version")),
                shortNativeVersion(clientId));
    }

    String fullNativeVersion() {
        return fullNativeVersion(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    String fullNativeVersion(String clientId) {
        return firstNonBlank(
                blankToNull(bridgeService.getStateValue(clientId, "frame_version")),
                blankToNull(soundcorkAppVersion),
                shortNativeVersion(clientId));
    }

    String shortNativeVersion() {
        return shortNativeVersion(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    String shortNativeVersion(String clientId) {
        return firstNonBlank(
                extractVersionPrefix(bridgeService.getStateValue(clientId, "nativeFrameVersion")),
                extractVersionPrefix(bridgeService.getStateValue(clientId, "frame_version")),
                extractVersionPrefix(soundcorkAppVersion));
    }

    String guid() {
        return guid(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    String guid(String clientId) {
        return firstNonBlank(
                blankToNull(bridgeService.getStateValue(clientId, "guid")),
                blankToNull(bridgeService.getStateValue(clientId, "deviceGuid")));
    }

    String margeServerKey() {
        return blankToNull(margeServerKey);
    }

    String margeServerKeyHeader() {
        return blankToNull(margeServerKeyHeader);
    }

    String overrideMargeUrl() {
        return overrideMargeUrl(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    String overrideMargeUrl(String clientId) {
        return normalizeBaseUrl(bridgeService.getStateValue(clientId, "overrideMargeURL"));
    }

    String currentMargeUrl() {
        return currentMargeUrl(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    String currentMargeUrl(String clientId) {
        return firstNonBlank(overrideMargeUrl(clientId), defaultMargeUrl);
    }

    String currentUpdateUrl() {
        return currentUpdateUrl(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    String currentUpdateUrl(String clientId) {
        return firstNonBlank(
                normalizeBaseUrl(bridgeService.getStateValue(clientId, "overrideUpdateURL")),
                defaultUpdateUrl);
    }

    String bmxApiKey() {
      return encryptedBmxToken;
    }

    String margeAuthToken() {
        return margeAuthToken(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    String margeAuthToken(String clientId) {
        return bridgeService.getStateValue(clientId, "margeAuthToken");
    }

    Map<String, Object> browserBootstrapPayload() {
        return browserBootstrapPayload(NativeBridgeService.DEFAULT_CLIENT_ID);
    }

    Map<String, Object> browserBootstrapPayload(String clientId) {
        seedBrowserRuntimeState(clientId);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("authServer", authServer(clientId));
        payload.put("clientId", clientId);
        payload.put("guid", guid(clientId));
        payload.put("nativeVersion", fullNativeVersion(clientId));
        // this may be used at some point but works fine empty
        payload.put("frameConfig", new HashMap<String, String>());
        return payload;
    }

    void storeMargeSession(String accountId, String authToken) {
        storeMargeSession(NativeBridgeService.DEFAULT_CLIENT_ID, accountId, authToken);
    }

    void storeMargeSession(String clientId, String accountId, String authToken) {
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        if (accountId != null && !accountId.isBlank()) {
            updates.put("margeAccountID", accountId);
        }
        if (authToken != null && !authToken.isBlank()) {
            updates.put("margeAuthToken", authToken);
        }
        bridgeService.putStateValues(clientId, updates);
    }

    void storeMargeAuthToken(String authToken) {
        storeMargeAuthToken(NativeBridgeService.DEFAULT_CLIENT_ID, authToken);
    }

    void storeMargeAuthToken(String clientId, String authToken) {
        if (authToken == null || authToken.isBlank()) {
            return;
        }
        bridgeService.putStateValue(clientId, "margeAuthToken", authToken);
    }

    void storeOverrideUrls(String streamingUrl, String updateUrl) {
        storeOverrideUrls(NativeBridgeService.DEFAULT_CLIENT_ID, streamingUrl, updateUrl);
    }

    void storeOverrideUrls(String clientId, String streamingUrl, String updateUrl) {
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        String normalizedStreamingUrl = normalizeBaseUrl(streamingUrl);
        String normalizedUpdateUrl = normalizeBaseUrl(updateUrl);
        if (normalizedStreamingUrl != null) {
            updates.put("overrideMargeURL", normalizedStreamingUrl);
        }
        if (normalizedUpdateUrl != null) {
            updates.put("overrideUpdateURL", normalizedUpdateUrl);
        }
        bridgeService.putStateValues(clientId, updates);
    }

    URI overrideTarget(URI target) {
        return overrideTarget(NativeBridgeService.DEFAULT_CLIENT_ID, target);
    }

    URI overrideTarget(String clientId, URI target) {
        if (target == null || !isMargeTarget(target.getHost(), target.getPath())) {
            return target;
        }
        String override = overrideMargeUrl(clientId);
        if (override == null) {
            return target;
        }
        return buildUriFromBase(override, target.getPath(), target.getQuery());
    }

    URI buildUriFromBase(String baseUrl, String path, String query) {
        String normalizedBase = normalizeBaseUrl(baseUrl);
        if (normalizedBase == null) {
            return null;
        }
        try {
            URI base = URI.create(normalizedBase);
            return new URI(
                    base.getScheme(),
                    base.getUserInfo(),
                    base.getHost(),
                    base.getPort(),
                    path,
                    query,
                    null);
        } catch (IllegalArgumentException | URISyntaxException exception) {
            LOGGER.warn("Failed to build URI from base {} and path {}", baseUrl, path, exception);
            return null;
        }
    }

    String bmxRegistryUrl() {
        return defaultBmxRegistryUrl;
    }


    boolean isBmxTarget(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return normalizedHost.equals("content.api.bose.io")
                || normalizedHost.equals("test.content.api.bose.io")
                || normalizedHost.equals("bose-prod.apigee.net")
                || normalizedHost.endsWith(".apigee.net");
    }

    boolean isMargeTarget(String host, String path) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        boolean isStreamingPath = normalizedPath.contains("/streaming/") || normalizedPath.contains("/customer/");
        if (!isStreamingPath) {
            return false;
        }
        return normalizedHost.endsWith(".bose.com") || normalizedHost.endsWith(".apigee.net");
    }

    void seedBrowserRuntimeState(String clientId) {
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        String existingGuid = guid(clientId);
        String persistedGuid = blankToNull(bridgeService.getStateValue(clientId, "guid"));
        String persistedDeviceGuid = blankToNull(bridgeService.getStateValue(clientId, "deviceGuid"));
        if (existingGuid == null) {
            existingGuid = UUID.randomUUID().toString().replace("-", "");
        }
        if (persistedGuid == null) {
            updates.put("guid", existingGuid);
        }
        if (persistedDeviceGuid == null) {
            updates.put("deviceGuid", existingGuid);
        }
        String shortVersion = shortNativeVersion(clientId);
        if (blankToNull(bridgeService.getStateValue(clientId, "nativeFrameVersion")) == null && shortVersion != null) {
            updates.put("nativeFrameVersion", shortVersion);
        }
        String fullVersion = firstNonBlank(blankToNull(bridgeService.getStateValue(clientId, "frame_version")), fullNativeVersion(clientId));
        if (blankToNull(bridgeService.getStateValue(clientId, "frame_version")) == null && fullVersion != null) {
            updates.put("frame_version", fullVersion);
        }
        if (blankToNull(bridgeService.getStateValue(clientId, "authServer")) == null) {
            updates.put("authServer", DEFAULT_AUTH_SERVER);
        }
        bridgeService.putStateValues(clientId, updates);
    }


    private String normalizeAuthServer(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_AUTH_SERVER;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 0 && parsed <= 3) {
                return Integer.toString(parsed);
            }
        } catch (NumberFormatException ignored) {
        }
        return DEFAULT_AUTH_SERVER;
    }

    private String extractVersionPrefix(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        Matcher matcher = VERSION_PREFIX.matcher(trimmed);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Map<String, Object> loadObject(Path file) {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            Object parsed = SimpleJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            return SimpleJson.asObject(parsed);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to load data file {}", file, exception);
            return Map.of();
        }
    }

    private Map<String, Object> childObject(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (!(value instanceof Map<?, ?> child)) {
            return Map.of();
        }
        return SimpleJson.asObject(child);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    String normalizeBaseUrl(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

}
