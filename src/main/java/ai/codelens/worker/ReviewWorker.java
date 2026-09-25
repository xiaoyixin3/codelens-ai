package ai.codelens.worker;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.review.ReviewEngine;
import ai.codelens.security.Redactor;
import ai.codelens.store.JdbcStore;
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
import java.util.concurrent.TimeUnit;

@Component
@Profile("worker")
public class ReviewWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ReviewWorker.class);
    private final JdbcStore store; private final ReviewEngine engine;
    private final ExecutorService executor; private final Semaphore slots;

    public ReviewWorker(JdbcStore store, ReviewEngine engine, RuntimeConfig config) {
        this.store = store; this.engine = engine;
        this.executor = Executors.newFixedThreadPool(config.workerConcurrency());
        this.slots = new Semaphore(config.workerConcurrency());
    }

    @PostConstruct
    void start() { store.recoverStaleJobs(); LOG.info("CodeLens Java worker started with {} slots", slots.availablePermits()); }

    @Scheduled(fixedDelayString = "${CODELENS_WORKER_POLL_MS:1000}")
    void poll() {
        while (slots.tryAcquire()) {
            var claimed = store.claimJob();
            if (claimed.isEmpty()) { slots.release(); break; }
            executor.submit(() -> process(claimed.get()));
        }
    }

    private void process(Models.ClaimedJob job) {
        try {
            engine.execute(job.payload());
            store.completeJob(job.id());
            LOG.info("Review run completed: {}", job.payload().reviewRunId());
        } catch (RuntimeException exception) {
            String detail = truncate(Redactor.redact(exception.getMessage()), 2000);
            try {
                boolean terminal = store.retryJob(job.id(), job.attempts(), detail);
                if (terminal) engine.fail(job.payload(), detail);
                LOG.error("Review run failed: {} terminal={} error={}", job.payload().reviewRunId(), terminal, detail);
            } catch (RuntimeException persistenceFailure) {
                LOG.error("Could not record review failure: {}", Redactor.redact(persistenceFailure.getMessage()));
            }
        } finally { slots.release(); }
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
        try { if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
    }
    private static String truncate(String value, int max) { value = value == null ? "unknown error" : value; return value.substring(0, Math.min(max, value.length())); }
}
