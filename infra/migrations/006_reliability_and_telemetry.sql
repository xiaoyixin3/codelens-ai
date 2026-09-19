CREATE TABLE IF NOT EXISTS llm_calls (
  id uuid PRIMARY KEY,
  review_run_id uuid REFERENCES review_runs(id) ON DELETE SET NULL,
  provider text NOT NULL,
  model text NOT NULL,
  task text NOT NULL,
  prompt_hash text NOT NULL,
  status text NOT NULL,
  input_chars integer NOT NULL,
  output_chars integer NOT NULL,
  input_tokens integer,
  output_tokens integer,
  duration_ms integer NOT NULL,
  http_status integer,
  error_code text,
  error_detail text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS llm_calls_review_run_idx ON llm_calls (review_run_id, task, created_at);
CREATE INDEX IF NOT EXISTS llm_calls_status_idx ON llm_calls (status, created_at);
