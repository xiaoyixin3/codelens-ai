-- No raw webhook payload is persisted. Retain the committed processing outcome.
ALTER TABLE webhook_deliveries ADD COLUMN result_status text;
ALTER TABLE webhook_deliveries ADD COLUMN review_run_id uuid
    REFERENCES review_runs(id) ON DELETE SET NULL;
CREATE INDEX webhook_deliveries_review_run_idx ON webhook_deliveries (review_run_id)
    WHERE review_run_id IS NOT NULL;
