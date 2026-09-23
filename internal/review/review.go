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
	"github.com/xiaoyixin3/codelens-ai/internal/intelligence"
	"github.com/xiaoyixin3/codelens-ai/internal/llm"
	"github.com/xiaoyixin3/codelens-ai/internal/policy"
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
	policy        *policy.Loader
	intelligence  *intelligence.Analyzer
	llm           *llm.Client
}

func New(repository Repository, github GitHub, maxFiles, maxPatchChars, maxComments int, components ...any) *Engine {
	engine := &Engine{store: repository, github: github, maxFiles: maxFiles, maxPatchChars: maxPatchChars, maxComments: maxComments}
	for _, component := range components {
		switch value := component.(type) {
		case *policy.Loader:
			engine.policy = value
		case *intelligence.Analyzer:
			engine.intelligence = value
		case *llm.Client:
			engine.llm = value
		}
	}
	return engine
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

	originalFileCount := len(pull.Files)
	configured := policy.Policy{Language: "en", MaxInlineComments: e.maxComments, Include: []string{"**/*"}, MinimumConfidence: map[string]float64{}}
	if e.policy != nil {
		configured, err = e.policy.Load(ctx, run.RepositoryID, job.InstallationID, job.Owner, job.Repo, job.HeadSHA)
		if err != nil {
			return err
		}
		updater, ok := e.store.(interface {
			UpdateReviewRunConfig(context.Context, string, string) error
		})
		if !ok {
			return errors.New("review repository does not support policy configuration hashes")
		}
		if err := updater.UpdateReviewRunConfig(ctx, run.ID, configured.Hash); err != nil {
			return err
		}
		pull.Files = policy.FilterFiles(pull.Files, configured)
	}
	summary := SummarizeWithLanguage(pull, e.maxFiles, configured.Language)
	if e.llm != nil && e.llm.Enabled() {
		if generated, generateErr := e.llm.GenerateSummary(ctx, run.ID, pull, configured, e.maxFiles, e.maxPatchChars); generateErr == nil {
			summary = generated
		}
	}
	if e.intelligence != nil {
		analysis, analysisErr := e.intelligence.Analyze(ctx, run.ID, run.RepositoryID, job, pull)
		if analysisErr != nil {
			return analysisErr
		}
		summary.Impact = &analysis.Summary
		if riskRank(analysis.Summary.Level) > riskRank(summary.RiskLevel) {
			summary.RiskLevel = analysis.Summary.Level
		}
	}
	commentLimit := configured.MaxInlineComments
	if commentLimit > e.maxComments {
		commentLimit = e.maxComments
	}
	findings := ReviewRisk(pull, e.maxPatchChars, 1000)
	if e.llm != nil && e.llm.Enabled() {
		if generated, reviewErr := e.llm.ReviewRisk(ctx, run.ID, pull, configured, e.maxFiles, e.maxPatchChars); reviewErr == nil {
			findings = mergeFindings(findings, generated)
		}
	}
	sort.SliceStable(findings, func(i, j int) bool { return severityRank(findings[i].Severity) > severityRank(findings[j].Severity) })
	for index := range findings {
		findings[index].Publishable = index < commentLimit
		threshold := defaultConfidence(findings[index].Severity)
		if override, exists := configured.MinimumConfidence[findings[index].Severity]; exists {
			threshold = override
		}
		if findings[index].Confidence < threshold {
			findings[index].Publishable = false
			findings[index].Status = "rejected"
		}
	}
	if len(findings) > 0 {
		published, verified, rejected := 0, 0, 0
		for _, finding := range findings {
			if finding.Publishable {
				published++
			}
			if finding.Status == "verified" {
				verified++
			} else {
				rejected++
			}
		}
		summary.Findings = &contracts.FindingSummary{Candidates: len(findings), Verified: verified, Published: published, Rejected: rejected, Items: findings}
		if hasSeverity(findings, "critical", "high") {
			summary.RiskLevel = "high"
		} else if summary.RiskLevel == "low" && hasSeverity(findings, "medium") {
			summary.RiskLevel = "medium"
		}
		if err := e.store.SaveFindings(ctx, run.ID, findings); err != nil {
			return err
		}
	}
	if e.policy != nil {
		summary.Policy = &contracts.PolicySummary{ConfigHash: configured.Hash, Rules: len(configured.Rules), IncludedFiles: len(pull.Files), ExcludedFiles: originalFileCount - len(pull.Files), Blocking: configured.Blocking, Language: configured.Language, Warnings: configured.Warnings}
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
	if summary.RiskLevel == "high" && configured.Blocking {
		conclusion = "failure"
	} else if summary.RiskLevel == "high" {
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
	testPath     = regexp.MustCompile(`(?i)(^|/)(__tests__|test|tests|spec)(/|\.|$)|(^|/)([^/]*\.(test|spec)\.[jt]sx?|[^/]*_test\.go)$`)
)

func Summarize(pull githubapp.PullRequest, maxFiles int) contracts.ChangeSummary {
	return SummarizeWithLanguage(pull, maxFiles, "en")
}

func SummarizeWithLanguage(pull githubapp.PullRequest, maxFiles int, language string) contracts.ChangeSummary {
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
		if regexp.MustCompile(`(?i)\.(?:[jt]sx?|go|java|kt|kts|py|rb|php|cs|c|cc|cpp|h|hpp|rs|swift)$`).MatchString(file.Path) {
			sourceFiles = true
		}
		if len(resultFiles) < 12 {
			resultFiles = append(resultFiles, contracts.FileSummary{Path: file.Path, Change: fmt.Sprintf("%s; +%d/-%d", file.Status, file.Additions, file.Deletions)})
		}
	}
	reasons := []string{}
	if len(highRisk) > 0 {
		if language == "zh" {
			reasons = append(reasons, "涉及敏感区域："+strings.Join(highRisk[:min(len(highRisk), 5)], ", ")+"。")
		} else {
			reasons = append(reasons, "Touches sensitive areas: "+strings.Join(highRisk[:min(len(highRisk), 5)], ", ")+".")
		}
	}
	if !hasTests && sourceFiles {
		if language == "zh" {
			reasons = append(reasons, "本次审核范围内没有测试文件。")
		} else {
			reasons = append(reasons, "No test file is included in the reviewed change set.")
		}
	}
	large := additions+deletions > 1000
	if large {
		if language == "zh" {
			reasons = append(reasons, "变更超过 1,000 行，建议分阶段审核。")
		} else {
			reasons = append(reasons, "The change exceeds 1,000 modified lines and deserves staged review.")
		}
	}
	if truncated {
		if language == "zh" {
			reasons = append(reasons, "已达到文件预算，部分文件未审核。")
		} else {
			reasons = append(reasons, "The file budget was reached; some files were not reviewed.")
		}
	}
	risk := "low"
	if len(highRisk) > 0 || large {
		risk = "high"
	} else if len(reasons) > 0 {
		risk = "medium"
	}
	intent := pull.Title
	if strings.TrimSpace(intent) == "" {
		if language == "zh" {
			intent = "审核拟议代码变更"
		} else {
			intent = "Review the proposed code change"
		}
	}
	overview := fmt.Sprintf("This PR changes %d file(s) with +%d/-%d lines in the reviewed scope.", len(pull.Files), additions, deletions)
	if language == "zh" {
		overview = fmt.Sprintf("本 PR 在审核范围内变更 %d 个文件，共 +%d/-%d 行。", len(pull.Files), additions, deletions)
	}
	return contracts.ChangeSummary{
		Intent: intent, Overview: overview,
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

var goRules = []rule{
	makeRule("go/security/insecure-tls", `\bInsecureSkipVerify\s*:\s*true\b`, "security", "critical", .99, "TLS certificate verification disabled", "This TLS configuration accepts certificates without verifying their chain or hostname, enabling man-in-the-middle attacks.", "Remove InsecureSkipVerify and configure trusted roots or explicit certificate pinning.", "Connect through an untrusted certificate and confirm the client rejects the connection."),
	makeRule("go/security/world-writable-permission", `\b(os\.)?(Chmod|Mkdir|MkdirAll|OpenFile|WriteFile)\s*\([^\n]*,\s*0?(666|777)\s*\)`, "security", "high", .96, "World-writable file permission", "This operation creates or changes a filesystem object so every local user can modify it.", "Use the minimum required permission, normally 0600 for sensitive files or 0750/0755 for directories.", "Inspect the resulting mode after applying the process umask and verify untrusted users cannot write it."),
	makeRule("go/correctness/discarded-context-cancel", `,\s*_\s*:?=\s*context\.With(Cancel|Timeout|Deadline)\s*\(`, "correctness", "high", .98, "Context cancel function discarded", "Discarding the cancel function can retain timers, child contexts, and related resources until the parent ends.", "Keep the returned cancel function and call it with defer as soon as the context is created.", "Exercise repeated calls and confirm timers and goroutines are released promptly."),
	makeRule("go/performance/default-http-client-no-timeout", `\bhttp\.(Get|Post|PostForm)\s*\(`, "performance", "medium", .90, "HTTP request has no client timeout", "The package-level HTTP helper uses the default client without a total timeout, so a stalled peer can hold this operation indefinitely.", "Use an http.Client with an explicit Timeout and a request carrying a bounded context.", "Test against a server that accepts the connection but never responds and assert the request terminates within the budget."),
	makeRule("go/security/formatted-sql", `\b(Query|QueryRow|Exec|Raw)\w*\s*\(\s*fmt\.Sprintf\s*\(`, "security", "high", .95, "SQL built with fmt.Sprintf", "Formatting values directly into a SQL statement can turn untrusted input into executable SQL.", "Pass dynamic values through the driver parameter-binding API and keep the query text static.", "Trace every formatted value and add an injection-focused test at the database boundary."),
	makeRule("go/security/dynamic-shell-command", `(?i)\bexec\.Command(Context)?\s*\([^\n]*("(sh|bash|cmd|powershell)(\.exe)?"|'(sh|bash|cmd|powershell)(\.exe)?')[^\n]*("-c"|'/c'|"/c")[^\n]*(fmt\.Sprintf|\+|args?\b)`, "security", "critical", .95, "Dynamic command passed through a shell", "A dynamically constructed command is executed by a shell, so metacharacters in external input can change the command being run.", "Invoke the target executable directly and pass each argument separately after validation.", "Test shell metacharacters in every dynamic value and confirm they are treated only as data."),
	makeRule("go/correctness/ignored-decode-error", `^\s*(json|xml|yaml)\.Unmarshal\s*\(`, "correctness", "high", .93, "Decode error is ignored", "The decoder result is discarded, so malformed input can leave a partially populated value in use.", "Check and return or handle the decode error before using the destination value.", "Pass malformed input and assert the operation fails without using partial data."),
	makeRule("go/correctness/ignored-server-error", `^\s*http\.(ListenAndServe|ListenAndServeTLS|Serve)\s*\(`, "correctness", "medium", .91, "HTTP server failure is ignored", "The server start or serve error is discarded, so bind failures and unexpected shutdowns can leave the process appearing healthy.", "Check the returned error and propagate or log it, while treating http.ErrServerClosed as the expected shutdown case.", "Start a second server on the same address and assert the process reports the bind failure."),
}

var (
	secretLine       = regexp.MustCompile(`(?i)\b(password|passwd|api[_-]?key|secret|access[_-]?token)\b\s*[:=]\s*['"][^'"]{8,}['"]`)
	sqlInterpolation = regexp.MustCompile("(?i)\\b(query|execute|raw)\\s*\\(\\s*`[^`]*\\$\\{")
	remoteCall       = regexp.MustCompile(`(?i)\b(fetch|axios\.(get|post|put|patch|delete)|http\.(get|request))\s*\(`)
	transaction      = regexp.MustCompile(`(?i)\b(transaction|beginTransaction|@Transactional)\b`)
	bodyClose        = regexp.MustCompile(`\bdefer\s+([A-Za-z_]\w*)\.Body\.Close\s*\(\s*\)`)
	resourceClose    = regexp.MustCompile(`\bdefer\s+([A-Za-z_]\w*)\.Close\s*\(\s*\)`)
	errorGuard       = regexp.MustCompile(`\bif\s+err\s*!=\s*nil\b`)
)

func makeRule(id, pattern, category, severity string, confidence float64, title, claim, suggestion, verification string) rule {
	return rule{id: id, pattern: pattern, category: category, severity: severity, confidence: confidence, title: title, claim: claim, suggestion: suggestion, verification: verification, compiled: regexp.MustCompile(pattern)}
}

type diffLine struct {
	path, content string
	line          int
	added         bool
}

func ReviewRisk(pull githubapp.PullRequest, maxPatchChars, maxComments int) []contracts.Finding {
	lines := []diffLine{}
	rightLines := []diffLine{}
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
		parsed := parseRightLines(file.Path, patch)
		rightLines = append(rightLines, parsed...)
		for _, line := range parsed {
			if line.added {
				lines = append(lines, line)
			}
		}
	}
	findings := []contracts.Finding{}
	for _, line := range lines {
		for _, candidate := range rules {
			if candidate.compiled.MatchString(line.content) {
				findings = append(findings, findingFromRule(candidate, line))
			}
		}
		if strings.HasSuffix(strings.ToLower(line.path), ".go") && !testPath.MatchString(line.path) {
			for _, candidate := range goRules {
				if candidate.compiled.MatchString(line.content) {
					findings = append(findings, findingFromRule(candidate, line))
				}
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
		if !strings.HasSuffix(strings.ToLower(line.path), ".go") || testPath.MatchString(line.path) {
			continue
		}
		match := bodyClose.FindStringSubmatch(line.content)
		if match == nil {
			continue
		}
		variable := regexp.QuoteMeta(match[1])
		assignment := regexp.MustCompile(`\b` + variable + `\s*,\s*err\s*:?=`)
		assignmentLine := 0
		guarded := false
		for _, nearby := range rightLines {
			if nearby.path != line.path || nearby.line >= line.line || nearby.line < line.line-6 {
				continue
			}
			if assignment.MatchString(nearby.content) {
				assignmentLine = nearby.line
				guarded = false
				continue
			}
			if assignmentLine > 0 && nearby.line > assignmentLine && errorGuard.MatchString(nearby.content) {
				guarded = true
			}
		}
		if assignmentLine > 0 && !guarded {
			findings = append(findings, findingFromRule(makeRule("go/correctness/response-close-before-error-check", ".", "correctness", "high", .97, "Response body closed before checking request error", "If the request fails, the response can be nil and this deferred Body.Close call will panic.", "Check err immediately after the request and only defer Body.Close after confirming the response is non-nil.", "Force the request to fail before receiving a response and confirm the function returns the error without panicking."), line))
		}
	}
	for _, line := range lines {
		if !strings.HasSuffix(strings.ToLower(line.path), ".go") || testPath.MatchString(line.path) || strings.Contains(line.content, ".Body.Close") {
			continue
		}
		match := resourceClose.FindStringSubmatch(line.content)
		if match == nil {
			continue
		}
		variable := regexp.QuoteMeta(match[1])
		assignment := regexp.MustCompile(`\b` + variable + `\s*,\s*err\s*:?=`)
		assignmentLine := 0
		guarded := false
		for _, nearby := range rightLines {
			if nearby.path != line.path || nearby.line >= line.line || nearby.line < line.line-6 {
				continue
			}
			if assignment.MatchString(nearby.content) {
				assignmentLine = nearby.line
				guarded = false
				continue
			}
			if assignmentLine > 0 && nearby.line > assignmentLine && errorGuard.MatchString(nearby.content) {
				guarded = true
			}
		}
		if assignmentLine > 0 && !guarded {
			findings = append(findings, findingFromRule(makeRule("go/correctness/resource-close-before-error-check", ".", "correctness", "high", .96, "Resource closed before checking open error", "If resource creation fails, this deferred Close call can dereference a nil resource and panic.", "Check err immediately after opening the resource and only defer Close after confirming it is valid.", "Force resource creation to fail and confirm the function returns the error without panicking."), line))
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
	all := parseRightLines(path, patch)
	result := make([]diffLine, 0, len(all))
	for _, line := range all {
		if line.added {
			result = append(result, line)
		}
	}
	return result
}

func parseRightLines(path, patch string) []diffLine {
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
			result = append(result, diffLine{path: path, content: strings.TrimPrefix(raw, "+"), line: right, added: true})
			right++
		} else if strings.HasPrefix(raw, " ") {
			result = append(result, diffLine{path: path, content: strings.TrimPrefix(raw, " "), line: right})
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
	if summary.Impact != nil {
		fmt.Fprintf(&builder, "\n### Impact analysis\n\n- Blast radius: **%s** (%d/100)\n- Changed symbols: %d; impacted symbols: %d\n", strings.ToUpper(summary.Impact.Level), summary.Impact.Score, summary.Impact.ChangedSymbols, summary.Impact.ImpactedSymbols)
		for _, path := range summary.Impact.TopPaths {
			fmt.Fprintf(&builder, "- `%s` → `%s` (depth %d, score %.3f)\n", path.ChangedName, path.ImpactedName, path.Depth, path.Score)
		}
		fmt.Fprintf(&builder, "\n> %s\n", summary.Impact.CoverageWarning)
	}
	if summary.Policy != nil {
		fmt.Fprintf(&builder, "\n### Repository policy\n\n- Rules: %d; included files: %d; excluded files: %d; blocking: %t; language: %s\n", summary.Policy.Rules, summary.Policy.IncludedFiles, summary.Policy.ExcludedFiles, summary.Policy.Blocking, summary.Policy.Language)
		for _, warning := range summary.Policy.Warnings {
			fmt.Fprintf(&builder, "- Warning: %s\n", warning)
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
func riskRank(value string) int { return map[string]int{"high": 3, "medium": 2, "low": 1}[value] }
func defaultConfidence(value string) float64 {
	return map[string]float64{"critical": .85, "high": .85, "medium": .8, "low": 1.01}[value]
}
func mergeFindings(groups ...[]contracts.Finding) []contracts.Finding {
	seen := map[string]bool{}
	result := []contracts.Finding{}
	for _, group := range groups {
		for _, finding := range group {
			if seen[finding.Fingerprint] {
				continue
			}
			seen[finding.Fingerprint] = true
			result = append(result, finding)
		}
	}
	return result
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
