CREATE TABLE IF NOT EXISTS semantic_review_analyses (
  review_run_id uuid PRIMARY KEY REFERENCES review_runs(id) ON DELETE CASCADE,
  base_snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id) ON DELETE CASCADE,
  head_snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id) ON DELETE CASCADE,
  adapter_version text NOT NULL,
  analysis_level text NOT NULL,
  execution_level text NOT NULL,
  impact jsonb NOT NULL,
  coverage jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS semantic_review_analyses_snapshots_idx
  ON semantic_review_analyses (base_snapshot_id, head_snapshot_id);
