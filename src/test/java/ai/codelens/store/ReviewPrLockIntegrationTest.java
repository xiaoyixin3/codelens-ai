package ai.codelens.store;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CODELENS_INTEGRATION_TESTS", matches="true")
class ReviewPrLockIntegrationTest {
    @Test void matchingEnqueueWaitsUntilPrTransactionCommits() throws Exception {
        try (Fixture f = new Fixture()) {
            var executor = Executors.newFixedThreadPool(2);
            var held = new CountDownLatch(1); var release = new CountDownLatch(1);
            try {
                var owner = executor.submit(() -> f.store.withReviewPrLock(f.repository, 7, () -> {
                    held.countDown(); await(release); return true;
                }));
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var attempting = new CountDownLatch(1);
                var admission = executor.submit(() -> { attempting.countDown(); return f.enqueue("head1234"); });
                assertTrue(attempting.await(5, TimeUnit.SECONDS));
                boolean waiting = false;
                for (int attempt=0; attempt<20; attempt++) {
                    if (f.jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid::bigint=? AND NOT granted",
                            Integer.class, 0x434c5052) > 0) { waiting=true; break; }
                    Thread.sleep(25);
                }
                assertTrue(waiting, "Enqueue must actually be waiting for the PR mutex, not just a delayed thread");
                assertThrows(TimeoutException.class, () -> admission.get(150, TimeUnit.MILLISECONDS));
                assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM review_runs WHERE github_repository_id=?", Integer.class, f.repository));
                release.countDown(); assertTrue(owner.get(5, TimeUnit.SECONDS));
                assertTrue(admission.get(5, TimeUnit.SECONDS).created());
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }

    @Test void differentPrMutexCanProceedWhileAnotherIsHeld() throws Exception {
        try (Fixture f = new Fixture()) {
            var executor = Executors.newFixedThreadPool(2);
            var held = new CountDownLatch(1); var release = new CountDownLatch(1);
            try {
                var owner = executor.submit(() -> f.store.withReviewPrLock(f.repository, 7, () -> {
                    held.countDown(); await(release); return true;
                }));
                assertTrue(held.await(5, TimeUnit.SECONDS));
                assertTrue(executor.submit(() -> f.second.withReviewPrLock(f.repository, 8, () -> true)).get(2, TimeUnit.SECONDS));
                release.countDown(); assertTrue(owner.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }

    @Test void newestRunIsRecheckedUnderTheActualMatchingMutex() {
        try (Fixture f = new Fixture()) {
            var old = f.enqueue("head1234").run();
            assertThrows(IllegalStateException.class, () -> f.store.requireLatestReviewRunLocked(old.id(), f.repository, 7));
            assertThrows(IllegalStateException.class, () -> f.store.withReviewPrLock(f.repository, 8, () -> {
                f.store.requireLatestReviewRunLocked(old.id(), f.repository, 7); return null;
            }));
            var newest = f.enqueue("head5678").run();
            f.store.withReviewPrLock(f.repository, 7, () -> {
                assertThrows(IllegalStateException.class, () -> f.store.requireLatestReviewRunLocked(old.id(), f.repository, 7));
                f.store.requireLatestReviewRunLocked(newest.id(), f.repository, 7); return null;
            });
        }
    }

    @Test void rollbackReleasesMutexAndUndoesNestedAdmission() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.store.withReviewPrLock(f.repository, 7, () -> {
                f.enqueue("head1234"); throw new IllegalStateException("rollback");
            }));
            assertTrue(f.second.withReviewPrLock(f.repository, 7, () -> f.enqueue("head5678").created()));
            assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM review_runs WHERE github_repository_id=?", Integer.class, f.repository));
        }
    }

    @Test void transactionStartedEarlierStillAdmitsItsRunAfterTheCommittedNewRun() throws Exception {
        try (Fixture f = new Fixture()) {
            var executor = Executors.newSingleThreadExecutor();
            var started = new CountDownLatch(1); var admit = new CountDownLatch(1);
            try {
                var late = executor.submit(() -> f.transactions.execute(status -> {
                    f.jdbc.queryForObject("SELECT transaction_timestamp()::text", String.class);
                    started.countDown(); await(admit); return f.enqueue("head5678").run();
                }));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var early = f.second.createOrGetAndEnqueue(f.input("head1234"), f.job("head1234")).run();
                admit.countDown(); var newest = late.get(5, TimeUnit.SECONDS);
                f.store.withReviewPrLock(f.repository, 7, () -> {
                    f.store.requireLatestReviewRunLocked(newest.id(), f.repository, 7);
                    assertThrows(IllegalStateException.class, () -> f.store.requireLatestReviewRunLocked(early.id(), f.repository, 7));
                    return null;
                });
            } finally { admit.countDown(); executor.shutdownNow(); }
        }
    }

    @Test void staleSnapshotIsolationAndInvalidScopesAreRejectedBeforeAdmission() {
        try (Fixture f = new Fixture()) {
            var repeatable = new TransactionTemplate(new DataSourceTransactionManager(f.pool));
            repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThrows(IllegalStateException.class, () -> repeatable.execute(status -> f.enqueue("head1234")));
            assertThrows(IllegalArgumentException.class, () -> f.store.withReviewPrLock(0, 7, () -> true));
            assertThrows(IllegalArgumentException.class, () -> f.store.withReviewPrLock(f.repository, 0, () -> true));
        }
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted fixture"); }
    }
    private static final class Fixture implements AutoCloseable {
        final HikariDataSource pool; final JdbcTemplate jdbc; final JdbcStore store; final JdbcStore second;
        final TransactionTemplate transactions;
        final long repository = UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE;
        final long installation = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        Fixture() {
            RuntimeConfig config = RuntimeConfig.fromEnvironment();
            HikariConfig settings = new HikariConfig(); settings.setJdbcUrl(config.jdbcUrl());
            settings.setUsername(config.databaseUser()); settings.setPassword(config.databasePassword()); settings.setMaximumPoolSize(4);
            pool = new HikariDataSource(settings); jdbc = new JdbcTemplate(pool);
            store = new JdbcStore(jdbc, pool, new ObjectMapper()); second = new JdbcStore(jdbc, pool, new ObjectMapper());
            transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        }
        JdbcStore.CreateReviewInput input(String head) { return new JdbcStore.CreateReviewInput(repository, 7, "base1234", head, Models.PIPELINE_VERSION, "pr-lock", "webhook", "automatic"); }
        Models.ReviewJob job(String head) { return new Models.ReviewJob("pending", installation, "pr-lock", "fixture", 7, "base1234", head); }
        JdbcStore.CreatedRun enqueue(String head) { return store.createOrGetAndEnqueue(input(head), job(head)); }
        public void close() {
            try {
                jdbc.update("DELETE FROM review_runs WHERE github_repository_id=?", repository);
                jdbc.update("DELETE FROM github_repositories WHERE id=?", repository);
                jdbc.update("DELETE FROM github_installations WHERE id=?", installation);
            } finally { pool.close(); }
        }
    }
}
