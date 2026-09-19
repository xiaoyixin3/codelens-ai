CREATE TABLE IF NOT EXISTS code_snapshots (
  id uuid PRIMARY KEY,
  github_repository_id bigint NOT NULL,
  commit_sha text NOT NULL,
  base_sha text NOT NULL,
  parser_version text NOT NULL,
  scope_hash text NOT NULL,
  status text NOT NULL,
  scope text NOT NULL,
  coverage jsonb,
  error_detail text,
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (github_repository_id, commit_sha, parser_version, scope_hash)
);

CREATE INDEX IF NOT EXISTS code_snapshots_repo_commit_idx
  ON code_snapshots (github_repository_id, commit_sha);

CREATE TABLE IF NOT EXISTS indexed_files (
  snapshot_id uuid NOT NULL REFERENCES code_snapshots(id) ON DELETE CASCADE,
  path text NOT NULL,
  language text,
  content_hash text,
  status text NOT NULL,
  skip_reason text,
  parse_errors integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (snapshot_id, path)
);

CREATE TABLE IF NOT EXISTS code_symbols (
  id uuid PRIMARY KEY,
  snapshot_id uuid NOT NULL REFERENCES code_snapshots(id) ON DELETE CASCADE,
  stable_key text NOT NULL,
  path text NOT NULL,
  kind text NOT NULL,
  name text NOT NULL,
  qualified_name text NOT NULL,
  start_line integer NOT NULL,
  end_line integer NOT NULL,
  signature text NOT NULL,
  content_hash text NOT NULL,
  exported boolean NOT NULL DEFAULT false,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (snapshot_id, stable_key)
);

CREATE INDEX IF NOT EXISTS code_symbols_snapshot_path_idx
  ON code_symbols (snapshot_id, path);
CREATE INDEX IF NOT EXISTS code_symbols_snapshot_name_idx
  ON code_symbols (snapshot_id, name);

CREATE TABLE IF NOT EXISTS code_edges (
  id uuid PRIMARY KEY,
  snapshot_id uuid NOT NULL REFERENCES code_snapshots(id) ON DELETE CASCADE,
  from_stable_key text NOT NULL,
  to_stable_key text NOT NULL,
  type text NOT NULL,
  confidence numeric(4,3) NOT NULL,
  source_path text NOT NULL,
  source_line integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (
    snapshot_id, from_stable_key, to_stable_key, type, source_path, source_line
  )
);

CREATE INDEX IF NOT EXISTS code_edges_from_idx
  ON code_edges (snapshot_id, from_stable_key);
CREATE INDEX IF NOT EXISTS code_edges_to_idx
  ON code_edges (snapshot_id, to_stable_key);
CREATE INDEX IF NOT EXISTS code_edges_path_idx
  ON code_edges (snapshot_id, source_path);
