package security

import "testing"

func TestVerifyWebhook(t *testing.T) {
	body := []byte(`{"action":"opened"}`)
	secret := "a-secret-longer-than-sixteen"
	signature := SignWebhook(body, secret)
	if !VerifyWebhook(body, signature, secret) {
		t.Fatal("expected signature to verify")
	}
	if VerifyWebhook([]byte("changed"), signature, secret) {
		t.Fatal("changed body must not verify")
	}
	if VerifyWebhook(body, "sha256=bad", secret) {
		t.Fatal("malformed signature must not verify")
	}
}

func TestRedact(t *testing.T) {
	result := Redact("api_key=super-secret-value")
	if result != "api_key=[REDACTED]" {
		t.Fatalf("unexpected redaction: %s", result)
	}
}
