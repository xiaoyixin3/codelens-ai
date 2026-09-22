package llm

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestValidateProviderBaseURL(t *testing.T) {
	if _, err := ValidateProviderBaseURL("http://127.0.0.1:11434/v1", false); err == nil {
		t.Fatal("expected private HTTP endpoint to be rejected")
	}
	if value, err := ValidateProviderBaseURL("http://127.0.0.1:11434/v1/", true); err != nil || value != "http://127.0.0.1:11434/v1" {
		t.Fatalf("expected explicit private endpoint opt-in, got %q err=%v", value, err)
	}
	if _, err := ValidateProviderBaseURL("https://user:pass@example.com/v1", false); err == nil {
		t.Fatal("expected embedded credentials to be rejected")
	}
}

func TestProviderAdaptersUseNativeRequestShapes(t *testing.T) {
	tests := []struct {
		kind           string
		expectedPath   string
		expectedAuth   string
		response       any
		expectedOutput string
	}{
		{ProviderOpenAICompatible, "/chat/completions", "Bearer secret", map[string]any{"choices": []any{map[string]any{"message": map[string]any{"content": `{"ok":true}`}}}}, `{"ok":true}`},
		{ProviderOpenAIResponses, "/responses", "Bearer secret", map[string]any{"output": []any{map[string]any{"content": []any{map[string]any{"type": "output_text", "text": `{"ok":true}`}}}}}, `{"ok":true}`},
		{ProviderAnthropic, "/v1/messages", "secret", map[string]any{"content": []any{map[string]any{"type": "text", "text": `{"ok":true}`}}}, `{"ok":true}`},
	}
	for _, test := range tests {
		t.Run(test.kind, func(t *testing.T) {
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
				if request.URL.Path != test.expectedPath {
					t.Fatalf("path=%s", request.URL.Path)
				}
				auth := request.Header.Get("Authorization")
				if test.kind == ProviderAnthropic {
					auth = request.Header.Get("x-api-key")
				}
				if auth != test.expectedAuth {
					t.Fatalf("auth=%q", auth)
				}
				w.Header().Set("Content-Type", "application/json")
				w.Header().Set("x-request-id", "request-123")
				_ = json.NewEncoder(w).Encode(test.response)
			}))
			defer server.Close()
			provider := &HTTPProvider{config: ProviderConfig{Kind: test.kind, BaseURL: server.URL, APIKey: "secret", Model: "test-model", MaxRetries: 0}, http: server.Client()}
			provider.http.Timeout = time.Second
			response, err := provider.GenerateStructured(context.Background(), StructuredRequest{System: "system", User: "user", MaxOutputTokens: 32})
			if err != nil {
				t.Fatal(err)
			}
			if response.Content != test.expectedOutput || response.ProviderRequestID != "request-123" {
				t.Fatalf("unexpected response %#v", response)
			}
		})
	}
}

func TestProviderRetriesTransientFailureAndRedactsError(t *testing.T) {
	attempts := 0
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		attempts++
		if attempts == 1 {
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"error":"api_key=super-secret"}`))
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"content": `{"ok":true}`}}}})
	}))
	defer server.Close()
	provider := &HTTPProvider{config: ProviderConfig{Kind: ProviderOpenAICompatible, BaseURL: server.URL, APIKey: "secret", Model: "test-model", MaxRetries: 1}, http: server.Client()}
	provider.http.Timeout = time.Second
	if _, err := provider.TestConnection(context.Background()); err != nil {
		t.Fatal(err)
	}
	if attempts != 2 {
		t.Fatalf("attempts=%d", attempts)
	}

	provider.config.MaxRetries = 0
	attempts = 0
	_, err := provider.GenerateStructured(context.Background(), StructuredRequest{User: "test"})
	if err == nil || strings.Contains(err.Error(), "super-secret") {
		t.Fatalf("expected a redacted provider error, got %v", err)
	}
}
