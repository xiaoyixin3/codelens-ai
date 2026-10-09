-- No repository source or rendered output is retained here, only request fingerprints.
CREATE TABLE check_publication_effects (
    review_run_id uuid NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
    operation text NOT NULL CHECK (operation IN ('check_start','check_result')),
    request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK (state IN ('sent','confirmed')),
    remote_id bigint CHECK (remote_id > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (review_run_id, operation),
    CHECK (state <> 'confirmed' OR remote_id IS NOT NULL)
);
