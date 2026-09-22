package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/xiaoyixin3/codelens-ai/internal/credentials"
	"github.com/xiaoyixin3/codelens-ai/internal/llm"
	"github.com/xiaoyixin3/codelens-ai/internal/security"
	"github.com/xiaoyixin3/codelens-ai/internal/store"
)

type createProviderRequest struct {
	Name           string `json:"name"`
	ProviderKind   string `json:"providerKind"`
	BaseURL        string `json:"baseUrl"`
	APIKey         string `json:"apiKey"`
	DefaultModel   string `json:"defaultModel"`
	Enabled        *bool  `json:"enabled"`
	TimeoutSeconds int    `json:"timeoutSeconds"`
	MaxRetries     *int   `json:"maxRetries"`
}

type updateProviderRequest struct {
	Name           *string `json:"name"`
	BaseURL        *string `json:"baseUrl"`
	DefaultModel   *string `json:"defaultModel"`
	Enabled        *bool   `json:"enabled"`
	TimeoutSeconds *int    `json:"timeoutSeconds"`
	MaxRetries     *int    `json:"maxRetries"`
}

type rotateProviderSecretRequest struct {
	APIKey string `json:"apiKey"`
}

func (s *Server) listProviders(w http.ResponseWriter, r *http.Request) {
	installationID, _, ok := s.authorizeModelAdmin(w, r)
	if !ok {
		return
	}
	connections, err := s.providers.ListProviderConnections(r.Context(), installationID)
	if err != nil {
		s.providerInternalError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"providers": connections})
}

func (s *Server) getProvider(w http.ResponseWriter, r *http.Request) {
	installationID, _, ok := s.authorizeModelAdmin(w, r)
	if !ok {
		return
	}
	connection, err := s.providers.GetProviderConnection(r.Context(), installationID, r.PathValue("id"))
	if err != nil {
		s.providerStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, connection)
}

func (s *Server) createProvider(w http.ResponseWriter, r *http.Request) {
	installationID, actor, ok := s.authorizeModelAdmin(w, r)
	if !ok {
		return
	}
	var request createProviderRequest
	if err := decodeJSON(w, r, &request); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_request", "detail": err.Error()})
		return
	}
	request.Name = strings.TrimSpace(request.Name)
	request.ProviderKind = strings.TrimSpace(request.ProviderKind)
	request.BaseURL = strings.TrimSpace(request.BaseURL)
	request.DefaultModel = strings.TrimSpace(request.DefaultModel)
	request.APIKey = strings.TrimSpace(request.APIKey)
	if request.BaseURL == "" {
		request.BaseURL = llm.DefaultBaseURL(request.ProviderKind)
	}
	maxRetries := 2
	if request.MaxRetries != nil {
		maxRetries = *request.MaxRetries
	}
	if err := validateProviderFields(request.Name, request.ProviderKind, request.BaseURL, request.APIKey, request.DefaultModel, request.TimeoutSeconds, maxRetries, s.allowPrivateModels); err != nil {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": "invalid_provider", "detail": err.Error()})
		return
	}
	if request.TimeoutSeconds == 0 {
		request.TimeoutSeconds = 45
	}
	connectionID := uuid.NewString()
	ciphertext, err := s.vault.Seal(request.APIKey, providerCredentialContext(installationID, connectionID))
	if err != nil {
		s.providerInternalError(w, err)
		return
	}
	enabled := true
	if request.Enabled != nil {
		enabled = *request.Enabled
	}
	connection, err := s.providers.CreateProviderConnection(r.Context(), store.CreateProviderConnectionInput{
		ID: connectionID, InstallationID: installationID, Name: request.Name,
		ProviderKind: request.ProviderKind, BaseURL: request.BaseURL,
		CredentialCiphertext: ciphertext, CredentialKeyVersion: credentials.KeyVersion,
		CredentialFingerprint: credentials.Fingerprint(request.APIKey), DefaultModel: request.DefaultModel,
		Enabled: enabled, TimeoutSeconds: request.TimeoutSeconds, MaxRetries: maxRetries, Actor: actor,
	})
	if err != nil {
		s.providerStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, connection)
}

func (s *Server) updateProvider(w http.ResponseWriter, r *http.Request) {
	installationID, actor, ok := s.authorizeModelAdmin(w, r)
	if !ok {
		return
	}
	current, err := s.providers.GetProviderConnection(r.Context(), installationID, r.PathValue("id"))
	if err != nil {
		s.providerStoreError(w, err)
		return
	}
	var request updateProviderRequest
	if err := decodeJSON(w, r, &request); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_request", "detail": err.Error()})
		return
	}
	if request.Name != nil {
		current.Name = strings.TrimSpace(*request.Name)
	}
	if request.BaseURL != nil {
		current.BaseURL = strings.TrimSpace(*request.BaseURL)
	}
	if request.DefaultModel != nil {
		current.DefaultModel = strings.TrimSpace(*request.DefaultModel)
	}
	if request.Enabled != nil {
		current.Enabled = *request.Enabled
	}
	if request.TimeoutSeconds != nil {
		current.TimeoutSeconds = *request.TimeoutSeconds
	}
	if request.MaxRetries != nil {
		current.MaxRetries = *request.MaxRetries
	}
	if err := validateProviderFields(current.Name, current.ProviderKind, current.BaseURL, "unchanged-credential", current.DefaultModel, current.TimeoutSeconds, current.MaxRetries, s.allowPrivateModels); err != nil {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": "invalid_provider", "detail": err.Error()})
		return
	}
	connection, err := s.providers.UpdateProviderConnection(r.Context(), store.UpdateProviderConnectionInput{
		InstallationID: installationID, ID: current.ID, Name: current.Name, BaseURL: current.BaseURL,
		DefaultModel: current.DefaultModel, Enabled: current.Enabled, TimeoutSeconds: current.TimeoutSeconds,
		MaxRetries: current.MaxRetries, Actor: actor,
	})
	if err != nil {
		s.providerStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, connection)
}

func (s *Server) rotateProviderSecret(w http.ResponseWriter, r *http.Request) {
	installationID, actor, ok := s.authorizeModelAdmin(w, r)
	if !ok {
		return
	}
	current, err := s.providers.GetProviderConnection(r.Context(), installationID, r.PathValue("id"))
	if err != nil {
		s.providerStoreError(w, err)
		return
	}
	var request rotateProviderSecretRequest
	if err := decodeJSON(w, r, &request); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_request", "detail": err.Error()})
		return
	}
	request.APIKey = strings.TrimSpace(request.APIKey)
	if request.APIKey == "" || len(request.APIKey) > 16_384 {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": "invalid_credential"})
		return
	}
	ciphertext, err := s.vault.Seal(request.APIKey, providerCredentialContext(installationID, current.ID))
	if err != nil {
		s.providerInternalError(w, err)
		return
	}
	connection, err := s.providers.RotateProviderCredential(r.Context(), installationID, current.ID, ciphertext, credentials.Fingerprint(request.APIKey), actor, credentials.KeyVersion)
	if err != nil {
		s.providerStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, connection)
}

func (s *Server) testProvider(w http.ResponseWriter, r *http.Request) {
	installationID, actor, ok := s.authorizeModelAdmin(w, r)
	if !ok {
		return
	}
	connection, err := s.providers.GetProviderConnection(r.Context(), installationID, r.PathValue("id"))
	if err != nil {
		s.providerStoreError(w, err)
		return
	}
	secret, err := s.vault.Open(connection.CredentialCiphertext, providerCredentialContext(installationID, connection.ID))
	if err != nil {
		s.providerInternalError(w, err)
		return
	}
	provider, err := llm.NewModelProvider(llm.ProviderConfig{
		Name: connection.Name, Kind: connection.ProviderKind, BaseURL: connection.BaseURL,
		APIKey: secret, Model: connection.DefaultModel, Timeout: time.Duration(connection.TimeoutSeconds) * time.Second,
		MaxRetries: connection.MaxRetries, AllowPrivate: s.allowPrivateModels,
	})
	secret = ""
	if err != nil {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": "invalid_provider", "detail": err.Error()})
		return
	}
	timeout := time.Duration(connection.TimeoutSeconds+5) * time.Second
	ctx, cancel := context.WithTimeout(r.Context(), timeout)
	defer cancel()
	result, testErr := provider.TestConnection(ctx)
	status, detail := "succeeded", ""
	if testErr != nil {
		status, detail = "failed", boundedProviderDetail(testErr.Error())
	}
	updated, recordErr := s.providers.RecordProviderTest(r.Context(), installationID, connection.ID, status, detail, actor)
	if recordErr != nil {
		s.providerInternalError(w, recordErr)
		return
	}
	if testErr != nil {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]any{"error": "provider_test_failed", "detail": detail, "provider": updated, "test": result})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "succeeded", "provider": updated, "test": result})
}

func (s *Server) deleteProvider(w http.ResponseWriter, r *http.Request) {
	installationID, actor, ok := s.authorizeModelAdmin(w, r)
	if !ok {
		return
	}
	if err := s.providers.DeleteProviderConnection(r.Context(), installationID, r.PathValue("id"), actor); err != nil {
		s.providerStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) authorizeModelAdmin(w http.ResponseWriter, r *http.Request) (int64, string, bool) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if !s.limiter.allow("model-admin:"+clientIP(r), time.Now()) {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "rate_limit_exceeded"})
		return 0, "", false
	}
	authorization := strings.TrimSpace(r.Header.Get("Authorization"))
	token := ""
	if strings.HasPrefix(authorization, "Bearer ") {
		token = strings.TrimSpace(strings.TrimPrefix(authorization, "Bearer "))
	}
	if token == "" || !credentials.TokenMatches(token, s.adminToken) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid_admin_token"})
		return 0, "", false
	}
	installationID, err := strconv.ParseInt(strings.TrimSpace(r.Header.Get("X-CodeLens-Installation-ID")), 10, 64)
	if err != nil || installationID <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_installation_id"})
		return 0, "", false
	}
	actor := strings.TrimSpace(r.Header.Get("X-CodeLens-Actor"))
	if actor == "" {
		actor = "model-admin-token"
	}
	if len(actor) > 200 {
		actor = actor[:200]
	}
	return installationID, actor, true
}

func validateProviderFields(name, kind, baseURL, apiKey, model string, timeoutSeconds, maxRetries int, allowPrivate bool) error {
	if name == "" || len(name) > 100 {
		return errors.New("name must contain between 1 and 100 characters")
	}
	if kind != llm.ProviderOpenAICompatible && kind != llm.ProviderOpenAIResponses && kind != llm.ProviderAnthropic {
		return errors.New("providerKind is unsupported")
	}
	if apiKey == "" || len(apiKey) > 16_384 {
		return errors.New("apiKey is required and must not exceed 16 KB")
	}
	if model == "" || len(model) > 200 {
		return errors.New("defaultModel must contain between 1 and 200 characters")
	}
	if len(baseURL) > 2_048 {
		return errors.New("baseUrl must not exceed 2048 characters")
	}
	if _, err := llm.ValidateProviderBaseURL(baseURL, allowPrivate); err != nil {
		return err
	}
	if timeoutSeconds != 0 && (timeoutSeconds < 1 || timeoutSeconds > 120) {
		return errors.New("timeoutSeconds must be between 1 and 120")
	}
	if maxRetries < 0 || maxRetries > 5 {
		return errors.New("maxRetries must be between 0 and 5")
	}
	return nil
}

func decodeJSON(w http.ResponseWriter, r *http.Request, destination any) error {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return errors.New("request body must be valid JSON with known fields")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("request body must contain one JSON value")
	}
	return nil
}

func providerCredentialContext(installationID int64, connectionID string) string {
	return fmt.Sprintf("installation:%d:connection:%s", installationID, connectionID)
}

func boundedProviderDetail(value string) string {
	value = security.Redact(value)
	if len(value) > 500 {
		return value[:500]
	}
	return value
}

func (s *Server) providerStoreError(w http.ResponseWriter, err error) {
	if errors.Is(err, pgx.ErrNoRows) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "provider_not_found"})
		return
	}
	var postgresErr *pgconn.PgError
	if errors.As(err, &postgresErr) && postgresErr.Code == "23505" {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "provider_name_conflict"})
		return
	}
	s.providerInternalError(w, err)
}

func (s *Server) providerInternalError(w http.ResponseWriter, err error) {
	s.logger.Error("provider control-plane request failed", "error", boundedProviderDetail(err.Error()))
	writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "internal_error"})
}
