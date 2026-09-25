# ADR 0006: Java runtime foundation

Status: accepted, 2026-09-25

## Context

The Go runtime proved the PostgreSQL queue and evidence-first review workflow,
but the product direction now standardizes the service layer on Java. The change
must preserve webhook, database, queue, review, and GitHub publication contracts
so existing installations and review history remain valid.

## Decision

Java 17 and Spring Boot 3.4 are the primary production runtime for the API,
worker, database migration runner, GitHub App authentication, repository policy,
code intelligence, model-assisted review, and publication pipeline.

One executable JAR exposes three modes through `-Dcodelens.mode=api`, `worker`,
or `migrate`. PostgreSQL remains both the system of record and durable leased job
queue. Existing migrations and external webhook contracts are unchanged.

The bounded multi-language parser is implemented in Java and does not compile or
execute repository code. TypeScript remains the benchmark, lifecycle, and
compatibility toolchain. The former Go implementation remains temporarily behind
explicit `legacy:go:*` commands as a rollback reference and is not shipped in the
production image.

## Consequences

- Production requires Java 17; Maven builds and tests the runtime.
- The container runs a non-root `codelens` user and retains Node only for lifecycle tooling.
- CodeQL analyzes Java and JavaScript/TypeScript; Maven dependencies are managed by Dependabot.
- Existing PostgreSQL data, GitHub App installations, webhook signatures, and job payloads remain compatible.
- Redis remains unnecessary for the production Java queue.
