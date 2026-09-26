# Phase 1 Java semantic foundation evidence — 2026-09-26

Status: foundation implemented; Phase 1 product exit remains open  
Baseline: [`../technical-baseline-v2.md`](../technical-baseline-v2.md)  
Source branch: `codex/phase0-phase1-foundation`

## Implemented scope

- `RepositoryWorkspace` contract and an S1 local Git implementation that accepts
  only full commit SHAs, uses `git archive` rather than checkout hooks, protects
  against zip-slip and extraction limits, creates read-only base plus writable
  head/artifact/patch directories, and deletes only the resolved run directory.
- Non-executing Maven/Gradle build-model detection with conventional module source
  roots, descriptor hashing, size limits, degradation reasons, and no build-plugin
  invocation.
- Java whole-repository parsing through JavaParser and its symbol solver.
- Stable type, method, constructor, and field symbols.
- Typed `CALLS`, `READS`, `WRITES`, `EXTENDS`, `IMPLEMENTS`, `OVERRIDES`,
  `THROWS`, `CATCHES`, `ANNOTATED_WITH`, and direct `TESTS` relationships.
- Every relationship includes path, line, confidence, and type-resolution status;
  failures and unresolved relationships remain visible in coverage.
- Base snapshot cache keys include repository, commit SHA, adapter version, and
  build-model hash. Writes are atomic and reject provenance mismatch.
- Head incremental indexing reuses unaffected files, reparses changed files and
  direct dependents, and revisits previously unresolved callers when a new target
  becomes resolvable.
- Forward-only PostgreSQL migration `011_phase1_semantic_index.sql`, including
  provenance, coverage, degradation, reuse, symbol, relationship, and file tables.
- Retention and confirmed repository deletion include the new semantic snapshots.
- The production fallback parser is versioned as `regex-multilang-fallback-v1`;
  GitHub output now states `diff-only/fallback`, S0, and concrete limitations.

## Verification evidence

The release-level check passed after implementation:

- secret scan: clean;
- Java unit/acceptance suite: passed (database integration remains opt-in);
- TypeScript typecheck: passed;
- TypeScript/Vitest: 12 files, 65 tests passed;
- Java and legacy TypeScript builds: passed;
- forward migration ordering and release artifact check: passed.

The synthetic Java call truth set covers overloads, same-named methods,
interface dispatch, constructors, unchanged callers, and test callers. It has
exact agreement for all eight labelled internal direct calls (8/8, with no false
positive or missed relationship). Separate tests cover inheritance,
implementation, override, field read/write, declared/thrown/caught exceptions,
snapshot provenance, safe cleanup, and incremental unresolved-to-resolved calls.

A full semantic acceptance run against this repository indexed 43 Java files,
599 symbols, and 9,229 relationships with zero parse failures; 9,183
relationships were type-resolved and 46 remained explicit unresolved evidence.
These adapter-version-specific counts are engineering diagnostics, not product
precision evidence.

## Unmet exit evidence

Phase 1 is **not complete** because:

1. The ≥90% direct-call precision threshold has not been measured against an
   independently labelled truth set from selected real Java repositories.
2. The self-repository acceptance run proves full-repository reach and finds
   callers/tests in unchanged files, but it is not an independent precision set.
3. Maven/Gradle dependency classpaths and custom source layouts are not yet loaded;
   external-library relationships may remain explicitly unresolved.
4. The semantic path is not yet wired into the production GitHub worker. Published
   reviews continue to use and clearly report the diff-only fallback.
5. Persistent database read/write integration and an enabled-by-policy rollout
   path still require implementation and migration testing against PostgreSQL.

## Decision

Continue Phase 1 with real-repository truth-set creation, database-backed snapshot
integration, dependency-aware build models, and disabled-by-default worker wiring.
Do not begin Phase 2 and do not describe the current worker as semantic review.
