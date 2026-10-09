package ai.codelens.review;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.github.PublicationUncertainException;
import ai.codelens.store.LeaseLostException;
import ai.codelens.intelligence.CodeIntelligenceService;
import ai.codelens.llm.LlmClient;
import ai.codelens.policy.RepositoryPolicyService;
import ai.codelens.semantic.SemanticReviewService;
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
    private final SemanticReviewService semantics;
    private final LlmClient llm;
    private final RuntimeConfig config;
    private final ObjectMapper json;
    private final CheckPublisher checkPublisher;

    public ReviewEngine(JdbcStore store, GitHubClient github, RepositoryPolicyService policies,
                        CodeIntelligenceService intelligence, SemanticReviewService semantics,
                        LlmClient llm, RuntimeConfig config, ObjectMapper json) {
        this.store = store; this.github = github; this.policies = policies; this.intelligence = intelligence;
        this.semantics = semantics; this.llm = llm; this.config = config; this.json = json;
        this.checkPublisher = new CheckPublisher(store, github, json);
    }

    public void execute(Models.ReviewJob job, ReviewExecutionGuard guard) {
        guard.check();
        if (config.publicTrial() && !config.permitsTrialRepository(job.owner(), job.repo())) {
            throw new IllegalArgumentException("Review repository is outside the public trial allowlist");
        }
        Models.ReviewRun run = store.getReviewRun(job.reviewRunId());
        if (run.pullNumber() != job.pullNumber() || !run.baseSha().equals(job.baseSha()) || !run.headSha().equals(job.headSha())) {
            throw new IllegalArgumentException("Review job revision does not match persisted run");
        }
        // The terminal run transaction already confirmed publication. Only acknowledge the queue on recovery.
        if (Set.of("completed", "stale").contains(run.status())) return;
        guard.check();
        Optional<JdbcStore.FrozenOutput> storedOutput = store.getFrozenReviewOutput(run.id());
        Optional<FrozenReviewOutput> frozen = storedOutput.map(record -> {
            if (!config.frozenPublicationsEnabled()) throw FrozenReviewOutput.unavailable();
            FrozenReviewOutput output = FrozenReviewOutput.open(record, job, run.repositoryId(),
                    config.publicTrial(), config.publicationKey(), json);
            JdbcStore.CheckPublicationEffect start = store.getCheckPublicationEffect(run.id(), "check_start")
                    .orElseThrow(FrozenReviewOutput::unavailable);
            if (!start.state().equals("confirmed") || start.remoteId() == null || start.remoteId() != output.checkId()) {
                throw FrozenReviewOutput.unavailable();
            }
            return output;
        });
        if (config.frozenPublicationsEnabled() && frozen.isEmpty()
                && (store.getCheckPublicationEffect(run.id(), "check_result").isPresent()
                || store.getCheckPublicationEffect(run.id(), "summary_comment").isPresent())) {
            throw FrozenReviewOutput.unavailable(); // Never backfill an output for an already recorded write.
        }
        Optional<Models.Publication> existing = store.getPublication(run.id());
        guard.check();
        long checkId = checkPublisher.start(job, existing.map(Models.Publication::checkRunId).orElse(null), guard);
        confirmPublication(() -> guard.write(() -> {
            store.savePublication(new Models.Publication(run.id(), job.headSha(), checkId,
                    existing.map(Models.Publication::summaryCommentId).orElse(null)));
            store.updateReviewRun(run.id(), "in_progress", null, "", "");
        }), "check-start/local-confirmation");

        if (frozen.isPresent()) {
            guard.check();
            if (!github.currentRevision(job.installationId(), job.owner(), job.repo(), job.pullNumber()).matches(job)) {
                stale(job, run.id(), checkId, "The PR revision changed; frozen output was not published.", guard);
                return;
            }
            if (checkId != frozen.get().checkId()) throw FrozenReviewOutput.unavailable();
            publishOutput(job, frozen.get(), existing, guard);
            return; // No diff fetch, policy reload, indexing or model call on this path.
        }

        guard.check();
        Models.PullRequest pull = github.getPullRequest(job.installationId(), job.owner(), job.repo(), job.pullNumber());
        guard.check();
        if (!new Models.PullRequestRevision(pull.baseSha(), pull.headSha()).matches(job)
                || !github.currentRevision(job.installationId(), job.owner(), job.repo(), job.pullNumber()).matches(job)) {
            stale(job, run.id(), checkId, "The PR base/head revision changed before analysis; no outdated result was posted.", guard); return;
        }
        int originalFiles = pull.files().size();
        guard.check();
        Models.RepositoryPolicy policy = trialPolicy(policies.load(run.repositoryId(), job.installationId(), job.owner(), job.repo(), job.headSha()), config.publicTrial());
        pull = new Models.PullRequest(pull.number(), pull.title(), pull.body(), pull.baseSha(), pull.headSha(), policies.filterFiles(pull.files(), policy));
        guard.write(() -> store.updateReviewRunConfig(run.id(), policy.hash()));

        guard.check();
        CodeIntelligenceService.Result analysis = intelligence.analyze(run.id(), run.repositoryId(), job, pull, guard);
        guard.check();
        SemanticReviewService.Result semantic = semantics.analyze(run.repositoryId(), job, pull, guard);
        Models.ImpactSummary selectedImpact = semantic.applied() ? semantic.impact() : analysis.summary();
        Models.ChangeSummary summary = summarize(pull, policy.language(), selectedImpact);
        guard.check();
        if (llm.enabled()) {
            try { summary = llm.generateSummary(run.id(), pull, policy, selectedImpact, guard::check); }
            catch (LeaseLostException lost) { throw lost; }
            catch (RuntimeException ignored) { summary = modelLimitation(summary, "Model summary unavailable or budget/audit refused; deterministic fallback used."); }
        }
        summary = applySemanticCoverage(summary, semantic);
        List<Models.Finding> findings = new ArrayList<>(reviewRisk(pull));
        guard.check();
        if (llm.enabled()) {
            try { findings.addAll(llm.reviewRisk(run.id(), pull, policy, selectedImpact, guard::check)); }
            catch (LeaseLostException lost) { throw lost; }
            catch (RuntimeException ignored) { summary = modelLimitation(summary, "Model risk review unavailable or budget/audit refused; no model risk coverage claimed."); }
        }
        findings = deduplicateAndThreshold(findings, policy);
        List<Models.Finding> savedFindings = findings;
        guard.write(() -> store.saveFindings(run.id(), savedFindings));
        int verified = (int) findings.stream().filter(item -> item.status().equals("verified")).count();
        int published = (int) findings.stream().filter(Models.Finding::publishable).count();
        int rejected = findings.size() - verified;
        String risk = findings.stream().anyMatch(item -> item.publishable() && Set.of("critical", "high").contains(item.severity())) ? "high"
                : findings.stream().anyMatch(item -> item.publishable() && item.severity().equals("medium")) && summary.riskLevel().equals("low") ? "medium" : summary.riskLevel();
        summary = summary.withFindings(new Models.FindingSummary(findings.size(), verified, published, rejected, findings), risk)
                .withPolicy(new Models.PolicySummary(policy.hash(), policy.rules().size(), pull.files().size(), originalFiles - pull.files().size(),
                        policy.blocking(), policy.language(), policy.warnings()));

        guard.check();
        if (!github.currentRevision(job.installationId(), job.owner(), job.repo(), job.pullNumber()).matches(job)) {
            stale(job, run.id(), checkId, "The PR base/head revision changed before publishing; no outdated result was posted.", guard); return;
        }
        String markdown = renderMarkdown(summary, semantic);
        List<Models.Annotation> annotations = findings.stream().filter(Models.Finding::publishable).limit(Math.min(50, config.maxInlineComments()))
                .map(item -> new Models.Annotation(item.path(), item.line(), item.line(),
                        Set.of("critical", "high").contains(item.severity()) ? "failure" : item.severity().equals("medium") ? "warning" : "notice",
                        truncate(item.severity().toUpperCase() + ": " + item.title(), 255),
                        truncate(item.claim() + "\n\nSuggestion: " + item.suggestion(), 64_000),
                        "Confidence: %.2f\nVerification: %s".formatted(item.confidence(), item.verification()))).toList();
        String conclusion = config.publicTrial() ? "neutral" : summary.riskLevel().equals("high") ? (policy.blocking() ? "failure" : "neutral") : "success";
        String finalSummary = toJson(summary);
        FrozenReviewOutput output = new FrozenReviewOutput(1, job, run.repositoryId(), checkId, conclusion,
                "CodeLens review: " + summary.riskLevel() + " risk", markdown, annotations, finalSummary);
        if (config.frozenPublicationsEnabled()) {
            FrozenReviewOutput.Sealed sealed = output.seal(config.publicationKey(), json);
            try {
                guard.write(() -> store.freezeReviewOutput(job, run.repositoryId(), sealed.hash(), sealed.ciphertext()));
            } catch (LeaseLostException lost) { throw lost; }
            catch (RuntimeException failed) { throw FrozenReviewOutput.unavailable(); }
        }
        publishOutput(job, output, existing, guard);
    }

    private void publishOutput(Models.ReviewJob job, FrozenReviewOutput output,
                               Optional<Models.Publication> existing, ReviewExecutionGuard guard) {
        guard.check();
        checkPublisher.complete(job, output.checkId(), output.conclusion(), output.title(),
                output.markdown(), output.annotations(), guard);
        confirmPublication(() -> {
            guard.check();
            long commentId = checkPublisher.summary(job, output.markdown(),
                    existing.map(Models.Publication::summaryCommentId).orElse(null), guard);
            guard.write(() -> {
                store.savePublication(new Models.Publication(job.reviewRunId(), job.headSha(), output.checkId(), commentId));
                store.updateReviewRun(job.reviewRunId(), "completed", output.summaryJson(), "", "");
            });
        }, "check-completion/local-confirmation");
    }

    private static void confirmPublication(Runnable confirmation, String stage) {
        try { confirmation.run(); }
        catch (LeaseLostException | PublicationUncertainException exception) { throw exception; }
        catch (RuntimeException exception) {
            throw new PublicationUncertainException("CONFIRM", stage, exception);
        }
    }

    static Models.RepositoryPolicy trialPolicy(Models.RepositoryPolicy policy, boolean publicTrial) {
        if (!publicTrial) return policy;
        List<String> warnings = new ArrayList<>(policy.warnings());
        warnings.add("Public trial: advisory only; repository blocking requests are ignored. This is not a quality approval.");
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("public-trial-v1:" + policy.hash()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        return new Models.RepositoryPolicy(hash, policy.sourceCommitSha(), policy.language(), false,
                policy.maxInlineComments(), policy.minimumConfidence(), policy.include(), policy.exclude(),
                policy.rules(), policy.guidance(), List.copyOf(warnings));
    }

    public void fail(Models.ReviewJob job, String detail, ReviewExecutionGuard guard) {
        guard.check();
        if (config.publicTrial() && !config.permitsTrialRepository(job.owner(), job.repo())) return;
        store.getPublication(job.reviewRunId()).filter(value -> value.checkRunId() != null).ifPresent(publication -> {
            guard.check();
            try { checkPublisher.complete(job, publication.checkRunId(), config.publicTrial() ? "neutral" : "failure",
                    "CodeLens review failed", "The review could not be completed after three attempts.", List.of(), guard); }
            catch (PublicationUncertainException exception) { throw exception; }
            catch (RuntimeException ignored) {}
        });
        // The queue and run failure are committed together by the fenced retry operation.
        guard.check();
    }

    private void stale(Models.ReviewJob job, String runId, long checkId, String detail, ReviewExecutionGuard guard) {
        guard.check();
        checkPublisher.complete(job, checkId, "cancelled", "CodeLens review superseded", detail, List.of(), guard);
        confirmPublication(() -> guard.write(() -> store.updateReviewRun(runId, "stale", null, "", "")), "stale/local-confirmation");
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

    static Models.ChangeSummary applySemanticCoverage(Models.ChangeSummary summary, SemanticReviewService.Result semantic) {
        if (semantic.applied()) {
            var coverage = semantic.coverage();
            List<String> limitations = new ArrayList<>(coverage.limitations());
            summary.coverage().limitations().stream().filter(value -> value.startsWith("Model ")).forEach(limitations::add);
            return summary.withCoverage(new Models.Coverage(coverage.reviewedFiles(), coverage.totalFiles(),
                    coverage.truncated() || summary.coverage().truncated(), coverage.analysisLevel(), coverage.executionLevel(), limitations));
        }
        if (!semantic.attempted()) return summary;
        List<String> limitations = new ArrayList<>(summary.coverage().limitations());
        limitations.add("Whole-repository Java semantics failed closed (" + semantic.reason()
                + "); the published result remains diff-only fallback evidence.");
        return summary.withCoverage(new Models.Coverage(summary.coverage().reviewedFiles(), summary.coverage().totalFiles(),
                summary.coverage().truncated(), "diff-only/fallback", semantic.executionLevel(), limitations));
    }

    private static Models.ChangeSummary modelLimitation(Models.ChangeSummary summary, String limitation) {
        var coverage = summary.coverage(); List<String> limitations = new ArrayList<>(coverage.limitations()); limitations.add(limitation);
        return summary.withCoverage(new Models.Coverage(coverage.reviewedFiles(), coverage.totalFiles(), coverage.truncated(),
                coverage.analysisLevel(), coverage.executionLevel(), limitations));
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
        return renderMarkdown(summary, null);
    }

    static String renderMarkdown(Models.ChangeSummary summary, SemanticReviewService.Result semantic) {
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
        if (semantic != null && semantic.applied() && semantic.reuseInvestigation() != null) {
            var reuse = semantic.reuseInvestigation();
            out.append("\n### Reuse investigation\n\n- Production Java graph at `")
                    .append(shortSha(reuse.provenance().headSha())).append("` searched ")
                    .append(reuse.searchScope().searchedSymbols()).append(" unchanged symbol(s) and ")
                    .append(reuse.searchScope().searchedRelationships()).append(" relationship(s).\n")
                    .append("- Semantic coverage for a `new` decision: **")
                    .append(reuse.searchScope().semanticCoverageComplete() ? "complete" : "incomplete").append("**.\n");
            if (reuse.candidates().isEmpty()) {
                out.append("- No candidate met the current deterministic recall signals; this is not proof that no reusable implementation exists.\n");
            } else {
                reuse.candidates().stream().limit(3).forEach(candidate -> out.append("- `")
                        .append(markdownCode(candidate.qualifiedName())).append("` in `")
                        .append(markdownCode(candidate.path())).append("` — ")
                        .append(candidate.relationship()).append(", ")
                        .append(candidate.fit()).append(", score ")
                        .append("%.2f".formatted(candidate.score())).append(".\n"));
            }
            out.append("- Patch publication: **blocked** until an audited ReuseDecision, selected SolutionOption, local diff, and verification plan pass the release gate.\n");
        }
        out.append("\n### Coverage and limitations\n\n- Analysis level: **").append(summary.coverage().analysisLevel()).append("**")
                .append("; execution level: **").append(summary.coverage().executionLevel()).append("**\n")
                .append("- Files reviewed: ").append(summary.coverage().reviewedFiles()).append('/').append(summary.coverage().totalFiles())
                .append(summary.coverage().truncated() ? " (truncated)" : "").append("\n");
        summary.coverage().limitations().forEach(limitation -> out.append("- Limitation: ").append(limitation).append('\n'));
        return out.toString();
    }

    private static String shortSha(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(12, value.length()));
    }

    private static String markdownCode(String value) {
        return value == null ? "unknown" : value.replace('`', '\'').replace('\r', ' ').replace('\n', ' ');
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
