-- Metadata only. No authorization token, review body, ciphertext or free-form reason.
CREATE TABLE publication_recovery_audit (
    attempt_id uuid NOT NULL,
    review_run_id uuid NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
    stage text NOT NULL CHECK (stage IN ('requested','denied','committed')),
    actor_hash text NOT NULL CHECK (actor_hash ~ '^[0-9a-f]{64}$'),
    evidence_hash text NOT NULL CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    reason text NOT NULL CHECK (reason IN ('none','not_authorized','expired_approval',
        'state_changed','remote_unverified','unsupported_protocol')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    request_stage text NOT NULL DEFAULT 'requested' CHECK (request_stage='requested'),
    PRIMARY KEY (attempt_id,stage),
    FOREIGN KEY (attempt_id,request_stage) REFERENCES publication_recovery_audit(attempt_id,stage) ON DELETE CASCADE,
    CHECK ((stage='denied') = (reason<>'none'))
);
CREATE UNIQUE INDEX publication_recovery_audit_terminal_unique
    ON publication_recovery_audit(attempt_id) WHERE stage IN ('denied','committed');
CREATE INDEX publication_recovery_audit_run_time_idx
    ON publication_recovery_audit(review_run_id,created_at);

CREATE FUNCTION reject_publication_recovery_audit_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Recovery audit event is immutable';
END;
$$;
CREATE TRIGGER publication_recovery_audit_immutable BEFORE UPDATE ON publication_recovery_audit
    FOR EACH ROW EXECUTE FUNCTION reject_publication_recovery_audit_update();
