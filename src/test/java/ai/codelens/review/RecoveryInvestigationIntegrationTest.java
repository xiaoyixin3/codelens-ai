package ai.codelens.review;

import ai.codelens.store.PublicationRecoveryAuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ai.codelens.store.PublicationRecoveryAuditStore.Outcome.*;

@EnabledIfEnvironmentVariable(named="CODELENS_INTEGRATION_TESTS", matches="true")
class RecoveryInvestigationIntegrationTest {
    @Test void pendingInvestigationNeverInfersFailureFromMissingTerminalEvent() throws Exception {
        try (var f=new PublicationRecoveryIntegrationTest.Fixture()) {
            UUID id=UUID.randomUUID();
            f.audit.requested(new PublicationRecoveryAuditStore.Attempt(id,UUID.fromString(f.id),f.principal.actorHash(),"b".repeat(64)));
            clearInvocations(f.github);
            var report=f.service.investigate(id,f.principal);
            assertEquals(PENDING_INVESTIGATION,report.outcome());
            assertFalse(report.automaticRecoveryAllowed()); assertFalse(report.approvalReusable());
            assertNull(report.terminalAt()); assertNotNull(report.requestedAt());
            String encoded=f.json.writeValueAsString(report);
            assertFalse(encoded.contains(f.principal.actorHash())); assertFalse(encoded.contains("b".repeat(64)));
            assertFalse(encoded.contains("original")); assertFalse(encoded.contains("ciphertext"));
            f.assertPaused(); verifyNoInteractions(f.github);
            assertEquals(1,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid",Integer.class));
        }
    }

    @Test void deniedAndAbsentAttemptsHaveExplicitReadOnlyOutcomes() {
        try (var f=new PublicationRecoveryIntegrationTest.Fixture()) {
            UUID id=UUID.randomUUID();
            var attempt=new PublicationRecoveryAuditStore.Attempt(id,UUID.fromString(f.id),f.principal.actorHash(),"b".repeat(64));
            f.audit.requested(attempt); f.audit.denied(attempt,PublicationRecoveryAuditStore.Denial.STATE_CHANGED);
            clearInvocations(f.github);
            var report=f.service.investigate(id,f.principal);
            assertEquals(DENIED,report.outcome()); assertEquals("state_changed",report.reason()); assertNotNull(report.terminalAt());
            assertEquals(NOT_FOUND,f.service.investigate(UUID.randomUUID(),f.principal).outcome());
            assertFalse(report.approvalReusable()); f.assertPaused(); verifyNoInteractions(f.github);
        }
    }

    @Test void foreignPrincipalCannotReadAndAnotherActorAttemptIsIndistinguishableFromMissing() {
        try (var f=new PublicationRecoveryIntegrationTest.Fixture()) {
            UUID id=UUID.randomUUID();
            f.audit.requested(new PublicationRecoveryAuditStore.Attempt(id,UUID.fromString(f.id),"c".repeat(64),"b".repeat(64)));
            assertEquals(NOT_FOUND,f.service.investigate(id,f.principal).outcome());
            var foreign=new RecoveryOperatorAuthenticator("foreign",PublicationRecoveryIntegrationTest.key(1))
                    .authenticate(PublicationRecoveryIntegrationTest.key(1));
            clearInvocations(f.audit,f.github);
            assertThrows(SecurityException.class, () -> f.service.investigate(id,foreign));
            verifyNoInteractions(f.audit,f.github);
        }
    }

    @Test void committedAuditIsDistinguishedFromCurrentLocalStateDriftAndRetentionDeletion() {
        try (var f=new PublicationRecoveryIntegrationTest.Fixture()) {
            var approval=f.service.prepare(f.id,f.principal); f.service.recover(f.id,f.principal,approval.bearer());
            UUID id=attemptId(f);
            assertEquals(COMMITTED_LOCAL_STRUCTURE_MATCHES,f.service.investigate(id,f.principal).outcome());
            f.jdbc.update("UPDATE publications SET check_run_id=10 WHERE review_run_id=?::uuid",f.id);
            clearInvocations(f.github);
            var drift=f.service.investigate(id,f.principal);
            assertEquals(COMMITTED_LOCAL_STRUCTURE_CHANGED,drift.outcome());
            assertFalse(drift.approvalReusable()); assertNotNull(drift.terminalAt());
            assertEquals(10L,f.store.getPublication(f.id).orElseThrow().checkRunId());
            f.jdbc.update("DELETE FROM review_runs WHERE id=?::uuid",f.id);
            assertEquals(NOT_FOUND,f.service.investigate(id,f.principal).outcome()); verifyNoInteractions(f.github);
        }
    }

    @Test void actualPostgresCommitWithLostCallerAcknowledgementIsNotReportedAsDeniedOrRetried() {
        var fault=new CommitFault();
        try (var f=new PublicationRecoveryIntegrationTest.Fixture(fault::decorate)) {
            var approval=f.service.prepare(f.id,f.principal);
            armAfterReservation(f,fault,Mode.AFTER_COMMIT);
            assertThrows(org.springframework.transaction.TransactionSystemException.class,
                    () -> f.service.recover(f.id,f.principal,approval.bearer()));
            assertEquals(Mode.NONE,fault.mode.get());
            assertEquals("completed",f.store.getReviewRun(f.id).status());
            assertEquals(1,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='committed'",Integer.class));
            assertEquals(0,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='denied'",Integer.class));
            UUID id=attemptId(f); clearInvocations(f.github);
            assertEquals(approval.attemptId(),id);
            var report=f.service.investigate(id,f.principal);
            assertEquals(COMMITTED_LOCAL_STRUCTURE_MATCHES,report.outcome()); verifyNoInteractions(f.github);
            assertFalse(report.automaticRecoveryAllowed()); assertFalse(report.approvalReusable());
            assertThrows(IllegalStateException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertReadsOnly();
        }
    }

    @Test void uncommittedConnectionFailureLeavesPendingAndBusinessStateRolledBack() {
        var fault=new CommitFault();
        try (var f=new PublicationRecoveryIntegrationTest.Fixture(fault::decorate)) {
            var approval=f.service.prepare(f.id,f.principal);
            armAfterReservation(f,fault,Mode.BEFORE_COMMIT);
            assertThrows(org.springframework.transaction.TransactionSystemException.class,
                    () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused();
            assertEquals(1L,f.scalar("SELECT lease_generation FROM review_jobs WHERE review_run_id=?::uuid",Long.class));
            assertEquals(1,f.scalar("SELECT count(*) FROM summary_publication_slots WHERE active_run_id=?::uuid",Integer.class));
            assertEquals(1,f.scalar("SELECT count(*) FROM check_publication_effects WHERE review_run_id=?::uuid AND state='confirmed'",Integer.class));
            assertNull(f.store.getPublication(f.id).orElseThrow().summaryCommentId());
            UUID id=attemptId(f); clearInvocations(f.github);
            assertEquals(PENDING_INVESTIGATION,f.service.investigate(id,f.principal).outcome()); verifyNoInteractions(f.github);
            assertThrows(org.springframework.dao.DataAccessException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertReadsOnly();
        }
    }

    @Test void investigationWhileSuccessTransactionIsUncommittedSeesPendingWithoutTakingWriteLocks() throws Exception {
        try (var f=new PublicationRecoveryIntegrationTest.Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            var appended=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            doAnswer(invocation -> {
                invocation.callRealMethod(); appended.countDown();
                assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS)); return null;
            }).when(f.audit).committedWithinTransaction(any());
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var recovery=executor.submit(() -> f.service.recover(f.id,f.principal,approval.bearer()));
                assertTrue(appended.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(PENDING_INVESTIGATION,f.service.investigate(approval.attemptId(),f.principal).outcome());
                release.countDown(); recovery.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(COMMITTED_LOCAL_STRUCTURE_MATCHES,f.service.investigate(approval.attemptId(),f.principal).outcome());
                f.assertReadsOnly();
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }

    private static UUID attemptId(PublicationRecoveryIntegrationTest.Fixture f) {
        return UUID.fromString(f.scalar("SELECT attempt_id::text FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='requested'",String.class));
    }
    private static void armAfterReservation(PublicationRecoveryIntegrationTest.Fixture f,CommitFault fault,Mode mode) {
        var body=f.github.getSummaryComment(f.job,11,()->{});
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(f.github.getSummaryComment(eq(f.job),eq(11L),any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet()==2) fault.mode.set(mode);
            return body;
        });
    }
    private enum Mode { NONE, BEFORE_COMMIT, AFTER_COMMIT }
    /** Test-only JDBC boundary fault; real SQL/transactions, no production fault flag or network proxy. */
    private static final class CommitFault {
        final AtomicReference<Mode> mode=new AtomicReference<>(Mode.NONE);
        DataSource decorate(DataSource source) {
            return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args) -> {
                try {
                    Object result=method.invoke(source,args);
                    if (!method.getName().equals("getConnection")) return result;
                    Connection connection=(Connection)result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a) -> {
                        if (m.getName().equals("commit")) {
                            Mode selected=mode.getAndSet(Mode.NONE);
                            if (selected==Mode.BEFORE_COMMIT) { connection.rollback(); throw new SQLException("fixture acknowledgement unavailable","08006"); }
                            if (selected==Mode.AFTER_COMMIT) { connection.commit(); throw new SQLException("fixture acknowledgement unavailable","08006"); }
                        }
                        try { return m.invoke(connection,a); } catch (InvocationTargetException failed) { throw failed.getCause(); }
                    });
                } catch (InvocationTargetException failed) { throw failed.getCause(); }
            });
        }
    }
}
