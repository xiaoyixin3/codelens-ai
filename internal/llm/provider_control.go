package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/xiaoyixin3/codelens-ai/internal/security"
)

const (
	ProviderOpenAICompatible = "openai_compatible"
	ProviderOpenAIResponses  = "openai_responses"
	ProviderAnthropic        = "anthropic"
)

type ProviderConfig struct {
	Name         string
	Kind         string
	BaseURL      string
	APIKey       string
	Model        string
	Timeout      time.Duration
	MaxRetries   int
	AllowPrivate bool
}

type StructuredRequest struct {
	System          string
	User            string
	MaxOutputTokens int
}

type StructuredResponse struct {
	Content           string
	InputTokens       *int
	OutputTokens      *int
	HTTPStatus        int
	ProviderRequestID string
}

type ProviderTestResult struct {
	DurationMS        int    `json:"durationMs"`
	HTTPStatus        int    `json:"httpStatus"`
	ProviderRequestID string `json:"providerRequestId,omitempty"`
}

type ModelProvider interface {
	GenerateStructured(context.Context, StructuredRequest) (StructuredResponse, error)
	TestConnection(context.Context) (ProviderTestResult, error)
}

type HTTPProvider struct {
	config ProviderConfig
	http   *http.Client
}

type ProviderError struct {
	Status int
	Code   string
	Detail string
}

func (e *ProviderError) Error() string { return e.Detail }

func DefaultBaseURL(kind string) string {
	switch kind {
	case ProviderOpenAICompatible, ProviderOpenAIResponses:
		return "https://api.openai.com/v1"
	case ProviderAnthropic:
		return "https://api.anthropic.com"
	default:
		return ""
	}
}

func NewModelProvider(config ProviderConfig) (ModelProvider, error) {
	if config.Kind != ProviderOpenAICompatible && config.Kind != ProviderOpenAIResponses && config.Kind != ProviderAnthropic {
		return nil, fmt.Errorf("unsupported provider kind %q", config.Kind)
	}
	if strings.TrimSpace(config.APIKey) == "" || strings.TrimSpace(config.Model) == "" {
		return nil, errors.New("provider API key and model are required")
	}
	validated, err := ValidateProviderBaseURL(config.BaseURL, config.AllowPrivate)
	if err != nil {
		return nil, err
	}
	config.BaseURL = validated
	if config.Timeout <= 0 || config.Timeout > 120*time.Second {
		config.Timeout = 45 * time.Second
	}
	if config.MaxRetries < 0 || config.MaxRetries > 5 {
		config.MaxRetries = 2
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = http.ProxyFromEnvironment
	transport.DialContext = safeDialer(config.AllowPrivate)
	return &HTTPProvider{config: config, http: &http.Client{
		Timeout:   config.Timeout,
		Transport: transport,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}}, nil
}

func ValidateProviderBaseURL(raw string, allowPrivate bool) (string, error) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || parsed.Hostname() == "" {
		return "", errors.New("provider base URL is invalid")
	}
	if parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", errors.New("provider base URL cannot contain credentials, query, or fragment")
	}
	if parsed.Scheme != "https" && !(allowPrivate && parsed.Scheme == "http") {
		return "", errors.New("provider base URL must use HTTPS")
	}
	if !allowPrivate && unsafeHostname(parsed.Hostname()) {
		return "", errors.New("private or local provider endpoints are disabled")
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/")
	return strings.TrimRight(parsed.String(), "/"), nil
}

func (p *HTTPProvider) TestConnection(ctx context.Context) (ProviderTestResult, error) {
	started := time.Now()
	response, err := p.GenerateStructured(ctx, StructuredRequest{
		System: "Return a minimal JSON object and no other text.", User: `Reply with {"ok":true}.`, MaxOutputTokens: 32,
	})
	result := ProviderTestResult{DurationMS: int(time.Since(started).Milliseconds()), HTTPStatus: response.HTTPStatus, ProviderRequestID: response.ProviderRequestID}
	if err != nil {
		return result, err
	}
	if strings.TrimSpace(response.Content) == "" {
		return result, &ProviderError{Status: response.HTTPStatus, Code: "empty_response", Detail: "provider returned an empty response"}
	}
	return result, nil
}

func (p *HTTPProvider) GenerateStructured(ctx context.Context, input StructuredRequest) (StructuredResponse, error) {
	if input.MaxOutputTokens <= 0 {
		input.MaxOutputTokens = 1024
	}
	body, endpoint, err := p.requestBody(input)
	if err != nil {
		return StructuredResponse{}, err
	}
	var last error
	for attempt := 0; attempt <= p.config.MaxRetries; attempt++ {
		response, requestErr := p.send(ctx, endpoint, body)
		if requestErr == nil {
			return response, nil
		}
		last = requestErr
		var providerErr *ProviderError
		transient := !errors.As(requestErr, &providerErr) || providerErr.Status == http.StatusTooManyRequests || providerErr.Status >= 500
		if !transient || attempt == p.config.MaxRetries {
			break
		}
		delay := time.Duration(1<<attempt) * 250 * time.Millisecond
		select {
		case <-ctx.Done():
			return StructuredResponse{}, ctx.Err()
		case <-time.After(delay):
		}
	}
	return StructuredResponse{}, last
}

func (p *HTTPProvider) requestBody(input StructuredRequest) ([]byte, string, error) {
	var body map[string]any
	var endpoint string
	switch p.config.Kind {
	case ProviderOpenAICompatible:
		endpoint = p.config.BaseURL + "/chat/completions"
		body = map[string]any{
			"model": p.config.Model, "max_tokens": input.MaxOutputTokens, "temperature": 0,
			"response_format": map[string]string{"type": "json_object"},
			"messages":        []map[string]string{{"role": "system", "content": input.System}, {"role": "user", "content": input.User}},
		}
	case ProviderOpenAIResponses:
		endpoint = p.config.BaseURL + "/responses"
		body = map[string]any{
			"model": p.config.Model, "max_output_tokens": input.MaxOutputTokens,
			"input": []map[string]any{
				{"role": "system", "content": []map[string]string{{"type": "input_text", "text": input.System}}},
				{"role": "user", "content": []map[string]string{{"type": "input_text", "text": input.User}}},
			},
			"text": map[string]any{"format": map[string]string{"type": "json_object"}},
		}
	case ProviderAnthropic:
		endpoint = p.config.BaseURL + "/v1/messages"
		body = map[string]any{
			"model": p.config.Model, "max_tokens": input.MaxOutputTokens, "temperature": 0,
			"system": input.System, "messages": []map[string]string{{"role": "user", "content": input.User}},
		}
	}
	encoded, err := json.Marshal(body)
	return encoded, endpoint, err
}

func (p *HTTPProvider) send(ctx context.Context, endpoint string, body []byte) (StructuredResponse, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return StructuredResponse{}, err
	}
	request.Header.Set("Content-Type", "application/json")
	if p.config.Kind == ProviderAnthropic {
		request.Header.Set("x-api-key", p.config.APIKey)
		request.Header.Set("anthropic-version", "2023-06-01")
	} else {
		request.Header.Set("Authorization", "Bearer "+p.config.APIKey)
	}
	response, err := p.http.Do(request)
	if err != nil {
		return StructuredResponse{}, fmt.Errorf("provider request failed: %w", err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 2<<20))
	if err != nil {
		return StructuredResponse{}, fmt.Errorf("read provider response: %w", err)
	}
	requestID := response.Header.Get("x-request-id")
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		detail := truncate(security.Redact(string(data)), 500)
		return StructuredResponse{HTTPStatus: response.StatusCode, ProviderRequestID: requestID}, &ProviderError{
			Status: response.StatusCode, Code: fmt.Sprintf("http_%d", response.StatusCode), Detail: fmt.Sprintf("provider returned HTTP %d: %s", response.StatusCode, detail),
		}
	}
	parsed, err := parseProviderResponse(p.config.Kind, data)
	if err != nil {
		return StructuredResponse{HTTPStatus: response.StatusCode, ProviderRequestID: requestID}, err
	}
	parsed.HTTPStatus = response.StatusCode
	parsed.ProviderRequestID = requestID
	return parsed, nil
}

func parseProviderResponse(kind string, data []byte) (StructuredResponse, error) {
	switch kind {
	case ProviderOpenAICompatible:
		var payload compatibleResponse
		if err := json.Unmarshal(data, &payload); err != nil || len(payload.Choices) == 0 {
			return StructuredResponse{}, errors.New("provider response did not match OpenAI Chat Completions")
		}
		return StructuredResponse{Content: security.Redact(payload.Choices[0].Message.Content), InputTokens: payload.Usage.PromptTokens, OutputTokens: payload.Usage.CompletionTokens}, nil
	case ProviderOpenAIResponses:
		var payload struct {
			OutputText string `json:"output_text"`
			Output     []struct {
				Content []struct {
					Type string `json:"type"`
					Text string `json:"text"`
				} `json:"content"`
			} `json:"output"`
			Usage struct {
				InputTokens  *int `json:"input_tokens"`
				OutputTokens *int `json:"output_tokens"`
			} `json:"usage"`
		}
		if err := json.Unmarshal(data, &payload); err != nil {
			return StructuredResponse{}, errors.New("provider response did not match OpenAI Responses")
		}
		content := payload.OutputText
		if content == "" {
			for _, output := range payload.Output {
				for _, item := range output.Content {
					if item.Type == "output_text" {
						content += item.Text
					}
				}
			}
		}
		return StructuredResponse{Content: security.Redact(content), InputTokens: payload.Usage.InputTokens, OutputTokens: payload.Usage.OutputTokens}, nil
	case ProviderAnthropic:
		var payload struct {
			Content []struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"content"`
			Usage struct {
				InputTokens  *int `json:"input_tokens"`
				OutputTokens *int `json:"output_tokens"`
			} `json:"usage"`
		}
		if err := json.Unmarshal(data, &payload); err != nil {
			return StructuredResponse{}, errors.New("provider response did not match Anthropic Messages")
		}
		content := ""
		for _, item := range payload.Content {
			if item.Type == "text" {
				content += item.Text
			}
		}
		return StructuredResponse{Content: security.Redact(content), InputTokens: payload.Usage.InputTokens, OutputTokens: payload.Usage.OutputTokens}, nil
	default:
		return StructuredResponse{}, errors.New("unsupported provider response")
	}
}

func safeDialer(allowPrivate bool) func(context.Context, string, string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		addresses, err := net.DefaultResolver.LookupIPAddr(ctx, host)
		if err != nil {
			return nil, err
		}
		if len(addresses) == 0 {
			return nil, errors.New("provider hostname did not resolve")
		}
		if !allowPrivate {
			for _, candidate := range addresses {
				if unsafeIP(candidate.IP) {
					return nil, errors.New("provider hostname resolved to a private or local address")
				}
			}
		}
		var lastErr error
		for _, candidate := range addresses {
			connection, dialErr := dialer.DialContext(ctx, network, net.JoinHostPort(candidate.IP.String(), port))
			if dialErr == nil {
				return connection, nil
			}
			lastErr = dialErr
		}
		return nil, lastErr
	}
}

func unsafeHostname(host string) bool {
	value := strings.ToLower(strings.TrimSuffix(host, "."))
	if value == "localhost" || strings.HasSuffix(value, ".localhost") || strings.HasSuffix(value, ".local") || value == "metadata.google.internal" {
		return true
	}
	if parsed := net.ParseIP(value); parsed != nil {
		return unsafeIP(parsed)
	}
	return false
}

func unsafeIP(ip net.IP) bool {
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified()
}
