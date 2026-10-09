CREATE TABLE IF NOT EXISTS repository_snapshots (
  id uuid PRIMARY KEY,
  github_repository_id bigint NOT NULL,
  commit_sha text NOT NULL,
  adapter_version text NOT NULL,
  build_model_hash text NOT NULL,
  status text NOT NULL,
  analysis_level text NOT NULL,
  execution_level text NOT NULL,
  parent_snapshot_id uuid REFERENCES repository_snapshots(id) ON DELETE SET NULL,
  coverage jsonb NOT NULL,
  degradation_reasons jsonb NOT NULL DEFAULT '{}'::jsonb,
  reused_file_count integer NOT NULL DEFAULT 0,
  error_detail text,
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT repository_snapshots_sha_format CHECK (commit_sha ~ '^[0-9a-fA-F]{40}$'),
  CONSTRAINT repository_snapshots_reused_files_nonnegative CHECK (reused_file_count >= 0),
  UNIQUE (github_repository_id, commit_sha, adapter_version, build_model_hash)
);

CREATE INDEX IF NOT EXISTS repository_snapshots_lookup_idx
  ON repository_snapshots (github_repository_id, commit_sha, adapter_version, build_model_hash);

CREATE TABLE IF NOT EXISTS semantic_index_files (
  snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id) ON DELETE CASCADE,
  path text NOT NULL,
  language text NOT NULL,
  content_hash text,
  status text NOT NULL,
  skip_reason text,
  test_source boolean NOT NULL DEFAULT false,
  reused boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (snapshot_id, path)
);

CREATE TABLE IF NOT EXISTS semantic_symbols (
  id uuid PRIMARY KEY,
  snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id) ON DELETE CASCADE,
  stable_key text NOT NULL,
  kind text NOT NULL,
  qualified_name text NOT NULL,
  signature text NOT NULL,
  path text NOT NULL,
  start_line integer NOT NULL,
  end_line integer NOT NULL,
  test_source boolean NOT NULL DEFAULT false,
  type_resolved boolean NOT NULL,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT semantic_symbols_lines_valid CHECK (start_line > 0 AND end_line >= start_line),
  UNIQUE (snapshot_id, stable_key)
);

CREATE INDEX IF NOT EXISTS semantic_symbols_path_idx
  ON semantic_symbols (snapshot_id, path);
CREATE INDEX IF NOT EXISTS semantic_symbols_qualified_name_idx
  ON semantic_symbols (snapshot_id, qualified_name);

CREATE TABLE IF NOT EXISTS semantic_relationships (
  id uuid PRIMARY KEY,
  snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id) ON DELETE CASCADE,
  from_stable_key text NOT NULL,
  to_stable_key text NOT NULL,
  relationship_type text NOT NULL,
  source_path text NOT NULL,
  source_line integer NOT NULL,
  confidence numeric(4,3) NOT NULL,
  type_resolved boolean NOT NULL,
  adapter_version text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT semantic_relationships_source_line_valid CHECK (source_line > 0),
  CONSTRAINT semantic_relationships_confidence_valid CHECK (confidence >= 0 AND confidence <= 1),
  UNIQUE (snapshot_id, from_stable_key, to_stable_key, relationship_type, source_path, source_line)
);

CREATE INDEX IF NOT EXISTS semantic_relationships_from_idx
  ON semantic_relationships (snapshot_id, from_stable_key, relationship_type);
CREATE INDEX IF NOT EXISTS semantic_relationships_to_idx
  ON semantic_relationships (snapshot_id, to_stable_key, relationship_type);
CREATE INDEX IF NOT EXISTS semantic_relationships_source_path_idx
  ON semantic_relationships (snapshot_id, source_path);

