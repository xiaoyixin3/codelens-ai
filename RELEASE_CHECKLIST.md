# v1.0 beta release checklist

## Automated gates

- [x] `npm ci`
- [x] `npm run release:check`
- [x] All migrations apply to a clean PostgreSQL 17 database
- [x] Production image builds and starts as a non-root user
- [x] `/healthz` and `/readyz` pass
- [x] `npm run smoke:pipeline` passes against PostgreSQL and Redis
- [x] GitHub Actions CI passes on the release commit
- [x] CodeQL reports no unresolved high-severity finding on the release commit

Automated gates last verified on 2026-09-19 with PostgreSQL 17, Redis 8.10.1, and the `codelens-ai:1.0.0-beta.1` image.

## Manual gates

- [ ] Fixed HTTPS deployment endpoint configured and recovery verified
- [x] GitHub App permissions and webhook events verified in a test organization
  - Evidence: [`docs/organization-validation-2026-09-19.md`](docs/organization-validation-2026-09-19.md)
- [ ] At least 100 approved historical PRs labelled and replayed
  - Run `npm run benchmark:gate -- benchmarks/approved-replay.jsonl`; defaults require 100 cases, at least 20 positive and 20 negative cases, precision ≥ 0.80, recall ≥ 0.70, and p95 evaluation latency ≤ 1,000 ms.
  - Candidate intake evidence: [`docs/benchmark-candidate-collection-2026-09-19.md`](docs/benchmark-candidate-collection-2026-09-19.md). Candidates do not satisfy this gate until they are labelled and approved.
- [ ] Five to ten design-partner repositories approved for staged rollout
- [ ] `npm run beta:readiness` reports at least 20 eligible reviews and a seven-day success rate of at least 95%
- [x] Retention, deletion, backup, and restore drills completed
  - Evidence: [`docs/operations-drill-2026-09-19.md`](docs/operations-drill-2026-09-19.md)
- [ ] Model-provider account budget and alerting configured
- [ ] Maintainer has approved the release notes and git tag

Do not create `v1.0.0-beta.1` until every applicable manual gate is checked.
