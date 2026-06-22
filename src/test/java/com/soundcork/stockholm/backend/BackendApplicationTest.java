package com.soundcork.stockholm.backend;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendApplicationTest {
    @Test
    void singleClientModeIgnoresBrowserClientId() {
        TestHttpExchange exchange = new TestHttpExchange(URI.create("http://localhost/?clientId=query-client"));
        exchange.getRequestHeaders().set("X-Stockholm-Client-Id", "header-client");
        exchange.getRequestHeaders().set("Cookie", "stockholmClientId=cookie-client");

        String clientId = BackendApplication.ensureClientId(exchange, BackendApplication.ClientStateMode.SINGLE);

        assertEquals(NativeBridgeService.DEFAULT_CLIENT_ID, clientId);
        assertTrue(exchange.getResponseHeaders().getOrDefault("Set-Cookie", List.of()).isEmpty());
    }

    @Test
    void perBrowserModeReusesCookieClientId() {
        TestHttpExchange exchange = new TestHttpExchange(URI.create("http://localhost/"));
        exchange.getRequestHeaders().set("Cookie", "stockholmClientId=cookie-client");

        String clientId = BackendApplication.ensureClientId(exchange, BackendApplication.ClientStateMode.PER_BROWSER);

        assertEquals("cookie-client", clientId);
        assertTrue(exchange.getResponseHeaders().getOrDefault("Set-Cookie", List.of()).isEmpty());
    }

    @Test
    void perBrowserModeCreatesCookieWhenNoClientIdExists() {
        TestHttpExchange exchange = new TestHttpExchange(URI.create("http://localhost/"));

        String clientId = BackendApplication.ensureClientId(exchange, BackendApplication.ClientStateMode.PER_BROWSER);

        assertTrue(clientId.startsWith("stockholm-"));
        assertTrue(exchange.getResponseHeaders().getFirst("Set-Cookie").contains("stockholmClientId=" + clientId));
    }

    @Test
    void clientStateModeDefaultsToPerBrowser() {
        assertEquals(BackendApplication.ClientStateMode.PER_BROWSER, BackendApplication.ClientStateMode.fromEnvironment(Map.of()));
        assertEquals(BackendApplication.ClientStateMode.PER_BROWSER,
                BackendApplication.ClientStateMode.fromEnvironment(Map.of("STOCKHOLM_CLIENT_STATE_MODE", "per-browser")));
        assertEquals(BackendApplication.ClientStateMode.SINGLE,
                BackendApplication.ClientStateMode.fromEnvironment(Map.of("STOCKHOLM_CLIENT_STATE_MODE", "single")));
    }

    private static final class TestHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final URI requestUri;
        private final ByteArrayInputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private TestHttpExchange(URI requestUri) {
            this.requestUri = requestUri;
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return requestUri;
        }

        @Override
        public String getRequestMethod() {
            return "GET";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return 200;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8088);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
