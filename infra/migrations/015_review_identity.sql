-- Keep enqueue identity separate from the policy hash resolved during execution.
-- Historical enqueue hashes cannot be reconstructed after config_hash was updated.
ALTER TABLE review_runs ADD COLUMN enqueue_config_hash text;
UPDATE review_runs SET enqueue_config_hash = config_hash;
ALTER TABLE review_runs ALTER COLUMN enqueue_config_hash SET NOT NULL;

DROP INDEX review_runs_request_key_unique;
CREATE UNIQUE INDEX review_runs_revision_identity_unique
    ON review_runs (github_repository_id, pull_number, base_sha, head_sha,
                   enqueue_config_hash, pipeline_version, request_key);
