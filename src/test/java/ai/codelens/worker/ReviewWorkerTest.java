package ai.codelens.worker;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.migration.MigrationSchemaVerifier;
import ai.codelens.review.ReviewEngine;
import ai.codelens.store.JdbcStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewWorkerTest {
    @Test
    void refusesToRecoverOrClaimJobsWhenTheSchemaIsNotReady() {
        JdbcStore store = mock(JdbcStore.class);
        RuntimeConfig config = mock(RuntimeConfig.class);
        MigrationSchemaVerifier schema = mock(MigrationSchemaVerifier.class);
        when(config.workerConcurrency()).thenReturn(1);
        doThrow(new IllegalStateException("schema not ready")).when(schema).requireReady();
        ReviewWorker worker = new ReviewWorker(store, mock(ReviewEngine.class), config, schema);
        try {
            assertThrows(IllegalStateException.class, worker::start);
            verify(store, never()).recoverStaleJobs();
        } finally {
            worker.stop();
        }
    }
}
