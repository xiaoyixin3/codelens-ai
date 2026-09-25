package ai.codelens.credentials;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

public final class CredentialVault {
    public static final int KEY_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialVault(String encodedKey) {
        byte[] raw;
        try { raw = Base64.getDecoder().decode(encodedKey); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("credential encryption key must be standard base64", exception); }
        if (raw.length != 32) throw new IllegalArgumentException("credential encryption key must decode to exactly 32 bytes");
        key = new SecretKeySpec(raw, "AES");
    }

    public String seal(String plaintext, String context) {
        if (plaintext == null || plaintext.isEmpty()) throw new IllegalArgumentException("credential cannot be empty");
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (Exception exception) {
            throw new IllegalStateException("credential encryption failed", exception);
        }
    }

    public String open(String encoded, String context) {
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length <= NONCE_BYTES) throw new IllegalArgumentException("credential ciphertext is truncated");
            byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("credential ciphertext authentication failed", exception);
        }
    }

    public static String fingerprint(String secret) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public static boolean tokenMatches(String actual, String expected) {
        try {
            Mac left = Mac.getInstance("HmacSHA256");
            left.init(new SecretKeySpec("codelens-token-compare".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            Mac right = Mac.getInstance("HmacSHA256");
            right.init(new SecretKeySpec("codelens-token-compare".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(left.doFinal(actual.getBytes(StandardCharsets.UTF_8)), right.doFinal(expected.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
