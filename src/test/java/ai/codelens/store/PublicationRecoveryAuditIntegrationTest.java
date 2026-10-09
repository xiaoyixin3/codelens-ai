package ai.codelens.store;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.review.RecoveryApprovalService;
import ai.codelens.review.RecoveryOperatorAuthenticator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CODELENS_INTEGRATION_TESTS", matches="true")
class PublicationRecoveryAuditIntegrationTest {
    @Test void approvalReservationIsDurableAndCannotBeRepeatedAfterBusinessRollback() {
        try (Fixture f = new Fixture()) {
            var operators = new RecoveryOperatorAuthenticator("fixture", testKey(1));
            var principal = operators.authenticate(testKey(1));
            var approvals = new RecoveryApprovalService(operators, testKey(2), new ObjectMapper());
            var binding = f.binding(); var issued = approvals.issue(principal, binding);
            var receipt = approvals.verify(principal, binding, issued.bearer());
            var attempt = approvals.reserveAttempt(receipt, f.audit);
            f.transactions.executeWithoutResult(status -> {
                f.store.updateReviewRun(f.runId, "in_progress", null, "", ""); status.setRollbackOnly();
            });
            assertThrows(DataAccessException.class, () -> approvals.reserveAttempt(receipt, f.audit));
            assertEquals("queued", f.store.getReviewRun(f.runId).status());
            assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM publication_recovery_audit WHERE attempt_id=?", Integer.class, attempt.id()));
        }
    }

    @Test void concurrentlyReservedApprovalHasExactlyOneSuccessfulReservation() throws Exception {
        try (Fixture f = new Fixture()) {
            var operators = new RecoveryOperatorAuthenticator("fixture", testKey(1));
            var principal = operators.authenticate(testKey(1));
            var approvals = new RecoveryApprovalService(operators, testKey(2), new ObjectMapper());
            var binding = f.binding(); var issued = approvals.issue(principal, binding);
            var receipt = approvals.verify(principal, binding, issued.bearer());
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                java.util.concurrent.Callable<Boolean> reserve = () -> {
                    assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    try { approvals.reserveAttempt(receipt, f.audit); return true; } catch (DataAccessException duplicate) { return false; }
                };
                var first = executor.submit(reserve); var second = executor.submit(reserve); start.countDown();
                assertNotEquals(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid", Integer.class, f.runId));
            } finally { start.countDown(); executor.shutdownNow(); }
        }
    }

    static String testKey(int value) { byte[] bytes = new byte[32]; bytes[0]=(byte)value; return java.util.Base64.getEncoder().encodeToString(bytes); }
    @Test void deniedAuditSurvivesBusinessRollback() {
        try (Fixture f = new Fixture()) {
            f.audit.requested(f.attempt);
            f.transactions.executeWithoutResult(status -> {
                f.store.updateReviewRun(f.runId, "in_progress", null, "", "");
                f.audit.denied(f.attempt, PublicationRecoveryAuditStore.Denial.STATE_CHANGED);
                status.setRollbackOnly();
            });
            assertEquals("queued", f.store.getReviewRun(f.runId).status());
            assertEquals(2, f.count());
            assertEquals("state_changed", f.jdbc.queryForObject("SELECT reason FROM publication_recovery_audit WHERE attempt_id=? AND stage='denied'", String.class, f.attempt.id()));
        }
    }

    @Test void duplicateAttemptsAndTerminalEventsAreRejected() {
        try (Fixture f = new Fixture()) {
            f.audit.requested(f.attempt);
            assertThrows(DataAccessException.class, () -> f.audit.requested(f.attempt));
            f.audit.denied(f.attempt, PublicationRecoveryAuditStore.Denial.NOT_AUTHORIZED);
            assertThrows(DataAccessException.class, () -> f.audit.denied(f.attempt, PublicationRecoveryAuditStore.Denial.STATE_CHANGED));
            assertThrows(DataAccessException.class, () -> f.transactions.executeWithoutResult(status -> {
                f.completeLocalFixture(); f.audit.committedWithinTransaction(f.attempt);
            }));
            assertEquals("queued", f.store.getReviewRun(f.runId).status());
            assertEquals(2, f.count());
        }
    }

    @Test void successAuditAndBusinessStateRollBackTogether() {
        try (Fixture f = new Fixture()) {
            f.audit.requested(f.attempt);
            assertThrows(IllegalStateException.class, () -> f.transactions.executeWithoutResult(status -> {
                f.completeLocalFixture(); f.audit.committedWithinTransaction(f.attempt);
                throw new IllegalStateException("simulated crash before commit");
            }));
            assertEquals(1, f.count());
            assertEquals("queued", f.store.getReviewRun(f.runId).status());
            assertEquals("queued", f.jdbc.queryForObject("SELECT status FROM review_jobs WHERE review_run_id=?::uuid", String.class, f.runId));
        }
    }

    @Test void auditAppendFailureRollsBackTheLocalConfirmation() {
        try (Fixture f = new Fixture()) {
            f.audit.requested(f.attempt);
            var changed = new PublicationRecoveryAuditStore.Attempt(f.attempt.id(), f.attempt.runId(), f.attempt.actorHash(), "c".repeat(64));
            assertThrows(IllegalStateException.class, () -> f.transactions.executeWithoutResult(status -> {
                f.completeLocalFixture(); f.audit.committedWithinTransaction(changed);
            }));
            assertEquals(1, f.count());
            assertEquals("queued", f.store.getReviewRun(f.runId).status());
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM check_publication_effects WHERE review_run_id=?::uuid", Integer.class, f.runId));
        }
    }

    @Test void concurrentRequestsWithTheSameAttemptHaveOnlyOneWinner() throws Exception {
        try (Fixture f = new Fixture()) {
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                java.util.concurrent.Callable<Boolean> request = () -> {
                    assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    try { f.audit.requested(f.attempt); return true; } catch (DataAccessException duplicate) { return false; }
                };
                var first = executor.submit(request); var second = executor.submit(request); start.countDown();
                assertNotEquals(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, f.count());
            } finally { start.countDown(); executor.shutdownNow(); }
        }
    }

    @Test void missingRequestOrConflictingMetadataCannotBeAppended() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.audit.denied(f.attempt, PublicationRecoveryAuditStore.Denial.NOT_AUTHORIZED));
            assertThrows(DataAccessException.class, () -> f.jdbc.update("INSERT INTO publication_recovery_audit(attempt_id,review_run_id,stage,actor_hash,evidence_hash,reason) VALUES(?,?,'committed',?,?,'none')",
                f.attempt.id(), f.attempt.runId(), f.attempt.actorHash(), f.attempt.evidenceHash()));
            f.audit.requested(f.attempt);
            var changed = new PublicationRecoveryAuditStore.Attempt(f.attempt.id(), f.attempt.runId(), "c".repeat(64), f.attempt.evidenceHash());
            assertThrows(IllegalStateException.class, () -> f.audit.denied(changed, PublicationRecoveryAuditStore.Denial.STATE_CHANGED));
            assertEquals(1, f.count());
        }
    }

    @Test void conflictingPublicationPointersCannotProduceSuccessfulAudit() {
        try (Fixture f = new Fixture()) {
            f.audit.requested(f.attempt);
            assertThrows(IllegalStateException.class, () -> f.transactions.executeWithoutResult(status -> {
                f.completeLocalFixture();
                f.jdbc.update("UPDATE publications SET check_run_id=10 WHERE review_run_id=?::uuid", f.runId);
                f.audit.committedWithinTransaction(f.attempt);
            }));
            assertEquals(1, f.count());
            assertEquals("queued", f.store.getReviewRun(f.runId).status());
        }
    }

    @Test void successRequiresBusinessTransactionAndFullyConfirmedLocalState() {
        try (Fixture f = new Fixture()) {
            f.audit.requested(f.attempt);
            assertThrows(IllegalStateException.class, () -> f.audit.committedWithinTransaction(f.attempt));
            assertThrows(IllegalStateException.class, () -> f.transactions.executeWithoutResult(status -> f.audit.committedWithinTransaction(f.attempt)));
            f.transactions.executeWithoutResult(status -> {
                f.completeLocalFixture(); f.audit.committedWithinTransaction(f.attempt);
            });
            assertEquals(2, f.count());
            assertEquals("completed", f.store.getReviewRun(f.runId).status());
        }
    }

    @Test void updateIsRejectedAndReviewDeletionCascadesAuditMetadata() {
        try (Fixture f = new Fixture()) {
            f.audit.requested(f.attempt);
            f.audit.denied(f.attempt, PublicationRecoveryAuditStore.Denial.REMOTE_UNVERIFIED);
            assertThrows(DataAccessException.class, () -> f.jdbc.update("UPDATE publication_recovery_audit SET reason='state_changed' WHERE attempt_id=? AND stage='denied'", f.attempt.id()));
            f.jdbc.update("DELETE FROM review_runs WHERE id=?::uuid", f.runId);
            assertEquals(0, f.count());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HikariDataSource pool;
        final JdbcTemplate jdbc;
        final JdbcStore store;
        final TransactionTemplate transactions;
        final PublicationRecoveryAuditStore audit;
        final long repository = UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE;
        final String runId;
        final String base = "b".repeat(40), head = "a".repeat(40);
        final PublicationRecoveryAuditStore.Attempt attempt;
        Fixture() {
            RuntimeConfig config = RuntimeConfig.fromEnvironment();
            HikariConfig settings = new HikariConfig(); settings.setJdbcUrl(config.jdbcUrl());
            settings.setUsername(config.databaseUser()); settings.setPassword(config.databasePassword());
            settings.setMaximumPoolSize(3);
            pool = new HikariDataSource(settings); jdbc = new JdbcTemplate(pool);
            store = new JdbcStore(jdbc, pool, new ObjectMapper());
            transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
            audit = new PublicationRecoveryAuditStore(jdbc, pool);
            runId = store.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(repository, 7, base, head,
                Models.PIPELINE_VERSION, "audit-fixture", "webhook", "audit-fixture"),
                new Models.ReviewJob("pending", 1, "audit-test", "fixture", 7, base, head)).run().id();
            attempt = new PublicationRecoveryAuditStore.Attempt(UUID.randomUUID(), UUID.fromString(runId), "a".repeat(64), "b".repeat(64));
        }
        void completeLocalFixture() {
            store.updateReviewRun(runId, "completed", "{}", "", "");
            store.savePublication(new Models.Publication(runId, head, 9L, 9L));
            jdbc.update("UPDATE review_jobs SET status='completed',locked_at=NULL,lease_expires_at=NULL WHERE review_run_id=?::uuid", runId);
            for (String operation : new String[]{"check_start","check_result","summary_comment"}) {
                jdbc.update("INSERT INTO check_publication_effects(review_run_id,operation,request_hash,state,remote_id) VALUES(?::uuid,? ,?,'confirmed',9)", runId, operation, "a".repeat(64));
            }
        }
        RecoveryApprovalService.Binding binding() {
            UUID job = UUID.fromString(jdbc.queryForObject("SELECT id::text FROM review_jobs WHERE review_run_id=?::uuid", String.class, runId));
            return new RecoveryApprovalService.Binding(UUID.fromString(runId), job, 1, repository, 7, "audit-test", "fixture", base, head,
                Models.PIPELINE_VERSION, "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), 9, 11, 1, UUID.fromString(runId), null, null);
        }
        int count() { return jdbc.queryForObject("SELECT count(*) FROM publication_recovery_audit WHERE attempt_id=?", Integer.class, attempt.id()); }
        public void close() {
            try { jdbc.update("DELETE FROM review_runs WHERE github_repository_id=?", repository); }
            finally { pool.close(); }
        }
    }
}
