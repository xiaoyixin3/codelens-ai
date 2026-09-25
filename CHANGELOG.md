# Changelog

All notable changes to CodeLens AI are documented in this file. The project follows Semantic Versioning; beta releases may still change configuration and stored data formats before `1.0.0`.

## [Unreleased]

### Changed

- Replaced the production Go runtime with Java 17 and Spring Boot for the webhook API, worker, migration runner, GitHub App client, repository policy, review intelligence, and publication pipeline.
- Ported the V2 model-provider control plane, AES-GCM credential vault, connection testing, and audit persistence to the Java API.
- Switched Maven tests, Java CodeQL, Maven Dependabot, local beta startup, Compose, and the production container to the Java runtime while retaining Go only as an explicit rollback layer.
- Replaced the production Redis/BullMQ dependency with a leased PostgreSQL job queue using bounded retries and stale-job recovery.
- Added Java unit and PostgreSQL contract tests, Java CodeQL analysis, Maven Dependabot updates, and the executable JAR to the production image.
- Ported multi-language symbol snapshots, bounded impact analysis, optional LLM summary/risk review, provider fallback, budgets, redaction, and telemetry into the Java worker.
- Retained TypeScript benchmark, lifecycle, and compatibility tools plus the former Go runtime only as explicit rollback/tooling layers.

## [1.0.0-beta.1] - 2026-09-19

### Added

- Signed GitHub webhook ingestion with delivery and PR-head deduplication.
- PostgreSQL-backed review runs, publication state, findings, repository policy, telemetry, retention, and deletion audits.
- Redis and BullMQ review processing with retries, concurrency limits, and stale-SHA publication suppression.
- Deterministic change summaries and evidence-verified risk findings, with optional OpenAI-compatible model providers and bounded fallback.
- TypeScript/JavaScript symbol indexing, PR-delta impact analysis, blast-radius scoring, and repository-specific policy files.
- GitHub Check Run annotations, summary comments, manual reruns, and signed reviewer feedback commands.
- Production container and Compose definitions, ordered migrations, health/readiness probes, and non-root runtime.
- GitHub App preflight, full-pipeline smoke testing, historical PR quality gates, and seven-day beta-readiness reporting.
- Stratified historical-PR candidate collection and a loopback-only human benchmark labelling workbench.
- SHA-pinned CI, PostgreSQL/Redis integration testing, production image validation, CodeQL, and Dependabot configuration.

### Known limitations

- Impact analysis is limited to the changed-file index and cannot yet discover every caller in unchanged files.
- Code intelligence currently targets TypeScript and JavaScript.
- Real GitHub publication requires an operator-created GitHub App and an approved sandbox repository.
- The checked-in replay dataset is a two-case format fixture; the release gate requires at least 100 approved historical PRs.
