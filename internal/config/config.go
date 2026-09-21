package config

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
)

// LoadDotEnv loads simple KEY=VALUE entries without overriding the process
// environment. It is intentionally optional so production remains env-only.
func LoadDotEnv(path string) error {
	file, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(strings.TrimPrefix(key, "export "))
		if key == "" {
			continue
		}
		if _, exists := os.LookupEnv(key); exists {
			continue
		}
		value = strings.TrimSpace(value)
		if len(value) >= 2 && ((value[0] == '"' && value[len(value)-1] == '"') || (value[0] == '\'' && value[len(value)-1] == '\'')) {
			value = value[1 : len(value)-1]
		}
		if err := os.Setenv(key, value); err != nil {
			return err
		}
	}
	return scanner.Err()
}

type Config struct {
	Environment         string
	Port                int
	LogLevel            string
	DatabaseURL         string
	GitHubAppID         string
	GitHubPrivateKey    string
	GitHubWebhookSecret string
	WebhookRateLimitMax int
	MaxChangedFiles     int
	MaxPatchChars       int
	MaxInlineComments   int
}

func Load() (Config, error) {
	cfg := Config{
		Environment:         env("NODE_ENV", "development"),
		Port:                envInt("PORT", 3000),
		LogLevel:            env("LOG_LEVEL", "info"),
		DatabaseURL:         env("DATABASE_URL", "postgres://codelens:codelens@localhost:5432/codelens"),
		GitHubAppID:         strings.TrimSpace(os.Getenv("GITHUB_APP_ID")),
		GitHubPrivateKey:    strings.ReplaceAll(os.Getenv("GITHUB_PRIVATE_KEY"), `\n`, "\n"),
		GitHubWebhookSecret: os.Getenv("GITHUB_WEBHOOK_SECRET"),
		WebhookRateLimitMax: envInt("WEBHOOK_RATE_LIMIT_MAX", 300),
		MaxChangedFiles:     envInt("MAX_CHANGED_FILES", 100),
		MaxPatchChars:       envInt("MAX_PATCH_CHARS", 120000),
		MaxInlineComments:   envInt("MAX_INLINE_COMMENTS", 8),
	}

	if cfg.Port < 1 || cfg.Port > 65535 {
		return Config{}, fmt.Errorf("PORT must be between 1 and 65535")
	}
	if cfg.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}
	if len(cfg.GitHubWebhookSecret) < 16 {
		return Config{}, errors.New("GITHUB_WEBHOOK_SECRET must contain at least 16 characters")
	}
	if cfg.WebhookRateLimitMax < 1 || cfg.MaxChangedFiles < 1 || cfg.MaxPatchChars < 1 || cfg.MaxInlineComments < 0 {
		return Config{}, errors.New("numeric limits are invalid")
	}
	return cfg, nil
}

func (c Config) Address() string { return fmt.Sprintf(":%d", c.Port) }

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envInt(name string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}
