# CodeLens AI installation

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

## 3. Start production Compose

```bash
docker compose -f infra/compose.production.yml up -d --build
```

The one-shot migration service must succeed before the API and worker start. Confirm:

```bash
curl -fsS https://YOUR_HOST/healthz
curl -fsS https://YOUR_HOST/readyz
```

Terminate TLS at a trusted reverse proxy or load balancer. Do not expose PostgreSQL or Redis publicly.

## 4. Configure repositories

Optionally copy `.codelens.yml.example` to `.codelens.yml` and `CODELENS.md.example` to `CODELENS.md`. Both are loaded from each PR's immutable head commit.

## 5. Operations

Run retention on a schedule:

```bash
docker compose -f infra/compose.production.yml --profile operations run --rm retention
```

See `OPERATIONS.md` for deletion, backup, rollback, and incident procedures.
