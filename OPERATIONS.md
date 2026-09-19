# Operations guide

## Health and recovery

- `/healthz` confirms the API process is alive.
- `/readyz` verifies PostgreSQL and Redis dependencies.
- The GitHub webhook route is limited to `WEBHOOK_RATE_LIMIT_MAX` requests per source IP per minute (default 300).
- BullMQ retries review jobs three times. Review and publication IDs remain stable across retries.
- A push during analysis makes the old run stale and suppresses its PR comment.

## Local pipeline smoke test

Run this against disposable or development PostgreSQL and Redis instances before a release:

```bash
npm run smoke:pipeline
```

The command uses a signed synthetic pull-request webhook, a real BullMQ worker, and the real PostgreSQL store. A controlled GitHub gateway captures the Check Run and summary comment instead of contacting GitHub. It verifies accepted, duplicate, and invalid-signature webhook paths, waits for a completed review, then removes its synthetic queue and database records.

## Beta readiness window

Run `npm run beta:readiness` against the beta database. It evaluates Review Runs created during the configured rolling window. Completed and failed runs form the reliability denominator; stale, skipped, queued, and in-progress runs are reported but do not distort the success rate. Defaults require at least 20 eligible runs over seven days and a success rate of at least 95%.

## Data retention

Defaults are 90 days for terminal reviews and model telemetry, and 30 days for snapshots and webhook deliveries. Override them with the `RETENTION_*_DAYS` variables.

```bash
npm run data:retain
```

Schedule this command daily. Back up PostgreSQL before changing retention periods.

## Repository deletion

Find the numeric GitHub repository ID, then run:

```bash
CODELENS_OPERATOR=operator-name npm run data:delete -- 123456789 CONFIRM
```

This deletes review runs and cascaded findings/publications, associated model telemetry, code snapshots and graph data, and project rules. The command returns a receipt containing counts. The retained audit row stores only a receipt-salted SHA-256 identifier, not the numeric repository ID.

## Model budget

`LLM_MAX_CALLS_PER_RUN` and `LLM_MAX_INPUT_CHARS_PER_RUN` are hard in-process limits. Exceeding either skips further provider calls and continues through deterministic fallback. Use provider-side account budgets as the final monetary control.

## Backup and rollback

Back up PostgreSQL volumes before deployment. Database migrations are forward-only. Roll back application images only when the older image understands the current schema; otherwise restore the matching database backup.

The latest isolated retention, deletion, backup, and restore exercise is recorded in
[`docs/operations-drill-2026-09-19.md`](docs/operations-drill-2026-09-19.md).

## Incident response

Pause the worker first, preserve logs and the affected run IDs, rotate any possibly exposed secret, and disable the GitHub App installation if publication safety is uncertain. Never paste raw repository content into an incident ticket without authorization.
