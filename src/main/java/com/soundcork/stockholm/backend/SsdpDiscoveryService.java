package com.soundcork.stockholm.backend;

import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

final class SsdpDiscoveryService {
    private static final String MULTICAST_HOST = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int MX_SECONDS = 1;
    private static final int SEARCH_PROBE_COUNT = 3;
    private static final int SEARCH_PROBE_INTERVAL_MS = 350;
    private static final int SEARCH_RESPONSE_GRACE_MS = 1250;
    private static final int RECEIVE_SLICE_MS = 250;
    private static final String RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String SERVER_ST = "urn:schemas-upnp-org:device:MediaServer:1";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    List<Map<String, Object>> discoverRenderers() {
        return discoverRenderers(null);
    }

    List<Map<String, Object>> discoverRenderers(Consumer<List<Map<String, Object>>> onDiscovered) {
        Map<String, Map<String, Object>> devices = new LinkedHashMap<>();
        for (Map<String, String> response : search(RENDERER_ST)) {
            String host = hostFromResponse(response);
            if (host == null || devices.containsKey(host)) {
                continue;
            }
            Map<String, Object> speaker = fetchSpeakerInfo(host);
            if (speaker != null) {
                devices.put(host, speaker);
                if (onDiscovered != null) {
                    onDiscovered.accept(List.of(new LinkedHashMap<>(speaker)));
                }
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
                int port = uri.getPort() >= 0 ? uri.getPort() : defaultPort(uri);
                if (uri.getHost() == null || port < 0) {
                    continue;
                }
                LinkedHashMap<String, Object> server = new LinkedHashMap<>();
                server.put("uID", normalizeUuid(usn, uri.getHost() + ":" + port));
                server.put("ip", uri.getHost());
                server.put("port", String.valueOf(port));
                servers.put(uri.getHost() + ":" + port, server);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ArrayList<>(servers.values());
    }

    private List<Map<String, String>> search(String searchTarget) {
        List<NetworkInterface> interfaces = discoveryInterfaces();
        if (interfaces.isEmpty()) {
            return searchOnInterface(searchTarget, null);
        }

        for (NetworkInterface networkInterface : interfaces) {
            List<Map<String, String>> responses = searchOnInterface(searchTarget, networkInterface);
            if (!responses.isEmpty()) {
                return responses;
            }
        }

        return List.of();
    }

    private List<Map<String, String>> searchOnInterface(String searchTarget, NetworkInterface networkInterface) {
        LinkedHashMap<String, Map<String, String>> responses = new LinkedHashMap<>();
        InetSocketAddress destination = new InetSocketAddress(MULTICAST_HOST, SSDP_PORT);
        byte[] payload = createSearchPayload(searchTarget).getBytes(StandardCharsets.UTF_8);

        try (MulticastSocket socket = createSocket(networkInterface)) {
            for (int probe = 0; probe < SEARCH_PROBE_COUNT; probe++) {
                socket.send(new DatagramPacket(payload, payload.length, destination));
                receiveResponsesUntil(socket, searchTarget, responses, System.currentTimeMillis() + SEARCH_PROBE_INTERVAL_MS);
            }
            receiveResponsesUntil(socket, searchTarget, responses, System.currentTimeMillis() + SEARCH_RESPONSE_GRACE_MS);
        } catch (Exception ignored) {
        }

        return new ArrayList<>(responses.values());
    }

    private MulticastSocket createSocket(NetworkInterface networkInterface) throws Exception {
        if (networkInterface == null) {
            MulticastSocket socket = new MulticastSocket();
            socket.setReuseAddress(true);
            socket.setTimeToLive(2);
            return socket;
        }

        Inet4Address bindAddress = primaryIpv4Address(networkInterface);
        if (bindAddress == null) {
            throw new SocketException("No IPv4 address on interface " + networkInterface.getDisplayName());
        }

        MulticastSocket socket = new MulticastSocket(new InetSocketAddress(bindAddress, 0));
        socket.setReuseAddress(true);
        socket.setNetworkInterface(networkInterface);
        socket.setTimeToLive(2);
        return socket;
    }

    private void receiveResponsesUntil(MulticastSocket socket, String searchTarget,
            Map<String, Map<String, String>> responses, long deadline) throws Exception {
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }

            socket.setSoTimeout((int) Math.min(RECEIVE_SLICE_MS, remaining));
            byte[] buffer = new byte[8192];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                Map<String, String> headers = parseHeaders(packet);
                if (!matchesSearchTarget(headers, searchTarget)) {
                    continue;
                }
                responses.putIfAbsent(responseKey(headers), headers);
            } catch (SocketTimeoutException ignored) {
            }
        }
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

    private boolean matchesSearchTarget(Map<String, String> response, String searchTarget) {
        String st = response.get("st");
        if (st != null && st.equalsIgnoreCase(searchTarget)) {
            return true;
        }
        String usn = response.get("usn");
        return usn != null && usn.toLowerCase(Locale.ROOT).contains(searchTarget.toLowerCase(Locale.ROOT));
    }

    private String responseKey(Map<String, String> response) {
        return String.join("|",
                response.getOrDefault("usn", ""),
                response.getOrDefault("location", ""),
                response.getOrDefault("remote-ip", ""));
    }

    private Map<String, Object> fetchSpeakerInfo(String host) {
        try {
            HttpRequest request = HttpRequest.newBuilder(new URI("http", null, host, 8090, "/info", null, null))
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

    private int defaultPort(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return -1;
        }
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    private List<NetworkInterface> discoveryInterfaces() {
        ArrayList<NetworkInterface> interfaces = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> candidates = NetworkInterface.getNetworkInterfaces();
            while (candidates != null && candidates.hasMoreElements()) {
                NetworkInterface networkInterface = candidates.nextElement();
                if (isDiscoveryInterface(networkInterface)) {
                    interfaces.add(networkInterface);
                }
            }
        } catch (SocketException ignored) {
        }
        interfaces.sort(Comparator
                .comparingInt(this::interfacePriority)
                .thenComparingInt(NetworkInterface::getIndex));
        return interfaces;
    }

    private boolean isDiscoveryInterface(NetworkInterface networkInterface) {
        try {
            if (!networkInterface.isUp() || networkInterface.isLoopback() || !networkInterface.supportsMulticast()) {
                return false;
            }
            if (networkInterface.isVirtual()) {
                return false;
            }
        } catch (SocketException exception) {
            return false;
        }

        String descriptor = (networkInterface.getName() + " " + networkInterface.getDisplayName()).toLowerCase(Locale.ROOT);
        if (descriptor.contains("docker") || descriptor.contains("vbox") || descriptor.contains("vmware")
                || descriptor.contains("hyper-v") || descriptor.contains("loopback") || descriptor.contains("bluetooth")
                || descriptor.contains("teredo") || descriptor.contains("tunnel")) {
            return false;
        }

        return primaryIpv4Address(networkInterface) != null;
    }

    private int interfacePriority(NetworkInterface networkInterface) {
        String descriptor = (networkInterface.getName() + " " + networkInterface.getDisplayName()).toLowerCase(Locale.ROOT);
        if (descriptor.contains("ethernet") || descriptor.startsWith("eth") || descriptor.startsWith("en")) {
            return 0;
        }
        if (descriptor.contains("wi-fi") || descriptor.contains("wifi") || descriptor.contains("wlan")
                || descriptor.startsWith("wl")) {
            return 1;
        }
        return 2;
    }

    private Inet4Address primaryIpv4Address(NetworkInterface networkInterface) {
        Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address instanceof Inet4Address inet4Address && !inet4Address.isLoopbackAddress()) {
                return inet4Address;
            }
        }
        return null;
    }

    private String createSearchPayload(String searchTarget) {
        return String.join("\r\n",
                "M-SEARCH * HTTP/1.1",
                "Host:239.255.255.250:1900",
                "Man:\"ssdp:discover\"",
                "MX:" + MX_SECONDS,
                "ST:" + searchTarget,
                "",
                "");
    }
}
