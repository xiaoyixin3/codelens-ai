package credentials

import (
	"encoding/base64"
	"strings"
	"testing"
)

func testKey() string {
	return base64.StdEncoding.EncodeToString([]byte("0123456789abcdef0123456789abcdef"))
}

func TestVaultRoundTripUsesAuthenticatedContext(t *testing.T) {
	vault, err := New(testKey())
	if err != nil {
		t.Fatal(err)
	}
	sealed, err := vault.Seal("provider-secret", "installation:42:connection:abc")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(sealed, "provider-secret") {
		t.Fatal("ciphertext exposed plaintext")
	}
	opened, err := vault.Open(sealed, "installation:42:connection:abc")
	if err != nil || opened != "provider-secret" {
		t.Fatalf("round trip failed: value=%q err=%v", opened, err)
	}
	if _, err := vault.Open(sealed, "installation:43:connection:abc"); err == nil {
		t.Fatal("expected associated-context validation failure")
	}
}

func TestVaultRejectsInvalidKeysAndTampering(t *testing.T) {
	if _, err := New(base64.StdEncoding.EncodeToString([]byte("too-short"))); err == nil {
		t.Fatal("expected short key rejection")
	}
	vault, _ := New(testKey())
	sealed, _ := vault.Seal("secret", "scope")
	last := sealed[len(sealed)-1]
	replacement := byte('A')
	if last == replacement {
		replacement = 'B'
	}
	sealed = sealed[:len(sealed)-1] + string(replacement)
	if _, err := vault.Open(sealed, "scope"); err == nil {
		t.Fatal("expected tamper rejection")
	}
}

func TestTokenMatches(t *testing.T) {
	if !TokenMatches("same-token", "same-token") || TokenMatches("wrong", "same-token") {
		t.Fatal("constant-time token comparison returned an invalid result")
	}
}
