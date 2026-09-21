# ADR 0004: Go runtime foundation

Status: accepted, 2026-09-21

## Context

The beta implementation proved the GitHub review workflow in TypeScript, but the
long-lived API and worker need predictable memory use, simple static deployment,
bounded concurrency, and fewer runtime dependencies. Review jobs also need a
durable source of truth that can be inspected and recovered together with review
state.

## Decision

Go 1.27 is the primary language for the production API, worker, database
migrations, GitHub App authentication, deterministic review, and publication
pipeline.

PostgreSQL replaces BullMQ as the production job queue. Workers claim jobs with
`FOR UPDATE SKIP LOCKED`, jobs have a 15-minute lease, and failed jobs retry up to
three times with exponential backoff. The queue row is deleted with its review run,
so operational state and review data share one transactional boundary.

The existing database schema and webhook contracts remain compatible. TypeScript
is retained as a temporary analysis/tooling layer for benchmark labeling,
lifecycle commands, and advanced AST/impact/LLM parity work.

## Consequences

- Production API and worker deployments no longer require Redis.
- The runtime ships as static Go binaries in the existing non-root image.
- Existing review history and GitHub App installations remain valid.
- The legacy TypeScript worker remains an explicit rollback command until advanced
  analyzer parity is complete.
- New runtime behavior requires Go tests; legacy analysis behavior continues to
  require the TypeScript test suite during the migration window.
