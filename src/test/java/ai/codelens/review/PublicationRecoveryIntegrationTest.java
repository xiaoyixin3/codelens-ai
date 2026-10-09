package ai.codelens.review;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.PublicationRecoveryAuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="CODELENS_INTEGRATION_TESTS", matches="true")
class PublicationRecoveryIntegrationTest {
    @Test void confirmsOnlyExistingRemoteEffectsAndNeverResendsOrRequeues() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id, f.principal);
            f.assertPaused();
            f.service.recover(f.id, f.principal, approval.bearer());
            assertEquals("completed", f.store.getReviewRun(f.id).status());
            assertEquals(42, new ObjectMapper().readTree(f.store.getReviewRun(f.id).summary()).path("original").asInt());
            assertEquals("completed", f.scalar("SELECT status FROM review_jobs WHERE review_run_id=?::uuid", String.class));
            assertEquals(2L, f.scalar("SELECT lease_generation FROM review_jobs WHERE review_run_id=?::uuid", Long.class));
            assertEquals(3, f.scalar("SELECT count(*) FROM check_publication_effects WHERE review_run_id=?::uuid AND state='confirmed'", Integer.class));
            assertEquals(0, f.scalar("SELECT count(*) FROM summary_publication_slots WHERE active_run_id=?::uuid", Integer.class));
            assertEquals(1, f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='committed'", Integer.class));
            assertEquals(11L, f.store.getPublication(f.id).orElseThrow().summaryCommentId());
            assertThrows(IllegalStateException.class, () -> f.service.recover(f.id, f.principal, approval.bearer()));
            f.assertReadsOnly();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new AssertionError(e); }
    }

    @Test void changedRevisionAfterReservationIsDeniedWithoutLocalChanges() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id, f.principal);
            when(f.github.currentRevision(1,"recovery-test","fixture",7)).thenReturn(
                    new Models.PullRequestRevision(f.base,f.head), new Models.PullRequestRevision("c".repeat(40),f.head));
            assertThrows(IllegalStateException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused(); f.assertDenied(); f.assertReadsOnly();
        }
    }

    @Test void modifiedRemoteBodyAfterReservationIsDeniedWithoutLocalChanges() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            var original=f.github.getSummaryComment(f.job,11,()->{});
            when(f.github.getSummaryComment(eq(f.job),eq(11L),any())).thenReturn(original,
                    f.json.createObjectNode().put("body","modified"));
            assertThrows(IllegalStateException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused(); f.assertDenied(); f.assertReadsOnly();
        }
    }

    @Test void changedLeaseGenerationInvalidatesApprovalBeforeReservation() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            f.jdbc.update("UPDATE review_jobs SET lease_generation=2 WHERE review_run_id=?::uuid",f.id);
            assertThrows(SecurityException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            assertEquals(0, f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid",Integer.class));
            f.assertPaused(); f.assertReadsOnly();
        }
    }

    @Test void successfulAuditFailureRollsBackEveryBusinessWriteAndApprovalIsBurned() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            doThrow(new IllegalStateException("fixture audit failure")).when(f.audit).committedWithinTransaction(any());
            assertThrows(IllegalStateException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused(); f.assertDenied();
            assertEquals(1L,f.scalar("SELECT lease_generation FROM review_jobs WHERE review_run_id=?::uuid",Long.class));
            assertEquals(1,f.scalar("SELECT count(*) FROM summary_publication_slots WHERE active_run_id=?::uuid",Integer.class));
            assertEquals(1,f.scalar("SELECT count(*) FROM check_publication_effects WHERE review_run_id=?::uuid AND state='confirmed'",Integer.class));
            assertNull(f.store.getPublication(f.id).orElseThrow().summaryCommentId());
            assertThrows(org.springframework.dao.DataAccessException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertReadsOnly();
        }
    }

    @Test void unownedPrincipalIsRejectedBeforeAnyRemoteRead() {
        try (Fixture f=new Fixture()) {
            clearInvocations(f.github);
            var foreign=new RecoveryOperatorAuthenticator("foreign",key(1)).authenticate(key(1));
            assertThrows(SecurityException.class, () -> f.service.prepare(f.id,foreign));
            assertThrows(SecurityException.class, () -> f.service.recover(f.id,foreign,"invalid"));
            verifyNoInteractions(f.github); f.assertPaused();
        }
    }

    @Test void rowChangeBetweenInspectionAndCommitIsRejectedUnderMutex() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            // Hook the last remote read of recovery's second inspection, before the local lock.
            var original=f.github.getSummaryComment(f.job,11,()->{});
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            when(f.github.getSummaryComment(eq(f.job),eq(11L),any())).thenAnswer(invocation -> {
                if (calls.incrementAndGet()==2) f.jdbc.update("UPDATE review_jobs SET lease_generation=2 WHERE review_run_id=?::uuid",f.id);
                return original;
            });
            assertThrows(SecurityException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused(); f.assertDenied(); f.assertReadsOnly();
        }
    }

    @Test void localConfirmationPrimitiveRequiresMatchingPrMutex() {
        try (Fixture f=new Fixture()) {
            var binding=f.store.recoveryBinding(f.id,9,11,false);
            assertThrows(IllegalStateException.class, () -> f.store.recoveryBinding(f.id,9,11,true));
            assertThrows(IllegalStateException.class, () -> f.store.withReviewPrLock(f.repository,8,
                    () -> f.store.recoveryBinding(f.id,9,11,true)));
            assertNotNull(binding); f.assertPaused();
        }
    }

    @Test void expiryAfterRemoteRecheckStillRejectsBeforeCommit() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            var original=f.github.getSummaryComment(f.job,11,()->{});
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            when(f.github.getSummaryComment(eq(f.job),eq(11L),any())).thenAnswer(invocation -> {
                if (calls.incrementAndGet()==2) f.now.addAndGet(300_000);
                return original;
            });
            assertThrows(SecurityException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused(); f.assertDenied(); f.assertReadsOnly();
        }
    }

    @Test void newerRunAdmittedAfterRemoteReadBlocksOldLocalCommit() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            var original=f.github.getSummaryComment(f.job,11,()->{});
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            when(f.github.getSummaryComment(eq(f.job),eq(11L),any())).thenAnswer(invocation -> {
                if (calls.incrementAndGet()==2) f.store.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(f.repository,7,
                        f.base,"c".repeat(40),Models.PIPELINE_VERSION,"newer","webhook","newer"),
                        new Models.ReviewJob("pending",1,"recovery-test","fixture",7,f.base,"c".repeat(40)));
                return original;
            });
            assertThrows(IllegalStateException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused(); f.assertDenied(); f.assertReadsOnly();
        }
    }

    @Test void missingFrozenRecordAfterReservationCannotBeRegenerated() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            var original=f.github.getSummaryComment(f.job,11,()->{});
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            when(f.github.getSummaryComment(eq(f.job),eq(11L),any())).thenAnswer(invocation -> {
                if (calls.incrementAndGet()==2) f.jdbc.update("DELETE FROM frozen_review_outputs WHERE review_run_id=?::uuid",f.id);
                return original;
            });
            assertThrows(IllegalStateException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused(); f.assertDenied(); f.assertReadsOnly();
        }
    }

    @Test void twoDifferentApprovalsForSameRunCanCommitOnlyOnce() throws Exception {
        try (Fixture f=new Fixture()) {
            var first=f.service.prepare(f.id,f.principal); var second=f.service.prepare(f.id,f.principal);
            var original=f.github.getSummaryComment(f.job,11,()->{});
            var calls=ThreadLocal.withInitial(() -> 0);
            var barrier=new java.util.concurrent.CyclicBarrier(2);
            when(f.github.getSummaryComment(eq(f.job),eq(11L),any())).thenAnswer(invocation -> {
                calls.set(calls.get()+1);
                if (calls.get()==2) barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);
                return original;
            });
            var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.function.Function<String,Boolean> recover=bearer -> {
                    try { f.service.recover(f.id,f.principal,bearer); return true; }
                    catch (IllegalStateException changed) { return false; }
                };
                var one=executor.submit(() -> recover.apply(first.bearer()));
                var two=executor.submit(() -> recover.apply(second.bearer()));
                assertNotEquals(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='committed'",Integer.class));
                assertEquals(1,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='denied'",Integer.class));
                assertEquals(2L,f.scalar("SELECT lease_generation FROM review_jobs WHERE review_run_id=?::uuid",Long.class));
                f.assertReadsOnly();
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void databaseFailureLeavesRequestedForInvestigationInsteadOfInventingTerminalOutcome() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture connection failure"))
                    .when(f.audit).committedWithinTransaction(any());
            assertThrows(org.springframework.dao.DataAccessException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused();
            assertEquals(1,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid",Integer.class));
            assertEquals(1,f.scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='requested'",Integer.class));
            assertEquals(1L,f.scalar("SELECT lease_generation FROM review_jobs WHERE review_run_id=?::uuid",Long.class));
            assertNull(f.store.getPublication(f.id).orElseThrow().summaryCommentId());
            f.assertReadsOnly();
        }
    }

    @Test void summarySlotInstallationChangeAfterReservationIsDenied() {
        try (Fixture f=new Fixture()) {
            var approval=f.service.prepare(f.id,f.principal);
            var original=f.github.getSummaryComment(f.job,11,()->{});
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            when(f.github.getSummaryComment(eq(f.job),eq(11L),any())).thenAnswer(invocation -> {
                if (calls.incrementAndGet()==2) f.jdbc.update("UPDATE summary_publication_slots SET installation_id=2 WHERE active_run_id=?::uuid",f.id);
                return original;
            });
            assertThrows(IllegalStateException.class, () -> f.service.recover(f.id,f.principal,approval.bearer()));
            f.assertPaused(); f.assertDenied(); f.assertReadsOnly();
            assertEquals(1,f.scalar("SELECT count(*) FROM summary_publication_slots WHERE active_run_id=?::uuid",Integer.class));
        }
    }

    static String key(int value) { byte[] bytes=new byte[32]; bytes[0]=(byte)value; return java.util.Base64.getEncoder().encodeToString(bytes); }
    static final class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper();
        final HikariDataSource pool;
        final JdbcTemplate jdbc;
        final JdbcStore store;
        final GitHubClient github=mock(GitHubClient.class);
        final RuntimeConfig config=mock(RuntimeConfig.class);
        final PublicationRecoveryAuditStore audit;
        final RecoveryOperatorAuthenticator operators=new RecoveryOperatorAuthenticator("fixture",key(1));
        final RecoveryOperatorAuthenticator.Principal principal=operators.authenticate(key(1));
        final java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        final long repository=UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE;
        final String base="b".repeat(40),head="a".repeat(40),id;
        final Models.ReviewJob job;
        final PublicationRecoveryService service;
        Fixture() { this(java.util.function.Function.identity()); }
        Fixture(java.util.function.Function<javax.sql.DataSource,javax.sql.DataSource> decorate) {
            this(decorate,settings -> {});
        }
        Fixture(java.util.function.Function<javax.sql.DataSource,javax.sql.DataSource> decorate,
                java.util.function.Consumer<HikariConfig> configure) {
            var runtime=RuntimeConfig.fromEnvironment(); var settings=new HikariConfig();
            settings.setJdbcUrl(runtime.jdbcUrl()); settings.setUsername(runtime.databaseUser()); settings.setPassword(runtime.databasePassword());
            configure.accept(settings);
            settings.setMaximumPoolSize(3); pool=new HikariDataSource(settings);
            var source=decorate.apply(pool); jdbc=new JdbcTemplate(source);
            store=new JdbcStore(jdbc,source,json); audit=spy(new PublicationRecoveryAuditStore(jdbc,source));
            id=store.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(repository,7,base,head,Models.PIPELINE_VERSION,
                    "recovery-fixture","webhook","fixture"),new Models.ReviewJob("pending",1,"recovery-test","fixture",7,base,head)).run().id();
            job=new Models.ReviewJob(id,1,"recovery-test","fixture",7,base,head);
            store.updateReviewRun(id,"in_progress",null,"","");
            var output=new FrozenReviewOutput(1,job,repository,9,"neutral","title","original",List.of(),"{\"original\":42}");
            var sealed=output.seal(key(3),json); store.freezeReviewOutput(job,repository,sealed.hash(),sealed.ciphertext());
            var hashes=new CheckPublisher(store,github,json); String identity=hashes.identity(job);
            String start=hashes.fingerprint(List.of("check-start-v1",identity));
            String result=hashes.fingerprint(List.of("check-result-v1",identity,9L,"neutral","title","original",List.of()));
            String summary=hashes.fingerprint(List.of("summary-comment-v1",identity,"original"));
            store.beginCheckPublication(id,"check_start",start); store.confirmCheckPublication(id,"check_start",start,9);
            store.beginCheckPublication(id,"check_result",result); store.claimSummaryPublication(job);
            store.beginSummaryPublication(id,summary,null); store.seedPublication(new Models.Publication(id,head,9L,null));
            jdbc.update("UPDATE review_jobs SET status='failed',lease_generation=1 WHERE review_run_id=?::uuid",id);
            store.updateReviewRun(id,"failed",null,"PUBLICATION_UNCERTAIN","fixture");
            when(config.frozenPublicationsEnabled()).thenReturn(true); when(config.publicationKey()).thenReturn(key(3));
            when(github.currentRevision(1,"recovery-test","fixture",7)).thenReturn(new Models.PullRequestRevision(base,head));
            when(github.findReviewCheck(eq(job),eq(identity),any())).thenReturn(Optional.of(9L));
            when(github.getReviewCheck(job,9,identity)).thenReturn(json.createObjectNode().put("status","completed").put("conclusion","neutral")
                    .set("output",json.createObjectNode().put("title","title").put("annotations_count",0)
                            .put("summary","original\n\n<!-- "+identity+":result:"+result+" -->")));
            when(github.getReviewAnnotations(eq(job),eq(9L),any())).thenReturn(List.of());
            when(github.findSummaryComment(eq(job),isNull(),any())).thenReturn(Optional.of(11L));
            when(github.getSummaryComment(eq(job),eq(11L),any())).thenReturn(json.createObjectNode().put("body",
                    GitHubClient.SUMMARY_MARKER+"\n<!-- "+identity+":summary:"+summary+" -->\noriginal"));
            var clock=new java.time.Clock() {
                public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
                public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
                public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(now.get()); }
            };
            service=new PublicationRecoveryService(store,github,json,config,new RecoveryApprovalService(operators,key(2),json,clock),audit);
        }
        <T> T scalar(String sql,Class<T> type) { return jdbc.queryForObject(sql,type,id); }
        void assertPaused() {
            assertEquals("failed",store.getReviewRun(id).status());
            assertEquals("failed",scalar("SELECT status FROM review_jobs WHERE review_run_id=?::uuid",String.class));
        }
        void assertDenied() {
            assertEquals(1,scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='denied'",Integer.class));
            assertEquals(0,scalar("SELECT count(*) FROM publication_recovery_audit WHERE review_run_id=?::uuid AND stage='committed'",Integer.class));
        }
        void assertReadsOnly() {
            var allowed=java.util.Set.of("currentRevision","findReviewCheck","getReviewCheck","getReviewAnnotations","findSummaryComment","getSummaryComment");
            assertTrue(mockingDetails(github).getInvocations().stream().allMatch(i -> allowed.contains(i.getMethod().getName())));
        }
        public void close() { try { jdbc.update("DELETE FROM review_runs WHERE github_repository_id=?",repository); } finally { pool.close(); } }
    }
}
