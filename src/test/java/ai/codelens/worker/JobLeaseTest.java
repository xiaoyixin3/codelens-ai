package ai.codelens.worker;

import ai.codelens.contracts.Models;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.LeaseLostException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class JobLeaseTest {
    private final JdbcStore store = mock(JdbcStore.class);
    private final Models.ClaimedJob job = new Models.ClaimedJob("job",
            new Models.ReviewJob("run", 1, "owner", "repo", 7, "base1234", "head1234"), 0, 1);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);

    private JobLease lease() {
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(20L), eq(20L), eq(TimeUnit.SECONDS));
        return new JobLease(store, job, scheduler);
    }

    @Test void renewsAndCancelsTheHeartbeatOnClose() {
        when(store.hasJobLease(job)).thenReturn(true);
        when(store.renewJobLease(job)).thenReturn(true);
        try (JobLease lease = lease()) {
            lease.renew();
            lease.check();
            Runnable write = mock(Runnable.class);
            lease.write(write);
            verify(store).withJobLease(job, write);
        }
        verify(future).cancel(false);
    }

    @Test void failedHeartbeatCannotBeRevivedByALaterSuccessfulLookup() {
        when(store.hasJobLease(job)).thenReturn(true);
        when(store.renewJobLease(job)).thenReturn(false);
        try (JobLease lease = lease()) {
            lease.renew();
            assertThrows(LeaseLostException.class, lease::check);
            assertThrows(LeaseLostException.class, () -> lease.write(() -> {}));
            verify(store, never()).hasJobLease(job);
            verify(store, never()).withJobLease(any(), any());
        }
    }

    @Test void uncertainHeartbeatFailsClosed() {
        when(store.renewJobLease(job)).thenThrow(new IllegalStateException("database unavailable"));
        try (JobLease lease = lease()) {
            lease.renew();
            assertThrows(LeaseLostException.class, lease::check);
        }
    }

    @Test void uncertainOwnershipLookupFailsClosed() {
        when(store.hasJobLease(job)).thenThrow(new IllegalStateException("database unavailable")).thenReturn(true);
        try (JobLease lease = lease()) {
            assertThrows(LeaseLostException.class, lease::check);
            assertThrows(LeaseLostException.class, lease::check);
            verify(store, times(1)).hasJobLease(job);
        }
    }
}
