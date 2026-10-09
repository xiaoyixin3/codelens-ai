-- Metadata only: never persist source, prompt bodies, provider credentials or model replies.
CREATE TABLE llm_request_plans (
  -- Logical link only: telemetry retention must NOT erase a live run's spend ledger.
  -- Inserted atomically with llm_calls; a missing telemetry row prevents further sends.
  call_id uuid PRIMARY KEY,
  review_run_id uuid NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
  request_bytes integer NOT NULL CHECK (request_bytes > 0),
  max_calls integer NOT NULL CHECK (max_calls > 0),
  max_input_bytes integer NOT NULL CHECK (max_input_bytes > 0),
  context_plan jsonb NOT NULL CHECK (jsonb_typeof(context_plan) = 'object'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX llm_request_plans_run_idx ON llm_request_plans(review_run_id);

CREATE FUNCTION reject_llm_request_plan_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Model request plans are immutable';
END;
$$;
CREATE TRIGGER llm_request_plan_immutable BEFORE UPDATE ON llm_request_plans
FOR EACH ROW EXECUTE FUNCTION reject_llm_request_plan_update();
