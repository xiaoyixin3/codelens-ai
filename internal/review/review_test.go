package review

import (
	"strings"
	"testing"

	"github.com/xiaoyixin3/codelens-ai/internal/contracts"
	"github.com/xiaoyixin3/codelens-ai/internal/githubapp"
)

func TestSummarizeMatchesRiskBudget(t *testing.T) {
	pull := githubapp.PullRequest{
		Title: "Change auth flow",
		Files: []githubapp.ChangedFile{
			{Path: "src/auth/login.ts", Status: "modified", Additions: 20, Deletions: 3},
			{Path: "src/user.ts", Status: "modified", Additions: 4, Deletions: 1},
		},
	}
	summary := Summarize(pull, 1)
	if summary.RiskLevel != "high" {
		t.Fatalf("risk=%s", summary.RiskLevel)
	}
	if !summary.Coverage.Truncated || summary.Coverage.ReviewedFiles != 1 {
		t.Fatalf("coverage=%+v", summary.Coverage)
	}
}

func TestReviewRiskProducesExactAddedLineEvidence(t *testing.T) {
	pull := githubapp.PullRequest{Files: []githubapp.ChangedFile{{
		Path:  "src/jobs.ts",
		Patch: "@@ -1,2 +1,3 @@\n const x = 1;\n+items.forEach(async (item) => run(item));\n return x;",
	}}}
	findings := ReviewRisk(pull, 10000, 8)
	if len(findings) != 1 {
		t.Fatalf("findings=%d", len(findings))
	}
	finding := findings[0]
	if finding.RuleID != "concurrency/no-async-foreach" || finding.Line != 2 || finding.Evidence == nil || finding.Evidence.StartLine != 2 {
		t.Fatalf("finding=%+v", finding)
	}
	if len(finding.Fingerprint) != 64 {
		t.Fatalf("fingerprint=%s", finding.Fingerprint)
	}
}

func TestRenderMarkdownIncludesFindingFingerprint(t *testing.T) {
	pull := githubapp.PullRequest{Title: "Unsafe", Files: []githubapp.ChangedFile{{Path: "src/a.ts", Patch: "@@ -0,0 +1 @@\n+eval(input)", Additions: 1}}}
	summary := Summarize(pull, 100)
	findings := ReviewRisk(pull, 10000, 8)
	summary.Findings = findingSummary(findings)
	markdown := RenderMarkdown(summary)
	if !strings.Contains(markdown, findings[0].Fingerprint[:12]) || !strings.Contains(markdown, "Verified findings") {
		t.Fatalf("markdown=%s", markdown)
	}
}

func findingSummary(findings []contracts.Finding) *contracts.FindingSummary {
	return &contracts.FindingSummary{Candidates: len(findings), Verified: len(findings), Published: len(findings), Items: findings}
}
