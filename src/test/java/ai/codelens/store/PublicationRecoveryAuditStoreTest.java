package ai.codelens.store;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PublicationRecoveryAuditStoreTest {
    @Test void investigationRejectsUnboundedIdentityBeforeDatabaseAccess() {
        var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.getDataSource()).thenReturn(source);
        var audit=new PublicationRecoveryAuditStore(jdbc,source);
        org.mockito.Mockito.clearInvocations(jdbc,source);
        assertThrows(IllegalArgumentException.class, () -> audit.investigate(null,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> audit.investigate(UUID.randomUUID(),"typed name"));
        org.mockito.Mockito.verifyNoInteractions(jdbc,source);
    }
    @Test void metadataRejectsFreeFormIdentitiesEvidenceAndNullIds() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new PublicationRecoveryAuditStore.Attempt(null, id, "a".repeat(64), "b".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new PublicationRecoveryAuditStore.Attempt(id, id, "operator typed name", "b".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new PublicationRecoveryAuditStore.Attempt(id, id, "a".repeat(64), "private review body"));
        assertDoesNotThrow(() -> new PublicationRecoveryAuditStore.Attempt(id, id, "a".repeat(64), "b".repeat(64)));
    }
}
