# Contributing

## Development

Requirements: Go 1.27, Node.js 24, npm 11, and PostgreSQL 17. Redis is only required by the temporary legacy smoke test.

```bash
npm ci
docker compose -f infra/compose.yml up -d
copy .env.example .env
npm run db:migrate
npm run typecheck
npm test
```

Keep package boundaries explicit. Cross-package domain data belongs in `@codelens/contracts`. Repository content is always untrusted: do not execute checked-out code, interpolate repository paths into filesystem operations, or publish a finding without exact evidence verification.

Every behavior change needs tests. Security, idempotency, stale-SHA publication, and redaction regressions are release blockers. SQL changes require a new ordered migration; never modify an applied migration.

Before opening a pull request:

```bash
npm run release:check
```

CI repeats the Go and TypeScript release checks and production dependency audit, applies migrations to PostgreSQL 17, runs the legacy compatibility smoke test, builds the Go production image, verifies its non-root user, and checks API readiness. CodeQL runs the extended Go and JavaScript/TypeScript security suites. GitHub Actions are pinned to full commit SHAs; Dependabot proposes reviewed updates for Go modules, npm, and workflow dependencies.

`release:check` also rejects private-key files, unapproved `.env*` files, and common live credential formats. The only allowlisted credential-shaped strings are fixed test fixtures used to verify redaction behavior.

Do not include real customer code, credentials, private PR data, or unapproved benchmark samples.
