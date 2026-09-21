package intelligence

import (
	"context"
	"testing"

	"github.com/xiaoyixin3/codelens-ai/internal/contracts"
	"github.com/xiaoyixin3/codelens-ai/internal/githubapp"
)

type fakeReader map[string]string

func (f fakeReader) GetFileContent(_ context.Context, _ int64, _, _, path, ref string) (string, error) {
	return f[ref+":"+path], nil
}

type fakeStore struct{ result Result }

func (f *fakeStore) SaveIntelligence(_ context.Context, _ string, result Result) error {
	f.result = result
	return nil
}

func TestParseGoSymbolsAndCalls(t *testing.T) {
	content := "package sample\n\nfunc Validate() bool { return true }\n\nfunc Handle() {\n\tValidate()\n}\n"
	symbols, edges := parse("service.go", "go", content)
	if len(symbols) != 3 {
		t.Fatalf("expected module and two functions, got %d", len(symbols))
	}
	if len(edges) != 1 || !stringsContain(edges[0].ToStableKey, "Validate") {
		t.Fatalf("expected Handle -> Validate edge, got %#v", edges)
	}
}

func TestAnalyzeBuildsImpactPath(t *testing.T) {
	reader := fakeReader{
		"base123:service.go": "package sample\nfunc Validate() bool { return true }\nfunc Handle() { Validate() }\n",
		"head123:service.go": "package sample\nfunc Validate() bool { return false }\nfunc Handle() { Validate() }\n",
	}
	store := &fakeStore{}
	analyzer := New(reader, store, 10, 10000)
	pull := githubapp.PullRequest{BaseSHA: "base123", HeadSHA: "head123", Files: []githubapp.ChangedFile{{Path: "service.go", Status: "modified"}}}
	result, err := analyzer.Analyze(context.Background(), "run", 42, contracts.ReviewJob{InstallationID: 7, Owner: "o", Repo: "r", BaseSHA: "base123", HeadSHA: "head123"}, pull)
	if err != nil {
		t.Fatal(err)
	}
	if result.Summary.ChangedSymbols != 1 {
		t.Fatalf("expected one changed symbol, got %d", result.Summary.ChangedSymbols)
	}
	if result.Summary.ImpactedSymbols != 1 || len(result.Summary.TopPaths) != 1 {
		t.Fatalf("expected one impact path, got %#v", result.Summary)
	}
	if store.result.Head.Coverage.IndexedFiles != 1 {
		t.Fatal("result was not persisted")
	}
}

func stringsContain(value, fragment string) bool {
	for index := 0; index+len(fragment) <= len(value); index++ {
		if value[index:index+len(fragment)] == fragment {
			return true
		}
	}
	return false
}
