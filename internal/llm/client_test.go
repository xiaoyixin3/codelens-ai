package llm

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/xiaoyixin3/codelens-ai/internal/githubapp"
	"github.com/xiaoyixin3/codelens-ai/internal/policy"
)

type telemetryStore struct{ calls []Call }

func (s *telemetryStore) RecordLLMCall(_ context.Context, call Call) error {
	s.calls = append(s.calls, call)
	return nil
}

func TestGenerateSummaryAndTelemetry(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer secret" {
			t.Error("missing authorization")
		}
		fmt.Fprint(w, `{"choices":[{"message":{"content":"{\"intent\":\"Improve validation\",\"overview\":\"Updates validation.\",\"files\":[{\"path\":\"service.go\",\"change\":\"modified\"}],\"riskLevel\":\"medium\",\"riskReasons\":[\"Core behavior changed\"],\"coverage\":{\"reviewedFiles\":1,\"totalFiles\":1,\"truncated\":false}}"}}],"usage":{"prompt_tokens":10,"completion_tokens":12}}`)
	}))
	defer server.Close()
	telemetry := &telemetryStore{}
	client := New([]Provider{{Name: "test", BaseURL: server.URL, APIKey: "secret", Model: "model"}}, telemetry, 4, 100000)
	pull := githubapp.PullRequest{Title: "test", BaseSHA: "base", HeadSHA: "head", Files: []githubapp.ChangedFile{{Path: "service.go", Patch: "@@ -1 +1 @@\n-old\n+new"}}}
	summary, err := client.GenerateSummary(context.Background(), "00000000-0000-0000-0000-000000000001", pull, policy.Policy{Language: "en"}, 10, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if summary.RiskLevel != "medium" || len(telemetry.calls) != 1 || telemetry.calls[0].Status != "succeeded" {
		t.Fatalf("unexpected result: %#v %#v", summary, telemetry.calls)
	}
}

func TestRiskFindingsRequireExactAddedLine(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(w, `{"choices":[{"message":{"content":"{\"findings\":[{\"category\":\"correctness\",\"severity\":\"high\",\"confidence\":0.95,\"title\":\"Bad return\",\"claim\":\"The new value is wrong.\",\"suggestion\":\"Return the expected value.\",\"verification\":\"Run the unit test.\",\"path\":\"service.go\",\"line\":2,\"excerpt\":\"return false\"},{\"category\":\"security\",\"severity\":\"high\",\"confidence\":0.99,\"title\":\"Invented\",\"claim\":\"Not in diff.\",\"suggestion\":\"Ignore.\",\"verification\":\"None.\",\"path\":\"other.go\",\"line\":9}] }"}}]}`)
	}))
	defer server.Close()
	client := New([]Provider{{Name: "test", BaseURL: server.URL, APIKey: "secret", Model: "model"}}, nil, 4, 100000)
	pull := githubapp.PullRequest{Files: []githubapp.ChangedFile{{Path: "service.go", Patch: "@@ -1,2 +1,2 @@\n func ok() bool {\n+return false"}}}
	findings, err := client.ReviewRisk(context.Background(), "run", pull, policy.Policy{Language: "en"}, 10, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if len(findings) != 1 || findings[0].Path != "service.go" || findings[0].Line != 2 {
		t.Fatalf("unexpected findings: %#v", findings)
	}
}
