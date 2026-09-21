CREATE TABLE IF NOT EXISTS review_jobs (
  id uuid PRIMARY KEY,
  review_run_id uuid NOT NULL UNIQUE REFERENCES review_runs(id) ON DELETE CASCADE,
  payload jsonb NOT NULL,
  status text NOT NULL DEFAULT 'queued',
  attempts integer NOT NULL DEFAULT 0,
  available_at timestamptz NOT NULL DEFAULT now(),
  locked_at timestamptz,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS review_jobs_available_idx
  ON review_jobs (status, available_at, created_at);
