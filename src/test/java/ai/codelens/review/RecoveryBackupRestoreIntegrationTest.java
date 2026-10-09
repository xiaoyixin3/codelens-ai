package ai.codelens.review;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.github.PublicationUncertainException;
import ai.codelens.migration.MigrationRunner;
import ai.codelens.migration.MigrationSchemaVerifier;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.PublicationRecoveryAuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static ai.codelens.store.PublicationRecoveryAuditStore.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Creates its own labelled disposable database. Never accepts a live container or database target. */
@EnabledIfEnvironmentVariable(named="CODELENS_INTEGRATION_TESTS",matches="true")
@EnabledIfEnvironmentVariable(named="CODELENS_BACKUP_TESTS",matches="true")
class RecoveryBackupRestoreIntegrationTest {
    @Test void pointInTimeBackupCanLoseLaterApprovalConsumptionSoRestoreRequiresFreshApprovalKey() throws Exception {
        try (var db=new DisposableDatabase(); var source=fixture(db)) {
            var json=new ObjectMapper();
            var binding=source.store.recoveryBinding(source.id,9,11,false);
            var issued=source.service.prepare(source.id,source.principal);
            db.backupAndRestore(); // Snapshot deliberately precedes durable approval consumption.
            var originalIssuer=new RecoveryApprovalService(source.operators,key(2),json);
            originalIssuer.reserveAttempt(originalIssuer.verify(source.principal,binding,issued.bearer()),source.audit);
            assertEquals(PENDING_INVESTIGATION,source.audit.investigate(issued.attemptId(),source.principal.actorHash()).outcome());
            try (var restoredPool=db.pool("restored_fixture")) {
                var jdbc=new JdbcTemplate(restoredPool); var store=new JdbcStore(jdbc,restoredPool,json);
                var audit=new PublicationRecoveryAuditStore(jdbc,restoredPool);
                var restoredBinding=store.recoveryBinding(source.id,9,11,false);
                assertEquals(binding,restoredBinding);
                assertEquals(NOT_FOUND,audit.investigate(issued.attemptId(),source.principal.actorHash()).outcome());
                var staleIssuer=new RecoveryApprovalService(source.operators,key(2),json);
                var staleReceipt=staleIssuer.verify(source.principal,restoredBinding,issued.bearer());
                // Counterexample: DB uniqueness alone cannot remember a consumption newer than its backup.
                assertDoesNotThrow(() -> staleIssuer.reserveAttempt(staleReceipt,audit));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM publication_recovery_audit",Integer.class));
                var freshIssuer=new RecoveryApprovalService(source.operators,key(4),json);
                assertThrows(SecurityException.class,() -> freshIssuer.verify(source.principal,restoredBinding,issued.bearer()));
                var fresh=freshIssuer.issue(source.principal,restoredBinding);
                assertNotNull(freshIssuer.verify(source.principal,restoredBinding,fresh.bearer()));
                assertFalse(fresh.attemptId().equals(issued.attemptId()));
                assertEquals("failed",store.getReviewRun(source.id).status());
                assertNull(store.getPublication(source.id).orElseThrow().summaryCommentId());
                // Publication key remains the original external key, independent of approval-key rotation.
                assertNotNull(FrozenReviewOutput.open(store.getFrozenReviewOutput(source.id).orElseThrow(),
                        source.job,source.repository,false,key(3),json));
            }
            source.assertPaused(); source.assertReadsOnly();
        }
    }

    @Test void pgDumpRestorePreservesCiphertextAuditAndBurnedApprovalsWhileKeysRemainExternal() throws Exception {
        try (var db=new DisposableDatabase();
             var pending=fixture(db);
             var completed=fixture(db);
             var denied=fixture(db)) {
            var json=new ObjectMapper();
            var approvals=new RecoveryApprovalService(pending.operators,key(2),json);
            var binding=pending.store.recoveryBinding(pending.id,9,11,false);
            var consumed=pending.service.prepare(pending.id,pending.principal);
            var unused=pending.service.prepare(pending.id,pending.principal);
            approvals.reserveAttempt(approvals.verify(pending.principal,binding,consumed.bearer()),pending.audit);
            var finished=completed.service.prepare(completed.id,completed.principal);
            completed.service.recover(completed.id,completed.principal,finished.bearer());
            var deniedApproval=denied.service.prepare(denied.id,denied.principal);
            var deniedIssuer=new RecoveryApprovalService(denied.operators,key(2),json);
            var deniedBinding=denied.store.recoveryBinding(denied.id,9,11,false);
            var deniedAttempt=deniedIssuer.reserveAttempt(deniedIssuer.verify(denied.principal,deniedBinding,deniedApproval.bearer()),denied.audit);
            denied.audit.denied(deniedAttempt,PublicationRecoveryAuditStore.Denial.STATE_CHANGED);

            String before=fingerprint(pending.jdbc);
            db.backupAndRestore(); // Actual pg_dump -Fc + pg_restore, no JDBC row-copy replacement.
            try (var restoredPool=db.pool("restored_fixture")) {
                var restoredJdbc=new JdbcTemplate(restoredPool);
                var restoredStore=new JdbcStore(restoredJdbc,restoredPool,json);
                var restoredAudit=new PublicationRecoveryAuditStore(restoredJdbc,restoredPool);
                assertEquals(before,fingerprint(restoredJdbc));
                assertEquals(before,fingerprint(pending.jdbc));
                var schemaConfig=mock(RuntimeConfig.class); when(schemaConfig.migrationsDir()).thenReturn("infra/migrations");
                assertTrue(new MigrationSchemaVerifier(restoredJdbc,schemaConfig).status().ready());
                assertEquals(22,restoredJdbc.queryForObject("SELECT count(*) FROM schema_migrations",Integer.class));

                var stored=restoredStore.getFrozenReviewOutput(pending.id).orElseThrow();
                assertEquals(pending.store.getFrozenReviewOutput(pending.id).orElseThrow(),stored);
                var original=FrozenReviewOutput.open(stored,pending.job,pending.repository,false,key(3),json);
                assertEquals("original",original.markdown()); assertEquals("{\"original\":42}",original.summaryJson());
                for (String unavailable:new String[]{"",key(4)}) {
                    var rejected=assertThrows(PublicationUncertainException.class,() -> FrozenReviewOutput.open(
                            stored,pending.job,pending.repository,false,unavailable,json));
                    assertNull(rejected.getCause()); assertFalse(rejected.getMessage().contains(stored.encryptedPayload()));
                }
                var restoreBinding=restoredStore.recoveryBinding(pending.id,9,11,false);
                assertEquals(binding,restoreBinding);
                var restoredApprovals=new RecoveryApprovalService(pending.operators,key(2),json);
                var verified=restoredApprovals.verify(pending.principal,restoreBinding,consumed.bearer());
                assertThrows(org.springframework.dao.DataAccessException.class,() -> restoredApprovals.reserveAttempt(verified,restoredAudit));
                assertNotNull(restoredApprovals.verify(pending.principal,restoreBinding,unused.bearer()));
                var wrongApprovalKey=new RecoveryApprovalService(pending.operators,key(4),json);
                assertThrows(SecurityException.class,() -> wrongApprovalKey.verify(pending.principal,restoreBinding,unused.bearer()));
                var expired=new RecoveryApprovalService(pending.operators,key(2),json,
                        Clock.fixed(Instant.ofEpochMilli(unused.expiresAtMillis()),ZoneOffset.UTC));
                assertThrows(SecurityException.class,() -> expired.verify(pending.principal,restoreBinding,unused.bearer()));

                assertEquals(PENDING_INVESTIGATION,restoredAudit.investigate(consumed.attemptId(),pending.principal.actorHash()).outcome());
                assertEquals(COMMITTED_LOCAL_STRUCTURE_MATCHES,restoredAudit.investigate(finished.attemptId(),completed.principal.actorHash()).outcome());
                assertEquals(DENIED,restoredAudit.investigate(deniedApproval.attemptId(),denied.principal.actorHash()).outcome());
                assertEquals("state_changed",restoredAudit.investigate(deniedApproval.attemptId(),denied.principal.actorHash()).reason());
                assertFalse(restoredAudit.investigate(consumed.attemptId(),pending.principal.actorHash()).approvalReusable());
                assertThrows(org.springframework.dao.DataAccessException.class,() -> restoredJdbc.update(
                        "UPDATE publication_recovery_audit SET reason='remote_unverified' WHERE attempt_id=? AND stage='denied'",deniedApproval.attemptId()));
                assertThrows(org.springframework.dao.DataAccessException.class,() -> restoredJdbc.update(
                        "UPDATE frozen_review_outputs SET payload_hash=? WHERE review_run_id=?::uuid","a".repeat(64),pending.id));

                // No key or approval bearer is recovered from the database: supply them separately from test memory.
                String rows=databaseRows(restoredJdbc);
                for (String secret:List.of(key(1),key(2),key(3),consumed.bearer(),unused.bearer(),finished.bearer(),deniedApproval.bearer())) {
                    assertFalse(rows.contains(secret));
                }
                assertEquals(before,fingerprint(restoredJdbc));
                assertEquals(before,fingerprint(pending.jdbc));
            }
            pending.assertPaused(); pending.assertReadsOnly(); completed.assertReadsOnly(); denied.assertReadsOnly();
        }
    }
    private static String key(int value) { return PublicationRecoveryIntegrationTest.key(value); }
    private static PublicationRecoveryIntegrationTest.Fixture fixture(DisposableDatabase db) {
        return new PublicationRecoveryIntegrationTest.Fixture(java.util.function.Function.identity(),settings -> db.configure(settings,"codelens"));
    }
    private static String databaseRows(JdbcTemplate jdbc) {
        StringBuilder rows=new StringBuilder();
        for (String table:jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class)) {
            if (!table.matches("[a-z_]+")) throw new IllegalStateException("Unexpected fixture table");
            rows.append(table).append('\n').append(jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM \""+table+"\" t",String.class)).append('\n');
        }
        return rows.toString(); // In-memory only; never printed or included in assertion messages.
    }
    private static String fingerprint(JdbcTemplate jdbc) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(databaseRows(jdbc).getBytes(StandardCharsets.UTF_8)));
    }

    private static final class DisposableDatabase implements AutoCloseable {
        final String name="codelens-backup-fixture-"+UUID.randomUUID();
        final String password=UUID.randomUUID().toString();
        String id; int port;
        DisposableDatabase() throws Exception {
            try {
                id=run(List.of("docker","run","--detach","--rm","--name",name,"--label","ai.codelens.test-kind=recovery-backup",
                        "-e","POSTGRES_USER=codelens","-e","POSTGRES_PASSWORD="+password,"-e","POSTGRES_DB=codelens",
                        "-p","127.0.0.1::5432","postgres:17-alpine"));
                if (!id.matches("[0-9a-f]{64}")) throw new IllegalStateException("Unexpected disposable container identity");
                String binding=run(List.of("docker","port",id,"5432/tcp"));
                if (!binding.matches("127\\.0\\.0\\.1:[0-9]+")) throw new IllegalStateException("Unexpected disposable port");
                port=Integer.parseInt(binding.substring(binding.lastIndexOf(':')+1));
                boolean ready=false;
                for (int attempt=0;attempt<20;attempt++) {
                    // The entrypoint's temporary initialization server only exposes a Unix socket.
                    // Require the permanent TCP server before connecting through the published host port.
                    try { run(List.of("docker","exec",id,"pg_isready","-h","127.0.0.1","-U","codelens")); ready=true; break; }
                    catch (IllegalStateException unavailable) { Thread.sleep(200); }
                }
                if (!ready) throw new IllegalStateException("Disposable database unavailable");
                try (var pool=pool("codelens")) {
                    var migrationConfig=mock(RuntimeConfig.class); when(migrationConfig.migrationsDir()).thenReturn("infra/migrations");
                    var context=new org.springframework.context.support.GenericApplicationContext(); context.refresh();
                    try { new MigrationRunner(new JdbcTemplate(pool),migrationConfig,context)
                            .run(new org.springframework.boot.DefaultApplicationArguments()); }
                    finally { context.close(); }
                }
            } catch (Exception failed) { close(); throw failed; }
        }
        void configure(HikariConfig settings,String database) {
            if (!List.of("codelens","restored_fixture").contains(database)) throw new IllegalArgumentException("Unknown test database");
            settings.setJdbcUrl("jdbc:postgresql://127.0.0.1:"+port+"/"+database);
            settings.setUsername("codelens"); settings.setPassword(password); settings.setMaximumPoolSize(3);
        }
        HikariDataSource pool(String database) { var settings=new HikariConfig(); configure(settings,database); return new HikariDataSource(settings); }
        void backupAndRestore() throws Exception {
            run(List.of("docker","exec",id,"pg_dump","-U","codelens","-d","codelens","-Fc","-f","/tmp/recovery-fixture.dump"));
            run(List.of("docker","exec",id,"createdb","-U","codelens","restored_fixture"));
            run(List.of("docker","exec",id,"pg_restore","-U","codelens","-d","restored_fixture","--exit-on-error","--no-owner","/tmp/recovery-fixture.dump"));
        }
        public void close() throws Exception {
            if (id==null) return;
            String identity=run(List.of("docker","inspect","--format","{{.Id}} {{.Name}}",id));
            String labels=run(List.of("docker","inspect","--format","{{json .Config.Labels}}",id));
            if (!identity.equals(id+" /"+name) || !"recovery-backup".equals(new ObjectMapper().readTree(labels).path("ai.codelens.test-kind").asText())) {
                throw new IllegalStateException("Refusing unexpected cleanup target");
            }
            run(List.of("docker","stop",id)); id=null;
        }
    }
    private static String run(List<String> args) throws Exception {
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        var reader=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var output=reader.submit(() -> process.getInputStream().readNBytes(1_048_577));
            if (!process.waitFor(30,TimeUnit.SECONDS)) throw new IllegalStateException("Isolated backup operation timed out");
            byte[] bytes=output.get(5,TimeUnit.SECONDS);
            if (bytes.length>1_048_576 || process.exitValue()!=0) throw new IllegalStateException("Isolated backup operation failed");
            return new String(bytes,StandardCharsets.UTF_8).trim(); // Never echo tool output, commands, credentials or rows.
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); }
            reader.shutdownNow();
        }
    }
}
