ALTER TABLE check_publication_effects DROP CONSTRAINT check_publication_effects_operation_check;
ALTER TABLE check_publication_effects ADD CONSTRAINT check_publication_effects_operation_check
    CHECK (operation IN ('check_start','check_result','summary_comment'));

-- Durable ownership, not a transaction lock across network calls. An uncertain write retains its slot.
CREATE TABLE summary_publication_slots (
    repository_id bigint NOT NULL,
    installation_id bigint NOT NULL CHECK (installation_id > 0),
    pull_number integer NOT NULL CHECK (pull_number > 0),
    active_run_id uuid NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
    PRIMARY KEY (repository_id, installation_id, pull_number)
);
CREATE INDEX summary_publication_slots_run_idx ON summary_publication_slots(active_run_id);
