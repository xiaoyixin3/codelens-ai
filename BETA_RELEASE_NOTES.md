# CodeLens AI v1.0.0-beta.1

This beta introduces evidence-first review for AI-generated pull requests:

- deterministic and optional model-assisted change summaries;
- TypeScript/JavaScript PR-delta code indexing and bounded impact analysis;
- exact diff-line verification before GitHub Check annotations;
- repository policy, manual reruns, and finding feedback;
- stale-SHA suppression, retries, provider fallback, redaction, and model telemetry;
- production containers, retention/deletion operations, and historical PR replay tooling.

Known limitations: impact analysis cannot see unchanged-file callers, language support is TypeScript/JavaScript only, and the checked-in replay data is a smoke fixture rather than the required 100-PR beta benchmark.
