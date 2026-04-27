package com.soundcork.stockholm.backend;

import java.nio.charset.StandardCharsets;
import java.util.Base64;


final class SoundcorkCrypto {
    static final String DEFAULT_KILO = "a7928d7b43dcd49f0af31e5aeed26458";
    static final String OLD_KILO = "7ec8d0edf85e6f48a38cdcda7690d3a1";

    private SoundcorkCrypto() {
    }

    static String decodeBase64String(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }


}
