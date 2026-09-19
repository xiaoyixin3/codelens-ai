CREATE TABLE IF NOT EXISTS findings (
  id uuid PRIMARY KEY,
  review_run_id uuid NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
  fingerprint text NOT NULL,
  source text NOT NULL,
  rule_id text,
  category text NOT NULL,
  severity text NOT NULL,
  confidence numeric(5,4) NOT NULL,
  title text NOT NULL,
  claim text NOT NULL,
  suggestion text NOT NULL,
  verification text NOT NULL,
  status text NOT NULL,
  rejection_reason text,
  published boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (review_run_id, fingerprint)
);

CREATE INDEX IF NOT EXISTS findings_review_run_idx ON findings (review_run_id, severity, status);

CREATE TABLE IF NOT EXISTS finding_evidence (
  id uuid PRIMARY KEY,
  finding_id uuid NOT NULL REFERENCES findings(id) ON DELETE CASCADE,
  path text NOT NULL,
  start_line integer NOT NULL,
  end_line integer NOT NULL,
  side text NOT NULL,
  excerpt_hash text NOT NULL,
  evidence_type text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS finding_evidence_finding_idx ON finding_evidence (finding_id);
