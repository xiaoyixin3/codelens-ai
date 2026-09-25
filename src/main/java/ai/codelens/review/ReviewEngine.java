package ai.codelens.review;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.intelligence.CodeIntelligenceService;
import ai.codelens.llm.LlmClient;
import ai.codelens.policy.RepositoryPolicyService;
import ai.codelens.security.Redactor;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Profile("worker")
public class ReviewEngine {
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
    private static final Pattern HIGH_RISK_PATH = Pattern.compile("(?i)(^|/)(auth|payment|billing|security|migration|database|permission)(/|\\.|$)");
    private static final Pattern TEST_PATH = Pattern.compile("(?i)(^|/)(__tests__|test|tests|spec)(/|\\.|$)|(^|/)([^/]*\\.(test|spec)\\.[jt]sx?|[^/]*_test\\.go)$");
    private static final List<Rule> RULES = List.of(
            rule("security/no-eval", "\\beval\\s*\\(", "security", "high", .98, "Dynamic code execution", "This added line executes a string as code, which can turn untrusted input into code execution.", "Replace eval with an explicit parser or a fixed dispatch table.", "Trace the value passed to eval and confirm whether user-controlled data can reach it."),
            rule("security/no-function-constructor", "\\bnew\\s+Function\\s*\\(", "security", "high", .97, "Dynamic Function constructor", "This line constructs executable code at runtime and creates an injection surface.", "Use a fixed function implementation or a constrained expression parser.", "Inspect every argument to the Function constructor for external input."),
            rule("concurrency/no-async-foreach", "\\.forEach\\s*\\(\\s*async\\b", "concurrency", "high", .96, "Async forEach is not awaited", "Promises created by forEach are not awaited, so the surrounding operation may finish early.", "Use await Promise.all(items.map(async ...)) or a for...of loop with await.", "Add a test proving the outer function waits for every iteration."),
            rule("correctness/no-empty-catch", "\\bcatch\\s*(\\([^)]*\\))?\\s*\\{\\s*\\}", "correctness", "medium", .94, "Exception is silently swallowed", "This empty catch block hides failures and can leave the operation in an unknown state.", "Handle the expected error explicitly or rethrow it with context.", "Exercise the failing branch and assert the caller receives the failure."),
            rule("go/security/insecure-tls", "\\bInsecureSkipVerify\\s*:\\s*true\\b", "security", "critical", .99, "TLS certificate verification disabled", "This TLS configuration accepts unverified certificates and enables man-in-the-middle attacks.", "Remove InsecureSkipVerify and configure trusted roots.", "Confirm an untrusted certificate is rejected."),
            rule("go/correctness/discarded-context-cancel", ",\\s*_\\s*:?=\\s*context\\.With(Cancel|Timeout|Deadline)\\s*\\(", "correctness", "high", .98, "Context cancel function discarded", "Discarding cancel can retain timers and child contexts.", "Keep the cancel function and defer it immediately.", "Exercise repeated calls and verify resources are released."),
            rule("go/performance/default-http-client-no-timeout", "\\bhttp\\.(Get|Post|PostForm)\\s*\\(", "performance", "medium", .90, "HTTP request has no client timeout", "The package-level HTTP helper can wait indefinitely for a stalled peer.", "Use an http.Client with an explicit timeout and bounded context.", "Test against a server that never responds."),
            rule("go/security/formatted-sql", "\\b(Query|QueryRow|Exec|Raw)\\w*\\s*\\(\\s*fmt\\.Sprintf\\s*\\(", "security", "high", .95, "SQL built with fmt.Sprintf", "Formatting values into SQL can turn untrusted input into executable SQL.", "Use driver parameter binding and static query text.", "Add an injection-focused database test.")
    );

    private final JdbcStore store;
    private final GitHubClient github;
    private final RepositoryPolicyService policies;
    private final CodeIntelligenceService intelligence;
    private final LlmClient llm;
    private final RuntimeConfig config;
    private final ObjectMapper json;

    public ReviewEngine(JdbcStore store, GitHubClient github, RepositoryPolicyService policies,
                        CodeIntelligenceService intelligence, LlmClient llm, RuntimeConfig config, ObjectMapper json) {
        this.store = store; this.github = github; this.policies = policies; this.intelligence = intelligence;
        this.llm = llm; this.config = config; this.json = json;
    }

    public void execute(Models.ReviewJob job) {
        Models.ReviewRun run = store.getReviewRun(job.reviewRunId());
        Optional<Models.Publication> existing = store.getPublication(run.id());
        long checkId = github.startCheck(job.installationId(), job.owner(), job.repo(), job.headSha(),
                existing.map(Models.Publication::checkRunId).orElse(null));
        store.savePublication(new Models.Publication(run.id(), job.headSha(), checkId,
                existing.map(Models.Publication::summaryCommentId).orElse(null)));
        store.updateReviewRun(run.id(), "in_progress", null, "", "");

        Models.PullRequest pull = github.getPullRequest(job.installationId(), job.owner(), job.repo(), job.pullNumber());
        if (!pull.headSha().equals(job.headSha())) { stale(job, run.id(), checkId, "A newer PR head SHA arrived before analysis completed."); return; }
        int originalFiles = pull.files().size();
        Models.RepositoryPolicy policy = policies.load(run.repositoryId(), job.installationId(), job.owner(), job.repo(), job.headSha());
        pull = new Models.PullRequest(pull.number(), pull.title(), pull.body(), pull.baseSha(), pull.headSha(), policies.filterFiles(pull.files(), policy));
        store.updateReviewRunConfig(run.id(), policy.hash());

        CodeIntelligenceService.Result analysis = intelligence.analyze(run.id(), run.repositoryId(), job, pull);
        Models.ChangeSummary summary = summarize(pull, policy.language(), analysis.summary());
        if (llm.enabled()) {
            try { summary = llm.generateSummary(run.id(), pull, policy, analysis.summary()); }
            catch (RuntimeException ignored) { /* deterministic summary is the safe fallback */ }
        }
        List<Models.Finding> findings = new ArrayList<>(reviewRisk(pull));
        if (llm.enabled()) {
            try { findings.addAll(llm.reviewRisk(run.id(), pull, policy, analysis.summary())); }
            catch (RuntimeException ignored) { /* deterministic findings remain available */ }
        }
        findings = deduplicateAndThreshold(findings, policy);
        store.saveFindings(run.id(), findings);
        int verified = (int) findings.stream().filter(item -> item.status().equals("verified")).count();
        int published = (int) findings.stream().filter(Models.Finding::publishable).count();
        int rejected = findings.size() - verified;
        String risk = findings.stream().anyMatch(item -> item.publishable() && Set.of("critical", "high").contains(item.severity())) ? "high"
                : findings.stream().anyMatch(item -> item.publishable() && item.severity().equals("medium")) && summary.riskLevel().equals("low") ? "medium" : summary.riskLevel();
        summary = summary.withFindings(new Models.FindingSummary(findings.size(), verified, published, rejected, findings), risk)
                .withPolicy(new Models.PolicySummary(policy.hash(), policy.rules().size(), pull.files().size(), originalFiles - pull.files().size(),
                        policy.blocking(), policy.language(), policy.warnings()));

        if (!github.currentHead(job.installationId(), job.owner(), job.repo(), job.pullNumber()).equals(job.headSha())) {
            stale(job, run.id(), checkId, "A newer PR head SHA arrived before publishing; no outdated result was posted."); return;
        }
        String markdown = renderMarkdown(summary);
        List<Models.Annotation> annotations = findings.stream().filter(Models.Finding::publishable).limit(config.maxInlineComments())
                .map(item -> new Models.Annotation(item.path(), item.line(), item.line(),
                        Set.of("critical", "high").contains(item.severity()) ? "failure" : item.severity().equals("medium") ? "warning" : "notice",
                        truncate(item.severity().toUpperCase() + ": " + item.title(), 255),
                        truncate(item.claim() + "\n\nSuggestion: " + item.suggestion(), 64_000),
                        "Confidence: %.2f\nVerification: %s".formatted(item.confidence(), item.verification()))).toList();
        String conclusion = summary.riskLevel().equals("high") ? (policy.blocking() ? "failure" : "neutral") : "success";
        github.completeCheck(job.installationId(), job.owner(), job.repo(), checkId, conclusion,
                "CodeLens review: " + summary.riskLevel() + " risk", markdown, annotations);
        long commentId = github.upsertSummaryComment(job.installationId(), job.owner(), job.repo(), job.pullNumber(), markdown,
                existing.map(Models.Publication::summaryCommentId).orElse(null));
        store.savePublication(new Models.Publication(run.id(), job.headSha(), checkId, commentId));
        store.updateReviewRun(run.id(), "completed", toJson(summary), "", "");
    }

    public void fail(Models.ReviewJob job, String detail) {
        store.getPublication(job.reviewRunId()).filter(value -> value.checkRunId() != null).ifPresent(publication -> {
            try { github.completeCheck(job.installationId(), job.owner(), job.repo(), publication.checkRunId(), "failure",
                    "CodeLens review failed", "The review could not be completed after three attempts.", List.of()); }
            catch (RuntimeException ignored) {}
        });
        store.updateReviewRun(job.reviewRunId(), "failed", null, "WORKER_FAILED", truncate(Redactor.redact(detail), 2000));
    }

    private void stale(Models.ReviewJob job, String runId, long checkId, String detail) {
        github.completeCheck(job.installationId(), job.owner(), job.repo(), checkId, "stale", "CodeLens review superseded", detail, List.of());
        store.updateReviewRun(runId, "stale", null, "", "");
    }

    static Models.ChangeSummary summarize(Models.PullRequest pull, String language, Models.ImpactSummary impact) {
        List<Models.ChangedFile> bounded = pull.files().subList(0, Math.min(pull.files().size(), 100));
        int additions = bounded.stream().mapToInt(Models.ChangedFile::additions).sum();
        int deletions = bounded.stream().mapToInt(Models.ChangedFile::deletions).sum();
        boolean tests = bounded.stream().anyMatch(file -> TEST_PATH.matcher(file.path()).find());
        List<String> highRisk = bounded.stream().map(Models.ChangedFile::path).filter(path -> HIGH_RISK_PATH.matcher(path).find()).toList();
        List<String> reasons = new ArrayList<>();
        if (!highRisk.isEmpty()) reasons.add(language.equals("zh") ? "涉及高风险路径：" + String.join("、", highRisk) : "High-risk paths changed: " + String.join(", ", highRisk));
        if (!tests && additions + deletions > 50) reasons.add(language.equals("zh") ? "当前变更未包含测试文件。" : "No test file is included in the reviewed change set.");
        if (additions + deletions > 1000) reasons.add(language.equals("zh") ? "变更超过 1,000 行，建议分阶段审核。" : "The change exceeds 1,000 modified lines and deserves staged review.");
        String risk = !highRisk.isEmpty() || additions + deletions > 1000 ? "high" : reasons.isEmpty() ? "low" : "medium";
        if (rank(impact.level()) > rank(risk)) risk = impact.level();
        String overview = language.equals("zh") ? "本 PR 在审核范围内变更 %d 个文件，共 +%d/-%d 行。".formatted(pull.files().size(), additions, deletions)
                : "This PR changes %d file(s) with +%d/-%d lines in the reviewed scope.".formatted(pull.files().size(), additions, deletions);
        List<Models.FileSummary> files = bounded.stream().map(file -> new Models.FileSummary(file.path(), file.status())).toList();
        return new Models.ChangeSummary(pull.title().isBlank() ? "Review the proposed code change" : pull.title(), overview, files, risk,
                reasons, new Models.Coverage(bounded.size(), pull.files().size(), bounded.size() < pull.files().size()), null, impact, null);
    }

    static List<Models.Finding> reviewRisk(Models.PullRequest pull) {
        List<DiffLine> lines = addedLines(pull.files()); List<Models.Finding> result = new ArrayList<>();
        for (DiffLine line : lines) for (Rule rule : RULES) if (rule.pattern().matcher(line.content()).find()) result.add(finding(rule, line));
        return result;
    }

    private List<Models.Finding> deduplicateAndThreshold(List<Models.Finding> input, Models.RepositoryPolicy policy) {
        Map<String, Models.Finding> unique = new LinkedHashMap<>();
        input.stream().sorted(Comparator.comparingInt((Models.Finding item) -> severity(item.severity())).reversed())
                .forEach(item -> unique.putIfAbsent(item.fingerprint(), item));
        List<Models.Finding> result = new ArrayList<>(); int index = 0;
        for (Models.Finding item : unique.values()) {
            double threshold = policy.minimumConfidence().getOrDefault(item.severity(), defaultConfidence(item.severity()));
            boolean publish = index < Math.min(config.maxInlineComments(), policy.maxInlineComments()) && item.confidence() >= threshold;
            result.add(item.withPublishable(publish)); index++;
        }
        return result;
    }

    public static String renderMarkdown(Models.ChangeSummary summary) {
        StringBuilder out = new StringBuilder("## CodeLens AI Review\n\n**Risk: ").append(summary.riskLevel().toUpperCase()).append("**\n\n")
                .append(summary.overview()).append("\n\n### Change intent\n\n").append(summary.intent()).append("\n\n### Files\n\n");
        summary.files().forEach(file -> out.append("- `").append(file.path()).append("` — ").append(file.change()).append('\n'));
        out.append("\n### Risk signals\n\n");
        if (summary.riskReasons().isEmpty()) out.append("- No material risk signal detected in the current scope.\n");
        else summary.riskReasons().forEach(reason -> out.append("- ").append(reason).append('\n'));
        if (summary.findings() != null) {
            out.append("\n### Verified findings\n\n");
            if (summary.findings().published() == 0) out.append("No candidate passed evidence verification and the publish threshold.\n");
            summary.findings().items().stream().filter(Models.Finding::publishable).forEach(item -> out.append("- **").append(item.severity().toUpperCase())
                    .append("** `").append(item.path()).append(':').append(item.line()).append("` — ").append(item.title()).append(" (`")
                    .append(item.fingerprint(), 0, Math.min(12, item.fingerprint().length())).append("`)\n"));
        }
        if (summary.impact() != null) {
            out.append("\n### Impact analysis\n\n- Blast radius: **").append(summary.impact().level().toUpperCase()).append("** (")
                    .append(summary.impact().score()).append("/100)\n- Changed symbols: ").append(summary.impact().changedSymbols())
                    .append("; impacted symbols: ").append(summary.impact().impactedSymbols()).append('\n');
            summary.impact().topPaths().forEach(path -> out.append("- `").append(path.changedName()).append("` → `").append(path.impactedName())
                    .append("` (depth ").append(path.depth()).append(", score ").append("%.3f".formatted(path.score())).append(")\n"));
            out.append("\n> ").append(summary.impact().coverageWarning()).append('\n');
        }
        if (summary.policy() != null) out.append("\n### Repository policy\n\n- Rules: ").append(summary.policy().rules()).append("; included files: ")
                .append(summary.policy().includedFiles()).append("; excluded files: ").append(summary.policy().excludedFiles())
                .append("; blocking: ").append(summary.policy().blocking()).append("; language: ").append(summary.policy().language()).append('\n');
        out.append("\n> Coverage: ").append(summary.coverage().reviewedFiles()).append('/').append(summary.coverage().totalFiles()).append(" files reviewed")
                .append(summary.coverage().truncated() ? " (truncated)" : "").append(".\n");
        return out.toString();
    }

    private static List<DiffLine> addedLines(List<Models.ChangedFile> files) {
        List<DiffLine> result = new ArrayList<>();
        for (Models.ChangedFile file : files) {
            int right = 0; boolean active = false;
            for (String raw : file.patch().split("\\R", -1)) {
                Matcher matcher = HUNK.matcher(raw);
                if (matcher.find()) { right = Integer.parseInt(matcher.group(1)); active = true; continue; }
                if (!active || raw.startsWith("\\ No newline")) continue;
                if (raw.startsWith("+") && !raw.startsWith("+++")) { result.add(new DiffLine(file.path(), right, raw.substring(1))); right++; }
                else if (raw.startsWith(" ")) right++;
            }
        }
        return result;
    }
    private static Models.Finding finding(Rule rule, DiffLine line) {
        String fingerprint = CodeIntelligenceService.digest(rule.id() + "\n" + line.path() + "\n" + line.line());
        return new Models.Finding(fingerprint, "deterministic", rule.id(), rule.category(), rule.severity(), rule.confidence(), rule.title(),
                rule.claim(), rule.suggestion(), rule.verification(), line.path(), line.line(), line.content(), "verified", true,
                new Models.FindingEvidence(line.path(), line.line(), line.line(), "RIGHT", CodeIntelligenceService.digest(line.content()), "diff"));
    }
    private String toJson(Object value) { try { return json.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static Rule rule(String id, String pattern, String category, String severity, double confidence, String title, String claim, String suggestion, String verification) {
        return new Rule(id, Pattern.compile(pattern), category, severity, confidence, title, claim, suggestion, verification);
    }
    private static int severity(String value) { return Map.of("critical",4,"high",3,"medium",2,"low",1).getOrDefault(value, 0); }
    private static int rank(String value) { return Map.of("high",3,"medium",2,"low",1).getOrDefault(value,0); }
    private static double defaultConfidence(String severity) { return Map.of("critical",.9,"high",.85,"medium",.8,"low",.8).getOrDefault(severity,.85); }
    private static String truncate(String value, int max) { return value.substring(0, Math.min(value.length(), max)); }
    private record DiffLine(String path, int line, String content) {}
    private record Rule(String id, Pattern pattern, String category, String severity, double confidence, String title, String claim, String suggestion, String verification) {}
}
