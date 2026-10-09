package ai.codelens.worker;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.PublicationUncertainException;
import ai.codelens.migration.MigrationSchemaVerifier;
import ai.codelens.review.ReviewEngine;
import ai.codelens.security.Redactor;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.LeaseLostException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Profile("worker")
public class ReviewWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ReviewWorker.class);
    private final JdbcStore store; private final ReviewEngine engine;
    private final MigrationSchemaVerifier schema;
    private final ExecutorService executor; private final Semaphore slots;
    private final ScheduledExecutorService heartbeats;

    public ReviewWorker(JdbcStore store, ReviewEngine engine, RuntimeConfig config, MigrationSchemaVerifier schema) {
        this.store = store; this.engine = engine; this.schema = schema;
        this.executor = Executors.newFixedThreadPool(config.workerConcurrency());
        this.slots = new Semaphore(config.workerConcurrency());
        this.heartbeats = Executors.newScheduledThreadPool(Math.min(4, config.workerConcurrency()));
    }

    @PostConstruct
    void start() {
        schema.requireReady();
        store.recoverStaleJobs();
        LOG.info("CodeLens Java worker started with {} slots", slots.availablePermits());
    }

    @Scheduled(fixedDelayString = "${CODELENS_WORKER_POLL_MS:1000}")
    void poll() {
        while (slots.tryAcquire()) {
            try {
                var claimed = store.claimJob();
                if (claimed.isEmpty()) { slots.release(); break; }
                executor.submit(() -> process(claimed.get()));
            } catch (RuntimeException exception) {
                slots.release();
                LOG.error("Could not claim or dispatch review job: {}", Redactor.redact(exception.getMessage()));
                break;
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    void recoverExpired() {
        try {
            int recovered = store.recoverStaleJobs();
            if (recovered > 0) LOG.warn("Recovered {} expired review job(s)", recovered);
        } catch (RuntimeException exception) {
            LOG.error("Could not recover expired leases: {}", Redactor.redact(exception.getMessage()));
        }
    }

    void process(Models.ClaimedJob job) {
        try (JobLease lease = new JobLease(store, job, heartbeats)) {
            try {
                lease.check();
                engine.execute(job.payload(), lease);
                lease.check();
                store.completeJob(job);
                LOG.info("Review run completed: {}", job.payload().reviewRunId());
            } catch (LeaseLostException exception) {
                LOG.warn("Stopped superseded job owner: {} generation={}", job.id(), job.leaseGeneration());
            } catch (PublicationUncertainException exception) {
                String detail = truncate(Redactor.redact(exception.getMessage()), 2000);
                try {
                    lease.check();
                    store.stopUncertainPublication(job, detail);
                    LOG.error("Publication paused for reconciliation: {} error={}", job.payload().reviewRunId(), detail);
                } catch (LeaseLostException lost) {
                    LOG.warn("Uncertain publication belongs to a superseded job owner: {}", job.id());
                } catch (RuntimeException persistenceFailure) {
                    LOG.error("Could not persist publication pause: {}", Redactor.redact(persistenceFailure.getMessage()));
                }
            } catch (RuntimeException exception) {
                String detail = truncate(Redactor.redact(exception.getMessage()), 2000);
                try {
                    lease.check();
                    if (job.attempts() >= 2) engine.fail(job.payload(), detail, lease);
                    boolean terminal = store.retryJob(job, detail);
                    LOG.error("Review run failed: {} terminal={} error={}", job.payload().reviewRunId(), terminal, detail);
                } catch (LeaseLostException lost) {
                    LOG.warn("Failure belongs to a superseded job owner: {}", job.id());
                } catch (PublicationUncertainException uncertain) {
                    try {
                        lease.check();
                        store.stopUncertainPublication(job, truncate(Redactor.redact(uncertain.getMessage()), 2000));
                    } catch (RuntimeException persistenceFailure) {
                        LOG.error("Could not persist uncertain terminal publication: {}", Redactor.redact(persistenceFailure.getMessage()));
                    }
                } catch (RuntimeException persistenceFailure) {
                    LOG.error("Could not record review failure: {}", Redactor.redact(persistenceFailure.getMessage()));
                }
            }
        } finally { slots.release(); }
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
        try { if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
        finally { heartbeats.shutdownNow(); }
    }
    private static String truncate(String value, int max) { value = value == null ? "unknown error" : value; return value.substring(0, Math.min(max, value.length())); }
}
