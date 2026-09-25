package ai.codelens.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public final class Models {
    public static final String PIPELINE_VERSION = "v1.0.0-beta.1-java.1";
    public static final String DEFAULT_CONFIG_HASH = "default-v1";

    private Models() {}

    public record ReviewJob(
            String reviewRunId, long installationId, String owner, String repo,
            int pullNumber, String baseSha, String headSha
    ) {
        public boolean valid() {
            return reviewRunId != null && !reviewRunId.isBlank() && installationId > 0
                    && notBlank(owner) && notBlank(repo) && pullNumber > 0
                    && baseSha != null && baseSha.length() >= 7 && headSha != null && headSha.length() >= 7;
        }
    }

    public record ReviewRun(
            String id, long repositoryId, int pullNumber, String baseSha, String headSha,
            String status, String pipelineVersion, String configHash, String trigger,
            String requestKey, String summary
    ) {}

    public record ChangedFile(
            @JsonProperty("filename") String path,
            String status,
            int additions,
            int deletions,
            String patch,
            @JsonProperty("previous_filename") String previousPath
    ) {
        public ChangedFile {
            patch = patch == null ? "" : patch;
            previousPath = previousPath == null ? "" : previousPath;
        }
    }

    public record PullRequest(
            int number, String title, String body, String baseSha, String headSha, List<ChangedFile> files
    ) {
        public PullRequest {
            title = title == null ? "" : title;
            body = body == null ? "" : body;
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    public record FileSummary(String path, String change) {}
    public record Coverage(int reviewedFiles, int totalFiles, boolean truncated) {}
    public record ImpactPath(String changedName, String impactedName, int depth, double score) {}
    public record ImpactSummary(
            String level, int score, int changedSymbols, int impactedSymbols,
            List<ImpactPath> topPaths, String coverageWarning
    ) {}
    public record PolicySummary(
            String configHash, int rules, int includedFiles, int excludedFiles,
            boolean blocking, String language, List<String> warnings
    ) {}
    public record FindingEvidence(
            String path, int startLine, int endLine, String side, String excerptHash, String evidenceType
    ) {}
    public record Finding(
            String fingerprint, String source, String ruleId, String category, String severity,
            double confidence, String title, String claim, String suggestion, String verification,
            String path, int line, String excerpt, String status, boolean publishable,
            FindingEvidence evidence
    ) {
        public Finding withPublishable(boolean value) {
            return new Finding(fingerprint, source, ruleId, category, severity, confidence, title, claim,
                    suggestion, verification, path, line, excerpt, value ? status : "rejected", value, evidence);
        }
    }
    public record FindingSummary(int candidates, int verified, int published, int rejected, List<Finding> items) {}
    public record ChangeSummary(
            String intent, String overview, List<FileSummary> files, String riskLevel,
            List<String> riskReasons, Coverage coverage, FindingSummary findings,
            ImpactSummary impact, PolicySummary policy
    ) {
        public ChangeSummary withImpact(ImpactSummary value, String newRisk) {
            return new ChangeSummary(intent, overview, files, newRisk, riskReasons, coverage, findings, value, policy);
        }
        public ChangeSummary withFindings(FindingSummary value, String newRisk) {
            return new ChangeSummary(intent, overview, files, newRisk, riskReasons, coverage, value, impact, policy);
        }
        public ChangeSummary withPolicy(PolicySummary value) {
            return new ChangeSummary(intent, overview, files, riskLevel, riskReasons, coverage, findings, impact, value);
        }
    }

    public record Annotation(
            String path,
            @JsonProperty("start_line") int startLine,
            @JsonProperty("end_line") int endLine,
            @JsonProperty("annotation_level") String level,
            String title,
            String message,
            @JsonProperty("raw_details") String rawDetails
    ) {}

    public record Publication(String reviewRunId, String headSha, Long checkRunId, Long summaryCommentId) {}
    public record ClaimedJob(String id, ReviewJob payload, int attempts) {}

    public record RepositoryPolicy(
            String hash, String sourceCommitSha, String language, boolean blocking, int maxInlineComments,
            Map<String, Double> minimumConfidence, List<String> include, List<String> exclude,
            List<PolicyRule> rules, String guidance, List<String> warnings
    ) {}
    public record PolicyRule(String id, String scope, String severity, String description, boolean enabled) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestWebhook(
            String action, Installation installation, Repository repository,
            @JsonProperty("pull_request") PullRequestPayload pullRequest
    ) {}
    public record Installation(long id) {}
    public record Owner(String login) {}
    public record Repository(long id, String name, Owner owner) {}
    public record RefPayload(String sha) {}
    public record PullRequestPayload(int number, RefPayload base, RefPayload head) {}

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
}
