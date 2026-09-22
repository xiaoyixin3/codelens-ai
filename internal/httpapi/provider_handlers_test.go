package httpapi

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/xiaoyixin3/codelens-ai/internal/credentials"
	"github.com/xiaoyixin3/codelens-ai/internal/store"
)

type fakeProviderBackend struct {
	connections map[string]store.ProviderConnection
}

func newFakeProviderBackend() *fakeProviderBackend {
	return &fakeProviderBackend{connections: map[string]store.ProviderConnection{}}
}

func (f *fakeProviderBackend) CreateProviderConnection(_ context.Context, input store.CreateProviderConnectionInput) (store.ProviderConnection, error) {
	now := time.Now().UTC()
	connection := store.ProviderConnection{
		ID: input.ID, InstallationID: input.InstallationID, Name: input.Name,
		ProviderKind: input.ProviderKind, BaseURL: input.BaseURL,
		CredentialCiphertext: input.CredentialCiphertext, CredentialKeyVersion: input.CredentialKeyVersion,
		CredentialFingerprint: input.CredentialFingerprint, DefaultModel: input.DefaultModel,
		Enabled: input.Enabled, TimeoutSeconds: input.TimeoutSeconds, MaxRetries: input.MaxRetries,
		LastTestStatus: "untested", CreatedAt: now, UpdatedAt: now,
	}
	f.connections[input.ID] = connection
	return connection, nil
}

func (f *fakeProviderBackend) ListProviderConnections(_ context.Context, installationID int64) ([]store.ProviderConnection, error) {
	result := []store.ProviderConnection{}
	for _, connection := range f.connections {
		if connection.InstallationID == installationID {
			result = append(result, connection)
		}
	}
	return result, nil
}

func (f *fakeProviderBackend) GetProviderConnection(_ context.Context, installationID int64, id string) (store.ProviderConnection, error) {
	connection, ok := f.connections[id]
	if !ok || connection.InstallationID != installationID {
		return store.ProviderConnection{}, pgx.ErrNoRows
	}
	return connection, nil
}

func (f *fakeProviderBackend) UpdateProviderConnection(_ context.Context, input store.UpdateProviderConnectionInput) (store.ProviderConnection, error) {
	connection, err := f.GetProviderConnection(context.Background(), input.InstallationID, input.ID)
	if err != nil {
		return store.ProviderConnection{}, err
	}
	connection.Name, connection.BaseURL, connection.DefaultModel = input.Name, input.BaseURL, input.DefaultModel
	connection.Enabled, connection.TimeoutSeconds, connection.MaxRetries = input.Enabled, input.TimeoutSeconds, input.MaxRetries
	connection.LastTestStatus, connection.LastTestDetail, connection.LastTestedAt = "untested", "", nil
	f.connections[input.ID] = connection
	return connection, nil
}

func (f *fakeProviderBackend) RotateProviderCredential(_ context.Context, installationID int64, id, ciphertext, fingerprint, _ string, keyVersion int) (store.ProviderConnection, error) {
	connection, err := f.GetProviderConnection(context.Background(), installationID, id)
	if err != nil {
		return store.ProviderConnection{}, err
	}
	connection.CredentialCiphertext, connection.CredentialFingerprint, connection.CredentialKeyVersion = ciphertext, fingerprint, keyVersion
	connection.LastTestStatus = "untested"
	f.connections[id] = connection
	return connection, nil
}

func (f *fakeProviderBackend) RecordProviderTest(_ context.Context, installationID int64, id, status, detail, _ string) (store.ProviderConnection, error) {
	connection, err := f.GetProviderConnection(context.Background(), installationID, id)
	if err != nil {
		return store.ProviderConnection{}, err
	}
	now := time.Now().UTC()
	connection.LastTestStatus, connection.LastTestDetail, connection.LastTestedAt = status, detail, &now
	f.connections[id] = connection
	return connection, nil
}

func (f *fakeProviderBackend) DeleteProviderConnection(_ context.Context, installationID int64, id, _ string) error {
	if _, err := f.GetProviderConnection(context.Background(), installationID, id); err != nil {
		return err
	}
	delete(f.connections, id)
	return nil
}

func TestProviderControlPlaneRequiresAuthenticationAndNeverReturnsSecrets(t *testing.T) {
	modelServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/chat/completions" {
			t.Fatalf("unexpected model path %s", request.URL.Path)
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"content": `{"ok":true}`}}}})
	}))
	defer modelServer.Close()

	vault, err := credentials.New(base64.StdEncoding.EncodeToString([]byte("0123456789abcdef0123456789abcdef")))
	if err != nil {
		t.Fatal(err)
	}
	providers := newFakeProviderBackend()
	handler := New(Options{
		Backend: newFakeBackend(), WebhookSecret: "1234567890abcdef", RateLimitPerMin: 100,
		ProviderBackend: providers, CredentialVault: vault,
		ModelAdminToken: "12345678901234567890123456789012", AllowPrivateModels: true,
	}).Handler()

	unauthorized := httptest.NewRecorder()
	handler.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodGet, "/api/v2/providers", nil))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized code=%d", unauthorized.Code)
	}

	body := `{"name":"local-test","providerKind":"openai_compatible","baseUrl":"` + modelServer.URL + `/v1","apiKey":"model-secret","defaultModel":"test-model","timeoutSeconds":5,"maxRetries":0}`
	created := serveProviderRequest(handler, http.MethodPost, "/api/v2/providers", body)
	if created.Code != http.StatusCreated {
		t.Fatalf("create code=%d body=%s", created.Code, created.Body.String())
	}
	if strings.Contains(created.Body.String(), "model-secret") || strings.Contains(created.Body.String(), "credentialCiphertext") {
		t.Fatalf("provider response exposed a secret: %s", created.Body.String())
	}
	var connection store.ProviderConnection
	if err := json.Unmarshal(created.Body.Bytes(), &connection); err != nil {
		t.Fatal(err)
	}
	stored := providers.connections[connection.ID]
	opened, err := vault.Open(stored.CredentialCiphertext, providerCredentialContext(42, connection.ID))
	if err != nil || opened != "model-secret" {
		t.Fatalf("encrypted credential was not recoverable: %q err=%v", opened, err)
	}

	tested := serveProviderRequest(handler, http.MethodPost, "/api/v2/providers/"+connection.ID+"/test", "")
	if tested.Code != http.StatusOK || !strings.Contains(tested.Body.String(), `"status":"succeeded"`) {
		t.Fatalf("test code=%d body=%s", tested.Code, tested.Body.String())
	}

	rotated := serveProviderRequest(handler, http.MethodPost, "/api/v2/providers/"+connection.ID+"/rotate-secret", `{"apiKey":"rotated-secret"}`)
	if rotated.Code != http.StatusOK || strings.Contains(rotated.Body.String(), "rotated-secret") {
		t.Fatalf("rotate code=%d body=%s", rotated.Code, rotated.Body.String())
	}
	opened, err = vault.Open(providers.connections[connection.ID].CredentialCiphertext, providerCredentialContext(42, connection.ID))
	if err != nil || opened != "rotated-secret" {
		t.Fatalf("rotated credential was not encrypted correctly: %q err=%v", opened, err)
	}

	deleted := serveProviderRequest(handler, http.MethodDelete, "/api/v2/providers/"+connection.ID, "")
	if deleted.Code != http.StatusNoContent {
		t.Fatalf("delete code=%d body=%s", deleted.Code, deleted.Body.String())
	}
}

func serveProviderRequest(handler http.Handler, method, path, body string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, bytes.NewBufferString(body))
	request.Header.Set("Authorization", "Bearer 12345678901234567890123456789012")
	request.Header.Set("X-CodeLens-Installation-ID", "42")
	request.Header.Set("X-CodeLens-Actor", "test-admin")
	if body != "" {
		request.Header.Set("Content-Type", "application/json")
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}
