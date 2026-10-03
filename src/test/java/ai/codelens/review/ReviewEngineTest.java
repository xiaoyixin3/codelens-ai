package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.semantic.SemanticReusePlanner;
import ai.codelens.semantic.SemanticReviewService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewEngineTest {
    @Test
    void mapsFindingsOnlyToAddedDiffLines() {
        Models.PullRequest pull = new Models.PullRequest(7, "Security change", "", "base1234", "head1234", List.of(
                new Models.ChangedFile("src/app.ts", "modified", 1, 1,
                        "@@ -10,2 +10,2 @@\n-old()\n+eval(input)\n context()", "")));
        List<Models.Finding> findings = ReviewEngine.reviewRisk(pull);
        assertEquals(1, findings.size());
        assertEquals("security/no-eval", findings.get(0).ruleId());
        assertEquals(10, findings.get(0).line());
        assertTrue(findings.get(0).evidence().excerptHash().length() >= 32);
    }

    @Test
    void deterministicSummaryRaisesRiskForSensitivePathsAndImpact() {
        Models.PullRequest pull = new Models.PullRequest(1, "Change auth", "", "base1234", "head1234", List.of(
                new Models.ChangedFile("src/auth/token.java", "modified", 20, 2, "", "")));
        Models.ImpactSummary impact = new Models.ImpactSummary("medium", 50, 1, 3, List.of(), "Changed-file coverage only");
        Models.ChangeSummary summary = ReviewEngine.summarize(pull, "en", impact);
        assertEquals("high", summary.riskLevel());
        assertTrue(summary.overview().contains("+20/-2"));
        assertEquals("diff-only/fallback", summary.coverage().analysisLevel());
        assertEquals("S0", summary.coverage().executionLevel());
        assertTrue(ReviewEngine.renderMarkdown(summary).contains("Callers and tests in unchanged files are not visible"));
    }

    @Test
    void publishesSemanticCoverageOnlyWhenTheSemanticResultWasApplied() {
        Models.PullRequest pull = new Models.PullRequest(1, "Java change", "", "base", "head", List.of(
                new Models.ChangedFile("src/main/java/example/Target.java", "modified", 1, 1, "", "")));
        Models.ImpactSummary impact = new Models.ImpactSummary("low", 10, 1, 1, List.of(), "semantic evidence");
        Models.ChangeSummary summary = ReviewEngine.summarize(pull, "en", impact);
        Models.Coverage semanticCoverage = new Models.Coverage(10, 10, false, "semantic", "S1",
                List.of("Repository code was not executed."));
        SemanticReviewService.Result applied = new SemanticReviewService.Result(true, true, "", "S1",
                impact, semanticCoverage, null, null, null);
        Models.ChangeSummary semantic = ReviewEngine.applySemanticCoverage(summary, applied);
        assertEquals("semantic", semantic.coverage().analysisLevel());
        assertEquals("S1", semantic.coverage().executionLevel());

        SemanticReviewService.Result failed = new SemanticReviewService.Result(true, false,
                "semantic_materialization_failed", "S0", null, null, null, null, null);
        Models.ChangeSummary fallback = ReviewEngine.applySemanticCoverage(summary, failed);
        assertEquals("diff-only/fallback", fallback.coverage().analysisLevel());
        assertTrue(fallback.coverage().limitations().stream().anyMatch(value -> value.contains("failed closed")));
    }

    @Test
    void rendersProductionReuseCandidatesWithoutOpeningThePatchGate() {
        Models.PullRequest pull = new Models.PullRequest(1, "Java change", "", "base", "head", List.of(
                new Models.ChangedFile("src/main/java/example/Target.java", "modified", 1, 1, "", "")));
        Models.ImpactSummary impact = new Models.ImpactSummary("low", 10, 1, 1, List.of(), "semantic evidence");
        Models.Coverage coverage = new Models.Coverage(10, 10, false, "semantic", "S1", List.of());
        SemanticReusePlanner.Candidate candidate = new SemanticReusePlanner.Candidate(
                "candidate", "method:Legacy.value", "example.Legacy.value", "src/main/java/example/Legacy.java",
                "method", "same_contract", "direct", .95, List.of(new SemanticReusePlanner.Evidence(
                "symbol:head:method:Legacy.value", "symbol", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "src/main/java/example/Legacy.java", 3, "method:Legacy.value", "", "", "", true)),
                "Existing contract");
        SemanticReusePlanner.Investigation reuse = new SemanticReusePlanner.Investigation(
                "reuse:head", new SemanticReusePlanner.Provenance("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "adapter", "base-model", "head-model"),
                new SemanticReusePlanner.SearchScope(List.of("src/main/java/example/Target.java"), 42, 73, true, List.of()),
                List.of(), List.of(candidate), new SemanticReusePlanner.PatchGate(false, List.of("decision missing")));
        SemanticReviewService.Result applied = new SemanticReviewService.Result(true, true, "", "S1",
                impact, coverage, null, null, reuse);
        Models.ChangeSummary summary = ReviewEngine.applySemanticCoverage(ReviewEngine.summarize(pull, "en", impact), applied);

        String markdown = ReviewEngine.renderMarkdown(summary, applied);

        assertTrue(markdown.contains("### Reuse investigation"));
        assertTrue(markdown.contains("searched 42 unchanged symbol(s) and 73 relationship(s)"));
        assertTrue(markdown.contains("example.Legacy.value"));
        assertTrue(markdown.contains("Patch publication: **blocked**"));
    }
}
