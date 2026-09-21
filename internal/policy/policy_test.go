package policy

import (
	"context"
	"errors"
	"testing"

	"github.com/xiaoyixin3/codelens-ai/internal/githubapp"
)

func TestParseAndFilterPolicy(t *testing.T) {
	source := `version: 1
review:
  language: zh
  blocking: true
  maxInlineComments: 3
  minimumConfidence:
    high: 0.9
include: ["src/**"]
exclude: ["src/generated/**"]
rules:
  - key: no-global
    content: Avoid global mutable state.
`
	parsed, err := Parse(source, 8)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Review.Language != "zh" || !parsed.Review.Blocking || parsed.Review.MaxInlineComments != 3 {
		t.Fatalf("unexpected review policy: %#v", parsed.Review)
	}
	configured := Policy{Include: parsed.Include, Exclude: parsed.Exclude}
	files := FilterFiles([]githubapp.ChangedFile{{Path: "src/main.go"}, {Path: "src/generated/api.go"}, {Path: "docs/readme.md"}}, configured)
	if len(files) != 1 || files[0].Path != "src/main.go" {
		t.Fatalf("unexpected filtered files: %#v", files)
	}
}

func TestMarkdownRulesAreBounded(t *testing.T) {
	rules := MarkdownRules("# Guidance\n- First rule\n* Second rule\nplain text")
	if len(rules) != 2 || rules[0].Source != "codelens_md" || rules[0].Key == rules[1].Key {
		t.Fatalf("unexpected rules: %#v", rules)
	}
}

func TestIncludedRejectsUnsafePaths(t *testing.T) {
	configured := Policy{Include: []string{"**/*"}}
	for _, path := range []string{"../secret", `src\secret.go`, "/etc/passwd", "C:/secret", "src/\x00secret"} {
		if Included(path, configured) {
			t.Fatalf("expected %q to be rejected", path)
		}
	}
}

type loaderReader map[string]string

func (f loaderReader) GetFileContent(_ context.Context, _ int64, _, _, path, _ string) (string, error) {
	if value, ok := f[path]; ok {
		return value, nil
	}
	return "", errors.New("status 404")
}

type loaderStore struct{ saved Policy }

func (f *loaderStore) SavePolicy(_ context.Context, _ int64, value Policy) error {
	f.saved = value
	return nil
}

func TestLoaderCombinesConfigAndMarkdown(t *testing.T) {
	store := &loaderStore{}
	loader := NewLoader(loaderReader{".codelens.yml": "version: 1\nreview:\n  language: en\n  maxInlineComments: 2\nrules:\n  - key: explicit\n    content: Check errors.\n", "CODELENS.md": "- Never log secrets\n"}, store, 8)
	configured, err := loader.Load(context.Background(), 1, 2, "owner", "repo", "abcdef1")
	if err != nil {
		t.Fatal(err)
	}
	if len(configured.Rules) != 2 || configured.Hash == "" || store.saved.Hash != configured.Hash {
		t.Fatalf("unexpected policy: %#v", configured)
	}
}
