package ai.codelens.worker;

import ai.codelens.contracts.Models;
import ai.codelens.review.ReviewExecutionGuard;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.LeaseLostException;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A failed heartbeat is fail-closed; only a fresh claim can restore ownership. */
final class JobLease implements ReviewExecutionGuard, AutoCloseable {
    private final JdbcStore store;
    private final Models.ClaimedJob job;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final ScheduledFuture<?> heartbeat;

    JobLease(JdbcStore store, Models.ClaimedJob job, ScheduledExecutorService scheduler) {
        this.store = store;
        this.job = job;
        this.heartbeat = scheduler.scheduleWithFixedDelay(this::renew, 20, 20, TimeUnit.SECONDS);
    }

    void renew() {
        if (lost.get()) return;
        try { if (!store.renewJobLease(job)) lost.set(true); }
        catch (RuntimeException exception) { lost.set(true); }
    }

    @Override public void check() {
        if (lost.get()) throw new LeaseLostException();
        try {
            if (!store.hasJobLease(job)) {
                lost.set(true);
                throw new LeaseLostException();
            }
        } catch (LeaseLostException exception) { throw exception; }
        catch (RuntimeException exception) {
            lost.set(true);
            throw new LeaseLostException(exception);
        }
    }

    @Override public void write(Runnable action) {
        check();
        try { store.withJobLease(job, action); }
        catch (LeaseLostException exception) { lost.set(true); throw exception; }
    }

    @Override public void close() { heartbeat.cancel(false); }
}
