package intelligence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"regexp"
	"sort"
	"strings"

	"github.com/google/uuid"
	"github.com/xiaoyixin3/codelens-ai/internal/contracts"
	"github.com/xiaoyixin3/codelens-ai/internal/githubapp"
	"github.com/xiaoyixin3/codelens-ai/internal/security"
)

const ParserVersion = "go-native-multilang-v2"

type Reader interface {
	GetFileContent(context.Context, int64, string, string, string, string) (string, error)
}

type Store interface {
	SaveIntelligence(context.Context, string, Result) error
}

type Coverage struct {
	TotalChangedFiles int `json:"totalChangedFiles"`
	IndexedFiles      int `json:"indexedFiles"`
	SkippedFiles      int `json:"skippedFiles"`
	AbsentFiles       int `json:"absentFiles"`
	FailedFiles       int `json:"failedFiles"`
}

type IndexedFile struct {
	Path        string
	Language    string
	ContentHash string
	Status      string
	SkipReason  string
	ParseErrors int
}

type Symbol struct {
	StableKey     string
	Path          string
	Kind          string
	Name          string
	QualifiedName string
	StartLine     int
	EndLine       int
	Signature     string
	ContentHash   string
	Exported      bool
	Metadata      map[string]any
}

type Edge struct {
	FromStableKey string
	ToStableKey   string
	Type          string
	Confidence    float64
	SourcePath    string
	SourceLine    int
}

type Snapshot struct {
	ID           string
	RepositoryID int64
	CommitSHA    string
	BaseSHA      string
	ScopeHash    string
	Coverage     Coverage
	Files        []IndexedFile
	Symbols      []Symbol
	Edges        []Edge
}

type Change struct {
	Type             string
	Kind             string
	QualifiedName    string
	BeforeStableKey  string
	AfterStableKey   string
	BeforePath       string
	AfterPath        string
	BodyChanged      bool
	SignatureChanged bool
}

type Path struct {
	ChangedStableKey  string
	ChangedName       string
	ImpactedStableKey string
	ImpactedName      string
	ImpactedPath      string
	ImpactedKind      string
	Depth             int
	Score             float64
	Keys              []string
	Evidence          []map[string]any
}

type Result struct {
	Base    Snapshot
	Head    Snapshot
	Changes []Change
	Paths   []Path
	Summary contracts.ImpactSummary
}

type Analyzer struct {
	reader       Reader
	store        Store
	maxFiles     int
	maxFileBytes int
	maxDepth     int
	maxPaths     int
}

func New(reader Reader, store Store, maxFiles, maxFileBytes int) *Analyzer {
	return &Analyzer{reader: reader, store: store, maxFiles: maxFiles, maxFileBytes: maxFileBytes, maxDepth: 2, maxPaths: 200}
}

func (a *Analyzer) Analyze(ctx context.Context, reviewRunID string, repositoryID int64, job contracts.ReviewJob, pull githubapp.PullRequest) (Result, error) {
	scopeSource, _ := json.Marshal(struct {
		Files    []githubapp.ChangedFile `json:"files"`
		MaxFiles int                     `json:"maxFiles"`
		MaxBytes int                     `json:"maxBytes"`
	}{pull.Files, a.maxFiles, a.maxFileBytes})
	scopeHash := digest(scopeSource)
	base := Snapshot{ID: uuid.NewString(), RepositoryID: repositoryID, CommitSHA: job.BaseSHA, BaseSHA: job.BaseSHA, ScopeHash: scopeHash}
	head := Snapshot{ID: uuid.NewString(), RepositoryID: repositoryID, CommitSHA: job.HeadSHA, BaseSHA: job.BaseSHA, ScopeHash: scopeHash}
	base = a.indexSide(ctx, job, pull.Files, base, "base")
	head = a.indexSide(ctx, job, pull.Files, head, "head")
	resolveEdges(&base)
	resolveEdges(&head)
	changes := compare(base.Symbols, head.Symbols)
	paths := trace(changes, base, head, a.maxDepth, a.maxPaths)
	summary := summarize(changes, paths, base, head, a.maxDepth)
	result := Result{Base: base, Head: head, Changes: changes, Paths: paths, Summary: summary}
	if err := a.store.SaveIntelligence(ctx, reviewRunID, result); err != nil {
		return Result{}, err
	}
	return result, nil
}

func (a *Analyzer) indexSide(ctx context.Context, job contracts.ReviewJob, files []githubapp.ChangedFile, snapshot Snapshot, side string) Snapshot {
	snapshot.Coverage.TotalChangedFiles = len(files)
	for index, file := range files {
		path := file.Path
		if side == "base" && file.PreviousPath != "" {
			path = file.PreviousPath
		}
		absent := (side == "base" && file.Status == "added") || (side == "head" && file.Status == "removed")
		if absent {
			snapshot.Coverage.AbsentFiles++
			snapshot.Files = append(snapshot.Files, IndexedFile{Path: path, Status: "absent", SkipReason: "File is not present on the " + side + " side of the change."})
			continue
		}
		language, supported := supportedLanguage(path)
		if index >= a.maxFiles || !supported || !safePath(path) {
			snapshot.Coverage.SkippedFiles++
			reason := "Unsupported file type."
			if index >= a.maxFiles {
				reason = "Changed-file budget exceeded."
			} else if !safePath(path) {
				reason = "Unsafe repository path rejected."
			}
			snapshot.Files = append(snapshot.Files, IndexedFile{Path: path, Language: language, Status: "skipped", SkipReason: reason})
			continue
		}
		ref := job.HeadSHA
		if side == "base" {
			ref = job.BaseSHA
		}
		content, err := a.reader.GetFileContent(ctx, job.InstallationID, job.Owner, job.Repo, path, ref)
		if err != nil {
			snapshot.Coverage.FailedFiles++
			snapshot.Files = append(snapshot.Files, IndexedFile{Path: path, Language: language, Status: "failed", SkipReason: truncate(security.Redact(err.Error()), 1000)})
			continue
		}
		if len(content) > a.maxFileBytes {
			snapshot.Coverage.SkippedFiles++
			snapshot.Files = append(snapshot.Files, IndexedFile{Path: path, Language: language, Status: "skipped", SkipReason: "File-size budget exceeded."})
			continue
		}
		symbols, edges := parse(path, language, content)
		snapshot.Symbols = append(snapshot.Symbols, symbols...)
		snapshot.Edges = append(snapshot.Edges, edges...)
		snapshot.Files = append(snapshot.Files, IndexedFile{Path: path, Language: language, ContentHash: digest([]byte(content)), Status: "indexed"})
		snapshot.Coverage.IndexedFiles++
	}
	return snapshot
}

func resolveEdges(snapshot *Snapshot) {
	byName := map[string][]Symbol{}
	for _, symbol := range snapshot.Symbols {
		if symbol.Kind != "file" {
			byName[symbol.Name] = append(byName[symbol.Name], symbol)
		}
	}
	for index := range snapshot.Edges {
		if !strings.HasPrefix(snapshot.Edges[index].ToStableKey, "unresolved:") {
			continue
		}
		name := strings.TrimPrefix(snapshot.Edges[index].ToStableKey, "unresolved:")
		if candidates := byName[name]; len(candidates) == 1 {
			snapshot.Edges[index].ToStableKey = candidates[0].StableKey
			snapshot.Edges[index].Confidence = .75
		}
	}
}

func compare(base, head []Symbol) []Change {
	before := symbolMap(base)
	after := symbolMap(head)
	changes := make([]Change, 0)
	for key, left := range before {
		right, found := after[key]
		if !found {
			changes = append(changes, Change{Type: "DELETED", Kind: left.Kind, QualifiedName: left.QualifiedName, BeforeStableKey: key, BeforePath: left.Path})
			continue
		}
		if left.ContentHash != right.ContentHash || left.Signature != right.Signature {
			changes = append(changes, Change{Type: "MODIFIED", Kind: right.Kind, QualifiedName: right.QualifiedName, BeforeStableKey: key, AfterStableKey: key, BeforePath: left.Path, AfterPath: right.Path, BodyChanged: left.ContentHash != right.ContentHash, SignatureChanged: left.Signature != right.Signature})
		}
	}
	for key, right := range after {
		if _, found := before[key]; !found {
			changes = append(changes, Change{Type: "ADDED", Kind: right.Kind, QualifiedName: right.QualifiedName, AfterStableKey: key, AfterPath: right.Path})
		}
	}
	sort.Slice(changes, func(i, j int) bool { return changes[i].QualifiedName < changes[j].QualifiedName })
	return changes
}

func trace(changes []Change, base, head Snapshot, maxDepth, maxPaths int) []Path {
	result := make([]Path, 0)
	for _, change := range changes {
		graph := head
		seed := change.AfterStableKey
		if change.Type == "DELETED" {
			graph, seed = base, change.BeforeStableKey
		}
		if seed == "" {
			continue
		}
		symbols := symbolMap(graph.Symbols)
		reverse := map[string][]Edge{}
		for _, edge := range graph.Edges {
			if edge.Type == "CALLS" {
				reverse[edge.ToStableKey] = append(reverse[edge.ToStableKey], edge)
			}
		}
		type item struct {
			key      string
			depth    int
			keys     []string
			evidence []map[string]any
			score    float64
		}
		queue := []item{{key: seed, keys: []string{seed}, score: 1}}
		visited := map[string]bool{seed: true}
		for len(queue) > 0 && len(result) < maxPaths {
			current := queue[0]
			queue = queue[1:]
			if current.depth >= maxDepth {
				continue
			}
			for _, edge := range reverse[current.key] {
				caller, found := symbols[edge.FromStableKey]
				if !found || visited[caller.StableKey] {
					continue
				}
				visited[caller.StableKey] = true
				depth := current.depth + 1
				score := round(current.score * edge.Confidence * map[bool]float64{true: 1, false: .55}[depth == 1])
				evidence := append([]map[string]any{{"type": edge.Type, "sourcePath": edge.SourcePath, "sourceLine": edge.SourceLine, "confidence": edge.Confidence}}, current.evidence...)
				keys := append([]string{caller.StableKey}, current.keys...)
				result = append(result, Path{ChangedStableKey: seed, ChangedName: change.QualifiedName, ImpactedStableKey: caller.StableKey, ImpactedName: caller.QualifiedName, ImpactedPath: caller.Path, ImpactedKind: caller.Kind, Depth: depth, Score: score, Keys: keys, Evidence: evidence})
				queue = append(queue, item{key: caller.StableKey, depth: depth, keys: keys, evidence: evidence, score: score})
			}
		}
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].Score == result[j].Score {
			return result[i].ImpactedName < result[j].ImpactedName
		}
		return result[i].Score > result[j].Score
	})
	return result
}

func summarize(changes []Change, paths []Path, base, head Snapshot, maxDepth int) contracts.ImpactSummary {
	impacted := map[string]bool{}
	weight := 0.0
	for _, path := range paths {
		impacted[path.ImpactedStableKey] = true
		weight += path.Score
	}
	exported := 0
	all := append(append([]Symbol{}, base.Symbols...), head.Symbols...)
	changed := map[string]bool{}
	for _, change := range changes {
		changed[change.BeforeStableKey] = true
		changed[change.AfterStableKey] = true
	}
	for _, symbol := range all {
		if symbol.Exported && changed[symbol.StableKey] {
			exported++
		}
	}
	score := min(100, int(math.Round(float64(len(changes)*3+exported*5)+weight*8)))
	level := "low"
	if score >= 60 {
		level = "high"
	} else if score >= 25 {
		level = "medium"
	}
	top := make([]contracts.ImpactPath, 0, min(8, len(paths)))
	for _, path := range paths[:min(8, len(paths))] {
		top = append(top, contracts.ImpactPath{ChangedName: path.ChangedName, ImpactedName: path.ImpactedName, Depth: path.Depth, Score: path.Score})
	}
	return contracts.ImpactSummary{Level: level, Score: score, ChangedSymbols: len(changes), ImpactedSymbols: len(impacted), TopPaths: top, CoverageWarning: fmt.Sprintf("Impact paths are limited to %d changed-file snapshot(s) and depth %d; unchanged callers are not indexed yet.", head.Coverage.IndexedFiles, maxDepth)}
}

func safePath(path string) bool {
	return path != "" && !strings.HasPrefix(path, "/") && !strings.Contains(path, "../") && !strings.Contains(path, `\`) && !strings.ContainsRune(path, '\x00') && !regexp.MustCompile(`^[A-Za-z]:`).MatchString(path)
}
func symbolMap(symbols []Symbol) map[string]Symbol {
	result := map[string]Symbol{}
	for _, symbol := range symbols {
		if symbol.Kind != "file" {
			result[symbol.StableKey] = symbol
		}
	}
	return result
}
func blockEnd(lines []string, start int) int {
	depth, opened := 0, false
	for index := start; index < len(lines); index++ {
		if !opened && index > start+20 {
			return start + 1
		}
		if !opened && strings.Contains(lines[index], ";") {
			return index + 1
		}
		if !opened && index == start && strings.Contains(lines[index], "=") && !strings.Contains(lines[index], "{") {
			return start + 1
		}
		depth += strings.Count(lines[index], "{") - strings.Count(lines[index], "}")
		if strings.Contains(lines[index], "{") {
			opened = true
		}
		if opened && depth <= 0 {
			return index + 1
		}
	}
	if !opened {
		return start + 1
	}
	return min(len(lines), start+200)
}
func controlWord(value string) bool {
	switch value {
	case "if", "for", "switch", "select", "catch", "while", "until", "unless", "when", "with", "match", "func", "function", "fn", "def", "class", "interface", "struct", "enum", "trait", "impl", "return", "new", "delete", "sizeof", "typeof", "super", "this":
		return true
	}
	return false
}
func digest(value []byte) string  { hash := sha256.Sum256(value); return hex.EncodeToString(hash[:]) }
func round(value float64) float64 { return math.Round(value*1000) / 1000 }
func truncate(value string, maximum int) string {
	if len(value) <= maximum {
		return value
	}
	return value[:maximum]
}
func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
func uniqueSymbols(input []Symbol) []Symbol {
	seen := map[string]bool{}
	result := make([]Symbol, 0, len(input))
	for _, value := range input {
		if !seen[value.StableKey] {
			seen[value.StableKey] = true
			result = append(result, value)
		}
	}
	return result
}
func uniqueEdges(input []Edge) []Edge {
	seen := map[string]bool{}
	result := make([]Edge, 0, len(input))
	for _, value := range input {
		key := fmt.Sprintf("%s|%s|%s|%d", value.FromStableKey, value.ToStableKey, value.Type, value.SourceLine)
		if !seen[key] {
			seen[key] = true
			result = append(result, value)
		}
	}
	return result
}
