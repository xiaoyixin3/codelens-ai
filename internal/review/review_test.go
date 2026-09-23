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

func TestReviewRiskDetectsGoSpecificRisks(t *testing.T) {
	pull := githubapp.PullRequest{Files: []githubapp.ChangedFile{{
		Path: "internal/client/client.go",
		Patch: strings.Join([]string{
			"@@ -10,0 +10,12 @@",
			"+tlsConfig := &tls.Config{InsecureSkipVerify: true}",
			"+ctx, _ := context.WithCancel(parent)",
			"+resp, err := http.Get(endpoint)",
			"+defer resp.Body.Close()",
			"+_ = os.WriteFile(path, payload, 0777)",
			"+rows, err := db.Query(fmt.Sprintf(\"SELECT * FROM users WHERE name = '%s'\", name))",
			"+cmd := exec.Command(\"sh\", \"-c\", \"echo \"+input)",
			"+json.Unmarshal(payload, &target)",
			"+http.ListenAndServe(\":8080\", handler)",
			"+file, err := os.Open(path)",
			"+defer file.Close()",
			"+return nil",
		}, "\n"),
	}}}
	findings := ReviewRisk(pull, 10000, 20)
	wanted := map[string]bool{
		"go/security/insecure-tls":                         false,
		"go/security/world-writable-permission":            false,
		"go/correctness/discarded-context-cancel":          false,
		"go/performance/default-http-client-no-timeout":    false,
		"go/correctness/response-close-before-error-check": false,
		"go/security/formatted-sql":                        false,
		"go/security/dynamic-shell-command":                false,
		"go/correctness/ignored-decode-error":              false,
		"go/correctness/ignored-server-error":              false,
		"go/correctness/resource-close-before-error-check": false,
	}
	for _, finding := range findings {
		if _, exists := wanted[finding.RuleID]; exists {
			wanted[finding.RuleID] = true
		}
	}
	for ruleID, found := range wanted {
		if !found {
			t.Fatalf("missing Go finding %s in %+v", ruleID, findings)
		}
	}
}

func TestReviewRiskSkipsGoTestFilesAndGuardedResponse(t *testing.T) {
	pull := githubapp.PullRequest{Files: []githubapp.ChangedFile{
		{Path: "internal/client/client_test.go", Patch: "@@ -1,0 +1 @@\n+tlsConfig := &tls.Config{InsecureSkipVerify: true}"},
		{Path: "internal/client/client.go", Patch: "@@ -20,0 +20,4 @@\n+resp, err := client.Do(req)\n+if err != nil { return err }\n+defer resp.Body.Close()\n+return nil"},
	}}
	findings := ReviewRisk(pull, 10000, 20)
	for _, finding := range findings {
		if strings.HasPrefix(finding.RuleID, "go/") {
			t.Fatalf("unexpected Go finding: %+v", finding)
		}
	}
}

func TestSummarizeRecognizesGoSourceAndTests(t *testing.T) {
	withoutTests := Summarize(githubapp.PullRequest{Files: []githubapp.ChangedFile{{Path: "service.go", Additions: 4}}}, 10)
	if len(withoutTests.RiskReasons) == 0 || !strings.Contains(withoutTests.RiskReasons[0], "No test file") {
		t.Fatalf("expected missing-test reason for Go source: %+v", withoutTests.RiskReasons)
	}
	withTests := Summarize(githubapp.PullRequest{Files: []githubapp.ChangedFile{{Path: "service.go", Additions: 4}, {Path: "service_test.go", Additions: 4}}}, 10)
	for _, reason := range withTests.RiskReasons {
		if strings.Contains(reason, "No test file") {
			t.Fatalf("did not expect missing-test reason with _test.go: %+v", withTests.RiskReasons)
		}
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
