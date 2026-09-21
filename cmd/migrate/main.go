package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/xiaoyixin3/codelens-ai/internal/config"
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
	ctx := context.Background()
	connection, err := pgx.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		slog.Error("database unavailable", "error", err)
		os.Exit(1)
	}
	defer connection.Close(ctx)
	if _, err := connection.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		slog.Error("create migration ledger", "error", err)
		os.Exit(1)
	}
	directory := os.Getenv("MIGRATIONS_DIR")
	if directory == "" {
		directory = "infra/migrations"
	}
	files, err := filepath.Glob(filepath.Join(directory, "*.sql"))
	if err != nil {
		slog.Error("list migrations", "error", err)
		os.Exit(1)
	}
	sort.Strings(files)
	for _, path := range files {
		name := filepath.Base(path)
		var applied string
		err := connection.QueryRow(ctx, `SELECT name FROM schema_migrations WHERE name=$1`, name).Scan(&applied)
		if err == nil {
			continue
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			slog.Error("read migration ledger", "error", err)
			os.Exit(1)
		}
		source, err := os.ReadFile(path)
		if err != nil {
			slog.Error("read migration", "name", name, "error", err)
			os.Exit(1)
		}
		tx, err := connection.Begin(ctx)
		if err != nil {
			slog.Error("begin migration", "name", name, "error", err)
			os.Exit(1)
		}
		if _, err = tx.Exec(ctx, string(source)); err == nil {
			_, err = tx.Exec(ctx, `INSERT INTO schema_migrations(name) VALUES($1)`, name)
		}
		if err != nil {
			_ = tx.Rollback(ctx)
			slog.Error("apply migration", "name", name, "error", fmt.Errorf("%s", strings.TrimSpace(err.Error())))
			os.Exit(1)
		}
		if err := tx.Commit(ctx); err != nil {
			slog.Error("commit migration", "name", name, "error", err)
			os.Exit(1)
		}
		slog.Info("migration applied", "name", name)
	}
	slog.Info("database migrations completed")
}
