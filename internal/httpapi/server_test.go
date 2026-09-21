package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/xiaoyixin3/codelens-ai/internal/contracts"
	"github.com/xiaoyixin3/codelens-ai/internal/security"
	"github.com/xiaoyixin3/codelens-ai/internal/store"
)

type fakeBackend struct {
	deliveries map[string]bool
	jobs       []contracts.ReviewJob
	runs       int
}

func newFakeBackend() *fakeBackend                { return &fakeBackend{deliveries: map[string]bool{}} }
func (f *fakeBackend) Ping(context.Context) error { return nil }
func (f *fakeBackend) ClaimDelivery(_ context.Context, id, _, _ string, _ []byte) (bool, error) {
	if f.deliveries[id] {
		return false, nil
	}
	f.deliveries[id] = true
	return true, nil
}
func (f *fakeBackend) MarkDeliveryProcessed(context.Context, string, string) error { return nil }
func (f *fakeBackend) CreateOrGetReviewRunAndEnqueue(_ context.Context, _ store.CreateReviewRunInput, job contracts.ReviewJob) (contracts.ReviewRun, bool, error) {
	f.runs++
	job.ReviewRunID = "2ad1bd4a-c92e-439a-93fb-99ea454c8efa"
	f.jobs = append(f.jobs, job)
	return contracts.ReviewRun{ID: "2ad1bd4a-c92e-439a-93fb-99ea454c8efa"}, true, nil
}
func (f *fakeBackend) SavePublication(context.Context, store.Publication) error { return nil }
func (f *fakeBackend) SaveFindingFeedback(context.Context, store.FindingFeedbackInput) (bool, error) {
	return true, nil
}
func TestHealthAndReady(t *testing.T) {
	handler := New(Options{Backend: newFakeBackend(), WebhookSecret: "1234567890abcdef", RateLimitPerMin: 10}).Handler()
	for _, path := range []string{"/healthz", "/readyz"} {
		request := httptest.NewRequest(http.MethodGet, path, nil)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusOK {
			t.Fatalf("%s returned %d", path, response.Code)
		}
	}
}

func TestWebhookQueuesPullRequestAndDeduplicatesDelivery(t *testing.T) {
	backend := newFakeBackend()
	secret := "1234567890abcdef"
	handler := New(Options{Backend: backend, WebhookSecret: secret, RateLimitPerMin: 10}).Handler()
	payload := map[string]any{
		"action": "opened", "installation": map[string]any{"id": 42},
		"repository":   map[string]any{"id": 7, "name": "demo", "owner": map[string]any{"login": "acme"}},
		"pull_request": map[string]any{"number": 3, "base": map[string]any{"sha": "aaaaaaaa"}, "head": map[string]any{"sha": "bbbbbbbb"}},
	}
	body, _ := json.Marshal(payload)
	request := func() *http.Request {
		r := httptest.NewRequest(http.MethodPost, "/webhooks/github", bytes.NewReader(body))
		r.Header.Set("X-Hub-Signature-256", security.SignWebhook(body, secret))
		r.Header.Set("X-GitHub-Delivery", "delivery-1")
		r.Header.Set("X-GitHub-Event", "pull_request")
		return r
	}
	first := httptest.NewRecorder()
	handler.ServeHTTP(first, request())
	if first.Code != http.StatusAccepted || len(backend.jobs) != 1 || backend.runs != 1 {
		t.Fatalf("first delivery code=%d jobs=%d runs=%d body=%s", first.Code, len(backend.jobs), backend.runs, first.Body.String())
	}
	second := httptest.NewRecorder()
	handler.ServeHTTP(second, request())
	if second.Code != http.StatusAccepted || len(backend.jobs) != 1 {
		t.Fatalf("duplicate delivery was queued")
	}
}

func TestWebhookRejectsInvalidSignature(t *testing.T) {
	backend := newFakeBackend()
	handler := New(Options{Backend: backend, WebhookSecret: "1234567890abcdef", RateLimitPerMin: 10}).Handler()
	request := httptest.NewRequest(http.MethodPost, "/webhooks/github", bytes.NewBufferString(`{}`))
	request.Header.Set("X-Hub-Signature-256", "sha256=bad")
	request.Header.Set("X-GitHub-Delivery", "delivery-1")
	request.Header.Set("X-GitHub-Event", "ping")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("code=%d", response.Code)
	}
}
