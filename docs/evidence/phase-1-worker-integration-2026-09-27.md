# Phase 1 production worker integration evidence — 2026-09-27

Status: implemented behind a default-off repository allowlist; product exit open

## Implemented production path

- Authenticated GitHub App archive streaming at exact base/head full SHAs.
- Redirect host allowlist restricted to GitHub API and codeload over HTTPS.
- Streaming compressed-size limit and bounded safe zip extraction.
- Single archive-root enforcement, traversal/drive/backslash/NUL rejection,
  extracted byte and entry limits, read-only base, and scoped cleanup.
- Java adapter and JDBC semantic snapshot cache wired into the worker.
- Migration `012_semantic_review_audit.sql` links each applied result to its exact
  base/head snapshots, adapter version, impact payload, and published coverage.
- Incremental head indexing reuses the base snapshot and changed/dependent units.
- Resolved incoming relationships discover callers and tests in unchanged files.
- Published coverage distinguishes `semantic`, `semantic/partial`, and
  `diff-only/fallback`, including S0/S1 execution and unresolved/failed/skipped
  limitations.
- Any archive, index, or snapshot failure fails closed to the existing fallback
  without failing the overall review job.
- Audit persistence is mandatory for application: a database write failure also
  fails closed, so untraceable semantic evidence cannot be published.

Activation requires both:

```text
CODELENS_SEMANTIC_ENABLED=true
CODELENS_SEMANTIC_REPOSITORIES=owner/repository[,owner/repository]
```

The global switch with an empty allowlist performs no archive download. Defaults
remain disabled. Resource limits are separately configurable, and the pipeline
version is `v1.0.0-beta.1-java.3`. The later dependency-aware S1 decision is
recorded in ADR 0010; Maven/Gradle are still never executed.

## Automated evidence

The production-path acceptance fixture supplies authenticated-style base/head
zipballs containing a changed Java callee plus unchanged caller and test files.
The worker path:

1. materializes both exact revisions at S1 without executing repository code;
2. indexes the complete Maven source/test roots;
3. discovers both unchanged caller and test impact paths;
4. reports semantic coverage with zero parse failures; and
5. deletes the ephemeral source workspace afterward.

Separate tests cover default-off behavior, empty-allowlist behavior, sanitized
fail-closed degradation, mandatory audit persistence, archive traversal,
compressed/expanded size limits, exact SHA validation, scoped cleanup, and
redirect-host restrictions.

## Database evidence boundary

`JdbcSemanticSnapshotStore` remains transactionally covered by the opt-in real
PostgreSQL integration test. On 2026-09-27 the local Docker Desktop Linux engine
was unavailable and TCP port 5432 was closed, so no new live PostgreSQL result is
claimed. The release test suite and all in-memory/file-backed production-path
tests pass; database execution remains an explicit external-environment gate.

## Interpretation

Merge-sequence item 5 (default-off production worker integration) is implemented.
This does not close B5 or authorize broad rollout. Only repositories named in the
allowlist can use the path, and independent sealed-holdout labels are still
required before a product precision claim.
