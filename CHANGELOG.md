# Changelog

All notable changes to CodeLens AI are documented in this file. The project follows Semantic Versioning; beta releases may still change configuration and stored data formats before `1.0.0`.

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
