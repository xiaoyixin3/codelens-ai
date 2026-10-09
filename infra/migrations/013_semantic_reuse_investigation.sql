ALTER TABLE semantic_review_analyses
  ADD COLUMN IF NOT EXISTS reuse_investigation jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS semantic_review_analyses_reuse_head_sha_idx
  ON semantic_review_analyses ((reuse_investigation #>> '{provenance,headSha}'));
