# CodeLens AI

Evidence-oriented intelligence for AI-generated pull requests.

This repository contains the automated `v1.0.0-beta.1` release candidate through the Week 8 packaging and operations milestone:

```text
GitHub webhook
  → signature verification and delivery deduplication
  → idempotent review run
  → PostgreSQL durable job queue
  → Java 17 worker
  → PR diff retrieval
  → immutable repository policy from .codelens.yml + CODELENS.md
  → Java-native base/head changed-file symbol snapshots
  → symbol matching and bounded impact traversal
  → structured change summary
  → deterministic + optional LLM risk candidates
  → secret redaction + primary/fallback provider telemetry
  → exact added-line evidence verification and deduplication
  → stale-SHA verification
  → GitHub Check Run annotations + one stable PR summary comment
```

The current milestone intentionally does not execute repository code or create fixes. Blocking is repository opt-in. Every result is bound to an immutable PR head SHA and is discarded if a newer SHA arrives before publication.

## What is implemented

- GitHub App webhook endpoint with exact-byte HMAC-SHA256 verification.
- Delivery-level and review-run-level idempotency.
- PostgreSQL run state, delivery audit, and publication records.
- PostgreSQL durable queue with leases, three attempts, exponential backoff, and run-level deduplication.
- GitHub App installation authentication implemented in Go with short-lived installation tokens.
- PR metadata and changed-file retrieval.
- Java-native bounded symbol indexing for changed Go, Java, Kotlin, Python,
  TypeScript, JavaScript, C#, C, C++, Rust, PHP, Ruby, and Swift files.
- Stable symbols for files, functions, methods, types, classes, interfaces,
  structs, protocols, traits, modules, aliases, and enums.
- `CALLS` relationships with line-level evidence and confidence; unresolved dynamic calls remain explicit.
- Commit-addressed delta snapshots with content hashes, parser versions, coverage, skip reasons, and safe reuse.
- Base/head matching for `ADDED`, `MODIFIED`, and `DELETED` symbols.
- Unique-name call linking across the changed-file snapshot, with confidence reduced for cross-file resolution.
- Reverse `CALLS` traversal with a default maximum depth of two.
- Explainable impact paths, edge confidence, coverage warnings, and deterministic blast-radius scoring.
- Persisted impact analyses plus Top Impact Paths in the GitHub Check Run summary.
- Deterministic local summarizer that works without an LLM key.
- Optional OpenAI-compatible structured-summary adapter with safe local fallback.
- Deterministic security, concurrency, correctness, SQL, secret, and transaction-boundary rules.
- Optional OpenAI-compatible risk reviewer; model failures safely degrade to deterministic review.
- V2 model-provider control plane with encrypted credentials, installation scoping,
  OpenAI-compatible/OpenAI Responses/Anthropic adapters, connection testing, secret
  rotation, bounded retries, redacted errors, and mutation audit events.
- Unified-diff hunk parsing with exact right-side line mapping.
- Evidence verification that rejects context lines, missing lines, excerpt mismatches, duplicates, and low-confidence candidates.
- Stable finding fingerprints, severity thresholds, a configurable annotation cap, and complete finding/evidence audit records.
- GitHub Check annotations only for verified added-line findings.
- Commit-bound `.codelens.yml` parsing for file scope, confidence thresholds, annotation limits, and blocking mode.
- Bounded `CODELENS.md` project guidance and persisted project-rule provenance.
- Native Check Run `rerequested` handling with a new auditable run for the same SHA.
- Signed PR comment feedback commands with finding-level persistence and replay protection.
- Rate-limit-aware retries for GitHub and model endpoints with bounded exponential backoff.
- Primary/fallback model routing with safe deterministic degradation.
- LLM telemetry for status, latency, token counts, character counts, and redacted prompt hashes; raw prompts are not stored.
- Secret redaction before model calls and before operational errors are persisted or published.
- Repository path traversal, absolute path, backslash, and NUL rejection before content retrieval or parsing.
- Permissioned historical-PR JSONL replay with precision, recall, false-positive, false-negative, and latency reporting.
- Compiled production API, worker, migration, retention, and deletion entrypoints.
- Multi-stage non-root container image and dependency-gated production Compose stack.
- Per-review model call/input budgets with deterministic fallback on exhaustion.
- Configurable retention and confirmed repository-level deletion with anonymous audit receipts.
- Installation, operations, security, contribution, ADR, release checklist, and example-policy documentation.
- Check Run lifecycle and one update-in-place PR summary comment.
- Head SHA checks before analysis and immediately before publication.
- Separate liveness (`/healthz`) and dependency readiness (`/readyz`) endpoints.
- Automated tests for signature security, webhook replay, SHA idempotency, stale runs, and publication idempotency.

## Prerequisites

- Java 17 and Maven 3.9 or newer for the API, worker, and migration runner.
- Node.js 24 and npm 11 for benchmark, labeling, compatibility, and operations tooling.
- PostgreSQL 17.
- A GitHub App for real repository integration.

## Java runtime status

Java 17 with Spring Boot is the primary runtime. The webhook API, configuration,
HMAC security, PostgreSQL persistence, durable queue, GitHub App client,
repository policy, code intelligence, deterministic and optional model review,
Check Run publication, and migration runner live under `src/main/java`.

The former Go runtime remains temporarily under `cmd/` and `internal/` through
the explicit `legacy:go:*` commands for rollback comparison only. TypeScript
remains for the benchmark workbench, lifecycle operations, compatibility tests,
and the explicit `legacy:*` rollback path; neither is part of the default API or worker.

## Local setup

```bash
npm install
mvn dependency:go-offline
docker compose -f infra/compose.yml up -d
copy .env.example .env
npm run db:migrate
```

Populate the GitHub values in `.env`, then run the two processes:

```bash
npm run dev:api
npm run dev:worker
```

The API listens on port `3000` by default:

- `GET /healthz` — process liveness only.
- `GET /readyz` — PostgreSQL and queue readiness.
- `POST /webhooks/github` — GitHub webhook receiver.

## GitHub App configuration

Repository permissions:

- Metadata: read
- Contents: read
- Pull requests: read and write
- Checks: read and write

Subscribe to:

- Pull request
- Check run
- Issue comment
- Installation
- Installation repositories

Set the webhook URL to:

```text
https://YOUR_HOST/webhooks/github
```

The webhook secret must be a high-entropy value and must match `GITHUB_WEBHOOK_SECRET`.

## Production deployment

Follow [INSTALLATION.md](INSTALLATION.md), then start the dependency-gated stack:

```bash
docker compose -f infra/compose.production.yml up -d --build
```

The image runs the Java API, worker, and migration modes as the non-root `codelens` user. It retains compiled TypeScript operational tools during the migration window. Migration completion gates API and worker startup. Operational retention is available through the `operations` Compose profile; backup, deletion, and rollback procedures are documented in [OPERATIONS.md](OPERATIONS.md).

For a local, single-machine beta with an automatically managed temporary HTTPS
tunnel, use `npm run beta:local`. See [INSTALLATION.md](INSTALLATION.md) for the
runtime requirements and limitations.

## Structured summary providers

The current provider integration is the V1 deployment-level foundation. The V2
proposal adds user-managed provider connections, per-repository routing, encrypted
credentials, budgets, usage visibility, and a setup console. See
[CodeLens AI V2: LLM provider platform](docs/v2-llm-provider-platform.md).

Milestone A is implemented. Operators can manage and test provider connections
through protected API endpoints without restarting the service. See
[Model provider control plane](docs/model-provider-control-plane.md). Repository
routing remains Milestone B; the current worker continues using the deployment-level
environment variables below until that routing step is complete.

Without LLM settings, the worker generates a deterministic summary from changed-file metadata. This is useful for local development and safe degradation.

To enable a compatible model endpoint, set all three values:

```dotenv
LLM_BASE_URL=https://provider.example/v1
LLM_API_KEY=...
LLM_MODEL=...
LLM_FALLBACK_BASE_URL=https://fallback-provider.example/v1
LLM_FALLBACK_API_KEY=...
LLM_FALLBACK_MODEL=...
```

The adapter calls `POST {LLM_BASE_URL}/chat/completions`, requires JSON output, validates it, retries transient failures, then uses the optional fallback provider before deterministic degradation. Repository text is explicitly treated as untrusted data and common credential forms are redacted before transmission.

## Repository policy

Copy `.codelens.yml.example` to `.codelens.yml` in a repository to control review scope and publication:

```yaml
version: 1
review:
  language: en
  blocking: false
  maxInlineComments: 8
  minimumConfidence:
    high: 0.9
include: ["src/**"]
exclude: ["**/*.generated.ts"]
```

The worker reads policy files from the immutable PR head SHA and stores their hash on the review run. Invalid configuration safely falls back to defaults and appears as a coverage warning. `CODELENS.md` bullet points become bounded project guidance. Custom natural-language rules are supplied to the optional LLM reviewer; deterministic built-in rules continue to work without model credentials.

When `blocking` is enabled, a high-risk result concludes the Check Run with `failure`; otherwise it remains `neutral`. Verified finding IDs are shown in the summary. Reviewers can record feedback with:

```text
/codelens feedback <finding-id> helpful
/codelens feedback <finding-id> false-positive
```

## Commands

```bash
npm run typecheck
npm test
npm run build
npm run benchmark
npm run benchmark:collect -- --target=120
npm run benchmark:collect:go
npm run benchmark:label
npm run benchmark:label:go
npm run benchmark:gate -- benchmarks/candidates/blind-approved-replay.jsonl
npm run beta:readiness
npm run github:preflight
npm run smoke:pipeline
npm run db:migrate
npm run data:retain
npm run data:delete -- 123456789 CONFIRM
npm run release:check
npm run dev:api
npm run dev:worker
```

The default labeler uses the [`blind-v1` protocol](docs/blind-benchmark-protocol.md):
machine predictions are withheld until the reviewer freezes independent human
labels, and the report includes 95% confidence intervals plus explicit misses
and extra predictions. The former machine-assisted 100-case set is retained
only as a deterministic regression suite and is not evidence of real-world
precision or recall.

For a Go-only evaluation, `benchmark:collect:go` creates an isolated 100-PR
queue and `benchmark:label:go` uses separate decisions and export files. The
existing JavaScript/TypeScript labels remain available as regression evidence.

## Repository layout

```text
cmd/
  api/                  Go webhook and health API
  worker/               Go asynchronous review worker
  migrate/              Go ordered database migrator
internal/
  config/               Go environment and local dotenv loading
  contracts/            Go webhook, review, summary, and finding types
  githubapp/            Go GitHub App authentication and REST client
  httpapi/              Go HTTP handlers, rate limiting, and validation
  intelligence/         Go/TS/JS symbols, snapshots, impact paths, blast radius
  llm/                  Go provider fallback, budgets, redaction, telemetry
  policy/               Go repository policy, guidance, and path scoping
  review/               Go orchestration, summaries, evidence-first review
  security/             Go HMAC verification and redaction
  store/                Go PostgreSQL state and durable job queue
apps/
  api/                  legacy TypeScript API compatibility implementation
  benchmark-labeler/    local-only human benchmark review workbench
  worker/               legacy rollback worker
packages/
  code-index/            TS/JS symbols, edges, and PR delta snapshots
  config/               environment validation
  contracts/            cross-module schemas and types
  evaluation/           historical replay metrics and benchmark runner
  github/               GitHub App gateway and publisher
  impact-engine/        symbol comparison, graph traversal, blast radius
  llm-runtime/          redaction, provider retries, model-call telemetry
  lifecycle/            retention and repository deletion operations
  persistence/          PostgreSQL and in-memory stores
  project-policy/       repository configuration, guidance, path scoping
  queue/                legacy BullMQ and in-memory queues
  resilience/           bounded retry and rate-limit handling
  risk-review/          risk candidates, diff evidence, verification, audit persistence
  review-core/          summary generation and orchestration
  security/             webhook signature verification
infra/
  migrations/           SQL schema
  compose.yml           PostgreSQL and Redis
tests/                  security and orchestration tests
benchmarks/             permissioned historical-PR replay datasets
```

## Reliability model

The automatic review-run uniqueness key is:

```text
repository + pull number + head SHA + pipeline version + request key
```

GitHub delivery IDs are deduplicated independently. A worker checks the current PR head before analysis and again before publication. A superseded run becomes `stale`; after a Check Run has been created, it is completed with the `stale` conclusion rather than being left in progress.

Delta code snapshots use this uniqueness key:

```text
repository + commit SHA + parser version + changed-file scope hash
```

The scope hash prevents two PRs with the same base commit but different changed files from incorrectly sharing a partial graph. The current snapshot scope is `pull_request_delta`: base and head versions of the PR's changed files are fetched and indexed. A ready snapshot is reused on job retry. Every file is recorded as indexed, skipped, absent on that side, or failed.

Impact analysis compares stable symbols across the base and head graphs. Exact stable keys identify modifications, additions, and deletions. The engine then walks incoming calls to a bounded depth and publishes only paths supported by stored edges and source locations.

Risk review treats both deterministic and model output as untrusted candidates. A candidate is publishable only when its path and right-side line resolve to an added line in the current unified diff, its optional excerpt matches, its confidence clears the severity threshold, and its fingerprint is unique. The full accepted/rejected audit is persisted, while only the highest-priority findings up to `MAX_INLINE_COMMENTS` become Check annotations.

Manual reruns create a distinct audit run keyed by the signed GitHub delivery while updating the rerequested Check Run. A head-SHA check occurs before analysis and immediately before publication; a push during analysis completes the old Check as stale and publishes no PR comment.

LLM telemetry stores no full prompt or completion. It records a hash of the already-redacted request plus provider, model, task, status, latency, size, token usage when supplied, and a bounded redacted error. The replay runner intentionally warns below 100 real PR cases; the checked-in two-case file is only a format smoke fixture.

The default lifecycle keeps terminal reviews and model telemetry for 90 days and snapshots/webhook deliveries for 30 days. Per-review provider calls and input size are hard-capped before network transmission. Provider-side account budgets remain the final monetary safeguard.

## Current limitations

- The index is a PR delta, so callers in unchanged files are not visible yet; every output carries this warning.
- Calls resolved within the same file or through direct imports have stronger confidence; dynamic calls remain explicit `unresolved:` targets.
- The Java-native multi-language indexer deliberately uses bounded declaration and
  direct-call parsers rather than full compilers. It recognizes common declarations,
  nested type/method scopes, and uniquely resolvable calls, while overload dispatch,
  reflection, generated code, macros, Ruby calls without parentheses, and dynamic
  cross-file imports may remain unresolved.
- Full compiler or Tree-sitter adapters and full-repository indexing remain post-beta improvements.
- Token telemetry depends on the provider returning a compatible `usage` object; monetary cost is not calculated yet.
- The production image and dependency-gated startup are exercised in CI; the current local beta endpoint still uses a temporary tunnel rather than a fixed production domain.
- The beta tag remains gated on a 100-PR approved replay set, 5–10 design partners, and seven days at ≥95% success.

Automated beta gates pass locally. See [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) for the remaining environment and rollout gates before creating the git tag.
