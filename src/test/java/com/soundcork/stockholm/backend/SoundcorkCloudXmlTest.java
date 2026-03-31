package com.soundcork.stockholm.backend;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SoundcorkCloudXmlTest {
    @Test
    void parsesLoginRequestXml() {
        byte[] body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <login><username>user@example.com</username><password>pw&amp;123</password></login>
                """.getBytes(StandardCharsets.UTF_8);

        SoundcorkCloudXml.LoginCredentials credentials = SoundcorkCloudXml.parseLoginRequest(body);

        assertNotNull(credentials);
        assertEquals("user@example.com", credentials.email());
        assertEquals("pw&123", credentials.password());
    }

    @Test
    void extractsStatusCodeAndAccountIdFromResponses() {
        byte[] failure = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <status><message>Account Login failure.</message><status-code>4024</status-code></status>
                """.getBytes(StandardCharsets.UTF_8);
        byte[] success = """
                <?xml version="1.0" encoding="UTF-8"?>
                <account id="12345"><accountStatus>ACTIVE</accountStatus></account>
                """.getBytes(StandardCharsets.UTF_8);

        assertEquals("4024", SoundcorkCloudXml.extractStatusCode(failure));
        assertEquals("12345", SoundcorkCloudXml.extractAccountId(success));
        assertNull(SoundcorkCloudXml.extractAccountId(failure));
    }

    @Test
    void extractsEnvironmentUrls() {
        byte[] body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <account_profile>
                  <streamingURL>https://alt-streaming.bose.com/</streamingURL>
                  <updateURL>https://worldwide.bose.com/updates/</updateURL>
                </account_profile>
                """.getBytes(StandardCharsets.UTF_8);

        SoundcorkCloudXml.EnvironmentInfo environmentInfo = SoundcorkCloudXml.extractEnvironment(body);

        assertNotNull(environmentInfo);
        assertEquals("https://alt-streaming.bose.com/", environmentInfo.streamingUrl());
        assertEquals("https://worldwide.bose.com/updates/", environmentInfo.updateUrl());
    }
}
