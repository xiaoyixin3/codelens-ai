package main

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/xiaoyixin3/codelens-ai/internal/config"
	"github.com/xiaoyixin3/codelens-ai/internal/githubapp"
	"github.com/xiaoyixin3/codelens-ai/internal/review"
	"github.com/xiaoyixin3/codelens-ai/internal/security"
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
	if cfg.GitHubAppID == "" || cfg.GitHubPrivateKey == "" {
		slog.Error("GITHUB_APP_ID and GITHUB_PRIVATE_KEY are required by the worker")
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
	if err := database.RecoverStaleJobs(ctx); err != nil {
		slog.Error("could not recover jobs", "error", err)
		os.Exit(1)
	}
	github, err := githubapp.New(cfg.GitHubAppID, cfg.GitHubPrivateKey)
	if err != nil {
		slog.Error("GitHub App configuration is invalid", "error", err)
		os.Exit(1)
	}
	engine := review.New(database, github, cfg.MaxChangedFiles, cfg.MaxPatchChars, cfg.MaxInlineComments)

	slog.Info("CodeLens Go worker started", "concurrency", 2)
	semaphore := make(chan struct{}, 2)
	var workers sync.WaitGroup
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			workers.Wait()
			return
		case <-ticker.C:
			for len(semaphore) < cap(semaphore) {
				job, err := database.ClaimJob(ctx)
				if errors.Is(err, pgx.ErrNoRows) {
					break
				}
				if err != nil {
					slog.Error("claim job failed", "error", security.Redact(err.Error()))
					break
				}
				semaphore <- struct{}{}
				workers.Add(1)
				go func() {
					defer func() { <-semaphore; workers.Done() }()
					jobCtx, cancel := context.WithTimeout(ctx, 10*time.Minute)
					defer cancel()
					if err := engine.Execute(jobCtx, job.Payload); err != nil {
						detail := security.Redact(err.Error())
						terminal, retryErr := database.RetryJob(context.Background(), job.ID, job.Attempts, detail)
						if retryErr != nil {
							slog.Error("record job failure failed", "error", retryErr)
							return
						}
						if terminal {
							_ = engine.Fail(context.Background(), job.Payload, detail)
						}
						slog.Error("review job failed", "reviewRunId", job.Payload.ReviewRunID, "terminal", terminal, "error", detail)
						return
					}
					if err := database.CompleteJob(context.Background(), job.ID); err != nil {
						slog.Error("complete job failed", "error", err)
						return
					}
					slog.Info("review run completed", "reviewRunId", job.Payload.ReviewRunID)
				}()
			}
		}
	}
}
