package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/xiaoyixin3/codelens-ai/internal/config"
	"github.com/xiaoyixin3/codelens-ai/internal/httpapi"
	"github.com/xiaoyixin3/codelens-ai/internal/store"
)

func main() {
	if err := config.LoadDotEnv(".env"); err != nil {
		slog.Error("could not load local environment", "error", err)
		os.Exit(1)
	}
	cfg, err := config.Load()
	if err != nil {
		slog.Error("invalid configuration", "error", err)
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	database, err := store.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		slog.Error("database unavailable", "error", err)
		os.Exit(1)
	}
	defer database.Close()

	handler := httpapi.New(httpapi.Options{
		Backend: database, WebhookSecret: cfg.GitHubWebhookSecret, GitHubAppID: cfg.GitHubAppID,
		RateLimitPerMin: cfg.WebhookRateLimitMax,
	}).Handler()
	server := &http.Server{Addr: cfg.Address(), Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second}
	go func() {
		slog.Info("CodeLens Go API listening", "address", cfg.Address())
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("API stopped unexpectedly", "error", err)
			stop()
		}
	}()
	<-ctx.Done()
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = server.Shutdown(shutdown)
}
