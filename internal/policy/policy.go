package policy

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"github.com/xiaoyixin3/codelens-ai/internal/githubapp"
	"github.com/xiaoyixin3/codelens-ai/internal/security"
	"gopkg.in/yaml.v3"
)

type Rule struct {
	Key      string `json:"key" yaml:"key"`
	Content  string `json:"content" yaml:"content"`
	Source   string `json:"source" yaml:"-"`
	Scope    string `json:"scope,omitempty" yaml:"scope,omitempty"`
	Severity string `json:"severity,omitempty" yaml:"severity,omitempty"`
	Enabled  *bool  `json:"-" yaml:"enabled,omitempty"`
}

func (r Rule) IsEnabled() bool { return r.Enabled == nil || *r.Enabled }

type Policy struct {
	Hash              string
	SourceCommitSHA   string
	Language          string
	Blocking          bool
	MaxInlineComments int
	MinimumConfidence map[string]float64
	Include           []string
	Exclude           []string
	Guidance          string
	Rules             []Rule
	Warnings          []string
}

type filePolicy struct {
	Version int `yaml:"version"`
	Review  struct {
		Language          string             `yaml:"language"`
		Blocking          bool               `yaml:"blocking"`
		MaxInlineComments int                `yaml:"maxInlineComments"`
		MinimumConfidence map[string]float64 `yaml:"minimumConfidence"`
	} `yaml:"review"`
	Include []string `yaml:"include"`
	Exclude []string `yaml:"exclude"`
	Rules   []Rule   `yaml:"rules"`
}

type Reader interface {
	GetFileContent(context.Context, int64, string, string, string, string) (string, error)
}

type Store interface {
	SavePolicy(context.Context, int64, Policy) error
}

type Loader struct {
	reader                   Reader
	store                    Store
	defaultMaxInlineComments int
}

func NewLoader(reader Reader, store Store, defaultMaxInlineComments int) *Loader {
	return &Loader{reader: reader, store: store, defaultMaxInlineComments: defaultMaxInlineComments}
}

func defaultFile(maxComments int) filePolicy {
	var result filePolicy
	result.Version = 1
	result.Review.Language = "en"
	result.Review.MaxInlineComments = maxComments
	result.Review.MinimumConfidence = map[string]float64{}
	result.Include = []string{"**/*"}
	return result
}

func Parse(source string, defaultMaxComments int) (filePolicy, error) {
	if len(source) > 100_000 {
		return filePolicy{}, fmt.Errorf(".codelens.yml exceeds 100 KB")
	}
	value := defaultFile(defaultMaxComments)
	decoder := yaml.NewDecoder(bytes.NewBufferString(source))
	decoder.KnownFields(true)
	if err := decoder.Decode(&value); err != nil {
		return filePolicy{}, err
	}
	if value.Version != 1 {
		return filePolicy{}, fmt.Errorf("unsupported policy version %d", value.Version)
	}
	if value.Review.Language == "" {
		value.Review.Language = "en"
	}
	if value.Review.Language != "en" && value.Review.Language != "zh" {
		return filePolicy{}, fmt.Errorf("review.language must be en or zh")
	}
	if value.Review.MaxInlineComments < 0 || value.Review.MaxInlineComments > 50 {
		return filePolicy{}, fmt.Errorf("review.maxInlineComments must be between 0 and 50")
	}
	if len(value.Include) == 0 {
		value.Include = []string{"**/*"}
	}
	for severity, confidence := range value.Review.MinimumConfidence {
		if severity != "critical" && severity != "high" && severity != "medium" && severity != "low" {
			return filePolicy{}, fmt.Errorf("unsupported severity %q", severity)
		}
		if confidence < 0 || confidence > 1 {
			return filePolicy{}, fmt.Errorf("confidence for %s must be between 0 and 1", severity)
		}
	}
	for index := range value.Rules {
		if strings.TrimSpace(value.Rules[index].Key) == "" || strings.TrimSpace(value.Rules[index].Content) == "" {
			return filePolicy{}, fmt.Errorf("policy rules require key and content")
		}
	}
	return value, nil
}

func MarkdownRules(source string) []Rule {
	lines := strings.Split(source[:min(len(source), 20_000)], "\n")
	result := make([]Rule, 0)
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "- ") && !strings.HasPrefix(line, "* ") {
			continue
		}
		content := strings.TrimSpace(line[2:])
		if content == "" {
			continue
		}
		digest := sha256.Sum256([]byte(content))
		result = append(result, Rule{Key: "codelens-md-" + hex.EncodeToString(digest[:])[:12], Content: content, Source: "codelens_md"})
		if len(result) == 100 {
			break
		}
	}
	return result
}

func (l *Loader) Load(ctx context.Context, repositoryID, installationID int64, owner, repo, headSHA string) (Policy, error) {
	configured := defaultFile(l.defaultMaxInlineComments)
	warnings := []string{}
	if source, err := l.reader.GetFileContent(ctx, installationID, owner, repo, ".codelens.yml", headSHA); err == nil {
		parsed, parseErr := Parse(source, l.defaultMaxInlineComments)
		if parseErr != nil {
			warnings = append(warnings, ".codelens.yml is invalid; defaults were used: "+parseErr.Error())
		} else {
			configured = parsed
		}
	} else if !isNotFound(err) {
		warnings = append(warnings, ".codelens.yml could not be loaded; defaults were used.")
	}

	guidance := ""
	if source, err := l.reader.GetFileContent(ctx, installationID, owner, repo, "CODELENS.md", headSHA); err == nil {
		guidance = security.Redact(source[:min(len(source), 20_000)])
	} else if !isNotFound(err) {
		warnings = append(warnings, "CODELENS.md could not be loaded.")
	}
	rules := make([]Rule, 0, len(configured.Rules)+100)
	for _, candidate := range configured.Rules {
		if !candidate.IsEnabled() {
			continue
		}
		candidate.Source = "config"
		candidate.Content = security.Redact(candidate.Content)
		rules = append(rules, candidate)
	}
	rules = append(rules, MarkdownRules(guidance)...)
	hashInput, _ := json.Marshal(struct {
		File     filePolicy `json:"file"`
		Guidance string     `json:"guidance"`
		Rules    []Rule     `json:"rules"`
	}{configured, guidance, rules})
	digest := sha256.Sum256(hashInput)
	result := Policy{
		Hash: hex.EncodeToString(digest[:]), SourceCommitSHA: headSHA,
		Language: configured.Review.Language, Blocking: configured.Review.Blocking,
		MaxInlineComments: configured.Review.MaxInlineComments,
		MinimumConfidence: configured.Review.MinimumConfidence,
		Include:           configured.Include, Exclude: configured.Exclude,
		Guidance: guidance, Rules: rules, Warnings: warnings,
	}
	if err := l.store.SavePolicy(ctx, repositoryID, result); err != nil {
		return Policy{}, err
	}
	return result, nil
}

func FilterFiles(files []githubapp.ChangedFile, configured Policy) []githubapp.ChangedFile {
	result := make([]githubapp.ChangedFile, 0, len(files))
	for _, file := range files {
		if Included(file.Path, configured) {
			result = append(result, file)
		}
	}
	return result
}

func Included(path string, configured Policy) bool {
	if path == "" || strings.HasPrefix(path, "/") || strings.Contains(path, "../") || strings.Contains(path, `\`) || strings.ContainsRune(path, '\x00') || regexp.MustCompile(`^[A-Za-z]:`).MatchString(path) {
		return false
	}
	included := false
	for _, pattern := range configured.Include {
		if glob(pattern, path) {
			included = true
			break
		}
	}
	if !included {
		return false
	}
	for _, pattern := range configured.Exclude {
		if glob(pattern, path) {
			return false
		}
	}
	return true
}

func glob(pattern, value string) bool {
	var expression strings.Builder
	expression.WriteByte('^')
	for index := 0; index < len(pattern); index++ {
		switch pattern[index] {
		case '*':
			if index+1 < len(pattern) && pattern[index+1] == '*' {
				index++
				if index+1 < len(pattern) && pattern[index+1] == '/' {
					index++
					expression.WriteString("(?:.*/)?")
				} else {
					expression.WriteString(".*")
				}
			} else {
				expression.WriteString("[^/]*")
			}
		case '?':
			expression.WriteString("[^/]")
		default:
			expression.WriteString(regexp.QuoteMeta(string(pattern[index])))
		}
	}
	expression.WriteByte('$')
	matched, _ := regexp.MatchString(expression.String(), value)
	return matched
}

func isNotFound(err error) bool { return err != nil && strings.Contains(err.Error(), "status 404") }
func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
