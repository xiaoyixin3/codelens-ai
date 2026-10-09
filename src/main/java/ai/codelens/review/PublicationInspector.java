package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.config.RuntimeConfig;
import ai.codelens.github.GitHubClient;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only, point-in-time evidence. Reports are never an authorization to replay a write. */
public final class PublicationInspector {
    public record Observation(String operation, String localState, String result, Long remoteId) {}
    public record RecoveryAssessment(String status, String artifact, List<String> blockers,
                                     boolean authorized, boolean writesAllowed) {}
    public record Report(String runId, String runStatus, String queueStatus, String errorCode,
                         boolean holdsSummarySlot, boolean latestRun, String revision,
                         boolean automaticRecoveryAllowed, List<Observation> operations,
                         RecoveryAssessment recovery, String observedAt) {}

    private final JdbcStore store;
    private final GitHubClient github;
    private final CheckPublisher fingerprints;
    private final Clock clock;
    private final RuntimeConfig config;
    private final ObjectMapper json;

    public PublicationInspector(JdbcStore store, GitHubClient github, ObjectMapper json) {
        this(store, github, json, Clock.systemUTC(), null);
    }

    public PublicationInspector(JdbcStore store, GitHubClient github, ObjectMapper json, RuntimeConfig config) {
        this(store, github, json, Clock.systemUTC(), config);
    }

    PublicationInspector(JdbcStore store, GitHubClient github, ObjectMapper json, Clock clock) {
        this(store, github, json, clock, null);
    }

    private PublicationInspector(JdbcStore store, GitHubClient github, ObjectMapper json, Clock clock, RuntimeConfig config) {
        this.store = store; this.github = github;
        this.fingerprints = new CheckPublisher(store, github, json); this.clock = clock;
        this.config = config; this.json = json;
    }

    public Report inspect(String runId) {
        UUID.fromString(runId);
        Models.ReviewRun run = store.getReviewRun(runId);
        JdbcStore.PublicationInspectionInput input = store.getPublicationInspectionInput(runId);
        Models.ReviewJob job = input.job();
        if (!job.valid() || !runId.equals(job.reviewRunId()) || !run.id().equals(runId)
                || run.pullNumber() != job.pullNumber() || !run.baseSha().equals(job.baseSha()) || !run.headSha().equals(job.headSha())) {
            throw new IllegalArgumentException("Stored job and run revision do not match");
        }
        Instant deadline = clock.instant().plusSeconds(120);
        Runnable budget = () -> {
            if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)) {
                throw new IllegalStateException("Inspection read budget exhausted");
            }
        };
        String revision;
        try { budget.run(); revision = github.currentRevision(job.installationId(), job.owner(), job.repo(), job.pullNumber()).matches(job)
                ? "current" : "changed"; }
        catch (RuntimeException unavailable) { revision = "lookup_failed"; }
        String identity = fingerprints.identity(job);
        List<Observation> observations = new ArrayList<>();
        var start = store.getCheckPublicationEffect(runId, "check_start");
        Observation created = observeStart(job, identity, start, budget);
        observations.add(created);
        observations.add(observeResult(job, identity, created.remoteId(),
                store.getCheckPublicationEffect(runId, "check_result"), budget));
        observations.add(observeSummary(job, identity, store.getCheckPublicationEffect(runId, "summary_comment"), budget));
        return new Report(runId, run.status(), input.queueStatus(), input.errorCode(), input.ownsSummarySlot(),
                input.latestRun(), revision, false, List.copyOf(observations),
                assessRecovery(run, input, revision, observations), clock.instant().toString());
    }

    private RecoveryAssessment assessRecovery(Models.ReviewRun run, JdbcStore.PublicationInspectionInput input,
                                               String revision, List<Observation> observations) {
        List<String> blockers = new ArrayList<>();
        if (!run.status().equals("failed") || !input.queueStatus().equals("failed")
                || !input.errorCode().equals("PUBLICATION_UNCERTAIN")) blockers.add("not_paused_uncertain_job");
        if (!run.pipelineVersion().equals(Models.PIPELINE_VERSION)) blockers.add("unsupported_pipeline");
        if (!input.latestRun()) blockers.add("superseded_run");
        if (!revision.equals("current")) blockers.add("revision_not_verified_current");
        if (!input.ownsSummarySlot()) blockers.add("summary_slot_not_owned");
        if (config != null && config.publicTrial()
                && !config.permitsTrialRepository(input.job().owner(), input.job().repo())) blockers.add("outside_trial_scope");
        if (observations.stream().anyMatch(value -> !value.result().equals("verified_remote"))) {
            blockers.add("remote_effects_not_fully_verified");
        }
        String artifact = "missing";
        try {
            var stored = store.getFrozenReviewOutput(run.id());
            if (stored.isEmpty()) blockers.add("frozen_output_missing");
            else if (config == null || !config.frozenPublicationsEnabled()) {
                artifact = "disabled"; blockers.add("frozen_output_validation_disabled");
            } else {
                FrozenReviewOutput output = FrozenReviewOutput.open(stored.get(), input.job(), run.repositoryId(),
                        config.publicTrial(), config.publicationKey(), json);
                String identity = fingerprints.identity(input.job());
                String startHash = fingerprints.fingerprint(List.of("check-start-v1", identity));
                String resultHash = fingerprints.fingerprint(List.of("check-result-v1", identity, output.checkId(),
                        output.conclusion(), output.title(), output.markdown(), output.annotations()));
                String summaryHash = fingerprints.fingerprint(List.of("summary-comment-v1", identity, output.markdown()));
                var start = store.getCheckPublicationEffect(run.id(), "check_start");
                var result = store.getCheckPublicationEffect(run.id(), "check_result");
                var summary = store.getCheckPublicationEffect(run.id(), "summary_comment");
                boolean matches = start.isPresent() && result.isPresent() && summary.isPresent()
                        && start.get().state().equals("confirmed") && start.get().requestHash().equals(startHash)
                        && Long.valueOf(output.checkId()).equals(start.get().remoteId())
                        && result.get().requestHash().equals(resultHash) && summary.get().requestHash().equals(summaryHash)
                        && observations.stream().filter(value -> !value.operation().equals("summary_comment"))
                            .allMatch(value -> Long.valueOf(output.checkId()).equals(value.remoteId()));
                var publication = store.getPublication(run.id());
                if (publication.isPresent()) {
                    var local = publication.get();
                    if (!local.reviewRunId().equals(run.id()) || !local.headSha().equals(input.job().headSha())
                            || (local.checkRunId() != null && local.checkRunId() != output.checkId())
                            || (local.summaryCommentId() != null && observations.stream()
                                .filter(value -> value.operation().equals("summary_comment"))
                                .noneMatch(value -> local.summaryCommentId().equals(value.remoteId())))) matches = false;
                }
                artifact = matches ? "verified" : "journal_conflict";
                if (!matches) blockers.add("frozen_output_journal_conflict");
            }
        } catch (RuntimeException unavailable) {
            artifact = "unavailable"; blockers.add("frozen_output_unavailable");
        }
        return new RecoveryAssessment(blockers.isEmpty() ? "candidate_for_authorized_local_confirmation" : "blocked",
                artifact, List.copyOf(blockers), false, false);
    }

    private Observation observeStart(Models.ReviewJob job, String identity, Optional<JdbcStore.CheckPublicationEffect> effect, Runnable budget) {
        if (effect.isEmpty()) return absent("check_start");
        var recorded = effect.get();
        if (!recorded.requestHash().equals(fingerprints.fingerprint(List.of("check-start-v1", identity)))) {
            return observation("check_start", recorded, "local_fingerprint_conflict", null);
        }
        try {
            Optional<Long> remote = github.findReviewCheck(job, identity, budget);
            if (remote.isEmpty()) return observation("check_start", recorded, "not_visible", null);
            long id = remote.get();
            return observation("check_start", recorded, recorded.remoteId() != null && recorded.remoteId() != id
                    ? "remote_id_conflict" : "verified_remote", id);
        } catch (RuntimeException unavailable) { return observation("check_start", recorded, "lookup_failed", null); }
    }

    private Observation observeResult(Models.ReviewJob job, String identity, Long observedId,
                                      Optional<JdbcStore.CheckPublicationEffect> effect, Runnable budget) {
        if (effect.isEmpty()) return absent("check_result");
        var recorded = effect.get();
        Long id = recorded.remoteId() == null ? observedId : recorded.remoteId();
        if (id == null) return observation("check_result", recorded, "not_visible", null);
        try {
            budget.run();
            JsonNode remote = github.getReviewCheck(job, id, identity);
            String summary = remote.path("output").path("summary").asText();
            String suffix = "\n\n<!-- " + identity + ":result:" + recorded.requestHash() + " -->";
            int count = remote.path("output").path("annotations_count").asInt(-1);
            if (!"completed".equals(remote.path("status").asText()) || !summary.endsWith(suffix) || count < 0 || count > 50) {
                return observation("check_result", recorded, "content_conflict", id);
            }
            List<Models.Annotation> annotations = github.getReviewAnnotations(job, id, budget);
            String hash = fingerprints.fingerprint(List.of("check-result-v1", identity, id,
                    remote.path("conclusion").asText(), remote.path("output").path("title").asText(),
                    summary.substring(0, summary.length() - suffix.length()), annotations));
            return observation("check_result", recorded, annotations.size() == count && hash.equals(recorded.requestHash())
                    ? "verified_remote" : "content_conflict", id);
        } catch (RuntimeException unavailable) { return observation("check_result", recorded, "lookup_failed", id); }
    }

    private Observation observeSummary(Models.ReviewJob job, String identity,
                                       Optional<JdbcStore.CheckPublicationEffect> effect, Runnable budget) {
        if (effect.isEmpty()) return absent("summary_comment");
        var recorded = effect.get();
        try {
            Optional<Long> target = github.findSummaryComment(job, null, budget);
            if (target.isEmpty()) return observation("summary_comment", recorded, "not_visible", null);
            long id = target.get();
            if (recorded.remoteId() != null && recorded.remoteId() != id) {
                return observation("summary_comment", recorded, "remote_id_conflict", id);
            }
            String body = github.getSummaryComment(job, id, budget).path("body").asText();
            String prefix = GitHubClient.SUMMARY_MARKER + "\n<!-- " + identity + ":summary:" + recorded.requestHash() + " -->\n";
            boolean verified = body.startsWith(prefix) && recorded.requestHash().equals(fingerprints.fingerprint(
                    List.of("summary-comment-v1", identity, body.substring(prefix.length()))));
            return observation("summary_comment", recorded, verified ? "verified_remote" : "content_conflict", id);
        } catch (RuntimeException unavailable) { return observation("summary_comment", recorded, "lookup_failed", null); }
    }

    private static Observation absent(String operation) { return new Observation(operation, "absent", "not_recorded", null); }
    private static Observation observation(String operation, JdbcStore.CheckPublicationEffect effect, String result, Long id) {
        return new Observation(operation, effect.state(), result, id);
    }
}
