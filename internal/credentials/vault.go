package credentials

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
)

const KeyVersion = 1

type Vault struct {
	aead cipher.AEAD
}

func New(encodedKey string) (*Vault, error) {
	key, err := base64.StdEncoding.DecodeString(encodedKey)
	if err != nil {
		return nil, errors.New("credential encryption key must be standard base64")
	}
	if len(key) != 32 {
		return nil, errors.New("credential encryption key must decode to exactly 32 bytes")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("create credential cipher: %w", err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("create credential AEAD: %w", err)
	}
	return &Vault{aead: aead}, nil
}

func (v *Vault) Seal(plaintext, context string) (string, error) {
	if plaintext == "" {
		return "", errors.New("credential cannot be empty")
	}
	nonce := make([]byte, v.aead.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", fmt.Errorf("create credential nonce: %w", err)
	}
	sealed := v.aead.Seal(nil, nonce, []byte(plaintext), []byte(context))
	payload := append(nonce, sealed...)
	return base64.RawStdEncoding.EncodeToString(payload), nil
}

func (v *Vault) Open(encoded, context string) (string, error) {
	payload, err := base64.RawStdEncoding.DecodeString(encoded)
	if err != nil {
		return "", errors.New("credential ciphertext is invalid")
	}
	if len(payload) <= v.aead.NonceSize() {
		return "", errors.New("credential ciphertext is truncated")
	}
	nonce, ciphertext := payload[:v.aead.NonceSize()], payload[v.aead.NonceSize():]
	plaintext, err := v.aead.Open(nil, nonce, ciphertext, []byte(context))
	if err != nil {
		return "", errors.New("credential ciphertext authentication failed")
	}
	return string(plaintext), nil
}

func Fingerprint(secret string) string {
	digest := sha256.Sum256([]byte(secret))
	return "sha256:" + hex.EncodeToString(digest[:])[:12]
}

func TokenMatches(actual, expected string) bool {
	actualDigest := sha256.Sum256([]byte(actual))
	expectedDigest := sha256.Sum256([]byte(expected))
	return subtle.ConstantTimeCompare(actualDigest[:], expectedDigest[:]) == 1
}
