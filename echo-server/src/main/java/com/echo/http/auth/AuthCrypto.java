package com.echo.http.auth;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** HMAC lookup keys plus authenticated encryption for replayable secret responses and phone PII. */
final class AuthCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    AuthCrypto(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("ECHO_AUTH_SECRET must contain at least 32 characters");
        }
        try {
            key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String hash(String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((namespace + "\u0000" + value)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] joined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, joined, 0, nonce.length);
            System.arraycopy(encrypted, 0, joined, nonce.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(joined);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String decrypt(String ciphertext) {
        try {
            byte[] joined = Base64.getUrlDecoder().decode(ciphertext);
            byte[] nonce = java.util.Arrays.copyOfRange(joined, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(joined, 12, joined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("auth ciphertext cannot be decrypted", e);
        }
    }

    static String token(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String digits(int count) {
        int bound = 1;
        for (int i = 0; i < count; i++) bound *= 10;
        return String.format("%0" + count + "d", RANDOM.nextInt(bound));
    }
}
