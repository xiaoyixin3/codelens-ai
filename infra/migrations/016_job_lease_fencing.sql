ALTER TABLE review_jobs ADD COLUMN lease_generation bigint NOT NULL DEFAULT 0
    CHECK (lease_generation >= 0);
ALTER TABLE review_jobs ADD COLUMN lease_expires_at timestamptz;
-- Preserve the old expiry for jobs left by a stopped legacy worker.
UPDATE review_jobs SET lease_expires_at = COALESCE(locked_at, now()) + interval '15 minutes'
    WHERE status = 'processing';
ALTER TABLE review_jobs ADD CONSTRAINT review_jobs_processing_lease_check
    CHECK ((status = 'processing') = (lease_expires_at IS NOT NULL));
CREATE INDEX review_jobs_expired_lease_idx ON review_jobs (lease_expires_at, id)
    WHERE status = 'processing';
