# ADR 0004: Go runtime foundation

Status: superseded by ADR 0006, 2026-09-25

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

The existing database schema and webhook contracts remain compatible. Repository
policy, changed-file symbol indexing for Go/TypeScript/JavaScript, impact
traversal, and optional LLM summary/risk review execute inside the Go worker.
TypeScript is retained as a temporary tooling and rollback layer for benchmark
labeling, lifecycle commands, and compatibility verification.

## Consequences

- Production API and worker deployments no longer require Redis.
- The runtime ships as static Go binaries in the existing non-root image.
- Existing review history and GitHub App installations remain valid.
- The legacy TypeScript worker remains an explicit rollback command during beta.
- New runtime behavior requires Go tests; legacy analysis behavior continues to
  require the TypeScript test suite during the migration window.
