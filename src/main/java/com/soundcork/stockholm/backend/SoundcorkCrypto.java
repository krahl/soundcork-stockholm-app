package com.soundcork.stockholm.backend;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

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

    static String decryptHex(String keyHex, String encryptedHex) {
        if (!isHexString(keyHex) || !isHexString(encryptedHex)) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] key = hexToBytes(keyHex);
            byte[] iv = new byte[16];
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(hexToBytes(encryptedHex));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to decrypt Soundcork value", exception);
        }
    }

    static String decryptLegacyValue(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        String[] parts = encryptedValue.split("\\]");
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            PBEKeySpec keySpec = new PBEKeySpec(DEFAULT_KILO.toCharArray(), salt, 1000, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(keySpec)
                    .getEncoded();
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return null;
        }
    }

    static boolean isHexString(String value) {
        if (value == null || value.isBlank() || (value.length() % 2) != 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            int high = Character.digit(value.charAt(index), 16);
            int low = Character.digit(value.charAt(index + 1), 16);
            bytes[index / 2] = (byte) ((high << 4) + low);
        }
        return bytes;
    }
}
