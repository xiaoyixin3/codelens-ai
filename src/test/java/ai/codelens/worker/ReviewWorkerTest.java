package ai.codelens.worker;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.PublicationUncertainException;
import ai.codelens.migration.MigrationSchemaVerifier;
import ai.codelens.review.ReviewEngine;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.LeaseLostException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.*;

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
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, worker::start);
            verify(store, never()).recoverStaleJobs();
        } finally { worker.stop(); }
    }

    private final JdbcStore store = mock(JdbcStore.class);
    private final ReviewEngine engine = mock(ReviewEngine.class);

    private ReviewWorker worker() {
        RuntimeConfig config = mock(RuntimeConfig.class);
        when(config.workerConcurrency()).thenReturn(1);
        return new ReviewWorker(store, engine, config, mock(MigrationSchemaVerifier.class));
    }

    private Models.ClaimedJob job(int attempts) {
        return new Models.ClaimedJob("job", new Models.ReviewJob("run", 1, "owner", "repo", 7,
                "base1234", "head1234"), attempts, 1);
    }

    @Test void lostOwnerNeverRetriesOrFailsAnotherOwnersRun() {
        Models.ClaimedJob job = job(2);
        when(store.hasJobLease(job)).thenReturn(true);
        doThrow(new LeaseLostException()).when(engine).execute(eq(job.payload()), any());
        ReviewWorker worker = worker();
        try { worker.process(job); }
        finally { worker.stop(); }
        verify(store, never()).completeJob(any());
        verify(store, never()).retryJob(any(), anyString());
        verify(engine, never()).fail(any(), anyString(), any());
    }

    @Test void staleCompletionDoesNotRetry() {
        Models.ClaimedJob job = job(0);
        when(store.hasJobLease(job)).thenReturn(true);
        doThrow(new LeaseLostException()).when(store).completeJob(job);
        ReviewWorker worker = worker();
        try { worker.process(job); }
        finally { worker.stop(); }
        verify(store, never()).retryJob(any(), anyString());
    }

    @Test void currentOwnerRetriesAndReportsTerminalFailureBeforeReleasingLease() {
        Models.ClaimedJob job = job(2);
        when(store.hasJobLease(job)).thenReturn(true);
        doThrow(new IllegalStateException("bounded failure")).when(engine).execute(eq(job.payload()), any());
        when(store.retryJob(job, "bounded failure")).thenReturn(true);
        ReviewWorker worker = worker();
        try { worker.process(job); }
        finally { worker.stop(); }
        var ordered = inOrder(engine, store);
        ordered.verify(engine).fail(eq(job.payload()), eq("bounded failure"), any());
        ordered.verify(store).retryJob(job, "bounded failure");
    }

    @Test void uncertainWriteStopsWithoutRetryOrAnotherRemoteFailureWrite() {
        Models.ClaimedJob job = job(2);
        when(store.hasJobLease(job)).thenReturn(true);
        doThrow(new PublicationUncertainException("POST", "/check-runs", new java.io.IOException())).when(engine).execute(eq(job.payload()), any());
        ReviewWorker worker = worker();
        try { worker.process(job); }
        finally { worker.stop(); }
        verify(store).stopUncertainPublication(eq(job), contains("requires reconciliation"));
        verify(store, never()).retryJob(any(), anyString());
        verify(store, never()).completeJob(any());
        verify(engine, never()).fail(any(), anyString(), any());
    }

    @Test void uncertainTerminalFailureWriteIsAlsoStoppedWithoutRetry() {
        Models.ClaimedJob job = job(2);
        when(store.hasJobLease(job)).thenReturn(true);
        doThrow(new IllegalStateException("analysis failed")).when(engine).execute(eq(job.payload()), any());
        doThrow(new PublicationUncertainException("PATCH", "/check-runs/9", new java.io.IOException()))
                .when(engine).fail(eq(job.payload()), anyString(), any());
        ReviewWorker worker = worker();
        try { worker.process(job); }
        finally { worker.stop(); }
        verify(store).stopUncertainPublication(eq(job), contains("requires reconciliation"));
        verify(store, never()).retryJob(any(), anyString());
    }

    @Test void claimFailureDoesNotLeakTheWorkerSlot() {
        when(store.claimJob()).thenThrow(new IllegalStateException("unavailable")).thenReturn(Optional.empty());
        ReviewWorker worker = worker();
        try { worker.poll(); worker.poll(); worker.recoverExpired(); worker.recoverExpired(); }
        finally { worker.stop(); }
        verify(store, times(2)).claimJob();
        verify(store, times(2)).recoverStaleJobs();
    }
}
