package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.github.PublicationUncertainException;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.LeaseLostException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Deterministic journal around existing GitHub operations. No HTTP inside a DB transaction. */
public final class CheckPublisher {
    private final JdbcStore store;
    private final GitHubClient github;
    private final ObjectMapper json;

    public CheckPublisher(JdbcStore store, GitHubClient github, ObjectMapper json) {
        this.store = store; this.github = github; this.json = json;
    }

    public String identity(Models.ReviewJob job) {
        return "codelens-ai:review:" + fingerprint(List.of(job.reviewRunId(), job.installationId(),
                job.owner(), job.repo(), job.pullNumber(), job.baseSha(), job.headSha()));
    }

    public long start(Models.ReviewJob job, Long seed, ReviewExecutionGuard guard) {
        String identity = identity(job);
        String hash = fingerprint(List.of("check-start-v1", identity));
        guard.check();
        Optional<JdbcStore.CheckPublicationEffect> effect = store.getCheckPublicationEffect(job.reviewRunId(), "check_start");
        effect.ifPresent(value -> requireHash(value, hash));
        Optional<Long> remote = github.findReviewCheck(job, identity, guard::check);
        if (effect.isPresent()) {
            long id = remote.orElseThrow(() -> uncertain("Recorded Check creation is not visible; do not resend"));
            if (effect.get().remoteId() != null && effect.get().remoteId() != id) throw uncertain("Recorded Check ID conflicts");
            confirm(job, "check_start", hash, id, guard);
            return id; // Never reopen a completed Check when recovering this run.
        }
        if (remote.isPresent()) throw uncertain("Remote Check exists without a local publication intent");
        if (seed != null) {
            guard.check();
            github.getReviewCheck(job, seed, null); // Validate the rerun source, never append to the old run's Check.
        }
        guard.write(() -> store.beginCheckPublication(job.reviewRunId(), "check_start", hash));
        guard.check();
        long id;
        try {
            id = github.startCheck(job.installationId(), job.owner(), job.repo(), job.headSha(), null, identity);
        } catch (PublicationUncertainException uncertain) {
            guard.check();
            try {
                id = github.findReviewCheck(job, identity, guard::check).orElseThrow(() -> uncertain);
            } catch (LeaseLostException lost) { throw lost; }
            catch (RuntimeException unavailable) { throw uncertain; }
        }
        confirm(job, "check_start", hash, id, guard);
        return id;
    }

    public void complete(Models.ReviewJob job, long id, String conclusion, String title, String summary,
                         List<Models.Annotation> annotations, ReviewExecutionGuard guard) {
        String identity = identity(job);
        List<Models.Annotation> bounded = List.copyOf(annotations.subList(0, Math.min(50, annotations.size())));
        String hash = fingerprint(List.of("check-result-v1", identity, id, conclusion, title, summary, bounded));
        String marked = summary + "\n\n<!-- " + identity + ":result:" + hash + " -->";
        guard.check();
        Optional<JdbcStore.CheckPublicationEffect> effect = store.getCheckPublicationEffect(job.reviewRunId(), "check_result");
        effect.ifPresent(value -> requireHash(value, hash));
        guard.check();
        JsonNode remote = github.getReviewCheck(job, id, identity);
        if (effect.isPresent()) {
            if (effect.get().remoteId() != null && effect.get().remoteId() != id) throw uncertain("Recorded result ID conflicts");
            if (!matches(remote, conclusion, title, marked, bounded.size())) {
                throw uncertain("Recorded Check result is not visible or differs; do not append annotations again");
            }
            confirm(job, "check_result", hash, id, guard);
            return;
        }
        if (!"in_progress".equals(remote.path("status").asText()) && !"queued".equals(remote.path("status").asText())) {
            throw uncertain("Check has an unexpected terminal status without a result intent");
        }
        guard.write(() -> store.beginCheckPublication(job.reviewRunId(), "check_result", hash));
        guard.check();
        try {
            github.completeCheck(job.installationId(), job.owner(), job.repo(), id, conclusion, title, marked, bounded);
        } catch (PublicationUncertainException uncertain) {
            guard.check();
            try {
                JsonNode observed = github.getReviewCheck(job, id, identity);
                if (!matches(observed, conclusion, title, marked, bounded.size())) throw uncertain;
            } catch (LeaseLostException lost) { throw lost; }
            catch (RuntimeException unavailable) { throw uncertain; }
        }
        confirm(job, "check_result", hash, id, guard);
    }

    private static boolean matches(JsonNode remote, String conclusion, String title, String summary, int annotations) {
        return "completed".equals(remote.path("status").asText())
                && conclusion.equals(remote.path("conclusion").asText())
                && title.equals(remote.path("output").path("title").asText())
                && summary.equals(remote.path("output").path("summary").asText())
                && remote.path("output").path("annotations_count").asInt(-1) == annotations;
    }

    public long summary(Models.ReviewJob job, String body, Long existing, ReviewExecutionGuard guard) {
        String identity = identity(job);
        String hash = fingerprint(List.of("summary-comment-v1", identity, body));
        String marked = GitHubClient.SUMMARY_MARKER + "\n<!-- " + identity + ":summary:" + hash + " -->\n" + body;
        guard.write(() -> store.claimSummaryPublication(job));
        guard.check();
        Optional<JdbcStore.CheckPublicationEffect> effect = store.getCheckPublicationEffect(job.reviewRunId(), "summary_comment");
        if (effect.isPresent()) {
            requireHash(effect.get(), hash);
            long observed = github.findSummaryComment(job, marked, guard::check)
                    .orElseThrow(() -> uncertain("Recorded summary is missing or changed; do not resend"));
            if (effect.get().remoteId() != null && effect.get().remoteId() != observed) throw uncertain("Summary target ID conflicts");
            confirmSummary(job, hash, observed, guard);
            return observed;
        }
        Long target = github.findSummaryComment(job, null, guard::check).orElse(null);
        if (existing != null) {
            github.getSummaryComment(job, existing, guard::check);
            if (!existing.equals(target)) throw uncertain("Stored summary reference conflicts with discovered comment");
        }
        Long planned = target;
        guard.write(() -> store.beginSummaryPublication(job.reviewRunId(), hash, planned));
        guard.check();
        long id;
        try {
            id = github.writeSummaryComment(job, marked, planned, guard::check);
            JsonNode observed = github.getSummaryComment(job, id, guard::check);
            if (!marked.equals(observed.path("body").asText())) throw uncertain("Published summary content differs");
        } catch (PublicationUncertainException uncertain) {
            guard.check();
            try {
                id = github.findSummaryComment(job, marked, guard::check).orElseThrow(() -> uncertain);
                if (planned != null && id != planned) throw uncertain;
            } catch (LeaseLostException lost) { throw lost; }
            catch (RuntimeException unavailable) { throw uncertain; }
        }
        confirmSummary(job, hash, id, guard);
        return id;
    }

    private void confirmSummary(Models.ReviewJob job, String hash, long id, ReviewExecutionGuard guard) {
        try {
            guard.write(() -> {
                store.confirmCheckPublication(job.reviewRunId(), "summary_comment", hash, id);
                store.releaseSummaryPublication(job.reviewRunId());
            });
        } catch (LeaseLostException lost) { throw lost; }
        catch (RuntimeException failure) { throw new PublicationUncertainException("CONFIRM", "summary-comment", failure); }
    }

    private void confirm(Models.ReviewJob job, String operation, String hash, long id, ReviewExecutionGuard guard) {
        try { guard.write(() -> store.confirmCheckPublication(job.reviewRunId(), operation, hash, id)); }
        catch (LeaseLostException lost) { throw lost; }
        catch (RuntimeException failure) {
            throw new PublicationUncertainException("CONFIRM", operation, failure);
        }
    }

    private void requireHash(JdbcStore.CheckPublicationEffect effect, String hash) {
        if (!effect.requestHash().equals(hash)) throw uncertain("Publication content changed on replay");
    }

    String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Could not fingerprint Check request", exception); }
    }

    private static PublicationUncertainException uncertain(String detail) {
        return new PublicationUncertainException("RECONCILE", detail, null);
    }
}
