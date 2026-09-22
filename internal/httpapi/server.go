package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/xiaoyixin3/codelens-ai/internal/contracts"
	"github.com/xiaoyixin3/codelens-ai/internal/credentials"
	"github.com/xiaoyixin3/codelens-ai/internal/security"
	"github.com/xiaoyixin3/codelens-ai/internal/store"
)

var (
	reviewActions   = map[string]bool{"opened": true, "reopened": true, "synchronize": true, "ready_for_review": true}
	feedbackPattern = regexp.MustCompile(`(?i)^/codelens\s+feedback\s+([a-f0-9]{12,64})\s+(helpful|false-positive)$`)
)

type Backend interface {
	Ping(context.Context) error
	ClaimDelivery(context.Context, string, string, string, []byte) (bool, error)
	MarkDeliveryProcessed(context.Context, string, string) error
	CreateOrGetReviewRunAndEnqueue(context.Context, store.CreateReviewRunInput, contracts.ReviewJob) (contracts.ReviewRun, bool, error)
	SavePublication(context.Context, store.Publication) error
	SaveFindingFeedback(context.Context, store.FindingFeedbackInput) (bool, error)
}

type ProviderBackend interface {
	CreateProviderConnection(context.Context, store.CreateProviderConnectionInput) (store.ProviderConnection, error)
	ListProviderConnections(context.Context, int64) ([]store.ProviderConnection, error)
	GetProviderConnection(context.Context, int64, string) (store.ProviderConnection, error)
	UpdateProviderConnection(context.Context, store.UpdateProviderConnectionInput) (store.ProviderConnection, error)
	RotateProviderCredential(context.Context, int64, string, string, string, string, int) (store.ProviderConnection, error)
	RecordProviderTest(context.Context, int64, string, string, string, string) (store.ProviderConnection, error)
	DeleteProviderConnection(context.Context, int64, string, string) error
}

type Options struct {
	Backend            Backend
	WebhookSecret      string
	GitHubAppID        string
	RateLimitPerMin    int
	Logger             *slog.Logger
	ProviderBackend    ProviderBackend
	CredentialVault    *credentials.Vault
	ModelAdminToken    string
	AllowPrivateModels bool
}

type Server struct {
	backend            Backend
	secret             string
	appID              string
	logger             *slog.Logger
	limiter            *fixedWindowLimiter
	mux                *http.ServeMux
	providers          ProviderBackend
	vault              *credentials.Vault
	adminToken         string
	allowPrivateModels bool
}

func New(options Options) *Server {
	logger := options.Logger
	if logger == nil {
		logger = slog.Default()
	}
	server := &Server{
		backend: options.Backend, secret: options.WebhookSecret, appID: options.GitHubAppID,
		logger: logger, limiter: newFixedWindowLimiter(options.RateLimitPerMin), mux: http.NewServeMux(),
		providers: options.ProviderBackend, vault: options.CredentialVault,
		adminToken: options.ModelAdminToken, allowPrivateModels: options.AllowPrivateModels,
	}
	server.mux.HandleFunc("GET /healthz", server.health)
	server.mux.HandleFunc("GET /readyz", server.ready)
	server.mux.HandleFunc("POST /webhooks/github", server.webhook)
	if server.providers != nil && server.vault != nil && server.adminToken != "" {
		server.mux.HandleFunc("GET /api/v2/providers", server.listProviders)
		server.mux.HandleFunc("POST /api/v2/providers", server.createProvider)
		server.mux.HandleFunc("GET /api/v2/providers/{id}", server.getProvider)
		server.mux.HandleFunc("PATCH /api/v2/providers/{id}", server.updateProvider)
		server.mux.HandleFunc("POST /api/v2/providers/{id}/rotate-secret", server.rotateProviderSecret)
		server.mux.HandleFunc("POST /api/v2/providers/{id}/test", server.testProvider)
		server.mux.HandleFunc("DELETE /api/v2/providers/{id}", server.deleteProvider)
	}
	return server
}

func (s *Server) Handler() http.Handler {
	return recoverMiddleware(s.logger, s.mux)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) ready(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.backend.Ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "not_ready"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

func (s *Server) webhook(w http.ResponseWriter, r *http.Request) {
	if !s.limiter.allow(clientIP(r), time.Now()) {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "rate_limit_exceeded"})
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2<<20))
	if err != nil {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "payload_too_large"})
		return
	}
	signature := r.Header.Get("X-Hub-Signature-256")
	deliveryID := r.Header.Get("X-GitHub-Delivery")
	event := r.Header.Get("X-GitHub-Event")
	if signature == "" || deliveryID == "" || event == "" || !security.VerifyWebhook(body, signature, s.secret) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid_webhook_signature"})
		return
	}
	var envelope struct {
		Action string `json:"action"`
	}
	if err := json.Unmarshal(body, &envelope); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}
	claimed, err := s.backend.ClaimDelivery(r.Context(), deliveryID, event, envelope.Action, body)
	if err != nil {
		s.fail(w, deliveryID, err)
		return
	}
	if !claimed {
		writeJSON(w, http.StatusAccepted, map[string]string{"status": "duplicate"})
		return
	}

	var status string
	var runID string
	switch event {
	case "pull_request":
		status, runID, err = s.handlePullRequest(r.Context(), body)
	case "check_run":
		status, runID, err = s.handleCheckRun(r.Context(), deliveryID, body)
	case "issue_comment":
		status, err = s.handleIssueComment(r.Context(), body)
	default:
		status = "ignored"
	}
	if err != nil {
		s.fail(w, deliveryID, err)
		return
	}
	if err := s.backend.MarkDeliveryProcessed(r.Context(), deliveryID, ""); err != nil {
		s.fail(w, deliveryID, err)
		return
	}
	response := map[string]string{"status": status}
	if runID != "" {
		response["reviewRunId"] = runID
	}
	writeJSON(w, http.StatusAccepted, response)
}

func (s *Server) handlePullRequest(ctx context.Context, body []byte) (string, string, error) {
	var event contracts.PullRequestEvent
	if err := json.Unmarshal(body, &event); err != nil {
		return "", "", err
	}
	if event.Installation.ID == 0 || event.Repository.ID == 0 || event.Repository.Name == "" || event.Repository.Owner.Login == "" || event.PullRequest.Number == 0 || event.PullRequest.Base.SHA == "" || event.PullRequest.Head.SHA == "" {
		return "ignored", "", nil
	}
	if !reviewActions[event.Action] {
		return "ignored_action", "", nil
	}
	run, created, err := s.createAndQueue(ctx, store.CreateReviewRunInput{
		RepositoryID: event.Repository.ID, PullNumber: event.PullRequest.Number,
		BaseSHA: event.PullRequest.Base.SHA, HeadSHA: event.PullRequest.Head.SHA,
		PipelineVersion: contracts.PipelineVersion, ConfigHash: contracts.DefaultConfigHash,
	}, contracts.ReviewJob{
		InstallationID: event.Installation.ID, Owner: event.Repository.Owner.Login, Repo: event.Repository.Name,
		PullNumber: event.PullRequest.Number, BaseSHA: event.PullRequest.Base.SHA, HeadSHA: event.PullRequest.Head.SHA,
	})
	if err != nil {
		return "", "", err
	}
	if created {
		return "queued", run.ID, nil
	}
	return "already_queued", run.ID, nil
}

func (s *Server) handleCheckRun(ctx context.Context, deliveryID string, body []byte) (string, string, error) {
	var event contracts.CheckRunEvent
	if err := json.Unmarshal(body, &event); err != nil {
		return "", "", err
	}
	if event.Action != "rerequested" || event.CheckRun.Name != "CodeLens AI Review" || len(event.CheckRun.PullRequests) == 0 {
		return "ignored", "", nil
	}
	if s.appID != "" && event.CheckRun.App != nil && strconv.FormatInt(event.CheckRun.App.ID, 10) != s.appID {
		return "ignored_foreign_check", "", nil
	}
	pull := event.CheckRun.PullRequests[0]
	if pull.Head.SHA != event.CheckRun.HeadSHA {
		return "ignored_stale_check", "", nil
	}
	run, created, err := s.createAndQueue(ctx, store.CreateReviewRunInput{
		RepositoryID: event.Repository.ID, PullNumber: pull.Number, BaseSHA: pull.Base.SHA, HeadSHA: pull.Head.SHA,
		PipelineVersion: contracts.PipelineVersion, ConfigHash: contracts.DefaultConfigHash,
		Trigger: "rerun", RequestKey: "rerun:" + deliveryID,
	}, contracts.ReviewJob{
		InstallationID: event.Installation.ID, Owner: event.Repository.Owner.Login, Repo: event.Repository.Name,
		PullNumber: pull.Number, BaseSHA: pull.Base.SHA, HeadSHA: pull.Head.SHA,
	})
	if err != nil {
		return "", "", err
	}
	if created {
		checkID := event.CheckRun.ID
		if err := s.backend.SavePublication(ctx, store.Publication{ReviewRunID: run.ID, HeadSHA: pull.Head.SHA, CheckRunID: &checkID}); err != nil {
			return "", "", err
		}
		return "rerun_queued", run.ID, nil
	}
	return "rerun_already_queued", run.ID, nil
}

func (s *Server) handleIssueComment(ctx context.Context, body []byte) (string, error) {
	var event contracts.IssueCommentEvent
	if err := json.Unmarshal(body, &event); err != nil {
		return "", err
	}
	if event.Action != "created" || event.Repository.ID == 0 || event.Issue.Number == 0 || len(event.Issue.PullRequest) == 0 || event.Comment.ID == 0 {
		return "ignored_comment", nil
	}
	match := feedbackPattern.FindStringSubmatch(strings.TrimSpace(event.Comment.Body))
	if len(match) != 3 {
		return "ignored_comment", nil
	}
	verdict := "helpful"
	if strings.EqualFold(match[2], "false-positive") {
		verdict = "false_positive"
	}
	recorded, err := s.backend.SaveFindingFeedback(ctx, store.FindingFeedbackInput{
		RepositoryID: event.Repository.ID, PullNumber: event.Issue.Number, FingerprintPrefix: strings.ToLower(match[1]),
		Verdict: verdict, ActorLogin: event.Comment.User.Login, SourceCommentID: event.Comment.ID,
	})
	if err != nil {
		return "", err
	}
	if recorded {
		return "feedback_recorded", nil
	}
	return "feedback_not_found", nil
}

func (s *Server) createAndQueue(ctx context.Context, input store.CreateReviewRunInput, job contracts.ReviewJob) (contracts.ReviewRun, bool, error) {
	return s.backend.CreateOrGetReviewRunAndEnqueue(ctx, input, job)
}

func (s *Server) fail(w http.ResponseWriter, deliveryID string, err error) {
	detail := security.Redact(err.Error())
	if len(detail) > 2000 {
		detail = detail[:2000]
	}
	if deliveryID != "" {
		_ = s.backend.MarkDeliveryProcessed(context.Background(), deliveryID, detail)
	}
	s.logger.Error("webhook processing failed", "error", detail)
	writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "internal_error"})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func clientIP(r *http.Request) string {
	if forwarded := strings.TrimSpace(strings.Split(r.Header.Get("X-Forwarded-For"), ",")[0]); forwarded != "" {
		return forwarded
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

func recoverMiddleware(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				logger.Error("request panic", "error", fmt.Sprint(recovered))
				writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "internal_error"})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

type rateWindow struct {
	start time.Time
	count int
}
type fixedWindowLimiter struct {
	mu      sync.Mutex
	max     int
	clients map[string]rateWindow
}

func newFixedWindowLimiter(max int) *fixedWindowLimiter {
	if max < 1 {
		max = 300
	}
	return &fixedWindowLimiter{max: max, clients: make(map[string]rateWindow)}
}

func (l *fixedWindowLimiter) allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	window := l.clients[key]
	if window.start.IsZero() || now.Sub(window.start) >= time.Minute {
		window = rateWindow{start: now}
	}
	window.count++
	l.clients[key] = window
	return window.count <= l.max
}
