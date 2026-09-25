package ai.codelens.credentials;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialVaultTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsWithContextAndRejectsTampering() {
        CredentialVault vault = new CredentialVault(KEY);
        String encrypted = vault.seal("provider-secret", "installation:42");
        assertNotEquals("provider-secret", encrypted);
        assertEquals("provider-secret", vault.open(encrypted, "installation:42"));
        assertThrows(IllegalArgumentException.class, () -> vault.open(encrypted, "installation:43"));

        byte[] bytes = Base64.getDecoder().decode(encrypted);
        bytes[bytes.length - 1] ^= 1;
        String tampered = Base64.getEncoder().withoutPadding().encodeToString(bytes);
        assertThrows(IllegalArgumentException.class, () -> vault.open(tampered, "installation:42"));
    }

    @Test
    void tokenComparisonAndFingerprintAreStable() {
        assertTrue(CredentialVault.tokenMatches("same-token", "same-token"));
        assertEquals(CredentialVault.fingerprint("secret"), CredentialVault.fingerprint("secret"));
    }
}
