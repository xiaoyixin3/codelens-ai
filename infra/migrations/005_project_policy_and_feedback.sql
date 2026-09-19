ALTER TABLE review_runs ADD COLUMN IF NOT EXISTS trigger text NOT NULL DEFAULT 'webhook';
ALTER TABLE review_runs ADD COLUMN IF NOT EXISTS request_key text NOT NULL DEFAULT 'automatic';

DO $$
DECLARE old_constraint text;
BEGIN
  SELECT conname INTO old_constraint
  FROM pg_constraint
  WHERE conrelid = 'review_runs'::regclass
    AND contype = 'u'
    AND pg_get_constraintdef(oid) LIKE '%config_hash%'
  LIMIT 1;
  IF old_constraint IS NOT NULL THEN
    EXECUTE format('ALTER TABLE review_runs DROP CONSTRAINT %I', old_constraint);
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS review_runs_request_key_unique
  ON review_runs (github_repository_id, pull_number, head_sha, pipeline_version, request_key);

CREATE TABLE IF NOT EXISTS project_rules (
  id uuid PRIMARY KEY,
  github_repository_id bigint NOT NULL,
  source text NOT NULL,
  rule_key text NOT NULL,
  content text NOT NULL,
  scope_glob text,
  severity text,
  enabled boolean NOT NULL DEFAULT true,
  source_commit_sha text NOT NULL,
  config_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (github_repository_id, source_commit_sha, source, rule_key)
);

CREATE TABLE IF NOT EXISTS finding_feedback (
  id uuid PRIMARY KEY,
  finding_id uuid NOT NULL REFERENCES findings(id) ON DELETE CASCADE,
  verdict text NOT NULL,
  actor_login text NOT NULL,
  source_comment_id bigint NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS finding_feedback_finding_idx ON finding_feedback (finding_id, created_at DESC);
