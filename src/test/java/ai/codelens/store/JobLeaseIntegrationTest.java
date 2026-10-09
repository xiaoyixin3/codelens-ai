package ai.codelens.store;

import ai.codelens.config.DotEnv;
import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CODELENS_INTEGRATION_TESTS", matches = "true")
class JobLeaseIntegrationTest {
    @Test void frozenOutputIsFencedInsertOnlyAndRemovedWithItsRun() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob claimed = fixture.store.claimJob().orElseThrow();
            fixture.store.withJobLease(claimed, () -> {
                fixture.store.updateReviewRun(fixture.runId, "in_progress", null, "", "");
                fixture.store.freezeReviewOutput(claimed.payload(), fixture.repository, "a".repeat(64), "first-encrypted-output");
                fixture.store.freezeReviewOutput(claimed.payload(), fixture.repository, "a".repeat(64), "another-encryption-of-same-output");
            });
            assertEquals("first-encrypted-output", fixture.store.getFrozenReviewOutput(fixture.runId).orElseThrow().encryptedPayload());
            assertThrows(ai.codelens.github.PublicationUncertainException.class, () -> fixture.store.withJobLease(claimed,
                    () -> fixture.store.freezeReviewOutput(claimed.payload(), fixture.repository + 1, "a".repeat(64), "wrong-repository")));
            assertThrows(ai.codelens.github.PublicationUncertainException.class, () -> fixture.store.withJobLease(claimed,
                    () -> fixture.store.freezeReviewOutput(claimed.payload(), fixture.repository, "b".repeat(64), "changed-output")));
            assertThrows(org.springframework.dao.DataAccessException.class, () -> fixture.jdbc.update(
                    "UPDATE frozen_review_outputs SET payload_hash=? WHERE review_run_id=?::uuid", "b".repeat(64), fixture.runId));
            fixture.expire(claimed);
            assertThrows(LeaseLostException.class, () -> fixture.store.withJobLease(claimed,
                    () -> fixture.store.freezeReviewOutput(claimed.payload(), fixture.repository, "a".repeat(64), "old-owner")));
            fixture.jdbc.update("DELETE FROM review_runs WHERE id=?::uuid", fixture.runId);
            assertTrue(fixture.store.getFrozenReviewOutput(fixture.runId).isEmpty());
        }
    }

    @Test void lostLeaseRollsBackTheNewFrozenRecord() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob claimed = fixture.store.claimJob().orElseThrow();
            assertThrows(LeaseLostException.class, () -> fixture.store.withJobLease(claimed, () -> {
                fixture.store.updateReviewRun(fixture.runId, "in_progress", null, "", "");
                fixture.store.freezeReviewOutput(claimed.payload(), fixture.repository, "a".repeat(64), "encrypted-output");
                fixture.expire(claimed);
            }));
            assertTrue(fixture.store.getFrozenReviewOutput(fixture.runId).isEmpty());
        }
    }
    @Test void twoWorkersCannotOwnTheSameJobAndAReplacedOwnerCannotWrite() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var workers = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Models.ClaimedJob old;
            try {
                var left = workers.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return fixture.store.claimJob(); });
                var right = workers.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return fixture.second.claimJob(); });
                assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
                Optional<Models.ClaimedJob> a = left.get(5, TimeUnit.SECONDS);
                Optional<Models.ClaimedJob> b = right.get(5, TimeUnit.SECONDS);
                assertNotEquals(a.isPresent(), b.isPresent());
                old = a.orElseGet(b::orElseThrow);
            } finally { start.countDown(); workers.shutdownNow(); }
            assertTrue(fixture.store.hasJobLease(old));
            assertTrue(fixture.store.renewJobLease(old));
            fixture.expire(old);
            assertFalse(fixture.store.renewJobLease(old), "expired leases cannot revive before recovery");
            assertThrows(LeaseLostException.class, () -> fixture.store.completeJob(old));
            assertEquals(1, fixture.second.recoverStaleJobs());
            Models.ClaimedJob current = fixture.second.claimJob().orElseThrow();
            assertEquals(1, current.attempts());
            assertTrue(current.leaseGeneration() > old.leaseGeneration());
            assertFalse(fixture.store.renewJobLease(old));
            assertThrows(LeaseLostException.class, () -> fixture.store.completeJob(old));
            assertThrows(LeaseLostException.class, () -> fixture.store.retryJob(old, "old failure"));
            assertThrows(LeaseLostException.class, () -> fixture.store.withJobLease(old,
                    () -> fixture.store.updateReviewRun(fixture.runId, "completed", null, "", "")));
            assertEquals("queued", fixture.store.getReviewRun(fixture.runId).status());
            fixture.second.withJobLease(current, () -> fixture.second.updateReviewRun(fixture.runId, "completed", null, "", ""));
            fixture.second.completeJob(current);
            assertThrows(LeaseLostException.class, () -> fixture.second.completeJob(current));
            assertEquals("completed", fixture.jobStatus());
        }
    }

    @Test void fencedWritesRollBackIfOwnershipExpiresInsideTheLocalTransaction() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob job = fixture.store.claimJob().orElseThrow();
            assertThrows(LeaseLostException.class, () -> fixture.store.withJobLease(job, () -> {
                fixture.store.updateReviewRun(fixture.runId, "completed", null, "", "");
                fixture.expire(job);
            }));
            assertEquals("queued", fixture.store.getReviewRun(fixture.runId).status());
            assertTrue(fixture.store.hasJobLease(job), "expiry update must roll back with the run write");
            fixture.store.completeJob(job);
        }
    }

    @Test void repeatedCrashesConsumeTheBoundedAttemptBudget() {
        try (Fixture fixture = new Fixture()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                Models.ClaimedJob job = fixture.store.claimJob().orElseThrow();
                assertEquals(attempt, job.attempts());
                fixture.expire(job);
                assertEquals(1, fixture.store.recoverStaleJobs());
                assertEquals(attempt == 2 ? "failed" : "retry", fixture.jobStatus());
            }
            assertTrue(fixture.store.claimJob().isEmpty());
            assertEquals("failed", fixture.store.getReviewRun(fixture.runId).status());
            assertEquals("WORKER_LEASE_EXPIRED", fixture.jdbc.queryForObject(
                    "SELECT error_code FROM review_runs WHERE id=?::uuid", String.class, fixture.runId));
        }
    }

    @Test void ordinaryRetriesAlsoRequireTheCurrentGeneration() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob first = fixture.store.claimJob().orElseThrow();
            assertFalse(fixture.store.retryJob(first, "bounded failure"));
            assertThrows(LeaseLostException.class, () -> fixture.store.retryJob(first, "duplicate failure"));
            assertThrows(LeaseLostException.class, () -> fixture.store.completeJob(first));
            fixture.jdbc.update("UPDATE review_jobs SET available_at=now() WHERE review_run_id=?::uuid", fixture.runId);
            Models.ClaimedJob second = fixture.store.claimJob().orElseThrow();
            assertEquals(1, second.attempts());
            assertTrue(second.leaseGeneration() > first.leaseGeneration());
            fixture.store.completeJob(second);
        }
    }

    @Test void terminalRetryCommitsQueueAndRunFailureTogether() {
        try (Fixture fixture = new Fixture()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                Models.ClaimedJob job = fixture.store.claimJob().orElseThrow();
                assertEquals(attempt == 2, fixture.store.retryJob(job, "bounded failure"));
                fixture.jdbc.update("UPDATE review_jobs SET available_at=now() WHERE review_run_id=?::uuid", fixture.runId);
            }
            assertEquals("failed", fixture.jobStatus());
            assertEquals("failed", fixture.store.getReviewRun(fixture.runId).status());
            assertTrue(fixture.store.claimJob().isEmpty());
        }
    }

    @Test void uncertainPublicationStopsQueueAndRunTogetherWithoutConsumingRetries() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob job = fixture.store.claimJob().orElseThrow();
            fixture.store.stopUncertainPublication(job, "remote result unknown");
            assertEquals("failed", fixture.jobStatus());
            assertEquals("failed", fixture.store.getReviewRun(fixture.runId).status());
            assertEquals("PUBLICATION_UNCERTAIN", fixture.jdbc.queryForObject(
                    "SELECT error_code FROM review_runs WHERE id=?::uuid", String.class, fixture.runId));
            assertEquals(0, fixture.jdbc.queryForObject(
                    "SELECT attempts FROM review_jobs WHERE review_run_id=?::uuid", Integer.class, fixture.runId));
            assertTrue(fixture.store.claimJob().isEmpty());
            assertThrows(LeaseLostException.class, () -> fixture.second.stopUncertainPublication(job, "duplicate"));
        }
    }

    @Test void expiredOwnerCannotStopAnotherOwnersPublication() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob old = fixture.store.claimJob().orElseThrow();
            fixture.expire(old);
            assertThrows(LeaseLostException.class, () -> fixture.store.stopUncertainPublication(old, "late failure"));
            fixture.second.recoverStaleJobs();
            Models.ClaimedJob current = fixture.second.claimJob().orElseThrow();
            assertThrows(LeaseLostException.class, () -> fixture.store.stopUncertainPublication(old, "late failure"));
            assertEquals("processing", fixture.jobStatus());
            fixture.second.completeJob(current);
        }
    }

    @Test void checkIntentAndConfirmationAreDurableAndRejectConflicts() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob job = fixture.store.claimJob().orElseThrow();
            String hash = "a".repeat(64);
            fixture.store.withJobLease(job, () -> fixture.store.beginCheckPublication(fixture.runId, "check_start", hash));
            assertEquals("sent", fixture.second.getCheckPublicationEffect(fixture.runId, "check_start").orElseThrow().state());
            assertThrows(ai.codelens.github.PublicationUncertainException.class, () -> fixture.store.withJobLease(job,
                    () -> fixture.store.beginCheckPublication(fixture.runId, "check_start", hash)));
            fixture.store.withJobLease(job, () -> fixture.store.confirmCheckPublication(fixture.runId, "check_start", hash, 9));
            var effect = fixture.second.getCheckPublicationEffect(fixture.runId, "check_start").orElseThrow();
            assertEquals("confirmed", effect.state()); assertEquals(9L, effect.remoteId());
            assertThrows(ai.codelens.github.PublicationUncertainException.class, () -> fixture.store.withJobLease(job,
                    () -> fixture.store.confirmCheckPublication(fixture.runId, "check_start", "b".repeat(64), 9)));
            assertThrows(ai.codelens.github.PublicationUncertainException.class, () -> fixture.store.withJobLease(job,
                    () -> fixture.store.confirmCheckPublication(fixture.runId, "check_start", hash, 10)));
            assertEquals(9L, fixture.second.getCheckPublicationEffect(fixture.runId, "check_start").orElseThrow().remoteId());
            fixture.store.completeJob(job);
            fixture.jdbc.update("DELETE FROM review_runs WHERE id=?::uuid", fixture.runId);
            assertTrue(fixture.store.getCheckPublicationEffect(fixture.runId, "check_start").isEmpty());
        }
    }

    @Test void expiredOwnerCannotRecordPublicationIntentOrConfirmation() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob old = fixture.store.claimJob().orElseThrow();
            fixture.expire(old); fixture.second.recoverStaleJobs();
            Models.ClaimedJob current = fixture.second.claimJob().orElseThrow();
            assertThrows(LeaseLostException.class, () -> fixture.store.withJobLease(old,
                    () -> fixture.store.beginCheckPublication(fixture.runId, "check_start", "a".repeat(64))));
            assertTrue(fixture.store.getCheckPublicationEffect(fixture.runId, "check_start").isEmpty());
            fixture.second.withJobLease(current, () -> fixture.second.beginCheckPublication(fixture.runId, "check_start", "a".repeat(64)));
            assertThrows(LeaseLostException.class, () -> fixture.store.withJobLease(old,
                    () -> fixture.store.confirmCheckPublication(fixture.runId, "check_start", "a".repeat(64), 9)));
            assertEquals("sent", fixture.second.getCheckPublicationEffect(fixture.runId, "check_start").orElseThrow().state());
            fixture.second.completeJob(current);
        }
    }

    @Test void remoteCreationSurvivesOwnerLossAndIsRecoveredByTheNextWorker() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob old = fixture.store.claimJob().orElseThrow();
            var github = org.mockito.Mockito.mock(ai.codelens.github.GitHubClient.class);
            var visible = new java.util.concurrent.atomic.AtomicBoolean();
            org.mockito.Mockito.when(github.findReviewCheck(org.mockito.Mockito.eq(old.payload()), org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
                    .thenAnswer(call -> visible.get() ? Optional.of(9L) : Optional.empty());
            org.mockito.Mockito.when(github.startCheck(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                    org.mockito.Mockito.anyString(), org.mockito.Mockito.isNull(), org.mockito.Mockito.anyString())).thenAnswer(call -> {
                        visible.set(true); fixture.expire(old); return 9L;
                    });
            var first = new ai.codelens.review.CheckPublisher(fixture.store, github, new ObjectMapper());
            assertThrows(LeaseLostException.class, () -> first.start(old.payload(), null, fixture.guard(fixture.store, old)));
            assertEquals("sent", fixture.second.getCheckPublicationEffect(fixture.runId, "check_start").orElseThrow().state());
            assertEquals(1, fixture.second.recoverStaleJobs());
            Models.ClaimedJob current = fixture.second.claimJob().orElseThrow();
            var resumed = new ai.codelens.review.CheckPublisher(fixture.second, github, new ObjectMapper());
            assertEquals(9, resumed.start(current.payload(), null, fixture.guard(fixture.second, current)));
            assertEquals("confirmed", fixture.store.getCheckPublicationEffect(fixture.runId, "check_start").orElseThrow().state());
            org.mockito.Mockito.verify(github, org.mockito.Mockito.times(1)).startCheck(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString(),
                    org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(), org.mockito.Mockito.anyString());
            fixture.second.completeJob(current);
        }
    }

    @Test void remoteCompletionSurvivesOwnerLossWithoutAnotherAnnotationPatch() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob old = fixture.store.claimJob().orElseThrow();
            var mapper = new ObjectMapper();
            var observed = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>(
                    mapper.createObjectNode().put("status", "in_progress"));
            var github = org.mockito.Mockito.mock(ai.codelens.github.GitHubClient.class);
            org.mockito.Mockito.when(github.getReviewCheck(org.mockito.Mockito.eq(old.payload()), org.mockito.Mockito.eq(9L), org.mockito.Mockito.anyString()))
                    .thenAnswer(call -> observed.get());
            org.mockito.Mockito.doAnswer(call -> {
                observed.set(mapper.createObjectNode().put("status", "completed").put("conclusion", "success")
                        .set("output", mapper.createObjectNode().put("title", "title").put("summary", call.<String>getArgument(6))
                                .put("annotations_count", 0)));
                fixture.expire(old); return null;
            }).when(github).completeCheck(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                    org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyList());
            var first = new ai.codelens.review.CheckPublisher(fixture.store, github, mapper);
            assertThrows(LeaseLostException.class, () -> first.complete(old.payload(), 9, "success", "title", "summary", java.util.List.of(),
                    fixture.guard(fixture.store, old)));
            assertEquals("sent", fixture.second.getCheckPublicationEffect(fixture.runId, "check_result").orElseThrow().state());
            fixture.second.recoverStaleJobs();
            Models.ClaimedJob current = fixture.second.claimJob().orElseThrow();
            new ai.codelens.review.CheckPublisher(fixture.second, github, mapper).complete(current.payload(), 9, "success", "title", "summary",
                    java.util.List.of(), fixture.guard(fixture.second, current));
            assertEquals("confirmed", fixture.store.getCheckPublicationEffect(fixture.runId, "check_result").orElseThrow().state());
            org.mockito.Mockito.verify(github, org.mockito.Mockito.times(1)).completeCheck(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString(),
                    org.mockito.Mockito.anyString(), org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                    org.mockito.Mockito.anyString(), org.mockito.Mockito.anyList());
            fixture.second.completeJob(current);
        }
    }

    @Test void summaryWriteSurvivesOwnerReplacementWithoutAnotherComment() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob old = fixture.store.claimJob().orElseThrow();
            var body = new java.util.concurrent.atomic.AtomicReference<String>();
            var github = org.mockito.Mockito.mock(ai.codelens.github.GitHubClient.class);
            org.mockito.Mockito.when(github.findSummaryComment(org.mockito.Mockito.eq(old.payload()), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
                    .thenAnswer(call -> call.getArgument(1) != null && call.getArgument(1).equals(body.get()) ? Optional.of(11L) : Optional.empty());
            org.mockito.Mockito.when(github.writeSummaryComment(org.mockito.Mockito.eq(old.payload()), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
                    .thenAnswer(call -> { body.set(call.getArgument(1)); fixture.expire(old); return 11L; });
            org.mockito.Mockito.when(github.getSummaryComment(org.mockito.Mockito.eq(old.payload()), org.mockito.Mockito.eq(11L), org.mockito.Mockito.any()))
                    .thenAnswer(call -> new ObjectMapper().createObjectNode().put("body", body.get()));
            assertThrows(LeaseLostException.class, () -> new ai.codelens.review.CheckPublisher(fixture.store, github, new ObjectMapper())
                    .summary(old.payload(), "summary", null, fixture.guard(fixture.store, old)));
            assertEquals("sent", fixture.second.getCheckPublicationEffect(fixture.runId, "summary_comment").orElseThrow().state());
            assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM summary_publication_slots WHERE active_run_id=?::uuid", Integer.class, fixture.runId));
            fixture.second.recoverStaleJobs();
            Models.ClaimedJob current = fixture.second.claimJob().orElseThrow();
            assertEquals(11, new ai.codelens.review.CheckPublisher(fixture.second, github, new ObjectMapper())
                    .summary(current.payload(), "summary", null, fixture.guard(fixture.second, current)));
            assertEquals("confirmed", fixture.store.getCheckPublicationEffect(fixture.runId, "summary_comment").orElseThrow().state());
            assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM summary_publication_slots WHERE active_run_id=?::uuid", Integer.class, fixture.runId));
            org.mockito.Mockito.verify(github, org.mockito.Mockito.times(1)).writeSummaryComment(org.mockito.Mockito.any(), org.mockito.Mockito.anyString(),
                    org.mockito.Mockito.any(), org.mockito.Mockito.any());
            fixture.second.completeJob(current);
        }
    }

    @Test void samePrCannotHaveTwoPendingSummaryOwnersAndOlderRunsCannotOverwriteNewerOnes() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob old = fixture.store.claimJob().orElseThrow();
            fixture.store.withJobLease(old, () -> fixture.store.claimSummaryPublication(old.payload()));
            fixture.store.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(fixture.repository, 7,
                            "base1234", "head1234", Models.PIPELINE_VERSION, "lease-test", "webhook", "newer-summary"),
                    new Models.ReviewJob("pending", fixture.installation, "lease-test", "fixture", 7, "base1234", "head1234"));
            Models.ClaimedJob newer = fixture.second.claimJob().orElseThrow();
            assertThrows(ai.codelens.github.PublicationUncertainException.class, () -> fixture.second.withJobLease(newer,
                    () -> fixture.second.claimSummaryPublication(newer.payload())));
            assertThrows(ai.codelens.github.PublicationUncertainException.class, () -> fixture.store.withJobLease(old,
                    () -> fixture.store.claimSummaryPublication(old.payload())));
            fixture.store.withJobLease(old, () -> fixture.store.releaseSummaryPublication(fixture.runId));
            fixture.second.withJobLease(newer, () -> fixture.second.claimSummaryPublication(newer.payload()));
            assertEquals(newer.payload().reviewRunId(), fixture.jdbc.queryForObject(
                    "SELECT active_run_id::text FROM summary_publication_slots WHERE repository_id=?", String.class, fixture.repository));
            fixture.second.withJobLease(newer, () -> fixture.second.releaseSummaryPublication(newer.payload().reviewRunId()));
            fixture.store.completeJob(old); fixture.second.completeJob(newer);
        }
    }

    @Test void recordedSummaryTargetCannotBeChangedDuringConfirmation() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob job = fixture.store.claimJob().orElseThrow();
            String hash = "a".repeat(64);
            fixture.store.withJobLease(job, () -> fixture.store.beginSummaryPublication(fixture.runId, hash, 9L));
            assertThrows(ai.codelens.github.PublicationUncertainException.class, () -> fixture.store.withJobLease(job,
                    () -> fixture.store.confirmCheckPublication(fixture.runId, "summary_comment", hash, 10)));
            assertEquals("sent", fixture.store.getCheckPublicationEffect(fixture.runId, "summary_comment").orElseThrow().state());
            fixture.store.withJobLease(job, () -> fixture.store.confirmCheckPublication(fixture.runId, "summary_comment", hash, 9));
            fixture.store.completeJob(job);
        }
    }

    @Test void publicationInspectionReadsPendingOwnershipWithoutChangingQueueOrJournal() {
        try (Fixture fixture = new Fixture()) {
            Models.ClaimedJob job = fixture.store.claimJob().orElseThrow();
            fixture.store.withJobLease(job, () -> {
                fixture.store.claimSummaryPublication(job.payload());
                fixture.store.beginSummaryPublication(fixture.runId, "a".repeat(64), 11L);
            });
            String before = fixture.jdbc.queryForObject("SELECT row_to_json(j)::text FROM review_jobs j WHERE review_run_id=?::uuid", String.class, fixture.runId);
            var input = fixture.second.getPublicationInspectionInput(fixture.runId);
            assertEquals(job.payload(), input.job());
            assertEquals("processing", input.queueStatus());
            assertTrue(input.ownsSummarySlot()); assertTrue(input.latestRun());
            assertEquals(before, fixture.jdbc.queryForObject("SELECT row_to_json(j)::text FROM review_jobs j WHERE review_run_id=?::uuid", String.class, fixture.runId));
            assertEquals("sent", fixture.second.getCheckPublicationEffect(fixture.runId, "summary_comment").orElseThrow().state());
            fixture.store.completeJob(job);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HikariDataSource pool;
        final JdbcTemplate jdbc;
        final JdbcStore store;
        final JdbcStore second;
        final long installation = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        final long repository = UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE;
        final String runId;

        Fixture() {
            DotEnv.load(".env"); RuntimeConfig config = RuntimeConfig.fromEnvironment();
            HikariConfig settings = new HikariConfig(); settings.setJdbcUrl(config.jdbcUrl());
            settings.setUsername(config.databaseUser()); settings.setPassword(config.databasePassword());
            pool = new HikariDataSource(settings); jdbc = new JdbcTemplate(pool);
            store = new JdbcStore(jdbc, pool, new ObjectMapper());
            second = new JdbcStore(jdbc, pool, new ObjectMapper());
            runId = store.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(repository, 7,
                    "base1234", "head1234", Models.PIPELINE_VERSION, "lease-test", "webhook", "lease-test"),
                    new Models.ReviewJob("pending", installation, "lease-test", "fixture", 7, "base1234", "head1234")).run().id();
        }

        void expire(Models.ClaimedJob job) {
            jdbc.update("UPDATE review_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?::uuid", job.id());
        }
        ai.codelens.review.ReviewExecutionGuard guard(JdbcStore owner, Models.ClaimedJob job) {
            return new ai.codelens.review.ReviewExecutionGuard() {
                public void check() { if (!owner.hasJobLease(job)) throw new LeaseLostException(); }
                public void write(Runnable action) { owner.withJobLease(job, action); }
            };
        }
        String jobStatus() { return jdbc.queryForObject("SELECT status FROM review_jobs WHERE review_run_id=?::uuid", String.class, runId); }
        @Override public void close() {
            try {
                jdbc.update("DELETE FROM review_runs WHERE github_repository_id=?", repository);
                jdbc.update("DELETE FROM github_repositories WHERE id=?", repository);
                jdbc.update("DELETE FROM github_installations WHERE id=?", installation);
            } finally { pool.close(); }
        }
    }
}
