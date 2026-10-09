# CodeLens AI v1.0.0-beta.1

This beta introduces evidence-first review for AI-generated pull requests:

- deterministic and optional model-assisted change summaries;
- TypeScript/JavaScript PR-delta code indexing and bounded impact analysis;
- exact diff-line verification before GitHub Check annotations;
- repository policy, manual reruns, and finding feedback;
- stale-SHA suppression, retries, provider fallback, redaction, and model telemetry;
- production containers, retention/deletion operations, and historical PR replay tooling.
- default-off Java whole-repository semantics with unchanged caller/test impact;
- fail-closed migration checksums, schema-aware readiness, strict production
  configuration, and minimum-privilege containers.

Known limitations: deep whole-repository analysis is currently Java-only and must
be enabled per repository. Dynamic build models, generated sources, and complete
transitive dependency resolution remain partial. Independent holdout evidence,
the required historical-PR benchmark, and managed-environment observation gates
remain open.
