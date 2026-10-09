package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.config.RuntimeConfig;
import ai.codelens.github.GitHubClient;
import ai.codelens.intelligence.CodeIntelligenceService;
import ai.codelens.llm.LlmClient;
import ai.codelens.policy.RepositoryPolicyService;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.codelens.semantic.SemanticReusePlanner;
import ai.codelens.semantic.SemanticReviewService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ReviewEngineTest {
    @Test void wholeRepositoryCoverageDoesNotEraseModelInputLimitations() {
        var impact=new Models.ImpactSummary("low",0,0,0,List.of(),"fixture");
        var summary=new Models.ChangeSummary("intent","overview",List.of(),"low",List.of(),
                new Models.Coverage(1,2,true,"diff-only/fallback","S0",List.of("Model input: omitted b.java")),null,impact,null);
        var coverage=new Models.Coverage(2,2,false,"semantic","S1",List.of("Tests not executed"));
        var semantic=new SemanticReviewService.Result(true,true,"complete","S1",impact,coverage,null,null,null);
        var merged=ReviewEngine.applySemanticCoverage(summary,semantic);
        assertTrue(merged.coverage().truncated()); assertEquals("semantic",merged.coverage().analysisLevel());
        assertTrue(merged.coverage().limitations().contains("Model input: omitted b.java"));
    }
    @Test
    void publicTrialRefusesNonAllowlistedJobsBeforeDatabaseOrGitHubAccess() {
        JdbcStore store = mock(JdbcStore.class);
        GitHubClient github = mock(GitHubClient.class);
        RuntimeConfig config = mock(RuntimeConfig.class);
        when(config.publicTrial()).thenReturn(true);
        var engine = new ReviewEngine(store, github, mock(RepositoryPolicyService.class),
                mock(CodeIntelligenceService.class), mock(SemanticReviewService.class), mock(LlmClient.class), config, new ObjectMapper());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> engine.execute(
                new Models.ReviewJob("run", 1, "owner", "other", 7, "base1234", "head1234"), TestReviewGuard.INSTANCE));
        verifyNoInteractions(store, github);
    }

    @Test
    void publicTrialReusesPolicyButOverridesBlockingAndSeparatesItsFingerprint() {
        var policy = new Models.RepositoryPolicy("source-hash", "head", "zh", true, 8,
                Map.of("high", .95), List.of("src/**"), List.of("generated/**"), List.of(), "reuse existing utilities", List.of("original warning"));
        var trial = ReviewEngine.trialPolicy(policy, true);
        org.junit.jupiter.api.Assertions.assertFalse(trial.blocking());
        assertEquals(policy.rules(), trial.rules());
        assertEquals(policy.include(), trial.include());
        assertEquals(policy.minimumConfidence(), trial.minimumConfidence());
        assertEquals(policy.guidance(), trial.guidance());
        assertEquals(policy.sourceCommitSha(), trial.sourceCommitSha());
        assertEquals(64, trial.hash().length());
        org.junit.jupiter.api.Assertions.assertNotEquals(policy.hash(), trial.hash());
        assertEquals(trial.hash(), ReviewEngine.trialPolicy(policy, true).hash());
        assertTrue(trial.warnings().get(1).contains("advisory only"));
        org.junit.jupiter.api.Assertions.assertSame(policy, ReviewEngine.trialPolicy(policy, false));
    }
    @Test
    void rejectsPersistedJobMismatchBeforeRemoteSideEffects() {
        JdbcStore store = mock(JdbcStore.class);
        GitHubClient github = mock(GitHubClient.class);
        when(store.getReviewRun("run")).thenReturn(new Models.ReviewRun("run", 42, 7,
                "base5678", "head1234", "queued", "test", "enqueue", "webhook", "automatic", null));
        ReviewEngine engine = new ReviewEngine(store, github, mock(RepositoryPolicyService.class),
                mock(CodeIntelligenceService.class), mock(SemanticReviewService.class), mock(LlmClient.class),
                mock(RuntimeConfig.class), new ObjectMapper());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> engine.execute(
                new Models.ReviewJob("run", 1, "owner", "repo", 7, "base1234", "head1234"), TestReviewGuard.INSTANCE));
        verifyNoInteractions(github);
    }

    @Test
    void committedTerminalRunDoesNotPublishOrReanalyzeWhenQueueAckWasLost() {
        JdbcStore store = mock(JdbcStore.class);
        GitHubClient github = mock(GitHubClient.class);
        when(store.getReviewRun("run")).thenReturn(new Models.ReviewRun("run", 42, 7,
                "base1234", "head1234", "completed", Models.PIPELINE_VERSION, "enqueue", "webhook", "automatic", null));
        CodeIntelligenceService intelligence = mock(CodeIntelligenceService.class);
        SemanticReviewService semantics = mock(SemanticReviewService.class);
        new ReviewEngine(store, github, mock(RepositoryPolicyService.class), intelligence, semantics,
                mock(LlmClient.class), mock(RuntimeConfig.class), new ObjectMapper()).execute(
                new Models.ReviewJob("run", 1, "owner", "repo", 7, "base1234", "head1234"), TestReviewGuard.INSTANCE);
        verifyNoInteractions(github, intelligence, semantics);
    }

    @Test
    void rejectsBaseOnlyChangeBeforeAnalysis() {
        checkRevision("base5678", "base1234", "base1234", false, false);
    }

    @Test
    void rejectsRevisionChangeWhileDiffPagesWereBeingFetched() {
        checkRevision("base1234", "base5678", "base1234", false, false);
    }

    @Test
    void rejectsBaseOnlyChangeBeforePublishing() {
        checkRevision("base1234", "base1234", "base5678", true, false);
    }

    @Test
    void publishesWhenBothRevisionsRemainCurrent() {
        checkRevision("base1234", "base1234", "base1234", true, true);
    }

    private void checkRevision(String diffBase, String initialBase, String finalBase, boolean analyzed, boolean published) {
        JdbcStore store = mock(JdbcStore.class);
        GitHubClient github = mock(GitHubClient.class);
        RepositoryPolicyService policies = mock(RepositoryPolicyService.class);
        CodeIntelligenceService intelligence = mock(CodeIntelligenceService.class);
        SemanticReviewService semantics = mock(SemanticReviewService.class);
        LlmClient llm = mock(LlmClient.class);
        RuntimeConfig config = mock(RuntimeConfig.class);
        Models.ReviewJob job = new Models.ReviewJob("run", 1, "owner", "repo", 7, "base1234", "head1234");
        Models.ReviewRun run = new Models.ReviewRun("run", 42, 7, "base1234", "head1234", "queued", "test", "enqueue", "webhook", "automatic", null);
        when(store.getReviewRun("run")).thenReturn(run);
        when(store.getPublication("run")).thenReturn(Optional.empty());
        when(github.getReviewCheck(any(), anyLong(), anyString())).thenReturn(new ObjectMapper().createObjectNode().put("status", "in_progress"));
        when(github.writeSummaryComment(any(), anyString(), any(), any())).thenReturn(11L);
        when(github.getSummaryComment(any(), anyLong(), any())).thenAnswer(call -> {
            var captured = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(github).writeSummaryComment(eq(job), captured.capture(), any(), any());
            return new ObjectMapper().createObjectNode().put("body", captured.getValue());
        });
        when(github.startCheck(eq(1L), eq("owner"), eq("repo"), eq("head1234"), isNull(), anyString())).thenReturn(9L);
        when(github.getPullRequest(1, "owner", "repo", 7)).thenReturn(new Models.PullRequest(7, "change", "", diffBase, "head1234", List.of()));
        when(github.currentRevision(1, "owner", "repo", 7)).thenReturn(
                new Models.PullRequestRevision(initialBase, "head1234"), new Models.PullRequestRevision(finalBase, "head1234"));
        Models.RepositoryPolicy policy = new Models.RepositoryPolicy("resolved", "head1234", "en", false, 10,
                Map.of(), List.of(), List.of(), List.of(), "", List.of());
        when(policies.load(42, 1, "owner", "repo", "head1234")).thenReturn(policy);
        when(policies.filterFiles(anyList(), eq(policy))).thenReturn(List.of());
        Models.ImpactSummary impact = new Models.ImpactSummary("low", 0, 0, 0, List.of(), "fallback");
        when(intelligence.analyze(eq("run"), eq(42L), eq(job), any(), any())).thenReturn(
                new CodeIntelligenceService.Result(null, null, List.of(), List.of(), impact));
        when(semantics.analyze(eq(42L), eq(job), any(), any())).thenReturn(
                new SemanticReviewService.Result(false, false, "disabled", "S0", null, null, null, null, null));
        new ReviewEngine(store, github, policies, intelligence, semantics, llm, config, new ObjectMapper()).execute(job, TestReviewGuard.INSTANCE);
        verify(intelligence, times(analyzed ? 1 : 0)).analyze(eq("run"), eq(42L), eq(job), any(), any());
        verify(semantics, times(analyzed ? 1 : 0)).analyze(eq(42L), eq(job), any(), any());
        verify(github, times(published ? 1 : 0)).writeSummaryComment(eq(job), anyString(), isNull(), any());
        if (!published) {
            verify(github).completeCheck(eq(1L), eq("owner"), eq("repo"), eq(9L), eq("cancelled"), anyString(), anyString(), eq(List.of()));
            verify(store).updateReviewRun("run", "stale", null, "", "");
            if (!analyzed) verifyNoInteractions(llm);
        }
    }

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
