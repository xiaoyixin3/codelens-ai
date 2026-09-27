# ADR 0009: Fail-closed semantic worker rollout

Status: accepted
Date: 2026-09-27
Baseline: `docs/technical-baseline-v2.md`

## Context

The Phase 1 adapter and persistent snapshot store existed only behind standalone
tests. The production worker had GitHub App credentials but no safe way to
materialize exact base/head commits, so every published result correctly remained
`diff-only/fallback`. Enabling whole-repository analysis broadly before the B5
precision gate would exceed the approved evidence.

## Decision

- Whole-repository semantics require both `CODELENS_SEMANTIC_ENABLED=true` and an
  exact, case-insensitive `owner/repository` entry in
  `CODELENS_SEMANTIC_REPOSITORIES`. An empty allowlist enables nothing.
- The GitHub App installation token downloads zipballs only for full 40-character
  base/head SHAs. Redirects are limited to HTTPS `api.github.com` and
  `codeload.github.com`; compressed size is bounded while streaming.
- Archives are extracted into a UUID child of the configured workspace with
  entry-count, compressed-size, expanded-size, multi-root, traversal, drive-path,
  and NUL/backslash checks. Base is made read-only and the run directory is
  removed after indexing. Repository code, hooks, builds, annotation processors,
  and tests are never executed.
- Base/head indexes use the existing adapter-version/build-model cache and JDBC
  snapshot store. A forward-only semantic review audit row links the review run,
  exact base/head snapshots, adapter, impact, and published coverage. Only
  resolved semantic relationships support unchanged-file caller/test impact paths.
- Semantic impact may replace the fallback impact summary only when both sides
  contain indexed files. Partial coverage and unresolved relationships remain
  visible. Any materialization, indexing, audit-persistence, or snapshot failure
  produces an explicit `diff-only/fallback` result rather than failing the review job.
- Existing deterministic and model findings remain constrained to added diff
  lines; this rollout adds impact context and does not treat unvalidated semantic
  relations as publishable defect findings.

## Consequences

The production path can now be exercised safely on named design-partner
repositories without claiming Phase 1 completion. The pipeline version advances
to `v1.0.0-beta.1-java.2` so prior fallback-only runs are not confused with the
new behavior. Operators must provision workspace capacity and explicitly name
each enabled repository. Formal rollout remains blocked on B5 independent labels
and the ≥90% precision gate.
