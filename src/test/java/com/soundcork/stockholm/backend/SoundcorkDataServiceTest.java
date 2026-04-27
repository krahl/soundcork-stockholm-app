package com.soundcork.stockholm.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SoundcorkDataServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void prefersConfigAndOverrideFilesBeforeLegacyFrameDefaults() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Path stockholmJson = workspaceRoot.resolve("stockholm").resolve("json");
        Path backendState = workspaceRoot.resolve("backend").resolve("state");
        Files.createDirectories(stockholmJson);
        Files.createDirectories(backendState);

        Files.writeString(stockholmJson.resolve("config.json"), """
                {
                  "app_versions": {
                    "bose_app": "27.0.8-test",
                    "bose_protocol": "67"
                  },
                  "api_versions": {
                    "bose_streaming": "1.1",
                    "bose_customer": "1.0"
                  },
                  "default": {
                    "d0": "aHR0cHM6Ly9jdXN0b20tc3RyZWFtaW5nLmV4YW1wbGUv",
                    "d1": "aHR0cHM6Ly9jdXN0b20tdXBkYXRlcy5leGFtcGxlLw==",
                    "d3": "aHR0cHM6Ly9jdXN0b20tcmVnaXN0cnkuZXhhbXBsZS9ibXgvcmVnaXN0cnkvdjEvc2VydmljZXM=",
                    "d7": "OGNhZWI1YjI0ZjQ0YTZlOGExOTdjYmEwZjFhYmQ0Y2VlN2ZmYzc1M2JhOWIyMzdiOWEwOWQ1ZDhjNGI1ZTYwN2E3Zjk2ZWVhOGU5OGFkNGI4MjY5OTM3MzM2YzhjZTFl",
                    "d8": "ZjY1Y2FlM2M4MGQ5Nzg5Yjg1Mzk1ZGRjNjg0YzVhYjJkMjIzYWUyZTM4NDQwNTY4Y2M2MGRkOTljMGY5YzdhMmNmMTczMjUyMGYyMTgzZmQ3ZDQzMjFkNGUzNmJkMDUzZTI1YTgxOTViNjVlNTM0NDdhNzVlY2ExZWRhZjg0ZmE="
                  }
                }
                """, StandardCharsets.UTF_8);


        NativeBridgeService bridgeService = new NativeBridgeService(backendState.resolve("native-state.json"));
        bridgeService.putStateValues(Map.of(
                "authServer", "2",
                "constant.kilo", "22222222222222222222222222222222"));

        SoundcorkDataService dataService = new SoundcorkDataService(workspaceRoot, bridgeService);

        assertEquals("https://custom-streaming.example/", dataService.currentMargeUrl());
        assertEquals("https://custom-updates.example/", dataService.currentUpdateUrl());
        assertEquals("https://custom-registry.example/bmx/registry/v1/services", dataService.bmxRegistryUrl());
    }
}
