package githubapp

import (
	"bytes"
	"context"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const summaryMarker = "<!-- codelens-ai:summary -->"

type Client struct {
	appID      string
	privateKey *rsa.PrivateKey
	http       *http.Client
	baseURL    string
	mu         sync.Mutex
	tokens     map[int64]cachedToken
}

type cachedToken struct {
	value     string
	expiresAt time.Time
}

type ChangedFile struct {
	Path         string `json:"filename"`
	Status       string `json:"status"`
	Additions    int    `json:"additions"`
	Deletions    int    `json:"deletions"`
	Patch        string `json:"patch"`
	PreviousPath string `json:"previous_filename,omitempty"`
}

type PullRequest struct {
	Number  int
	Title   string
	Body    string
	BaseSHA string
	HeadSHA string
	Files   []ChangedFile
}

type Annotation struct {
	Path       string `json:"path"`
	StartLine  int    `json:"start_line"`
	EndLine    int    `json:"end_line"`
	Level      string `json:"annotation_level"`
	Title      string `json:"title"`
	Message    string `json:"message"`
	RawDetails string `json:"raw_details,omitempty"`
}

func New(appID, privateKey string) (*Client, error) {
	key, err := parsePrivateKey([]byte(privateKey))
	if err != nil {
		return nil, err
	}
	return &Client{
		appID: appID, privateKey: key, http: &http.Client{Timeout: 30 * time.Second},
		baseURL: "https://api.github.com", tokens: make(map[int64]cachedToken),
	}, nil
}

func parsePrivateKey(source []byte) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode(source)
	if block == nil {
		return nil, errors.New("GITHUB_PRIVATE_KEY is not valid PEM")
	}
	if key, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
		return key, nil
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse GitHub private key: %w", err)
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("GitHub private key is not RSA")
	}
	return key, nil
}

func (c *Client) appJWT() (string, error) {
	now := time.Now()
	claims := jwt.RegisteredClaims{
		Issuer:    c.appID,
		IssuedAt:  jwt.NewNumericDate(now.Add(-60 * time.Second)),
		ExpiresAt: jwt.NewNumericDate(now.Add(9 * time.Minute)),
	}
	return jwt.NewWithClaims(jwt.SigningMethodRS256, claims).SignedString(c.privateKey)
}

func (c *Client) installationToken(ctx context.Context, installationID int64) (string, error) {
	c.mu.Lock()
	cached := c.tokens[installationID]
	c.mu.Unlock()
	if cached.value != "" && time.Until(cached.expiresAt) > 2*time.Minute {
		return cached.value, nil
	}

	appToken, err := c.appJWT()
	if err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		fmt.Sprintf("%s/app/installations/%d/access_tokens", c.baseURL, installationID), nil)
	if err != nil {
		return "", err
	}
	request.Header.Set("Authorization", "Bearer "+appToken)
	request.Header.Set("Accept", "application/vnd.github+json")
	request.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	response, err := c.http.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		data, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
		return "", fmt.Errorf("GitHub installation token: status %d: %s", response.StatusCode, strings.TrimSpace(string(data)))
	}
	var payload struct {
		Token     string    `json:"token"`
		ExpiresAt time.Time `json:"expires_at"`
	}
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		return "", err
	}
	c.mu.Lock()
	c.tokens[installationID] = cachedToken{value: payload.Token, expiresAt: payload.ExpiresAt}
	c.mu.Unlock()
	return payload.Token, nil
}

func (c *Client) doJSON(ctx context.Context, installationID int64, method, path string, body, out any) error {
	token, err := c.installationToken(ctx, installationID)
	if err != nil {
		return err
	}
	var encoded []byte
	if body != nil {
		encoded, err = json.Marshal(body)
		if err != nil {
			return err
		}
	}
	for attempt := 0; attempt < 3; attempt++ {
		request, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, bytes.NewReader(encoded))
		if err != nil {
			return err
		}
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set("Accept", "application/vnd.github+json")
		request.Header.Set("X-GitHub-Api-Version", "2022-11-28")
		if body != nil {
			request.Header.Set("Content-Type", "application/json")
		}
		response, err := c.http.Do(request)
		if err != nil {
			if attempt < 2 {
				time.Sleep(time.Duration(1<<attempt) * 500 * time.Millisecond)
				continue
			}
			return err
		}
		data, readErr := io.ReadAll(io.LimitReader(response.Body, 8<<20))
		response.Body.Close()
		if readErr != nil {
			return readErr
		}
		if response.StatusCode >= 200 && response.StatusCode < 300 {
			if out != nil && len(data) > 0 {
				return json.Unmarshal(data, out)
			}
			return nil
		}
		if (response.StatusCode == http.StatusTooManyRequests || response.StatusCode >= 500) && attempt < 2 {
			time.Sleep(time.Duration(1<<attempt) * time.Second)
			continue
		}
		return fmt.Errorf("GitHub %s %s: status %d: %s", method, path, response.StatusCode, strings.TrimSpace(string(data)))
	}
	return errors.New("GitHub request retry exhausted")
}

func repoPath(owner, repo string) string {
	return "/repos/" + url.PathEscape(owner) + "/" + url.PathEscape(repo)
}

func (c *Client) GetPullRequest(ctx context.Context, installationID int64, owner, repo string, number int) (PullRequest, error) {
	var pull struct {
		Number int    `json:"number"`
		Title  string `json:"title"`
		Body   string `json:"body"`
		Base   struct {
			SHA string `json:"sha"`
		} `json:"base"`
		Head struct {
			SHA string `json:"sha"`
		} `json:"head"`
	}
	base := repoPath(owner, repo)
	if err := c.doJSON(ctx, installationID, http.MethodGet, fmt.Sprintf("%s/pulls/%d", base, number), nil, &pull); err != nil {
		return PullRequest{}, err
	}
	files := make([]ChangedFile, 0)
	for page := 1; ; page++ {
		var batch []ChangedFile
		path := fmt.Sprintf("%s/pulls/%d/files?per_page=100&page=%d", base, number, page)
		if err := c.doJSON(ctx, installationID, http.MethodGet, path, nil, &batch); err != nil {
			return PullRequest{}, err
		}
		files = append(files, batch...)
		if len(batch) < 100 {
			break
		}
	}
	return PullRequest{Number: pull.Number, Title: pull.Title, Body: pull.Body, BaseSHA: pull.Base.SHA, HeadSHA: pull.Head.SHA, Files: files}, nil
}

func (c *Client) CurrentHead(ctx context.Context, installationID int64, owner, repo string, number int) (string, error) {
	var pull struct {
		Head struct {
			SHA string `json:"sha"`
		} `json:"head"`
	}
	err := c.doJSON(ctx, installationID, http.MethodGet,
		fmt.Sprintf("%s/pulls/%d", repoPath(owner, repo), number), nil, &pull)
	return pull.Head.SHA, err
}

func (c *Client) StartCheck(ctx context.Context, installationID int64, owner, repo, headSHA string, existing *int64) (int64, error) {
	base := repoPath(owner, repo)
	if existing != nil {
		payload := map[string]any{"status": "in_progress", "started_at": time.Now().UTC().Format(time.RFC3339)}
		if err := c.doJSON(ctx, installationID, http.MethodPatch, fmt.Sprintf("%s/check-runs/%d", base, *existing), payload, nil); err != nil {
			return 0, err
		}
		return *existing, nil
	}
	payload := map[string]any{"name": "CodeLens AI Review", "head_sha": headSHA, "status": "in_progress", "started_at": time.Now().UTC().Format(time.RFC3339)}
	var result struct {
		ID int64 `json:"id"`
	}
	if err := c.doJSON(ctx, installationID, http.MethodPost, base+"/check-runs", payload, &result); err != nil {
		return 0, err
	}
	return result.ID, nil
}

func (c *Client) CompleteCheck(ctx context.Context, installationID int64, owner, repo string, checkID int64, conclusion, title, summary string, annotations []Annotation) error {
	if len(annotations) > 50 {
		annotations = annotations[:50]
	}
	output := map[string]any{"title": title, "summary": summary}
	if len(annotations) > 0 {
		output["annotations"] = annotations
	}
	payload := map[string]any{
		"status": "completed", "conclusion": conclusion,
		"completed_at": time.Now().UTC().Format(time.RFC3339), "output": output,
	}
	return c.doJSON(ctx, installationID, http.MethodPatch,
		fmt.Sprintf("%s/check-runs/%d", repoPath(owner, repo), checkID), payload, nil)
}

func (c *Client) UpsertSummaryComment(ctx context.Context, installationID int64, owner, repo string, pullNumber int, body string, existing *int64) (int64, error) {
	base := repoPath(owner, repo)
	fullBody := summaryMarker + "\n" + body
	commentID := existing
	if commentID == nil {
		var comments []struct {
			ID   int64  `json:"id"`
			Body string `json:"body"`
		}
		path := fmt.Sprintf("%s/issues/%d/comments?per_page=100", base, pullNumber)
		if err := c.doJSON(ctx, installationID, http.MethodGet, path, nil, &comments); err != nil {
			return 0, err
		}
		for _, comment := range comments {
			if strings.Contains(comment.Body, summaryMarker) {
				id := comment.ID
				commentID = &id
				break
			}
		}
	}
	if commentID != nil {
		if err := c.doJSON(ctx, installationID, http.MethodPatch,
			fmt.Sprintf("%s/issues/comments/%d", base, *commentID), map[string]string{"body": fullBody}, nil); err != nil {
			return 0, err
		}
		return *commentID, nil
	}
	var result struct {
		ID int64 `json:"id"`
	}
	if err := c.doJSON(ctx, installationID, http.MethodPost,
		fmt.Sprintf("%s/issues/%d/comments", base, pullNumber), map[string]string{"body": fullBody}, &result); err != nil {
		return 0, err
	}
	return result.ID, nil
}

func ParseAppID(value string) (int64, error) { return strconv.ParseInt(value, 10, 64) }
