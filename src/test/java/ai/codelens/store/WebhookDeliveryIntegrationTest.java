package ai.codelens.store;

import ai.codelens.config.DotEnv;
import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CODELENS_INTEGRATION_TESTS", matches = "true")
class WebhookDeliveryIntegrationTest {
    @Test void failureAfterEnqueueRollsBackEverythingAndCanBeRedelivered() {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> fixture.store.processDelivery(fixture.input, () -> {
                fixture.queue(fixture.store);
                throw new IllegalStateException("failure between enqueue and receipt commit");
            }));
            assertEquals(0, fixture.runCount());
            assertEquals(0, fixture.receiptCount());
            assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM github_repositories WHERE id=?", Integer.class, fixture.repository));
            fixture.store.recordDeliveryFailure(fixture.input, "bounded failure");
            assertEquals("failed", fixture.receiptStatus());
            JdbcStore.ProcessedDelivery retried = fixture.store.processDelivery(fixture.input, () -> fixture.queue(fixture.store));
            assertFalse(retried.duplicate());
            assertEquals(1, fixture.runCount());
            assertEquals(1, fixture.jobCount());
            assertEquals("processed", fixture.receiptStatus());
            fixture.store.recordDeliveryFailure(fixture.input, "late failure from another request");
            assertEquals("processed", fixture.receiptStatus(), "a late error audit must not downgrade committed success");
            assertNull(fixture.jdbc.queryForObject("SELECT error_detail FROM webhook_deliveries WHERE delivery_id=?", String.class, fixture.input.id()));
        }
    }

    @Test void failureSavingTheFinalOutcomeRollsBackTheAlreadyEnqueuedJob() {
        try (Fixture fixture = new Fixture()) {
            assertThrows(DataAccessException.class, () -> fixture.store.processDelivery(fixture.input, () -> {
                fixture.queue(fixture.store);
                return new JdbcStore.DeliveryResult("queued", UUID.randomUUID().toString()); // missing FK target
            }));
            assertEquals(0, fixture.runCount()); assertEquals(0, fixture.receiptCount());
            assertFalse(fixture.store.processDelivery(fixture.input, () -> fixture.queue(fixture.store)).duplicate());
            assertEquals(1, fixture.jobCount());
        }
    }

    @Test void lostHttpAcknowledgementReturnsTheCommittedRunWithoutRepeatingWork() {
        try (Fixture fixture = new Fixture()) {
            JdbcStore.ProcessedDelivery committed = fixture.store.processDelivery(fixture.input, () -> fixture.queue(fixture.store));
            JdbcStore.ProcessedDelivery retry = fixture.second.processDelivery(fixture.input,
                    () -> { throw new AssertionError("duplicate must not execute the handler"); });
            assertTrue(retry.duplicate());
            assertEquals(committed.result(), retry.result());
            assertEquals(1, fixture.runCount()); assertEquals(1, fixture.jobCount());
        }
    }

    @Test void concurrentRedeliveriesExecuteTheHandlerOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var workers = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            try {
                var left = workers.submit(() -> {
                    ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                    return fixture.store.processDelivery(fixture.input, () -> { calls.incrementAndGet(); return fixture.queue(fixture.store); });
                });
                var right = workers.submit(() -> {
                    ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                    return fixture.second.processDelivery(fixture.input, () -> { calls.incrementAndGet(); return fixture.queue(fixture.second); });
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
                JdbcStore.ProcessedDelivery a = left.get(10, TimeUnit.SECONDS), b = right.get(10, TimeUnit.SECONDS);
                assertNotEquals(a.duplicate(), b.duplicate());
                assertEquals(a.result().runId(), b.result().runId());
                assertEquals(1, calls.get()); assertEquals(1, fixture.jobCount());
            } finally { start.countDown(); workers.shutdownNow(); }
        }
    }

    @Test void historicalReceivedAndFailedRowsCanBeRetriedButProcessedRowsCannot() {
        for (String state : new String[]{"received", "failed"}) {
            try (Fixture fixture = new Fixture()) {
                fixture.store.recordDeliveryFailure(fixture.input, "historical failure");
                fixture.jdbc.update("UPDATE webhook_deliveries SET status=? WHERE delivery_id=?", state, fixture.input.id());
                assertFalse(fixture.store.processDelivery(fixture.input, () -> fixture.queue(fixture.store)).duplicate());
                assertEquals(1, fixture.jobCount());
            }
        }
        try (Fixture fixture = new Fixture()) {
            fixture.store.recordDeliveryFailure(fixture.input, "legacy row");
            fixture.jdbc.update("UPDATE webhook_deliveries SET status='processed',result_status=NULL,review_run_id=NULL WHERE delivery_id=?", fixture.input.id());
            assertTrue(fixture.store.processDelivery(fixture.input, () -> { throw new AssertionError(); }).duplicate());
        }
    }

    @Test void deliveryIdentityConflictCannotMutateTheCommittedReceipt() {
        try (Fixture fixture = new Fixture()) {
            fixture.store.processDelivery(fixture.input, () -> fixture.queue(fixture.store));
            for (JdbcStore.DeliveryInput forged : new JdbcStore.DeliveryInput[]{
                    new JdbcStore.DeliveryInput(fixture.input.id(), "check_run", fixture.input.action(), fixture.input.body()),
                    new JdbcStore.DeliveryInput(fixture.input.id(), fixture.input.event(), "synchronize", fixture.input.body()),
                    new JdbcStore.DeliveryInput(fixture.input.id(), fixture.input.event(), fixture.input.action(), "different".getBytes(StandardCharsets.UTF_8))}) {
                assertThrows(DeliveryConflictException.class, () -> fixture.store.processDelivery(forged, () -> { throw new AssertionError(); }));
                fixture.store.recordDeliveryFailure(forged, "different identity");
            }
            assertEquals("processed", fixture.receiptStatus()); assertEquals(1, fixture.jobCount());
        }
    }

    @Test void rerunPublicationSeedIsAtomicAndNeverOverwritesTheWorkerResult() {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> fixture.store.processDelivery(fixture.input, () -> {
                JdbcStore.DeliveryResult result = fixture.queue(fixture.store);
                fixture.store.seedPublication(new Models.Publication(result.runId(), "head1234", 9L, null));
                throw new IllegalStateException("receipt failed after rerun seed");
            }));
            assertEquals(0, fixture.runCount());
            String runId = fixture.store.processDelivery(fixture.input, () -> {
                JdbcStore.DeliveryResult result = fixture.queue(fixture.store);
                fixture.store.seedPublication(new Models.Publication(result.runId(), "head1234", 9L, null));
                return result;
            }).result().runId();
            assertEquals(9L, fixture.store.getPublication(runId).orElseThrow().checkRunId());
            fixture.store.savePublication(new Models.Publication(runId, "head1234", 10L, 11L));
            fixture.store.seedPublication(new Models.Publication(runId, "head1234", 9L, null));
            assertEquals(10L, fixture.store.getPublication(runId).orElseThrow().checkRunId());
            assertEquals(11L, fixture.store.getPublication(runId).orElseThrow().summaryCommentId());
        }
    }

    @Test void deletingRetainedReviewDataDoesNotReplayItsProcessedDelivery() {
        try (Fixture fixture = new Fixture()) {
            fixture.store.processDelivery(fixture.input, () -> fixture.queue(fixture.store));
            fixture.jdbc.update("DELETE FROM review_runs WHERE github_repository_id=?", fixture.repository);
            JdbcStore.ProcessedDelivery duplicate = fixture.store.processDelivery(fixture.input,
                    () -> { throw new AssertionError("retention must not cause implicit re-review"); });
            assertTrue(duplicate.duplicate()); assertNull(duplicate.result().runId());
            assertEquals("processed", fixture.receiptStatus()); assertEquals(0, fixture.runCount());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HikariDataSource pool; final JdbcTemplate jdbc; final JdbcStore store; final JdbcStore second;
        final long installation = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        final long repository = UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE;
        final JdbcStore.DeliveryInput input = new JdbcStore.DeliveryInput("delivery-test-" + UUID.randomUUID(),
                "pull_request", "opened", "{\"fixture\":true}".getBytes(StandardCharsets.UTF_8));
        Fixture() {
            DotEnv.load(".env"); RuntimeConfig config = RuntimeConfig.fromEnvironment();
            HikariConfig settings = new HikariConfig(); settings.setJdbcUrl(config.jdbcUrl());
            settings.setUsername(config.databaseUser()); settings.setPassword(config.databasePassword());
            pool = new HikariDataSource(settings); jdbc = new JdbcTemplate(pool);
            store = new JdbcStore(jdbc, pool, new ObjectMapper()); second = new JdbcStore(jdbc, pool, new ObjectMapper());
        }
        JdbcStore.DeliveryResult queue(JdbcStore selected) {
            JdbcStore.CreatedRun created = selected.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(repository, 7,
                    "base1234", "head1234", Models.PIPELINE_VERSION, "delivery-test", "webhook", "automatic"),
                    new Models.ReviewJob("pending", installation, "delivery-test", "fixture", 7, "base1234", "head1234"));
            return new JdbcStore.DeliveryResult(created.created() ? "queued" : "already_queued", created.run().id());
        }
        int runCount() { return jdbc.queryForObject("SELECT count(*) FROM review_runs WHERE github_repository_id=?", Integer.class, repository); }
        int jobCount() { return jdbc.queryForObject("SELECT count(*) FROM review_jobs j JOIN review_runs r ON r.id=j.review_run_id WHERE r.github_repository_id=?", Integer.class, repository); }
        int receiptCount() { return jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE delivery_id=?", Integer.class, input.id()); }
        String receiptStatus() { return jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE delivery_id=?", String.class, input.id()); }
        @Override public void close() {
            try {
                jdbc.update("DELETE FROM webhook_deliveries WHERE delivery_id=?", input.id());
                jdbc.update("DELETE FROM review_runs WHERE github_repository_id=?", repository);
                jdbc.update("DELETE FROM github_repositories WHERE id=?", repository);
                jdbc.update("DELETE FROM github_installations WHERE id=?", installation);
            } finally { pool.close(); }
        }
    }
}
