package config

import "testing"

func TestLoadDefaultsAndPrivateKeyNormalization(t *testing.T) {
	t.Setenv("GITHUB_WEBHOOK_SECRET", "1234567890abcdef")
	t.Setenv("GITHUB_PRIVATE_KEY", "first\\nsecond")
	t.Setenv("PORT", "4312")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 4312 {
		t.Fatalf("port=%d", cfg.Port)
	}
	if cfg.GitHubPrivateKey != "first\nsecond" {
		t.Fatalf("private key was not normalized")
	}
}

func TestLoadRejectsShortWebhookSecret(t *testing.T) {
	t.Setenv("GITHUB_WEBHOOK_SECRET", "short")
	if _, err := Load(); err == nil {
		t.Fatal("expected validation error")
	}
}
