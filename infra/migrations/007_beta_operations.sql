CREATE TABLE IF NOT EXISTS data_deletion_audit (
  id uuid PRIMARY KEY,
  repository_hash text NOT NULL,
  requested_by text NOT NULL,
  review_runs_deleted integer NOT NULL,
  snapshots_deleted integer NOT NULL,
  project_rules_deleted integer NOT NULL,
  llm_calls_deleted integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS data_deletion_audit_created_idx
  ON data_deletion_audit (created_at DESC);
