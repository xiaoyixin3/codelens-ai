package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminRequestAuthorizerTest {
    @Test
    void appliesOneConstantTimeInstallationScopedAdminBoundary() {
        RuntimeConfig config = mock(RuntimeConfig.class);
        when(config.modelAdminToken()).thenReturn("01234567890123456789012345678901");
        AdminRequestAuthorizer authorizer = new AdminRequestAuthorizer(config);

        var accepted = authorizer.authorize(Map.of(
                "Authorization", "Bearer 01234567890123456789012345678901",
                "X-CodeLens-Installation-Id", "42", "X-CodeLens-Actor", "reviewer@example"));
        assertNull(accepted.error());
        assertEquals(42, accepted.installationId());
        assertEquals("reviewer@example", accepted.actor());

        var denied = authorizer.authorize(Map.of("Authorization", "Bearer wrong",
                "X-CodeLens-Installation-Id", "42"));
        assertEquals(HttpStatus.UNAUTHORIZED, denied.error().getStatusCode());
    }
}
