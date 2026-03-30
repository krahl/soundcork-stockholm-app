package com.soundcork.stockholm.backend;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BackendApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendApplication.class);

    private BackendApplication() {
    }

    public static void main(String[] args) throws IOException {
        Path workspaceRoot = resolveWorkspaceRoot();
        Path stockholmRoot = workspaceRoot.resolve("stockholm").normalize();
        Path stateFile = workspaceRoot.resolve("backend").resolve("state").resolve("native-state.json").normalize();
        BackendConfig backendConfig = BackendConfig.load(workspaceRoot);
        NativeBridgeService bridgeService = new NativeBridgeService(stateFile);
        HttpProxyService httpProxyService = new HttpProxyService();
        LOGGER.debug("Resolved workspace root {} with stockholmRoot={} and stateFile={}",
                workspaceRoot, stockholmRoot, stateFile);
        LOGGER.debug("Frontend logging level is configured to {}", backendConfig.frontendLoggingLevel());

        InetSocketAddress socketAddress = new InetSocketAddress("0.0.0.0", 8088);
        HttpServer server = HttpServer.create(socketAddress, 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/native/appSend", exchange -> handleAppSend(exchange, bridgeService));
        server.createContext("/api/native/runQueue", exchange -> handleRunQueue(exchange, bridgeService));
        server.createContext("/api/http-proxy", httpProxyService::handle);
        server.createContext("/", new StaticStockholmHandler(stockholmRoot, backendConfig));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down Stockholm backend");
            bridgeService.close();
            server.stop(0);
        }));

        server.start();
        LOGGER.info("Stockholm backend listening on http://{}:{}/",
                socketAddress.getHostString(), socketAddress.getPort());
    }

    private static void handleAppSend(HttpExchange exchange, NativeBridgeService bridgeService) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            LOGGER.debug("Rejected {} request to /api/native/appSend", exchange.getRequestMethod());
            sendText(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        String clientId = clientId(exchange);
        String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LOGGER.debug("Received /api/native/appSend for client '{}'", describeClientId(clientId));
        bridgeService.handleAppSend(clientId, payload);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private static void handleRunQueue(HttpExchange exchange, NativeBridgeService bridgeService) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            LOGGER.debug("Rejected {} request to /api/native/runQueue", exchange.getRequestMethod());
            sendText(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }
        String clientId = clientId(exchange);
        LOGGER.debug("Received /api/native/runQueue for client '{}'", describeClientId(clientId));
        sendText(exchange, 200, bridgeService.runQueue(clientId), "application/json; charset=UTF-8");
    }

    private static String clientId(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Stockholm-Client-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator > 0 && "clientId".equals(part.substring(0, separator))) {
                return URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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

        private StaticStockholmHandler(Path stockholmRoot, BackendConfig backendConfig) {
            this.stockholmRoot = stockholmRoot;
            this.backendConfig = backendConfig;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
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
}
