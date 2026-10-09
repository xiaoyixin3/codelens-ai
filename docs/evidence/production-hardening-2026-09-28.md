# Production hardening evidence — 2026-09-28

Status: implementation complete; managed-environment rollout evidence remains external

## Implemented controls

- Explicit development/test/production runtime modes and production fail-fast
  validation for database, webhook, GitHub App, semantic allowlist, and cache path.
- Java and legacy Node migration runners share a PostgreSQL advisory lock and
  persist exact SHA-256 checksums.
- API readiness and worker startup verify the complete migration manifest.
- Trusted-proxy opt-in prevents direct clients from bypassing in-memory rate
  limits with a forged `X-Forwarded-For` header.
- API responses add no-store, MIME-sniffing, framing, referrer, CSP, and conditional
  HSTS headers. Public Actuator exposure is reduced to health only.
- Production Compose uses split database credentials, safely encodes special
  characters for Node operations, and applies read-only/minimum-privilege
  container settings.
- Release tags re-run the release gates and publish both a version tag and an
  commit-SHA tag to GHCR, then report the registry digest; Compose accepts a full
  digest-pinned image reference instead of requiring a build on the deployment host.

## Automated evidence

Tests cover valid and invalid production configuration, placeholder secrets,
unknown runtime modes, semantic activation without an allowlist, migration
ordering and content hashes, API schema-readiness failure, worker refusal before
job recovery, forwarded-header trust, and security response headers. The opt-in
PostgreSQL CI test temporarily changes an applied checksum, proves readiness
fails, restores it in `finally`, and proves readiness recovers.

## External boundary

Local unit/release checks do not replace a managed deployment. The release still
requires a real PostgreSQL migration run, image build, fixed TLS endpoint, GitHub
App preflight, backup/restore confirmation, monitoring, and a staged allowlist.
Those steps are documented and remain visible gates rather than being marked
complete from local mocks.
