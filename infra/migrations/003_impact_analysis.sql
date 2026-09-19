CREATE TABLE IF NOT EXISTS impact_analyses (
  id uuid PRIMARY KEY,
  review_run_id uuid NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
  base_snapshot_id uuid NOT NULL REFERENCES code_snapshots(id) ON DELETE CASCADE,
  head_snapshot_id uuid NOT NULL REFERENCES code_snapshots(id) ON DELETE CASCADE,
  max_depth integer NOT NULL,
  blast_radius_score integer NOT NULL,
  risk_level text NOT NULL,
  coverage jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (review_run_id)
);

CREATE TABLE IF NOT EXISTS symbol_changes (
  id uuid PRIMARY KEY,
  impact_analysis_id uuid NOT NULL REFERENCES impact_analyses(id) ON DELETE CASCADE,
  change_type text NOT NULL,
  kind text NOT NULL,
  qualified_name text NOT NULL,
  before_stable_key text,
  after_stable_key text,
  before_path text,
  after_path text,
  body_changed boolean NOT NULL,
  signature_changed boolean NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS symbol_changes_analysis_idx
  ON symbol_changes (impact_analysis_id, change_type);

CREATE TABLE IF NOT EXISTS impact_paths (
  id uuid PRIMARY KEY,
  impact_analysis_id uuid NOT NULL REFERENCES impact_analyses(id) ON DELETE CASCADE,
  changed_stable_key text NOT NULL,
  impacted_stable_key text NOT NULL,
  impacted_name text NOT NULL,
  impacted_path text NOT NULL,
  impacted_kind text NOT NULL,
  depth integer NOT NULL,
  score numeric(6,3) NOT NULL,
  path jsonb NOT NULL,
  evidence jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS impact_paths_changed_idx
  ON impact_paths (impact_analysis_id, changed_stable_key);
CREATE INDEX IF NOT EXISTS impact_paths_impacted_idx
  ON impact_paths (impact_analysis_id, impacted_stable_key);
