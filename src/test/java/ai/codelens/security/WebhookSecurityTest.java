package ai.codelens.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSecurityTest {
    @Test
    void acceptsExactPayloadAndRejectsTampering() {
        byte[] body = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSecurity.sign(body, "a-strong-test-secret-value");
        assertTrue(WebhookSecurity.verify(body, signature, "a-strong-test-secret-value"));
        assertFalse(WebhookSecurity.verify("{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8), signature, "a-strong-test-secret-value"));
        assertFalse(WebhookSecurity.verify(body, "sha256=bad", "a-strong-test-secret-value"));
    }
}
