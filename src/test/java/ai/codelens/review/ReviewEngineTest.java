package ai.codelens.review;

import ai.codelens.contracts.Models;
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
    }
}
