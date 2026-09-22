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

func TestLoadRequiresCompleteModelControlPlaneConfiguration(t *testing.T) {
	t.Setenv("GITHUB_WEBHOOK_SECRET", "1234567890abcdef")
	t.Setenv("CODELENS_MODEL_ADMIN_TOKEN", "12345678901234567890123456789012")
	t.Setenv("CODELENS_CREDENTIAL_KEY", "")
	if _, err := Load(); err == nil {
		t.Fatal("expected incomplete model control-plane configuration to fail")
	}
}

func TestLoadEnablesPrivateModelEndpointsExplicitly(t *testing.T) {
	t.Setenv("GITHUB_WEBHOOK_SECRET", "1234567890abcdef")
	t.Setenv("CODELENS_ALLOW_PRIVATE_MODEL_ENDPOINTS", "true")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.AllowPrivateModels {
		t.Fatal("expected private model endpoints to be enabled")
	}
}
