package com.soundcork.stockholm.backend;

import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class SoundcorkCloudXml {
    private SoundcorkCloudXml() {
    }

    static LoginCredentials parseLoginRequest(byte[] body) {
        Document document = parse(body);
        if (document == null) {
            return null;
        }
        String username = textOfFirst(document, "username");
        String password = textOfFirst(document, "password");
        if (username == null || username.isBlank() || password == null) {
            return null;
        }
        return new LoginCredentials(username, password);
    }

    static String extractStatusCode(byte[] body) {
        Document document = parse(body);
        return document == null ? null : textOfFirst(document, "status-code");
    }

    static String extractAccountId(byte[] body) {
        Document document = parse(body);
        if (document == null) {
            return null;
        }
        Element account = firstElement(document, "account");
        if (account == null) {
            return null;
        }
        String accountId = account.getAttribute("id");
        return accountId == null || accountId.isBlank() ? null : accountId;
    }

    static EnvironmentInfo extractEnvironment(byte[] body) {
        Document document = parse(body);
        if (document == null) {
            return null;
        }
        String streamingUrl = textOfFirst(document, "streamingURL");
        String updateUrl = textOfFirst(document, "updateURL");
        if ((streamingUrl == null || streamingUrl.isBlank()) && (updateUrl == null || updateUrl.isBlank())) {
            return null;
        }
        return new EnvironmentInfo(streamingUrl, updateUrl);
    }

    private static Document parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (Exception exception) {
            return null;
        }
    }

    private static Element firstElement(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element element)) {
            return null;
        }
        return element;
    }

    private static String textOfFirst(Document document, String tagName) {
        Element element = firstElement(document, tagName);
        if (element == null) {
            return null;
        }
        String text = element.getTextContent();
        return text == null || text.isBlank() ? null : text;
    }

    record LoginCredentials(String email, String password) {
    }

    record EnvironmentInfo(String streamingUrl, String updateUrl) {
    }
}
