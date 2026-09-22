ALTER TABLE data_deletion_audit
  ADD COLUMN IF NOT EXISTS repository_records_deleted integer NOT NULL DEFAULT 0;
