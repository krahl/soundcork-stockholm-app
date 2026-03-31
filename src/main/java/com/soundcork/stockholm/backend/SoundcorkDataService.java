package com.soundcork.stockholm.backend;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final Map<Integer, Map<String, String>> LEGACY_FRAME_CONFIG = createLegacyFrameConfig();

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
        seedBrowserRuntimeState();
        LOGGER.debug("Loaded SoundcorkDataService using config={} override={}", configFile, overrideFile);
    }

    String currentKilo() {
        if (SoundcorkCrypto.isHexString(overrideKilo)) {
            return overrideKilo;
        }
        String persistedKilo = bridgeService.getStateValue("constant.kilo");
        if (SoundcorkCrypto.isHexString(persistedKilo)) {
            return persistedKilo;
        }
        return SoundcorkCrypto.DEFAULT_KILO;
    }

    String authServer() {
        return normalizeAuthServer(bridgeService.getStateValue("authServer"));
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
        return firstNonBlank(
                blankToNull(bridgeService.getStateValue("nativeFrameVersion")),
                extractVersionPrefix(bridgeService.getStateValue("frame_version")),
                shortNativeVersion());
    }

    String fullNativeVersion() {
        return firstNonBlank(
                blankToNull(bridgeService.getStateValue("frame_version")),
                blankToNull(soundcorkAppVersion),
                shortNativeVersion());
    }

    String shortNativeVersion() {
        return firstNonBlank(
                extractVersionPrefix(bridgeService.getStateValue("nativeFrameVersion")),
                extractVersionPrefix(bridgeService.getStateValue("frame_version")),
                extractVersionPrefix(soundcorkAppVersion));
    }

    String guid() {
        return firstNonBlank(
                blankToNull(bridgeService.getStateValue("guid")),
                blankToNull(bridgeService.getStateValue("deviceGuid")));
    }

    String margeServerKey() {
        return blankToNull(firstNonBlank(margeServerKey, legacyFrameValue("f10")));
    }

    String margeServerKeyHeader() {
        return blankToNull(margeServerKeyHeader);
    }

    String overrideMargeUrl() {
        return normalizeBaseUrl(bridgeService.getStateValue("overrideMargeURL"));
    }

    String currentMargeUrl() {
        return firstNonBlank(overrideMargeUrl(), defaultMargeUrl, normalizeBaseUrl(legacyFrameValue("f0")));
    }

    String currentUpdateUrl() {
        return firstNonBlank(
                normalizeBaseUrl(bridgeService.getStateValue("overrideUpdateURL")),
                defaultUpdateUrl,
                normalizeBaseUrl(legacyFrameValue("f1")));
    }

    String bmxApiKey() {
        if (!SoundcorkCrypto.isHexString(encryptedBmxToken)) {
            return null;
        }
        try {
            return SoundcorkCrypto.decryptHex(currentKilo(), encryptedBmxToken);
        } catch (IllegalStateException exception) {
            LOGGER.warn("Failed to decrypt BMX API key with current kilo", exception);
            return null;
        }
    }

    String margeAuthToken() {
        return bridgeService.getStateValue("margeAuthToken");
    }

    Map<String, Object> browserBootstrapPayload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("authServer", authServer());
        payload.put("guid", guid());
        payload.put("nativeVersion", fullNativeVersion());
        payload.put("frameConfig", browserFrameConfig());
        return payload;
    }

    Map<String, String> browserFrameConfig() {
        return new LinkedHashMap<>(legacyFrameConfig());
    }

    void storeMargeSession(String accountId, String authToken) {
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        if (accountId != null && !accountId.isBlank()) {
            updates.put("margeAccountID", accountId);
        }
        if (authToken != null && !authToken.isBlank()) {
            updates.put("margeAuthToken", authToken);
        }
        bridgeService.putStateValues(updates);
    }

    void storeMargeAuthToken(String authToken) {
        if (authToken == null || authToken.isBlank()) {
            return;
        }
        bridgeService.putStateValue("margeAuthToken", authToken);
    }

    void storeOverrideUrls(String streamingUrl, String updateUrl) {
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        String normalizedStreamingUrl = normalizeBaseUrl(streamingUrl);
        String normalizedUpdateUrl = normalizeBaseUrl(updateUrl);
        if (normalizedStreamingUrl != null) {
            updates.put("overrideMargeURL", normalizedStreamingUrl);
        }
        if (normalizedUpdateUrl != null) {
            updates.put("overrideUpdateURL", normalizedUpdateUrl);
        }
        bridgeService.putStateValues(updates);
    }

    URI overrideTarget(URI target) {
        if (target == null || !isMargeTarget(target.getHost(), target.getPath())) {
            return target;
        }
        String override = overrideMargeUrl();
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
        String registryInfo = bridgeService.getStateValue("bmxRegistryInfo");
        if (SoundcorkCrypto.isHexString(registryInfo)) {
            String decrypted = decryptWithCurrentKilo(registryInfo);
            if (decrypted != null) {
                try {
                    Map<String, Object> info = SimpleJson.asObject(SimpleJson.parse(decrypted));
                    String url = stringValue(info.get("url"));
                    if ("dev".equals(url)) {
                        return decryptWithCurrentKilo(encryptedBmxServerUrlAlt);
                    }
                    if (url != null && !url.isBlank()) {
                        return url;
                    }
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to parse decrypted BMX registry info", exception);
                }
            }
        }
        return firstNonBlank(defaultBmxRegistryUrl, blankToNull(legacyFrameValue("f3")));
    }

    String decryptWithCurrentKilo(String encryptedHex) {
        if (!SoundcorkCrypto.isHexString(encryptedHex)) {
            return null;
        }
        try {
            return SoundcorkCrypto.decryptHex(currentKilo(), encryptedHex);
        } catch (IllegalStateException exception) {
            LOGGER.warn("Failed to decrypt value with current kilo", exception);
            return null;
        }
    }

    String decryptWithOldKilo(String encryptedHex) {
        if (!SoundcorkCrypto.isHexString(encryptedHex)) {
            return null;
        }
        try {
            return SoundcorkCrypto.decryptHex(SoundcorkCrypto.OLD_KILO, encryptedHex);
        } catch (IllegalStateException exception) {
            LOGGER.warn("Failed to decrypt value with old kilo", exception);
            return null;
        }
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

    private void seedBrowserRuntimeState() {
        LinkedHashMap<String, String> updates = new LinkedHashMap<>();
        String existingGuid = guid();
        String persistedGuid = blankToNull(bridgeService.getStateValue("guid"));
        String persistedDeviceGuid = blankToNull(bridgeService.getStateValue("deviceGuid"));
        if (existingGuid == null) {
            existingGuid = UUID.randomUUID().toString().replace("-", "");
        }
        if (persistedGuid == null) {
            updates.put("guid", existingGuid);
        }
        if (persistedDeviceGuid == null) {
            updates.put("deviceGuid", existingGuid);
        }
        String shortVersion = shortNativeVersion();
        if (blankToNull(bridgeService.getStateValue("nativeFrameVersion")) == null && shortVersion != null) {
            updates.put("nativeFrameVersion", shortVersion);
        }
        String fullVersion = firstNonBlank(blankToNull(bridgeService.getStateValue("frame_version")), fullNativeVersion());
        if (blankToNull(bridgeService.getStateValue("frame_version")) == null && fullVersion != null) {
            updates.put("frame_version", fullVersion);
        }
        if (blankToNull(bridgeService.getStateValue("authServer")) == null) {
            updates.put("authServer", DEFAULT_AUTH_SERVER);
        }
        bridgeService.putStateValues(updates);
    }

    private String legacyFrameValue(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return legacyFrameConfig().get(key);
    }

    private Map<String, String> legacyFrameConfig() {
        int authServerIndex;
        try {
            authServerIndex = Integer.parseInt(authServer());
        } catch (NumberFormatException exception) {
            authServerIndex = 0;
        }
        return LEGACY_FRAME_CONFIG.getOrDefault(authServerIndex, LEGACY_FRAME_CONFIG.get(0));
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

    private static Map<Integer, Map<String, String>> createLegacyFrameConfig() {
        LinkedHashMap<Integer, Map<String, String>> configs = new LinkedHashMap<>();
        configs.put(0, decryptLegacyConfig(Map.of(
                "f0", "GfM5nQ1y6bNnEYGpd7GV57KqYSgAUWbVdOnA5mk/2PA=]IzHQFETt2CGC5vgz866Fag==]sAxb+XIikd57QuVjUw8iGve0d3U+1c4tV5ZBUDvAzpc=",
                "f1", "ire+Q36DXQeQygZyLNmUtCt7VJ8DsaDRM10p6D5rL2Q=]tEdKPWlD+3XGivV7inq2UA==]grToI+dNe73s55j6ba2IsaroiannwSZ9qDy9AeFYcgA/XzxJ/2d1Me07CEJkSgWw",
                "f2", "zacDWzLrZN45cgRE7hgNV4fhSh+hnvay2+U3dwLChnQ=]0BCTdj420qWUFn7qKvc1mg==]RkOlPtRhqOzU+FOFiat2F2hvnTotKDK9FlrcCRM7phrvs92uAKGxBTQYfCcAXmgg/XZgYnCB",
                "f3", "iFVKmDnusxhy/8Y8z/3wEq6fP3P8eEQipLfTETN3gf0=]P2Q15yW2Fsayenqu/O0j7w==]tO8uB/XzUMVbvJcPEqI537KRCTAjxcnhBMUqh1PISVDnkalvHwd/nJFwTT17T01NBCULaIYHVodCh9Rnha5+Pg==")));
        configs.put(1, decryptLegacyConfig(Map.of(
                "f0", "MujR8nniK2GUOSW9TsGmnovY+4fkM3Y+hg2s3Nv3IHs=]hoacfT9bVni8d3p1osjjWg==]CeUPFk9ZiLK21jy3HR+Lr1kmdQj2k1bddV1TZw3MUwWQF6UEDv1IutggJn2UpLW0",
                "f1", "UOjTncFWwLL6dePcoP99/qxnq0AifTD1jPedSapQhiE=]PuQ8cKr1tomU6t/+fB+AyQ==]paVIvVNJPF9fMtgPInUwFkbnDs3n1ab3f2yTbIPMvu/P2OoPxhNZLmAxhkXZz/FH7n/I6SZGDc2ig8D6N5yUdg==",
                "f2", "K7QAcXRSyoYE3z/hGFaoX7mWPfnz54G22IoGTGEhOGE=]j3opJQOIEuXyLASoECk5Rw==]SE6S9qheIPXda/KdUq2hU/FIHKdJzEOFWTFCxxj3E5Rq6VN82zvDgafrGDtfKY2K",
                "f3", "SNhi0a+NjgojuU6nnbf4vV7tmgBPcfr//JTsnhZT8Jg=]F5g+TZLsMK3IeEdEVncyWw==]TEbD9aUU41v4xJtb7Auloy95oFifXiBy6GEYUu5pqBk9cP9PTawL5hswFrO3FbhaY//Z9ugvuaEbBrhDT0z6Ag==",
                "f6", "Bn+1LrygNdruGFmxoEmYPqWV+/S8vlMJ/YpfvWvHxEE=]jL22TPAf2Ezqzs85u5lVvw==]HU0jcnSQqxzTbUDdgkNtt21kzgOSlW9ZH7C7RfQnguXj6aHJqR6TCOicxT5lmNoP",
                "f10", "DSblnw1NT/8E/VoMvUjnAZ3J/+fkj4Hd9n8eFbANNDk=]i4hQSyqjA88UNqyaqXNyNA==]a37VT/ieQQt3A3+0GXBOLSJ2I1pKe4CCcgT1jNipG8acSI9sIqtSacq92pKV/kGz",
                "f11", "P9Qev2N1QlRVQUClRM0IoswjI2LgLErtyHhIhQsUJhY=]WmgrJ5w5TEvQi0bWKdgDrA==]/5zcaKqANWTIcF9mSFKC69QWPOkUMSwH7I4gv4+0tNs0BZ3mpalbByRBhwfIi3uU")));
        configs.put(2, decryptLegacyConfig(Map.of(
                "f0", "73poud1SvPqeK7/JCIAcSANafT1TqRNP3t0LbwHH4ZU=]QgSGT8wRfAakd822qiUPSw==]E9oyeb9ySS4TO1enPBPauUYlR2ihYSu8HEP3Vy/o8RI1MSmxnRvexFqUTQGc9FLT",
                "f1", "bImPSxqKlKtZPDdBnn7iV/q8HjorH4a2aw7tvkDzPKw=]P8ryg5Be84k/XVlwU7pxkw==]2IMntv+P1we6OrD0Pe2QORcazZlHso0avfz2b819gQzR7RvIQAudinNx626FM3d6REU2yYXkbA2cQDNXHECrNA==",
                "f2", "82Cffu8BX9+lqzOWJn9ImKw5jkps6UWaONK7fu0dF68=]iqc6GTVILTBpe6V9sUpTww==]dTaD+sLItWZCAPk6xKV5fs+fEpIN6ZLqITVLneoVju7HmIXFOGtwxbTiIKWj1aTz",
                "f3", "k+s3YHwxF9Nhu1tTWzvxFA5oAjVRRbUop1Gkox/C8BU=]Y6ib10jG3nK44Y86Y2z1lw==]lA5dIuHSqxllQ+eIdhd3MaXEeMbSYQD8KqOJGnt9pe72NOJAJBfN69LmuF3zl+pRUP55n8l8HCcRfbLQtllTig==",
                "f6", "GyomywRlqu8Hp42JOWBpRrD3k3UMdtMJcf00Z+SC0jQ=]2HWG8Gg/A1kna7zhRRngwA==]8xgIAE+82y5pbRsZQ6HbfaG0qcNLNcupPRdWi8LU0BcXVxgiJQg2NHMqUq+40l6/",
                "f10", "Qe+vCN39mYcFq9k1hKkJ9L+KpuDvL98FQc63GqyRvoo=]ULEVFNkMUuMbCNcPff1kYg==]bRThyTUYTY2yQg+GxvNTwdnm1mpONIhuOe6nWSMZjT+NGknCjdQmLja4UMdKCGYf",
                "f11", "Rk0BPt0fmEkwI/itRamBW/fKNKddCcJQr9iXkwHKZlQ=]8w4dn/tFe1hNY/b/WT95dg==]hCtrUvVCf37pgh6/a2j82NA7nYvB/NTco62KOxBsY5ojOZ5Aq2MSW/wE2N7EH6Jw")));
        configs.put(3, decryptLegacyConfig(Map.of(
                "f0", "L9utY3FvsToBtmhdaTgXBdgYgZdyo7AA1VNzPDU2FL0=]QKHDHqKLExi8DiFYgeIHYw==]OIDxap8RcSaJlOAUY58Tg4klvFQtLf6eG0ZgHhQzgeQ=",
                "f1", "dAH1WBO+8Do5eaNMf7Q/kYZV/dH6SlOiQyz65bqlfKc=]H4rQ403kZNmCgGOmmaQ25A==]VG6cVRwFj7SgviGcRZJCJNzPDPFbFiP5U7Z2FWJ2Dpl00u8pjS0fGuf+BJbKlmGSuXVO590CcS4VGbqNGaujrg==",
                "f2", "7mLkWaz9yjo9i87EUfgFpvwW1jTxQKgB5oTiules9n8=]BnjjbZZlmWejCrlJeAxAnA==]FUAHXxEqfEiOCmKhJ6duhppcN3Aa8qVeGRGhCsdg4ogzh6Dkhr0xetiHZSJZu9il",
                "f10", "OFKIQgZHdlEliI6Nyq859yr7qfU5Y9bSkDM3Xzn/hZA=]YjhWtkP1OddU4WXE0Znq4w==]511DrU3jDh1wAZFW8yhn/QOxowM/wHe37MY2Vz3NFrq7efBXhC0LQaUWM+UsL/NO",
                "f11", "Am6YHT6lqmy5RrvwqbqPrzjlK8FTrlmssLqw9/1mXXo=]V0tVdFG/DCr0UolI1v9mrg==]hlVrGg6fPo6XTfDkGwsGNIfDoYlCh5JXYLQDvEVLJYXIdG054j3hRRoWqe3Wpnke")));
        return Map.copyOf(configs);
    }

    private static Map<String, String> decryptLegacyConfig(Map<String, String> encryptedValues) {
        LinkedHashMap<String, String> decrypted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : encryptedValues.entrySet()) {
            String value = SoundcorkCrypto.decryptLegacyValue(entry.getValue());
            if (value != null && !value.isBlank()) {
                decrypted.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(decrypted);
    }
}
