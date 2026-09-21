package contracts

import "encoding/json"

const (
	PipelineVersion   = "v1.0.0-beta.1"
	DefaultConfigHash = "default-v1"
)

type ReviewJob struct {
	ReviewRunID    string `json:"reviewRunId"`
	InstallationID int64  `json:"installationId"`
	Owner          string `json:"owner"`
	Repo           string `json:"repo"`
	PullNumber     int    `json:"pullNumber"`
	BaseSHA        string `json:"baseSha"`
	HeadSHA        string `json:"headSha"`
}

func (j ReviewJob) Valid() bool {
	return j.ReviewRunID != "" && j.InstallationID > 0 && j.Owner != "" && j.Repo != "" && j.PullNumber > 0 && len(j.BaseSHA) >= 7 && len(j.HeadSHA) >= 7
}

type ReviewRun struct {
	ID              string          `json:"id"`
	RepositoryID    int64           `json:"repositoryId"`
	PullNumber      int             `json:"pullNumber"`
	BaseSHA         string          `json:"baseSha"`
	HeadSHA         string          `json:"headSha"`
	Status          string          `json:"status"`
	PipelineVersion string          `json:"pipelineVersion"`
	ConfigHash      string          `json:"configHash"`
	Trigger         string          `json:"trigger"`
	RequestKey      string          `json:"requestKey"`
	Summary         json.RawMessage `json:"summary,omitempty"`
}

type ChangeSummary struct {
	Intent      string          `json:"intent"`
	Overview    string          `json:"overview"`
	Files       []FileSummary   `json:"files"`
	RiskLevel   string          `json:"riskLevel"`
	RiskReasons []string        `json:"riskReasons"`
	Coverage    Coverage        `json:"coverage"`
	Findings    *FindingSummary `json:"findings,omitempty"`
}

type FileSummary struct {
	Path   string `json:"path"`
	Change string `json:"change"`
}
type Coverage struct {
	ReviewedFiles int  `json:"reviewedFiles"`
	TotalFiles    int  `json:"totalFiles"`
	Truncated     bool `json:"truncated"`
}

type Finding struct {
	Fingerprint  string           `json:"fingerprint"`
	Source       string           `json:"source"`
	RuleID       string           `json:"ruleId,omitempty"`
	Category     string           `json:"category"`
	Severity     string           `json:"severity"`
	Confidence   float64          `json:"confidence"`
	Title        string           `json:"title"`
	Claim        string           `json:"claim"`
	Suggestion   string           `json:"suggestion"`
	Verification string           `json:"verification"`
	Path         string           `json:"path"`
	Line         int              `json:"line"`
	Excerpt      string           `json:"excerpt,omitempty"`
	Status       string           `json:"status"`
	Publishable  bool             `json:"publishable"`
	Evidence     *FindingEvidence `json:"evidence,omitempty"`
}

type FindingEvidence struct {
	Path         string `json:"path"`
	StartLine    int    `json:"startLine"`
	EndLine      int    `json:"endLine"`
	Side         string `json:"side"`
	ExcerptHash  string `json:"excerptHash"`
	EvidenceType string `json:"evidenceType"`
}

type FindingSummary struct {
	Candidates int       `json:"candidates"`
	Verified   int       `json:"verified"`
	Published  int       `json:"published"`
	Rejected   int       `json:"rejected"`
	Items      []Finding `json:"items"`
}

type PullRequestEvent struct {
	Action       string `json:"action"`
	Installation struct {
		ID int64 `json:"id"`
	} `json:"installation"`
	Repository struct {
		ID    int64  `json:"id"`
		Name  string `json:"name"`
		Owner struct {
			Login string `json:"login"`
		} `json:"owner"`
	} `json:"repository"`
	PullRequest struct {
		Number int `json:"number"`
		Base   struct {
			SHA string `json:"sha"`
		} `json:"base"`
		Head struct {
			SHA string `json:"sha"`
		} `json:"head"`
	} `json:"pull_request"`
}

type CheckRunEvent struct {
	Action       string `json:"action"`
	Installation struct {
		ID int64 `json:"id"`
	} `json:"installation"`
	Repository struct {
		ID    int64  `json:"id"`
		Name  string `json:"name"`
		Owner struct {
			Login string `json:"login"`
		} `json:"owner"`
	} `json:"repository"`
	CheckRun struct {
		ID      int64  `json:"id"`
		Name    string `json:"name"`
		HeadSHA string `json:"head_sha"`
		App     *struct {
			ID int64 `json:"id"`
		} `json:"app,omitempty"`
		PullRequests []struct {
			Number int `json:"number"`
			Base   struct {
				SHA string `json:"sha"`
			} `json:"base"`
			Head struct {
				SHA string `json:"sha"`
			} `json:"head"`
		} `json:"pull_requests"`
	} `json:"check_run"`
}

type IssueCommentEvent struct {
	Action     string `json:"action"`
	Repository struct {
		ID int64 `json:"id"`
	} `json:"repository"`
	Issue struct {
		Number      int             `json:"number"`
		PullRequest json.RawMessage `json:"pull_request"`
	} `json:"issue"`
	Comment struct {
		ID   int64  `json:"id"`
		Body string `json:"body"`
		User struct {
			Login string `json:"login"`
		} `json:"user"`
	} `json:"comment"`
}
