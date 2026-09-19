CREATE TABLE IF NOT EXISTS webhook_deliveries (
  delivery_id text PRIMARY KEY,
  event text NOT NULL,
  action text,
  payload_hash text NOT NULL,
  signature_valid boolean NOT NULL,
  status text NOT NULL DEFAULT 'received',
  error_detail text,
  received_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz
);

CREATE TABLE IF NOT EXISTS review_runs (
  id uuid PRIMARY KEY,
  github_repository_id bigint NOT NULL,
  pull_number integer NOT NULL,
  base_sha text NOT NULL,
  head_sha text NOT NULL,
  status text NOT NULL,
  pipeline_version text NOT NULL,
  config_hash text NOT NULL,
  summary jsonb,
  error_code text,
  error_detail text,
  started_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (github_repository_id, pull_number, head_sha, config_hash, pipeline_version)
);

CREATE INDEX IF NOT EXISTS review_runs_pr_created_idx
  ON review_runs (github_repository_id, pull_number, created_at DESC);

CREATE TABLE IF NOT EXISTS publications (
  id uuid PRIMARY KEY,
  review_run_id uuid NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
  head_sha text NOT NULL,
  check_run_id bigint,
  summary_comment_id bigint,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (review_run_id),
  UNIQUE (review_run_id, head_sha)
);
