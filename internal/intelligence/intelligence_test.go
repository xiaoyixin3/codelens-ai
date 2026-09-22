package intelligence

import (
	"context"
	"strings"
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

func TestSupportedMainstreamLanguages(t *testing.T) {
	tests := map[string]string{
		"service.go":    "go",
		"Service.java":  "java",
		"Service.kt":    "kotlin",
		"service.py":    "python",
		"service.tsx":   "typescript",
		"service.mjs":   "javascript",
		"Service.cs":    "csharp",
		"service.c":     "c",
		"service.hpp":   "cpp",
		"service.rs":    "rust",
		"service.php":   "php",
		"service.rb":    "ruby",
		"Service.swift": "swift",
	}
	for path, expected := range tests {
		t.Run(path, func(t *testing.T) {
			actual, supported := supportedLanguage(path)
			if !supported || actual != expected {
				t.Fatalf("expected %s to resolve to %s, got %q supported=%t", path, expected, actual, supported)
			}
		})
	}
}

func TestParseMainstreamLanguageSymbolsAndCalls(t *testing.T) {
	tests := []struct {
		language string
		path     string
		content  string
	}{
		{"go", "service.go", "package sample\nfunc Validate() bool { return true }\nfunc Handle() { Validate() }\n"},
		{"java", "Service.java", "public class Service {\n  public boolean validate() { return true; }\n  public void handle() {\n    validate();\n  }\n}\n"},
		{"kotlin", "Service.kt", "class Service {\n  fun validate(): Boolean = true\n  fun handle() { validate() }\n}\n"},
		{"python", "service.py", "def validate():\n    return True\n\ndef handle():\n    validate()\n"},
		{"typescript", "service.ts", "export function validate() { return true }\nexport function handle() { validate() }\n"},
		{"javascript", "service.js", "export function validate() { return true }\nexport function handle() { validate() }\n"},
		{"csharp", "Service.cs", "public class Service {\n  public bool Validate() { return true; }\n  public void Handle() {\n    Validate();\n  }\n}\n"},
		{"c", "service.c", "bool validate(void) { return true; }\nvoid handle(void) { validate(); }\n"},
		{"cpp", "service.cpp", "bool validate() { return true; }\nvoid handle() { validate(); }\n"},
		{"rust", "service.rs", "pub fn validate() -> bool { true }\npub fn handle() { validate(); }\n"},
		{"php", "service.php", "<?php\nfunction validate() { return true; }\nfunction handle() { validate(); }\n"},
		{"ruby", "service.rb", "def validate\n  true\nend\n\ndef handle\n  validate()\nend\n"},
		{"swift", "Service.swift", "public func validate() -> Bool { true }\npublic func handle() { validate() }\n"},
	}

	for _, test := range tests {
		t.Run(test.language, func(t *testing.T) {
			symbols, edges := parse(test.path, test.language, test.content)
			if !hasNamedSymbol(symbols, caseName(test.language, "validate")) || !hasNamedSymbol(symbols, caseName(test.language, "handle")) {
				t.Fatalf("expected validate and handle symbols, got %#v", symbols)
			}
			if countNamedSymbols(symbols, caseName(test.language, "validate")) != 1 {
				t.Fatalf("call expression was incorrectly indexed as a declaration: %#v", symbols)
			}
			if !hasCallEdge(edges, caseName(test.language, "handle"), caseName(test.language, "validate")) {
				t.Fatalf("expected handle -> validate edge, got %#v", edges)
			}
		})
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

func TestAnalyzeBuildsJavaImpactPath(t *testing.T) {
	reader := fakeReader{
		"base123:Service.java": "public class Service {\n  public boolean validate() { return true; }\n  public void handle() { validate(); }\n}\n",
		"head123:Service.java": "public class Service {\n  public boolean validate() { return false; }\n  public void handle() { validate(); }\n}\n",
	}
	store := &fakeStore{}
	analyzer := New(reader, store, 10, 10000)
	pull := githubapp.PullRequest{BaseSHA: "base123", HeadSHA: "head123", Files: []githubapp.ChangedFile{{Path: "Service.java", Status: "modified"}}}
	result, err := analyzer.Analyze(context.Background(), "run", 42, contracts.ReviewJob{InstallationID: 7, Owner: "o", Repo: "r", BaseSHA: "base123", HeadSHA: "head123"}, pull)
	if err != nil {
		t.Fatal(err)
	}
	if result.Summary.ChangedSymbols == 0 || result.Summary.ImpactedSymbols == 0 {
		t.Fatalf("expected changed Java method and impacted caller, got %#v", result.Summary)
	}
	if result.Head.Files[0].Language != "java" || result.Head.Files[0].Status != "indexed" {
		t.Fatalf("expected indexed Java file, got %#v", result.Head.Files[0])
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

func hasNamedSymbol(symbols []Symbol, name string) bool {
	for _, symbol := range symbols {
		if symbol.Name == name {
			return true
		}
	}
	return false
}

func countNamedSymbols(symbols []Symbol, name string) int {
	count := 0
	for _, symbol := range symbols {
		if symbol.Name == name {
			count++
		}
	}
	return count
}

func hasCallEdge(edges []Edge, from, to string) bool {
	for _, edge := range edges {
		if stringsContain(edge.FromStableKey, from) && stringsContain(edge.ToStableKey, to) {
			return true
		}
	}
	return false
}

func caseName(language, value string) string {
	if language == "go" || language == "csharp" {
		return strings.ToUpper(value[:1]) + value[1:]
	}
	return value
}
