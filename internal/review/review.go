package review

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/xiaoyixin3/codelens-ai/internal/contracts"
	"github.com/xiaoyixin3/codelens-ai/internal/githubapp"
	"github.com/xiaoyixin3/codelens-ai/internal/security"
	"github.com/xiaoyixin3/codelens-ai/internal/store"
)

type Repository interface {
	GetReviewRun(context.Context, string) (contracts.ReviewRun, error)
	UpdateReviewRun(context.Context, string, string, json.RawMessage, string, string) error
	GetPublication(context.Context, string) (store.Publication, error)
	SavePublication(context.Context, store.Publication) error
	SaveFindings(context.Context, string, []contracts.Finding) error
}

type GitHub interface {
	GetPullRequest(context.Context, int64, string, string, int) (githubapp.PullRequest, error)
	CurrentHead(context.Context, int64, string, string, int) (string, error)
	StartCheck(context.Context, int64, string, string, string, *int64) (int64, error)
	CompleteCheck(context.Context, int64, string, string, int64, string, string, string, []githubapp.Annotation) error
	UpsertSummaryComment(context.Context, int64, string, string, int, string, *int64) (int64, error)
}

type Engine struct {
	store         Repository
	github        GitHub
	maxFiles      int
	maxPatchChars int
	maxComments   int
}

func New(repository Repository, github GitHub, maxFiles, maxPatchChars, maxComments int) *Engine {
	return &Engine{store: repository, github: github, maxFiles: maxFiles, maxPatchChars: maxPatchChars, maxComments: maxComments}
}

func (e *Engine) Execute(ctx context.Context, job contracts.ReviewJob) error {
	run, err := e.store.GetReviewRun(ctx, job.ReviewRunID)
	if err != nil {
		return err
	}
	if run.Status == "completed" || run.Status == "stale" || run.Status == "skipped" {
		return nil
	}
	if err := e.store.UpdateReviewRun(ctx, run.ID, "in_progress", nil, "", ""); err != nil {
		return err
	}

	publication, err := e.store.GetPublication(ctx, run.ID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	var existingCheck *int64
	var existingComment *int64
	if err == nil {
		existingCheck = publication.CheckRunID
		existingComment = publication.SummaryCommentID
	}

	currentHead, err := e.github.CurrentHead(ctx, job.InstallationID, job.Owner, job.Repo, job.PullNumber)
	if err != nil {
		return err
	}
	if currentHead != job.HeadSHA {
		return e.store.UpdateReviewRun(ctx, run.ID, "stale", nil, "", "")
	}
	checkID, err := e.github.StartCheck(ctx, job.InstallationID, job.Owner, job.Repo, job.HeadSHA, existingCheck)
	if err != nil {
		return err
	}
	if err := e.store.SavePublication(ctx, store.Publication{ReviewRunID: run.ID, HeadSHA: job.HeadSHA, CheckRunID: &checkID, SummaryCommentID: existingComment}); err != nil {
		return err
	}

	pull, err := e.github.GetPullRequest(ctx, job.InstallationID, job.Owner, job.Repo, job.PullNumber)
	if err != nil {
		return err
	}
	if pull.HeadSHA != job.HeadSHA {
		_ = e.github.CompleteCheck(ctx, job.InstallationID, job.Owner, job.Repo, checkID, "stale", "CodeLens review superseded", "A newer PR head SHA arrived before analysis completed.", nil)
		return e.store.UpdateReviewRun(ctx, run.ID, "stale", nil, "", "")
	}

	summary := Summarize(pull, e.maxFiles)
	findings := ReviewRisk(pull, e.maxPatchChars, e.maxComments)
	if len(findings) > 0 {
		published := 0
		for _, finding := range findings {
			if finding.Publishable {
				published++
			}
		}
		summary.Findings = &contracts.FindingSummary{Candidates: len(findings), Verified: len(findings), Published: published, Items: findings}
		if hasSeverity(findings, "critical", "high") {
			summary.RiskLevel = "high"
		} else if summary.RiskLevel == "low" && hasSeverity(findings, "medium") {
			summary.RiskLevel = "medium"
		}
		if err := e.store.SaveFindings(ctx, run.ID, findings); err != nil {
			return err
		}
	}

	publishHead, err := e.github.CurrentHead(ctx, job.InstallationID, job.Owner, job.Repo, job.PullNumber)
	if err != nil {
		return err
	}
	if publishHead != job.HeadSHA {
		_ = e.github.CompleteCheck(ctx, job.InstallationID, job.Owner, job.Repo, checkID, "stale", "CodeLens review superseded", "A newer PR head SHA arrived before publishing; no outdated result was posted.", nil)
		return e.store.UpdateReviewRun(ctx, run.ID, "stale", nil, "", "")
	}

	markdown := RenderMarkdown(summary)
	annotations := make([]githubapp.Annotation, 0, e.maxComments)
	for _, finding := range findings {
		if !finding.Publishable || finding.Evidence == nil || len(annotations) >= e.maxComments {
			continue
		}
		level := "notice"
		if finding.Severity == "critical" || finding.Severity == "high" {
			level = "failure"
		} else if finding.Severity == "medium" {
			level = "warning"
		}
		annotations = append(annotations, githubapp.Annotation{
			Path: finding.Path, StartLine: finding.Line, EndLine: finding.Line, Level: level,
			Title:      truncate(strings.ToUpper(finding.Severity)+": "+finding.Title, 255),
			Message:    truncate(finding.Claim+"\n\nSuggestion: "+finding.Suggestion, 64000),
			RawDetails: fmt.Sprintf("Confidence: %.2f\nVerification: %s", finding.Confidence, finding.Verification),
		})
	}
	conclusion := "success"
	if summary.RiskLevel == "high" {
		conclusion = "neutral"
	}
	if err := e.github.CompleteCheck(ctx, job.InstallationID, job.Owner, job.Repo, checkID, conclusion, "CodeLens review: "+summary.RiskLevel+" risk", markdown, annotations); err != nil {
		return err
	}
	commentID, err := e.github.UpsertSummaryComment(ctx, job.InstallationID, job.Owner, job.Repo, job.PullNumber, markdown, existingComment)
	if err != nil {
		return err
	}
	if err := e.store.SavePublication(ctx, store.Publication{ReviewRunID: run.ID, HeadSHA: job.HeadSHA, CheckRunID: &checkID, SummaryCommentID: &commentID}); err != nil {
		return err
	}
	payload, err := json.Marshal(summary)
	if err != nil {
		return err
	}
	return e.store.UpdateReviewRun(ctx, run.ID, "completed", payload, "", "")
}

func (e *Engine) Fail(ctx context.Context, job contracts.ReviewJob, detail string) error {
	detail = truncate(security.Redact(detail), 2000)
	publication, err := e.store.GetPublication(ctx, job.ReviewRunID)
	if err == nil && publication.CheckRunID != nil {
		_ = e.github.CompleteCheck(ctx, job.InstallationID, job.Owner, job.Repo, *publication.CheckRunID, "failure", "CodeLens review failed", "The review could not be completed after three attempts.", nil)
	}
	return e.store.UpdateReviewRun(ctx, job.ReviewRunID, "failed", nil, "WORKER_FAILED", detail)
}

var (
	highRiskPath = regexp.MustCompile(`(?i)(^|/)(auth|payment|billing|security|migration|database|permission)(/|\.|$)`)
	testPath     = regexp.MustCompile(`(?i)(^|/)(__tests__|test|tests|spec)(/|\.|$)|(^|/)[^/]*\.(test|spec)\.[jt]sx?$`)
)

func Summarize(pull githubapp.PullRequest, maxFiles int) contracts.ChangeSummary {
	files := pull.Files
	truncated := false
	if len(files) > maxFiles {
		files = files[:maxFiles]
		truncated = true
	}
	additions, deletions, highRisk, hasTests, sourceFiles := 0, 0, []string{}, false, false
	resultFiles := make([]contracts.FileSummary, 0, min(len(files), 12))
	for _, file := range files {
		additions += file.Additions
		deletions += file.Deletions
		if highRiskPath.MatchString(file.Path) {
			highRisk = append(highRisk, file.Path)
		}
		if testPath.MatchString(file.Path) {
			hasTests = true
		}
		if regexp.MustCompile(`\.[jt]sx?$`).MatchString(file.Path) {
			sourceFiles = true
		}
		if len(resultFiles) < 12 {
			resultFiles = append(resultFiles, contracts.FileSummary{Path: file.Path, Change: fmt.Sprintf("%s; +%d/-%d", file.Status, file.Additions, file.Deletions)})
		}
	}
	reasons := []string{}
	if len(highRisk) > 0 {
		reasons = append(reasons, "Touches sensitive areas: "+strings.Join(highRisk[:min(len(highRisk), 5)], ", ")+".")
	}
	if !hasTests && sourceFiles {
		reasons = append(reasons, "No test file is included in the reviewed change set.")
	}
	large := additions+deletions > 1000
	if large {
		reasons = append(reasons, "The change exceeds 1,000 modified lines and deserves staged review.")
	}
	if truncated {
		reasons = append(reasons, "The file budget was reached; some files were not reviewed.")
	}
	risk := "low"
	if len(highRisk) > 0 || large {
		risk = "high"
	} else if len(reasons) > 0 {
		risk = "medium"
	}
	intent := pull.Title
	if strings.TrimSpace(intent) == "" {
		intent = "Review the proposed code change"
	}
	return contracts.ChangeSummary{
		Intent: intent, Overview: fmt.Sprintf("This PR changes %d file(s) with +%d/-%d lines in the reviewed scope.", len(pull.Files), additions, deletions),
		Files: resultFiles, RiskLevel: risk, RiskReasons: reasons,
		Coverage: contracts.Coverage{ReviewedFiles: len(files), TotalFiles: len(pull.Files), Truncated: truncated},
	}
}

type rule struct {
	id, pattern, category, severity, title, claim, suggestion, verification string
	confidence                                                              float64
	compiled                                                                *regexp.Regexp
}

var rules = []rule{
	makeRule("security/no-eval", `\beval\s*\(`, "security", "high", .98, "Dynamic code execution", "This added line executes a string as code, which can turn untrusted input into code execution.", "Replace eval with an explicit parser or a fixed dispatch table.", "Trace the value passed to eval and confirm whether any user-controlled data can reach it."),
	makeRule("security/no-function-constructor", `\bnew\s+Function\s*\(`, "security", "high", .97, "Dynamic Function constructor", "This line constructs executable code at runtime and creates an injection surface.", "Use a fixed function implementation or a constrained expression parser.", "Inspect every argument to the Function constructor for external or persisted input."),
	makeRule("concurrency/no-async-foreach", `\.forEach\s*\(\s*async\b`, "concurrency", "high", .96, "Async forEach is not awaited", "Promises created by forEach are not awaited, so the surrounding operation may finish before this work completes.", "Use await Promise.all(items.map(async ...)) or a for...of loop with await.", "Add a test proving the outer function waits for every iteration and propagates failures."),
	makeRule("correctness/no-empty-catch", `\bcatch\s*(\([^)]*\))?\s*\{\s*\}`, "correctness", "medium", .94, "Exception is silently swallowed", "This empty catch block hides failures and can leave the operation in an unknown state.", "Handle the expected error explicitly or rethrow it with useful context.", "Exercise the failing branch and assert the caller receives or records the failure."),
}

var (
	secretLine       = regexp.MustCompile(`(?i)\b(password|passwd|api[_-]?key|secret|access[_-]?token)\b\s*[:=]\s*['"][^'"]{8,}['"]`)
	sqlInterpolation = regexp.MustCompile("(?i)\\b(query|execute|raw)\\s*\\(\\s*`[^`]*\\$\\{")
	remoteCall       = regexp.MustCompile(`(?i)\b(fetch|axios\.(get|post|put|patch|delete)|http\.(get|request))\s*\(`)
	transaction      = regexp.MustCompile(`(?i)\b(transaction|beginTransaction|@Transactional)\b`)
)

func makeRule(id, pattern, category, severity string, confidence float64, title, claim, suggestion, verification string) rule {
	return rule{id: id, pattern: pattern, category: category, severity: severity, confidence: confidence, title: title, claim: claim, suggestion: suggestion, verification: verification, compiled: regexp.MustCompile(pattern)}
}

type diffLine struct {
	path, content string
	line          int
}

func ReviewRisk(pull githubapp.PullRequest, maxPatchChars, maxComments int) []contracts.Finding {
	lines := []diffLine{}
	remaining := maxPatchChars
	for _, file := range pull.Files {
		if remaining <= 0 {
			break
		}
		patch := file.Patch
		if len(patch) > remaining {
			patch = patch[:remaining]
		}
		remaining -= len(patch)
		lines = append(lines, parseAddedLines(file.Path, patch)...)
	}
	findings := []contracts.Finding{}
	for _, line := range lines {
		for _, candidate := range rules {
			if candidate.compiled.MatchString(line.content) {
				findings = append(findings, findingFromRule(candidate, line))
			}
		}
		if !testPath.MatchString(line.path) && secretLine.MatchString(line.content) {
			findings = append(findings, findingFromRule(makeRule("security/no-hardcoded-secret", ".", "security", "critical", .93, "Possible hardcoded secret", "This added line appears to embed a credential in source code.", "Load the secret from the deployment secret store and rotate the exposed value.", "Confirm whether the value is live, then inspect repository history and rotate it if necessary."), line))
		}
		if sqlInterpolation.MatchString(line.content) {
			findings = append(findings, findingFromRule(makeRule("security/no-interpolated-sql", ".", "security", "high", .91, "Interpolated SQL query", "A template expression is inserted into a SQL execution call and may bypass parameterization.", "Use the database client parameter binding API for every dynamic value.", "Trace each interpolated value and run an injection-focused test against the query boundary."), line))
		}
	}
	for _, line := range lines {
		if !remoteCall.MatchString(line.content) {
			continue
		}
		for _, nearby := range lines {
			if nearby.path == line.path && abs(nearby.line-line.line) <= 8 && transaction.MatchString(nearby.content) {
				findings = append(findings, findingFromRule(makeRule("data-integrity/no-remote-call-in-transaction", ".", "data_integrity", "high", .87, "Remote call inside transaction scope", "This remote call appears inside newly added transaction code and can hold locks while waiting on the network.", "Move the remote call outside the database transaction or use an outbox/saga boundary.", "Confirm the transaction lifetime and test timeout/retry behavior while the remote dependency is unavailable."), line))
				break
			}
		}
	}
	sort.SliceStable(findings, func(i, j int) bool { return severityRank(findings[i].Severity) > severityRank(findings[j].Severity) })
	for index := range findings {
		findings[index].Publishable = index < maxComments
	}
	return findings
}

func parseAddedLines(path, patch string) []diffLine {
	hunk := regexp.MustCompile(`^@@ -\d+(,\d+)? \+(\d+)(,\d+)? @@`)
	right, active := 0, false
	result := []diffLine{}
	for _, raw := range strings.Split(patch, "\n") {
		if match := hunk.FindStringSubmatch(raw); match != nil {
			fmt.Sscanf(match[2], "%d", &right)
			active = true
			continue
		}
		if !active || strings.HasPrefix(raw, `\ No newline`) {
			continue
		}
		if strings.HasPrefix(raw, "+") && !strings.HasPrefix(raw, "+++") {
			result = append(result, diffLine{path: path, content: strings.TrimPrefix(raw, "+"), line: right})
			right++
		} else if strings.HasPrefix(raw, " ") {
			right++
		}
	}
	return result
}

func findingFromRule(candidate rule, line diffLine) contracts.Finding {
	fingerprintSource := fmt.Sprintf("%s|%s|%d|%s", candidate.id, line.path, line.line, candidate.claim)
	fingerprint := sha256.Sum256([]byte(fingerprintSource))
	excerpt := strings.TrimSpace(line.content)
	excerptDigest := sha256.Sum256([]byte(excerpt))
	return contracts.Finding{
		Fingerprint: hex.EncodeToString(fingerprint[:]), Source: "deterministic", RuleID: candidate.id,
		Category: candidate.category, Severity: candidate.severity, Confidence: candidate.confidence,
		Title: candidate.title, Claim: candidate.claim, Suggestion: candidate.suggestion, Verification: candidate.verification,
		Path: line.path, Line: line.line, Excerpt: excerpt, Status: "verified", Publishable: true,
		Evidence: &contracts.FindingEvidence{Path: line.path, StartLine: line.line, EndLine: line.line, Side: "RIGHT", ExcerptHash: hex.EncodeToString(excerptDigest[:]), EvidenceType: "diff"},
	}
}

func RenderMarkdown(summary contracts.ChangeSummary) string {
	var builder strings.Builder
	fmt.Fprintf(&builder, "## CodeLens AI Review\n\n**Risk: %s**\n\n%s\n\n### Change intent\n\n%s\n\n### Files\n\n", strings.ToUpper(summary.RiskLevel), summary.Overview, summary.Intent)
	if len(summary.Files) == 0 {
		builder.WriteString("- No reviewable files.\n")
	}
	for _, file := range summary.Files {
		fmt.Fprintf(&builder, "- `%s` — %s\n", file.Path, file.Change)
	}
	builder.WriteString("\n### Risk signals\n\n")
	if len(summary.RiskReasons) == 0 {
		builder.WriteString("- No material risk signal detected in the current scope.\n")
	}
	for _, reason := range summary.RiskReasons {
		fmt.Fprintf(&builder, "- %s\n", reason)
	}
	if summary.Findings != nil {
		builder.WriteString("\n### Verified findings\n\n")
		if summary.Findings.Published == 0 {
			builder.WriteString("No candidate passed evidence verification and the publish threshold.\n")
		}
		for _, finding := range summary.Findings.Items {
			if finding.Publishable {
				fmt.Fprintf(&builder, "- **%s** `%s:%d` — %s (`%s`)\n", strings.ToUpper(finding.Severity), finding.Path, finding.Line, finding.Title, finding.Fingerprint[:12])
			}
		}
	}
	fmt.Fprintf(&builder, "\n> Coverage: %d/%d files reviewed%s.\n", summary.Coverage.ReviewedFiles, summary.Coverage.TotalFiles, map[bool]string{true: " (truncated)", false: ""}[summary.Coverage.Truncated])
	return builder.String()
}

func hasSeverity(findings []contracts.Finding, values ...string) bool {
	for _, f := range findings {
		for _, value := range values {
			if f.Publishable && f.Severity == value {
				return true
			}
		}
	}
	return false
}
func severityRank(value string) int {
	return map[string]int{"critical": 4, "high": 3, "medium": 2, "low": 1}[value]
}
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
func abs(value int) int {
	if value < 0 {
		return -value
	}
	return value
}
