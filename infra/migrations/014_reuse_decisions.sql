CREATE TABLE IF NOT EXISTS reuse_decisions (
  id uuid PRIMARY KEY,
  review_run_id uuid NOT NULL REFERENCES semantic_review_analyses(review_run_id) ON DELETE CASCADE,
  investigation_id text NOT NULL,
  base_sha text NOT NULL,
  head_sha text NOT NULL,
  adapter_version text NOT NULL,
  base_build_model_hash text NOT NULL,
  head_build_model_hash text NOT NULL,
  revision integer NOT NULL CHECK (revision > 0),
  decision text NOT NULL CHECK (decision IN ('reuse', 'extend', 'extract', 'new')),
  selected_candidate_id text,
  decision_payload jsonb NOT NULL,
  actor text NOT NULL,
  superseded_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reuse_decisions_base_sha_format CHECK (base_sha ~ '^[0-9a-fA-F]{40}$'),
  CONSTRAINT reuse_decisions_head_sha_format CHECK (head_sha ~ '^[0-9a-fA-F]{40}$'),
  CONSTRAINT reuse_decisions_investigation_present CHECK (length(investigation_id) > 0),
  CONSTRAINT reuse_decisions_actor_valid CHECK (length(actor) BETWEEN 1 AND 200),
  CONSTRAINT reuse_decisions_candidate_consistent CHECK (
    (decision = 'new' AND selected_candidate_id IS NULL)
    OR (decision <> 'new' AND selected_candidate_id IS NOT NULL)
  ),
  UNIQUE (review_run_id, revision)
);

CREATE UNIQUE INDEX IF NOT EXISTS reuse_decisions_current_idx
  ON reuse_decisions (review_run_id) WHERE superseded_at IS NULL;
CREATE INDEX IF NOT EXISTS reuse_decisions_provenance_idx
  ON reuse_decisions (review_run_id, head_sha, adapter_version, head_build_model_hash);

CREATE TABLE IF NOT EXISTS reuse_solution_options (
  id uuid PRIMARY KEY,
  reuse_decision_id uuid NOT NULL UNIQUE REFERENCES reuse_decisions(id) ON DELETE CASCADE,
  strategy text NOT NULL CHECK (strategy IN ('reuse', 'extend', 'extract', 'new')),
  candidate_id text,
  option_payload jsonb NOT NULL,
  actor text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reuse_solution_options_actor_valid CHECK (length(actor) BETWEEN 1 AND 200),
  CONSTRAINT reuse_solution_options_candidate_consistent CHECK (
    (strategy = 'new' AND candidate_id IS NULL)
    OR (strategy <> 'new' AND candidate_id IS NOT NULL)
  )
);
