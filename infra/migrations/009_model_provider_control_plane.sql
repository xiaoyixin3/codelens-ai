CREATE TABLE IF NOT EXISTS github_installations (
  id bigint PRIMARY KEY,
  account_login text NOT NULL,
  account_type text NOT NULL DEFAULT 'unknown',
  active boolean NOT NULL DEFAULT true,
  installed_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS github_repositories (
  id bigint PRIMARY KEY,
  installation_id bigint NOT NULL REFERENCES github_installations(id) ON DELETE CASCADE,
  owner_login text NOT NULL,
  name text NOT NULL,
  selected boolean NOT NULL DEFAULT true,
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (installation_id, id)
);

CREATE INDEX IF NOT EXISTS github_repositories_installation_idx
  ON github_repositories (installation_id, selected, name);

CREATE TABLE IF NOT EXISTS provider_connections (
  id uuid PRIMARY KEY,
  installation_id bigint NOT NULL REFERENCES github_installations(id) ON DELETE CASCADE,
  name text NOT NULL,
  provider_kind text NOT NULL CHECK (provider_kind IN ('openai_compatible', 'openai_responses', 'anthropic')),
  base_url text NOT NULL,
  credential_ciphertext text NOT NULL,
  credential_key_version integer NOT NULL DEFAULT 1 CHECK (credential_key_version > 0),
  credential_fingerprint text NOT NULL,
  default_model text NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  timeout_seconds integer NOT NULL DEFAULT 45 CHECK (timeout_seconds BETWEEN 1 AND 120),
  max_retries integer NOT NULL DEFAULT 2 CHECK (max_retries BETWEEN 0 AND 5),
  last_test_status text NOT NULL DEFAULT 'untested' CHECK (last_test_status IN ('untested', 'succeeded', 'failed')),
  last_test_detail text,
  last_tested_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (installation_id, name),
  UNIQUE (installation_id, id)
);

CREATE INDEX IF NOT EXISTS provider_connections_installation_idx
  ON provider_connections (installation_id, enabled, name);

CREATE TABLE IF NOT EXISTS repository_model_policies (
  github_repository_id bigint PRIMARY KEY,
  installation_id bigint NOT NULL REFERENCES github_installations(id) ON DELETE CASCADE,
  mode text NOT NULL DEFAULT 'deterministic' CHECK (mode IN ('deterministic', 'assisted', 'required')),
  primary_connection_id uuid,
  primary_model text,
  fallback_connection_id uuid,
  fallback_model text,
  monthly_budget_microusd bigint CHECK (monthly_budget_microusd IS NULL OR monthly_budget_microusd >= 0),
  max_calls_per_review integer NOT NULL DEFAULT 4 CHECK (max_calls_per_review BETWEEN 0 AND 20),
  max_input_tokens_per_review integer NOT NULL DEFAULT 100000 CHECK (max_input_tokens_per_review >= 0),
  updated_by text NOT NULL DEFAULT 'system',
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (primary_connection_id IS NULL OR primary_connection_id <> fallback_connection_id),
  FOREIGN KEY (installation_id, github_repository_id)
    REFERENCES github_repositories(installation_id, id) ON DELETE CASCADE,
  FOREIGN KEY (installation_id, primary_connection_id)
    REFERENCES provider_connections(installation_id, id) ON DELETE SET NULL (primary_connection_id),
  FOREIGN KEY (installation_id, fallback_connection_id)
    REFERENCES provider_connections(installation_id, id) ON DELETE SET NULL (fallback_connection_id)
);

CREATE TABLE IF NOT EXISTS provider_connection_audit (
  id uuid PRIMARY KEY,
  installation_id bigint NOT NULL REFERENCES github_installations(id) ON DELETE CASCADE,
  connection_id uuid,
  action text NOT NULL CHECK (action IN ('created', 'updated', 'tested', 'rotated', 'disabled', 'enabled', 'deleted')),
  actor text NOT NULL,
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS provider_connection_audit_installation_idx
  ON provider_connection_audit (installation_id, created_at DESC);
