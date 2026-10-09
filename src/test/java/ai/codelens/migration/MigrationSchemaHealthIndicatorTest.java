package ai.codelens.migration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MigrationSchemaHealthIndicatorTest {
    @Test
    void actuatorHealthIsDownWhenTheSchemaManifestIsNotReady() {
        MigrationSchemaVerifier verifier = mock(MigrationSchemaVerifier.class);
        when(verifier.status()).thenReturn(new MigrationSchemaVerifier.Status(false, "migration_missing"));

        assertEquals(Status.DOWN, new MigrationSchemaHealthIndicator(verifier).health().getStatus());
    }
}
