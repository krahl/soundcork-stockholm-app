package com.soundcork.stockholm.backend;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BackendApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendApplication.class);
    private static final String CLIENT_ID_COOKIE = "stockholmClientId";
    private static final int CLIENT_ID_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365;

    private BackendApplication() {
    }

    public static void main(String[] args) throws IOException {
        Path workspaceRoot = resolveWorkspaceRoot();
        Path stockholmRoot = workspaceRoot.resolve("stockholm").normalize();
        Path stateFile = workspaceRoot.resolve("backend").resolve("state").resolve("native-state.json").normalize();
        BackendConfig backendConfig = BackendConfig.load(workspaceRoot);
        ClientStateMode clientStateMode = ClientStateMode.fromEnvironment(System.getenv());
        NativeBridgeService bridgeService = new NativeBridgeService(stateFile);
        SoundcorkDataService soundcorkDataService = new SoundcorkDataService(workspaceRoot, bridgeService);
        HttpProxyService httpProxyService = new HttpProxyService(soundcorkDataService, clientStateMode);
        LOGGER.debug("Resolved workspace root {} with stockholmRoot={} and stateFile={}",
                workspaceRoot, stockholmRoot, stateFile);
        LOGGER.debug("Frontend logging level is configured to {}", backendConfig.frontendLoggingLevel());
        LOGGER.info("Client state mode is {}", clientStateMode.configValue());

        // Read IP and port from environment variables, fallback to defaults
        String bindIp = System.getenv().getOrDefault("BACKEND_BIND_IP", "0.0.0.0");
        int bindPort;
        try {
            bindPort = Integer.parseInt(System.getenv().getOrDefault("BACKEND_PORT", "8088"));
        } catch (NumberFormatException e) {
            bindPort = 8088;
        }
        InetSocketAddress socketAddress = new InetSocketAddress(bindIp, bindPort);
        HttpServer server = HttpServer.create(socketAddress, 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/native/appSend", exchange -> handleAppSend(exchange, bridgeService, clientStateMode));
        server.createContext("/api/native/runQueue", exchange -> handleRunQueue(exchange, bridgeService, clientStateMode));
        server.createContext("/api/http-proxy", httpProxyService::handle);
        server.createContext("/api/debug/state", exchange -> handleDebugState(exchange, soundcorkDataService, bridgeService, clientStateMode));
        server.createContext("/api/debug/test-login", exchange -> handleDebugTestLogin(exchange, soundcorkDataService));
        server.createContext("/", new StaticStockholmHandler(stockholmRoot, backendConfig, soundcorkDataService, clientStateMode));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down Stockholm backend");
            bridgeService.close();
            server.stop(0);
        }));

        server.start();
        LOGGER.info("Stockholm backend listening on http://{}:{}/",
                socketAddress.getHostString(), socketAddress.getPort());
        LOGGER.info("Debug endpoints:");
        LOGGER.info("  State:      http://127.0.0.1:{}/api/debug/state", socketAddress.getPort());
        LOGGER.info("  Test login: http://127.0.0.1:{}/api/debug/test-login?email=EMAIL&password=PASS", socketAddress.getPort());
    }

    private static void handleAppSend(
            HttpExchange exchange,
            NativeBridgeService bridgeService,
            ClientStateMode clientStateMode) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            LOGGER.debug("Rejected {} request to /api/native/appSend", exchange.getRequestMethod());
            sendText(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        String clientId = ensureClientId(exchange, clientStateMode);
        String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LOGGER.debug("Received /api/native/appSend for client '{}'", describeClientId(clientId));
        bridgeService.handleAppSend(clientId, payload);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private static void handleRunQueue(
            HttpExchange exchange,
            NativeBridgeService bridgeService,
            ClientStateMode clientStateMode) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            LOGGER.debug("Rejected {} request to /api/native/runQueue", exchange.getRequestMethod());
            sendText(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        String clientId = ensureClientId(exchange, clientStateMode);
        LOGGER.debug("Received /api/native/runQueue for client '{}'", describeClientId(clientId));
        sendText(exchange, 200, bridgeService.runQueue(clientId), "application/json; charset=UTF-8");
    }

    static String ensureClientId(HttpExchange exchange, ClientStateMode clientStateMode) {
        if (!clientStateMode.isPerBrowser()) {
            return NativeBridgeService.DEFAULT_CLIENT_ID;
        }
        String clientId = clientId(exchange);
        if (clientId == null) {
            clientId = "stockholm-" + UUID.randomUUID();
        }
        String cookieClientId = clientIdCookie(exchange);
        if (!clientId.equals(cookieClientId)) {
            exchange.getResponseHeaders().add("Set-Cookie",
                    CLIENT_ID_COOKIE + "=" + clientId + "; Max-Age=" + CLIENT_ID_COOKIE_MAX_AGE_SECONDS
                            + "; Path=/; SameSite=Lax");
        }
        return clientId;
    }

    static String ensureClientId(HttpExchange exchange) {
        return ensureClientId(exchange, ClientStateMode.fromEnvironment(System.getenv()));
    }

    static String clientId(HttpExchange exchange) {
        String header = normalizeClientId(exchange.getRequestHeaders().getFirst("X-Stockholm-Client-Id"));
        if (header != null) {
            return header;
        }
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && !query.isBlank()) {
            for (String part : query.split("&")) {
                int separator = part.indexOf('=');
                if (separator > 0 && "clientId".equals(part.substring(0, separator))) {
                    String queryClientId = normalizeClientId(urlDecode(part.substring(separator + 1)));
                    if (queryClientId != null) {
                        return queryClientId;
                    }
                }
            }
        }
        return clientIdCookie(exchange);
    }

    private static String clientIdCookie(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            int separator = trimmed.indexOf('=');
            if (separator <= 0 || !CLIENT_ID_COOKIE.equals(trimmed.substring(0, separator))) {
                continue;
            }
            return normalizeClientId(urlDecode(trimmed.substring(separator + 1)));
        }
        return null;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            LOGGER.debug("Ignoring malformed client id encoding", exception);
            return null;
        }
    }

    private static String normalizeClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        String trimmed = clientId.trim();
        if (trimmed.length() > 128) {
            return null;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            boolean allowed = current >= 'a' && current <= 'z'
                    || current >= 'A' && current <= 'Z'
                    || current >= '0' && current <= '9'
                    || current == '.'
                    || current == '_'
                    || current == '~'
                    || current == '-';
            if (!allowed) {
                return null;
            }
        }
        return trimmed;
    }

    static void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void handleDebugState(
            HttpExchange exchange,
            SoundcorkDataService soundcorkDataService,
            NativeBridgeService bridgeService,
            ClientStateMode clientStateMode) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        var info = new java.util.LinkedHashMap<String, Object>();
        String clientId = ensureClientId(exchange, clientStateMode);
        info.put("clientStateMode", clientStateMode.configValue());
        info.put("clientId", clientId);
        info.put("authServer", soundcorkDataService.authServer(clientId));
        info.put("guid", soundcorkDataService.guid(clientId));
        info.put("nativeFrameVersion", soundcorkDataService.nativeFrameVersion(clientId));
        info.put("fullNativeVersion", soundcorkDataService.fullNativeVersion(clientId));
        info.put("currentMargeUrl", soundcorkDataService.currentMargeUrl(clientId));
        info.put("overrideMargeUrl", soundcorkDataService.overrideMargeUrl(clientId));
        info.put("currentUpdateUrl", soundcorkDataService.currentUpdateUrl(clientId));
        info.put("bmxApiKey", soundcorkDataService.bmxApiKey());
        info.put("margeServerKey", soundcorkDataService.margeServerKey());
        info.put("margeServerKeyHeader", soundcorkDataService.margeServerKeyHeader());
        info.put("margeAuthToken", soundcorkDataService.margeAuthToken(clientId) != null ? "<present>" : null);
        info.put("margeAccountID", bridgeService.getStateValue(clientId, "margeAccountID"));
        info.put("defaultClientType", soundcorkDataService.defaultClientType());
        info.put("streamingMediaType", soundcorkDataService.streamingMediaType());
        info.put("boseAppVersion", soundcorkDataService.soundcorkAppVersion());
        info.put("protocolVersion", soundcorkDataService.protocolVersion());
        sendText(exchange, 200, SimpleJson.stringify(info), "application/json; charset=UTF-8");
    }

    private static void handleDebugTestLogin(HttpExchange exchange, SoundcorkDataService soundcorkDataService) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        String query = exchange.getRequestURI().getRawQuery();
        String email = null;
        String password = null;
        if (query != null) {
            for (String part : query.split("&")) {
                int sep = part.indexOf('=');
                if (sep > 0) {
                    String key = part.substring(0, sep);
                    String val = URLDecoder.decode(part.substring(sep + 1), StandardCharsets.UTF_8);
                    if ("email".equals(key)) email = val;
                    if ("password".equals(key)) password = val;
                }
            }
        }
        if (email == null || email.isBlank() || password == null) {
            sendText(exchange, 400, "{\"error\":\"Provide email and password query parameters\"}", "application/json; charset=UTF-8");
            return;
        }

        var report = new java.util.LinkedHashMap<String, Object>();
        String margeUrl = soundcorkDataService.currentMargeUrl();
        String envUrl = margeUrl + "streaming/account/email/" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "/environment";
        String loginUrl = margeUrl + "streaming/account/login";
        String auth = "Basic " + java.util.Base64.getEncoder().encodeToString((email + ":" + password).getBytes(StandardCharsets.UTF_8));
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><login><username>" + email + "</username><password>" + password + "</password></login>";

        report.put("margeUrl", margeUrl);
        report.put("envUrl", envUrl);
        report.put("loginUrl", loginUrl);

        var headersUsed = new java.util.LinkedHashMap<String, String>();
        headersUsed.put("Accept", soundcorkDataService.streamingMediaType());
        headersUsed.put("Content-Type", soundcorkDataService.streamingMediaType());
        headersUsed.put("GUID", soundcorkDataService.guid());
        headersUsed.put("ClientType", soundcorkDataService.defaultClientType());
        headersUsed.put("version_NativeFrameVersion", soundcorkDataService.nativeFrameVersion());
        headersUsed.put("version_StockholmVersion", soundcorkDataService.soundcorkAppVersion());
        headersUsed.put("version_ProtocolVersion", soundcorkDataService.protocolVersion());
        if (soundcorkDataService.margeServerKey() != null) {
            headersUsed.put(soundcorkDataService.margeServerKeyHeader(), soundcorkDataService.margeServerKey());
        }
        report.put("headers", headersUsed);

        try {
            var httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            // Step 1: Environment discovery (uses Basic auth)
            var envReqBuilder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(envUrl))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Accept", soundcorkDataService.streamingMediaType())
                    .header("Content-Type", soundcorkDataService.streamingMediaType())
                    .header("Authorization", auth)
                    .header("GUID", soundcorkDataService.guid())
                    .header("ClientType", soundcorkDataService.defaultClientType())
                    .header("version_NativeFrameVersion", soundcorkDataService.nativeFrameVersion())
                    .GET();
            var envResp = httpClient.send(envReqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());

            var envResult = new java.util.LinkedHashMap<String, Object>();
            envResult.put("status", envResp.statusCode());
            envResult.put("body", envResp.body() != null ? envResp.body().substring(0, Math.min(envResp.body().length(), 1024)) : null);
            report.put("environmentResponse", envResult);

            // Step 2: Login (NO Authorization header — credentials are in XML body only)
            var loginReqBuilder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(loginUrl))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Accept", soundcorkDataService.streamingMediaType())
                    .header("Content-Type", soundcorkDataService.streamingMediaType())
                    .header("GUID", soundcorkDataService.guid())
                    .header("ClientType", soundcorkDataService.defaultClientType())
                    .header("version_NativeFrameVersion", soundcorkDataService.nativeFrameVersion())
                    .header("version_StockholmVersion", soundcorkDataService.soundcorkAppVersion())
                    .header("version_ProtocolVersion", soundcorkDataService.protocolVersion())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
            if (soundcorkDataService.margeServerKey() != null) {
                loginReqBuilder.header(soundcorkDataService.margeServerKeyHeader(), soundcorkDataService.margeServerKey());
            }
            var loginResp = httpClient.send(loginReqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());

            var loginResult = new java.util.LinkedHashMap<String, Object>();
            loginResult.put("status", loginResp.statusCode());
            loginResult.put("body", loginResp.body() != null ? loginResp.body().substring(0, Math.min(loginResp.body().length(), 2048)) : null);
            var respHeaders = new java.util.LinkedHashMap<String, String>();
            loginResp.headers().map().forEach((k, v) -> { if (!v.isEmpty()) respHeaders.put(k, v.get(0)); });
            loginResult.put("responseHeaders", respHeaders);
            report.put("loginResponse", loginResult);

            if (loginResp.statusCode() >= 200 && loginResp.statusCode() < 300) {
                String accountId = SoundcorkCloudXml.extractAccountId(loginResp.body().getBytes(StandardCharsets.UTF_8));
                String credentials = loginResp.headers().firstValue("Credentials").orElse(null);
                report.put("extractedAccountId", accountId);
                report.put("extractedCredentials", credentials != null ? credentials.substring(0, Math.min(credentials.length(), 20)) + "..." : null);
                report.put("SUCCESS", true);
            } else {
                report.put("SUCCESS", false);
            }
        } catch (Exception e) {
            report.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        sendText(exchange, 200, SimpleJson.stringify(report), "application/json; charset=UTF-8");
    }

    private static String describeClientId(String clientId) {
        return clientId == null || clientId.isBlank() ? "default" : clientId;
    }

    private static Path resolveWorkspaceRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("stockholm"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("stockholm"))) {
            return parent;
        }
        throw new IllegalStateException("Unable to locate stockholm directory from " + cwd);
    }

    private static final class StaticStockholmHandler implements HttpHandler {
        private final Path stockholmRoot;
        private final BackendConfig backendConfig;
        private final SoundcorkDataService soundcorkDataService;
        private final ClientStateMode clientStateMode;

        private StaticStockholmHandler(
                Path stockholmRoot,
                BackendConfig backendConfig,
                SoundcorkDataService soundcorkDataService,
                ClientStateMode clientStateMode) {
            this.stockholmRoot = stockholmRoot;
            this.backendConfig = backendConfig;
            this.soundcorkDataService = soundcorkDataService;
            this.clientStateMode = clientStateMode;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String clientId = ensureClientId(exchange, clientStateMode);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                LOGGER.debug("Rejected {} request for static path {}", exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath());
                sendText(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            Path file = resolveFile(exchange.getRequestURI().getPath());
            if (file == null || !Files.exists(file) || Files.isDirectory(file)) {
                LOGGER.debug("Static asset not found for request path {}", exchange.getRequestURI().getPath());
                sendText(exchange, 404, "Not Found", "text/plain");
                return;
            }

            byte[] bytes = Files.readAllBytes(file);
            if (shouldInjectBrowserBootstrap(file)) {
                bytes = injectBrowserBootstrap(clientId, bytes);
            }
            Headers headers = exchange.getResponseHeaders();
            applyFrontendLoggingCookie(headers);
            headers.set("Content-Type", contentType(file));
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : bytes.length);
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.close();
                return;
            }
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private Path resolveFile(String rawPath) {
            String path = (rawPath == null || rawPath.isBlank()) ? "/" : rawPath;
            if ("/".equals(path)) {
                return stockholmRoot.resolve("index.html");
            }
            Path resolved = stockholmRoot.resolve(path.substring(1)).normalize();
            if (!resolved.startsWith(stockholmRoot)) {
                LOGGER.debug("Rejected static path outside stockholm root: {}", rawPath);
                return null;
            }
            if (Files.isDirectory(resolved)) {
                Path index = resolved.resolve("index.html");
                return Files.exists(index) ? index : null;
            }
            return resolved;
        }

        private void applyFrontendLoggingCookie(Headers headers) {
            String cookieValue = backendConfig.shouldEnableFrontendDebug()
                    ? "stockholmFrontendLoggingLevel=" + backendConfig.frontendLoggingLevel()
                            + "; Path=/; SameSite=Lax"
                    : "stockholmFrontendLoggingLevel=; Max-Age=0; Path=/; SameSite=Lax";
            headers.add("Set-Cookie", cookieValue);
            LOGGER.debug("Applied frontend logging cookie with level {}", backendConfig.frontendLoggingLevel());
        }

        private boolean shouldInjectBrowserBootstrap(Path file) {
            if (!"text/html; charset=UTF-8".equals(contentType(file))) {
                return false;
            }
            String relativePath = stockholmRoot.relativize(file)
                    .toString()
                    .replace('\\', '/')
                    .toLowerCase(Locale.ROOT);
            return "index.html".equals(relativePath) || "setup/index.html".equals(relativePath);
        }

        private byte[] injectBrowserBootstrap(String clientId, byte[] originalBytes) {
            String html = new String(originalBytes, StandardCharsets.UTF_8);
            if (html.contains("window.StockholmBrowserBootstrap")) {
                return originalBytes;
            }
            int headEnd = html.indexOf("</head>");
            if (headEnd < 0) {
                return originalBytes;
            }
            String injected = html.substring(0, headEnd)
                    + browserBootstrapScript(clientId)
                    + html.substring(headEnd);
            return injected.getBytes(StandardCharsets.UTF_8);
        }

        private String browserBootstrapScript(String clientId) {
            String bootstrapJson = SimpleJson.stringify(soundcorkDataService.browserBootstrapPayload(clientId));
            String clientStateModeJson = SimpleJson.stringify(clientStateMode.configValue());
            return """
                    <script>
                    (function () {
                        window.StockholmBrowserBootstrap = %s;
                        var bootstrap = window.StockholmBrowserBootstrap || {};
                        var clientStateMode = %s;

                        function toBase64(value) {
                            return window.btoa(unescape(encodeURIComponent(String(value))));
                        }

                        function mergeFrameConfig(config) {
                            if (!bootstrap.frameConfig || typeof bootstrap.frameConfig !== "object") {
                                return config;
                            }
                            config = config || {};
                            config.default = config.default || {};
                            Object.keys(bootstrap.frameConfig).forEach(function (key) {
                                var value = bootstrap.frameConfig[key];
                                if (!/^f\\d+$/.test(key) || value === undefined || value === null) {
                                    return;
                                }
                                var targetKey = "d" + key.substring(1);
                                if (config.default[targetKey] === undefined || config.default[targetKey] === null
                                        || config.default[targetKey] === "") {
                                    config.default[targetKey] = toBase64(value);
                                }
                            });
                            return config;
                        }

                        if (clientStateMode === "per-browser" && bootstrap.clientId) {
                            try {
                                window.localStorage.setItem("stockholmBridgeClientId", bootstrap.clientId);
                            } catch (ignored) {
                            }
                            document.cookie = "stockholmClientId=" + encodeURIComponent(bootstrap.clientId)
                                    + "; Max-Age=31536000; Path=/; SameSite=Lax";
                        }

                        var originalGetURLParams = window.getURLParams;
                        if (typeof originalGetURLParams === "function") {
                            window.getURLParams = function (name, url) {
                                var value = originalGetURLParams(name, url);
                                if (value !== null && value !== undefined) {
                                    return value;
                                }
                                if (name === "native_version" && bootstrap.nativeVersion) {
                                    return bootstrap.nativeVersion;
                                }
                                if (name === "authServer" && bootstrap.authServer !== undefined && bootstrap.authServer !== null) {
                                    return String(bootstrap.authServer);
                                }
                                if (name === "guid" && bootstrap.guid) {
                                    return bootstrap.guid;
                                }
                                return value;
                            };
                        }

                        var originalGetUserAgentValue = window.getUserAgentValue;
                        if (typeof originalGetUserAgentValue === "function") {
                            window.getUserAgentValue = function (name) {
                                var value = originalGetUserAgentValue(name);
                                if ((!value || value === "") && name === "_app" && bootstrap.guid) {
                                    return bootstrap.guid;
                                }
                                return value;
                            };
                        }

                        if ((!window.guid || window.guid === "") && bootstrap.guid) {
                            window.guid = bootstrap.guid;
                        }
                        if ((!window.frame_version || window.frame_version === "") && bootstrap.nativeVersion) {
                            window.frame_version = bootstrap.nativeVersion;
                        }
                        if ((window.auth_server === undefined || window.auth_server === null || window.auth_server === "")
                                && bootstrap.authServer !== undefined && bootstrap.authServer !== null) {
                            window.auth_server = bootstrap.authServer;
                        }

                        var originalSettingsLoad = window.settingsLoad;
                        if (typeof originalSettingsLoad === "function") {
                            window.settingsLoad = function (config) {
                                return originalSettingsLoad(mergeFrameConfig(config));
                            };
                        }
                    })();
                    </script>
                    """
                    .formatted(bootstrapJson, clientStateModeJson);
        }

        private String contentType(Path file) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".html")) {
                return "text/html; charset=UTF-8";
            }
            if (name.endsWith(".js")) {
                return "application/javascript; charset=UTF-8";
            }
            if (name.endsWith(".css")) {
                return "text/css; charset=UTF-8";
            }
            if (name.endsWith(".json")) {
                return "application/json; charset=UTF-8";
            }
            if (name.endsWith(".xml")) {
                return "application/xml; charset=UTF-8";
            }
            if (name.endsWith(".svg")) {
                return "image/svg+xml";
            }
            if (name.endsWith(".png")) {
                return "image/png";
            }
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                return "image/jpeg";
            }
            if (name.endsWith(".gif")) {
                return "image/gif";
            }
            if (name.endsWith(".ttf")) {
                return "font/ttf";
            }
            if (name.endsWith(".otf")) {
                return "font/otf";
            }
            if (name.endsWith(".txt")) {
                return "text/plain; charset=UTF-8";
            }
            return "application/octet-stream";
        }
    }

    enum ClientStateMode {
        SINGLE("single"),
        PER_BROWSER("per-browser");

        private static final String ENVIRONMENT_KEY = "STOCKHOLM_CLIENT_STATE_MODE";
        private final String configValue;

        ClientStateMode(String configValue) {
            this.configValue = configValue;
        }

        String configValue() {
            return configValue;
        }

        boolean isPerBrowser() {
            return this == PER_BROWSER;
        }

        static ClientStateMode fromEnvironment(java.util.Map<String, String> environment) {
            if (environment == null) {
                return PER_BROWSER;
            }
            return fromValue(environment.get(ENVIRONMENT_KEY));
        }

        static ClientStateMode fromValue(String value) {
            if (value == null || value.isBlank()) {
                return PER_BROWSER;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if ("per-browser".equals(normalized) || "per_browser".equals(normalized)
                    || "multi".equals(normalized) || "default".equals(normalized)) {
                return PER_BROWSER;
            }
            if (!"single".equals(normalized) && !"legacy".equals(normalized)) {
                LOGGER.warn("Unknown {} value '{}'; using per-browser state", ENVIRONMENT_KEY, value);
                return PER_BROWSER;
            }
            return SINGLE;
        }
    }
}
