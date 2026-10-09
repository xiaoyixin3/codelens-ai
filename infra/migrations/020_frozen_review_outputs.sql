-- Derived publication output only, encrypted with a separately managed key.
-- No historical output is reconstructed or backfilled.
CREATE TABLE frozen_review_outputs (
    review_run_id uuid PRIMARY KEY REFERENCES review_runs(id) ON DELETE CASCADE,
    installation_id bigint NOT NULL CHECK (installation_id > 0),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    payload_hash text NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    encrypted_payload text NOT NULL CHECK (octet_length(encrypted_payload) BETWEEN 1 AND 1400000),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION reject_frozen_review_output_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Frozen review output is immutable';
END;
$$;
CREATE TRIGGER frozen_review_outputs_immutable BEFORE UPDATE ON frozen_review_outputs
    FOR EACH ROW EXECUTE FUNCTION reject_frozen_review_output_update();
