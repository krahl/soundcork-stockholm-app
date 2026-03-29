package com.soundcork.stockholm.backend;

import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

final class SsdpDiscoveryService {
    private static final String MULTICAST_HOST = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int SOCKET_TIMEOUT_MS = 700;
    private static final int SEARCH_WINDOW_MS = 1800;
    private static final String RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String SERVER_ST = "urn:schemas-upnp-org:device:MediaServer:1";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    List<Map<String, Object>> discoverRenderers() {
        Map<String, Map<String, Object>> devices = new LinkedHashMap<>();
        for (Map<String, String> response : search(RENDERER_ST)) {
            String host = hostFromResponse(response);
            if (host == null || devices.containsKey(host)) {
                continue;
            }
            Map<String, Object> speaker = fetchSpeakerInfo(host);
            if (speaker != null) {
                devices.put(host, speaker);
            }
        }
        return devices.values().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.get("ip"))))
                .toList();
    }

    List<Map<String, Object>> discoverServers() {
        Map<String, Map<String, Object>> servers = new LinkedHashMap<>();
        for (Map<String, String> response : search(SERVER_ST)) {
            String location = response.get("location");
            String usn = response.get("usn");
            if (location == null) {
                continue;
            }
            try {
                URI uri = URI.create(location);
                if (uri.getHost() == null || uri.getPort() < 0) {
                    continue;
                }
                LinkedHashMap<String, Object> server = new LinkedHashMap<>();
                server.put("uID", normalizeUuid(usn, uri.getHost() + ":" + uri.getPort()));
                server.put("ip", uri.getHost());
                server.put("port", String.valueOf(uri.getPort()));
                servers.put(uri.getHost() + ":" + uri.getPort(), server);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ArrayList<>(servers.values());
    }

    private List<Map<String, String>> search(String searchTarget) {
        ArrayList<Map<String, String>> responses = new ArrayList<>();
        InetSocketAddress destination = new InetSocketAddress(MULTICAST_HOST, SSDP_PORT);
        byte[] payload = createSearchPayload(searchTarget).getBytes(StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + SEARCH_WINDOW_MS;

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.send(new DatagramPacket(payload, payload.length, destination));
            while (System.currentTimeMillis() < deadline) {
                byte[] buffer = new byte[8192];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    responses.add(parseHeaders(packet));
                } catch (SocketTimeoutException ignored) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        return responses;
    }

    private Map<String, String> parseHeaders(DatagramPacket packet) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        String[] lines = payload.split("\\r?\\n");
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            headers.putIfAbsent(key, value);
        }
        headers.putIfAbsent("remote-ip", packet.getAddress().getHostAddress());
        return headers;
    }

    private String hostFromResponse(Map<String, String> response) {
        String location = response.get("location");
        if (location != null) {
            try {
                URI uri = URI.create(location);
                if (uri.getHost() != null) {
                    return uri.getHost();
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return response.get("remote-ip");
    }

    private Map<String, Object> fetchSpeakerInfo(String host) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":8090/info"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().isBlank()) {
                return null;
            }
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(response.body())));
            var infoNodes = document.getElementsByTagName("info");
            if (infoNodes.getLength() == 0) {
                return null;
            }
            var info = infoNodes.item(0);
            var attribute = info.getAttributes().getNamedItem("deviceID");
            if (attribute == null || attribute.getNodeValue().isBlank()) {
                return null;
            }
            LinkedHashMap<String, Object> speaker = new LinkedHashMap<>();
            speaker.put("uID", attribute.getNodeValue().toUpperCase(Locale.ROOT));
            speaker.put("ip", host);
            return speaker;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeUuid(String usn, String fallback) {
        if (usn == null || usn.isBlank()) {
            return fallback;
        }
        String value = usn;
        int separator = value.indexOf("::");
        if (separator >= 0) {
            value = value.substring(0, separator);
        }
        if (value.regionMatches(true, 0, "uuid:", 0, 5)) {
            value = value.substring(5);
        }
        return value;
    }

    private String createSearchPayload(String searchTarget) {
        return String.join("\r\n",
                "M-SEARCH * HTTP/1.1",
                "Host:239.255.255.250:1900",
                "Man:\"ssdp:discover\"",
                "MX:3",
                "ST:" + searchTarget,
                "",
                "");
    }
}
