# CodeLens AI installation

## Local Java review (no GitHub setup required)

For read-only local use, run `npm run review:local` and open `http://127.0.0.1:4310`.
The launcher builds the Java analyzer when needed and reuses an already-running local instance.
Enter a Git repository path and Base / Head commits in the page. No database, tunnel, GitHub App, model key, or expert labels are required.
See [local review usage and limitations](docs/local-review.md). This is an assisted reading/planning entry, not automatic repair or formal quality acceptance.

The GitHub App instructions below apply to remote PR integration, not this local entry.

## 1. Create the GitHub App

Use these repository permissions:

- Metadata: read
- Contents: read
- Pull requests: read and write
- Checks: read and write
- Issues: read (required for signed PR-comment feedback events)

Subscribe to `pull_request`, `check_run`, and `issue_comment`. Set the webhook URL to `https://YOUR_HOST/webhooks/github` and generate a high-entropy webhook secret. Install the App only on repositories approved for the beta.

## 2. Configure the service

Copy `.env.example` to `.env`. Set `POSTGRES_PASSWORD` for production Compose, then provide the GitHub App ID, PEM private key, and webhook secret. LLM settings are optional; without them, deterministic summary and risk rules remain available.

Production Compose sets `CODELENS_ENVIRONMENT=production`. Startup will fail if
`POSTGRES_PASSWORD` or `GITHUB_WEBHOOK_SECRET` still contains the example
placeholder, if the database password is shorter than 16 characters, or if the
worker lacks a numeric App ID and valid PEM key. This is intentional.

Never commit `.env` or a private key. In a managed environment, inject them from the platform secret store.

Before starting the worker, set `GITHUB_TEST_OWNER` and `GITHUB_TEST_REPO` to the approved sandbox repository and run:

```bash
npm run github:preflight
```

The preflight authenticates as the App, finds the repository installation, verifies repository access, and checks the required permissions. Its output contains App and installation metadata only; it never prints the private key or an installation token.

### Local beta supervisor

For a single-machine beta, install Docker and `cloudflared`, then run:

```bash
npm run beta:local
```

The supervisor starts the development PostgreSQL and Redis services, applies
migrations, starts the API and worker, creates a temporary HTTPS tunnel, verifies
local and public readiness, and updates the GitHub App webhook URL. If the
default host ports are occupied, set `POSTGRES_HOST_PORT`, `REDIS_HOST_PORT`,
`DATABASE_URL`, and `REDIS_URL` consistently in `.env`.

Quick Tunnels have no uptime guarantee and their URL changes after restart. The
supervisor updates GitHub automatically, which is useful for a local beta, but a
fixed production domain remains required for unattended operation.

For a fixed Cloudflare Named Tunnel, create a remotely managed Tunnel and map
its published application hostname to `http://localhost:3000`. Put the public
HTTPS origin and Tunnel token in the ignored `.env` file:

```dotenv
CODELENS_PUBLIC_BASE_URL=https://reviews.example.com
CLOUDFLARE_TUNNEL_TOKEN=replace-with-the-tunnel-token
```

With both values present, `npm run beta:local` starts the Named Tunnel using the
token through the child-process environment, verifies public readiness, and
keeps the GitHub App webhook on the fixed URL. The token is never included in
the process command line. Without those values, the supervisor continues to use
a temporary Quick Tunnel. Both modes use HTTP/2 for the Tunnel transport to
support networks where outbound QUIC is unreliable.

## 3. Start production Compose

Do not run the following commands against an existing installation until its
maintenance window and backup/restore plan are approved. For migrations 015–019,
stop all old API/worker processes, reconcile pre-journal in-flight publications,
take and verify a database backup, and validate the forward migration in an
isolated restore first. Do not mix legacy workers with the new lease protocol or
roll back to binaries that cannot support the new schema. See `OPERATIONS.md`.
The outstanding Phase 0/1 and publication-recovery gates still prohibit a broad
rollout and blocking review; a running container is not evidence of product quality.

The explicitly selected, isolated PC-dependent public trial has separate start/stop
instructions in `docs/local-public-trial-2026-10-04.md`; do not use the generic
production commands below to restart that trial or connect its worker to legacy data.

For an immutable tagged release published by the release workflow:

```bash
export CODELENS_IMAGE_REFERENCE=ghcr.io/OWNER/codelens-ai@sha256:RELEASE_DIGEST
docker compose -f infra/compose.production.yml pull
docker compose -f infra/compose.production.yml up -d
```

Use the digest printed in the release workflow summary; a digest cannot drift if
a registry tag is moved. For a local image build instead, leave
`CODELENS_IMAGE_REFERENCE` unset and run:

```bash
docker compose -f infra/compose.production.yml up -d --build
```

The one-shot migration service must succeed before the API and worker start. Confirm:

```bash
curl -fsS https://YOUR_HOST/healthz
curl -fsS https://YOUR_HOST/readyz
```

Terminate TLS at a trusted reverse proxy or load balancer. Do not expose PostgreSQL or Redis publicly.
Production Compose binds the API to `127.0.0.1` and does not trust forwarding
headers by default. A host reverse proxy or host tunnel can reach that loopback
port; a proxy in another container cannot use its own localhost to reach the API.
For a containerized proxy, use a private shared Docker network and service address
instead of opening the API publicly. Only set `CODELENS_TRUST_PROXY=true` after
ensuring every request goes through a trusted proxy that removes client-supplied
`X-Forwarded-For` and `X-Forwarded-Proto` values and writes its own. Any override of
`CODELENS_BIND_ADDRESS` needs an explicit network/firewall review. The API container
health check uses `/readyz`, so database/schema failures are not reported as healthy.

Before the first deployment containing migration checksums, back up PostgreSQL.
The migration job assigns a one-time checksum baseline to legacy migration rows;
all migration SQL files are immutable afterward.

To enable the optional semantic dependency cache, mount it read-only with a
Compose override and use the container path, for example:

```yaml
services:
  worker:
    environment:
      CODELENS_SEMANTIC_DEPENDENCY_CACHE: /opt/codelens/dependencies
    volumes:
      - /managed/maven-cache:/opt/codelens/dependencies:ro
```

## 4. Configure repositories

Optionally copy `.codelens.yml.example` to `.codelens.yml` and `CODELENS.md.example` to `CODELENS.md`. Both are loaded from each PR's immutable head commit.

## 5. Operations

Run retention on a schedule:

```bash
docker compose -f infra/compose.production.yml --profile operations run --rm retention
```

See `OPERATIONS.md` for deletion, backup, rollback, and incident procedures.
