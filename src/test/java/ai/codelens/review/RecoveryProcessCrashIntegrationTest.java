package ai.codelens.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static ai.codelens.store.PublicationRecoveryAuditStore.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Kills only the exact child started by this test; never targets a worker PID or process name. */
@EnabledIfEnvironmentVariable(named="CODELENS_INTEGRATION_TESTS",matches="true")
@EnabledIfEnvironmentVariable(named="CODELENS_CRASH_TESTS",matches="true")
class RecoveryProcessCrashIntegrationTest {
    @Test void processKilledAfterReservationLeavesBurnedApprovalAndPausedState() throws Exception {
        crashAt(RecoveryCrashChild.Point.AFTER_RESERVATION);
    }
    @Test void processKilledBeforeCommitRollsBackBusinessAndSuccessfulAuditAndReleasesLocks() throws Exception {
        crashAt(RecoveryCrashChild.Point.BEFORE_COMMIT);
    }
    @Test void processKilledAfterCommitPreservesSuccessDespiteNoResponseToCaller() throws Exception {
        crashAt(RecoveryCrashChild.Point.AFTER_COMMIT);
    }
    private static void crashAt(RecoveryCrashChild.Point point) throws Exception {
        try (var f=new PublicationRecoveryIntegrationTest.Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            String javaName=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
            var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",javaName).toString(),
                    "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                    RecoveryCrashChild.class.getName(),point.name(),f.id);
            command.environment().put("CODELENS_CRASH_FIXTURE_RUN",f.id);
            command.redirectErrorStream(true);
            Process child=command.start();
            var reader=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                try (var input=child.getOutputStream()) { input.write(approval.bearer().getBytes(StandardCharsets.UTF_8)); }
                var checkpoint=reader.submit(() -> {
                    try (var output=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))) {
                        String line; int bytes=0;
                        while ((line=output.readLine())!=null) {
                            bytes+=line.length(); if (bytes>32_768) throw new AssertionError("Unexpected child output volume");
                            if (line.equals("RECOVERY_CRASH_POINT:"+point.name())) return true;
                        }
                        return false;
                    }
                });
                assertTrue(checkpoint.get(25,TimeUnit.SECONDS),"Child exited before crash checkpoint");
                assertTrue(child.isAlive()); child.destroyForcibly(); assertTrue(child.waitFor(10,TimeUnit.SECONDS));
                assertNotEquals(0,child.exitValue());
                // A fresh connection/transaction must acquire the formerly held PR lock after process death.
                f.store.withReviewPrLock(f.repository,7,() -> null);
                var investigation=f.service.investigate(approval.attemptId(),f.principal);
                assertFalse(investigation.automaticRecoveryAllowed()); assertFalse(investigation.approvalReusable());
                assertEquals(1,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='requested'",Integer.class));
                assertEquals(0,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='denied'",Integer.class));
                if (point==RecoveryCrashChild.Point.AFTER_COMMIT) {
                    assertEquals(COMMITTED_LOCAL_STRUCTURE_MATCHES,investigation.outcome());
                    assertEquals("completed",f.store.getReviewRun(f.id).status());
                    assertEquals("completed",f.scalar("SELECT status FROM review_jobs WHERE review_run_id=?::uuid",String.class));
                    assertEquals(2L,f.scalar("SELECT lease_generation FROM review_jobs WHERE review_run_id=?::uuid",Long.class));
                    assertEquals(3,f.scalar("SELECT count(*) FROM check_publication_effects WHERE review_run_id=?::uuid AND state='confirmed'",Integer.class));
                    assertEquals(0,f.scalar("SELECT count(*) FROM summary_publication_slots WHERE active_run_id=?::uuid",Integer.class));
                    assertEquals(1,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='committed'",Integer.class));
                    assertEquals(11L,f.store.getPublication(f.id).orElseThrow().summaryCommentId());
                    assertThrows(IllegalStateException.class,() -> f.service.recover(f.id,f.principal,approval.bearer()));
                } else {
                    assertEquals(PENDING_INVESTIGATION,investigation.outcome()); f.assertPaused();
                    assertEquals(1L,f.scalar("SELECT lease_generation FROM review_jobs WHERE review_run_id=?::uuid",Long.class));
                    assertEquals(1,f.scalar("SELECT count(*) FROM check_publication_effects WHERE review_run_id=?::uuid AND state='confirmed'",Integer.class));
                    assertEquals(1,f.scalar("SELECT count(*) FROM summary_publication_slots WHERE active_run_id=?::uuid",Integer.class));
                    assertEquals(0,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='committed'",Integer.class));
                    assertNull(f.store.getPublication(f.id).orElseThrow().summaryCommentId());
                    assertThrows(org.springframework.dao.DataAccessException.class,() -> f.service.recover(f.id,f.principal,approval.bearer()));
                }
                f.assertReadsOnly();
            } finally {
                if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(10,TimeUnit.SECONDS)); }
                reader.shutdownNow();
            }
        }
    }
}
