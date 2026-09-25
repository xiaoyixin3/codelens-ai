package ai.codelens.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class WebhookSecurity {
    private WebhookSecurity() {}

    public static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign webhook", exception);
        }
    }

    public static boolean verify(byte[] body, String signature, String secret) {
        if (signature == null || !signature.startsWith("sha256=") || signature.length() != 71) return false;
        return MessageDigest.isEqual(sign(body, secret).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
