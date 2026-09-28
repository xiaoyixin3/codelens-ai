# ADR 0012: Fail-closed production startup and migration integrity

Status: accepted
Date: 2026-09-28
Baseline: `docs/technical-baseline-v2.md`

## Context

The application could previously start with development defaults, and database
migrations were tracked only by filename. A long placeholder secret could pass
basic length checks, an applied SQL file could be changed without detection, and
API readiness proved connectivity but not schema compatibility. Those behaviors
are unsuitable for an unattended production service.

## Decision

- `CODELENS_ENVIRONMENT=production` activates strict startup validation. The API
  rejects placeholder/short webhook or database credentials. The worker requires
  a numeric GitHub App ID and PEM private key. Semantic activation requires an
  explicit repository allowlist, and production dependency-cache paths must be
  absolute.
- Migration files must be non-empty, bounded, contiguous
  `NNN_description.sql` files. SHA-256 checksums are recorded in
  `schema_migrations`; a changed or unknown applied migration fails closed.
- Migration runners acquire the same PostgreSQL advisory lock before inspecting
  or applying the manifest. Existing pre-checksum rows receive a one-time checksum
  baseline from the deployed artifact, after which drift is rejected.
- API readiness requires both database connectivity and an exact schema manifest.
  The worker verifies the same manifest before recovering or claiming jobs.
- Production containers run as fixed UID/GID 10001, with read-only root
  filesystems, dropped Linux capabilities, no-new-privileges, bounded CPU/memory/
  process settings, and a dedicated writable semantic-workspace volume.
- Forwarded client addresses are ignored unless `CODELENS_TRUST_PROXY=true`.
  Even then, only an address-shaped first hop is accepted. The trusted reverse
  proxy must overwrite inbound forwarding headers.

## Consequences

Misconfigured instances and incompatible databases stop before accepting work.
Deployment operators must use real secrets, run the migration job before API and
worker rollout, and retain migration files unchanged forever. The first rollout
of checksum support must be preceded by a database backup because legacy rows are
baselined once. Production Compose enables proxy trust and therefore must not be
exposed without the documented reverse proxy behavior.
