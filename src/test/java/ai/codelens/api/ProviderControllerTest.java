package ai.codelens.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProviderControllerTest {
    @Test
    void rejectsUnsafeOrMalformedProviderConfiguration() {
        assertEquals("baseUrl must be an absolute HTTPS URL", ProviderController.validate(
                "primary", "openai_compatible", "http://example.com/v1", "key", "model", 45, 2, false));
        assertEquals("baseUrl must not resolve to a private address", ProviderController.validate(
                "primary", "openai_compatible", "https://127.0.0.1/v1", "key", "model", 45, 2, false));
        assertEquals("providerKind is unsupported", ProviderController.validate(
                "primary", "unknown", "https://example.com", "key", "model", 45, 2, true));
        assertNull(ProviderController.validate(
                "primary", "anthropic", "https://example.com", "key", "model", 45, 2, true));
    }
}
