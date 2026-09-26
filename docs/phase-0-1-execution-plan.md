# Phase 0/1 execution plan

Status: in progress  
Baseline: [`technical-baseline-v2.md`](technical-baseline-v2.md)  
Decision record: [`adr/0007-phase-0-1-baseline.md`](adr/0007-phase-0-1-baseline.md)

## Guardrails

- Do not add languages or shallow detector rules.
- Do not use generated, assisted, or mutation-only data for product precision or
  recall claims.
- Do not execute repository code during materialization or semantic indexing.
- Do not publish high-risk findings from the fallback graph as semantic evidence.
- Do not mark either phase complete without every exit criterion and its evidence.
- Preserve the existing Java production runtime, GitHub delivery semantics,
  PostgreSQL audit history, and forward-only migration policy.

## Workstream A — Phase 0 real baseline

| Item | Deliverable | Evidence required | Current state |
|---|---|---|---|
| Freeze claims | README and ADR identify broad parsing as fallback | Reviewed diff | Implemented |
| Corpus contract | Root-cause labels, clean PR labels, provenance, reviewer identities, conflicts, and timing fields | Schema tests | Implemented |
| Candidate intake | At least 50 permissioned real PRs, including at least 20 positive root causes | Immutable manifest with source and permission | Blocked on human corpus selection |
| Blind labelling | Two independent reviewers; third-person adjudication | Frozen decisions with no prediction exposure | Blocked on reviewers |
| Current-version replay | Precision, recall, false-positive rate, latency, and cost by repository/category/PR size | Reproducible report and version hashes | Pending corpus |
| Review-time method | Active first-review timer and crossover protocol | Pilot sessions and exclusion rules | Contract implemented; pilot data pending |

Phase 0 exit decision: **open**. Existing smoke/replay tooling is useful, but the
repository contains no approved 50-PR/20-positive corpus and no controlled review
time study. No precision, recall, or time-saving product claim is currently valid.

## Workstream B — Phase 1 repository and semantic foundation

### B1. Contracts and materialization

- Define `RepositoryWorkspace`, `BuildModel`, `SemanticAdapter`, snapshot,
  relationship, coverage, and degradation contracts.
- Materialize exact base/head commits into one ephemeral run directory.
- Validate commit identifiers before invoking Git; do not fetch, run hooks, use
  checkout filters, or execute repository scripts.
- Extract archives with zip-slip protection, make base read-only, and delete only
  a resolved run directory beneath the configured workspace root.
- Record S1 execution level and cleanup result.

### B2. Java build model

- Detect Maven and Gradle descriptors without invoking them.
- Discover conventional main/test source roots across modules.
- Hash the normalized descriptor set to create a reproducible build-model key.
- Report unknown layouts and unreadable descriptors as degradation reasons.

### B3. Java full-repository semantic adapter

- Parse every eligible Java source in the repository snapshot.
- Extract types, methods, constructors, fields, annotations, inheritance, and
  implementations with stable repository-independent keys.
- Resolve direct calls using Java AST/type information; unresolved calls remain
  visible and are never upgraded to semantic evidence.
- Associate JUnit test symbols with directly exercised production symbols.
- Preserve path, line, parser, confidence, and `typeResolved` provenance on every
  relationship.
- Expose indexed/failed/skipped file counts and resolution coverage.

### B4. Snapshot reuse and head increment

- Cache base snapshots by repository + SHA + adapter version + build model hash.
- Reparse only changed/dependency-affected units for head and reuse unaffected
  base units.
- Invalidate reuse when build descriptors or adapter version change.
- Keep the existing changed-file parser as explicit `diff-only/fallback` output.

### B5. Exit evaluation

- Maintain a separately labelled Java call-resolution fixture containing
  overloads, interfaces, inheritance, same-named methods, and test callers.
- Evaluate selected real Java repositories without tuning on the held-out set.
- Report precision denominator, unresolved rate, parse failures, generated-code
  exclusions, adapter version, build hash, and commit SHAs.
- Demonstrate at least one changed callee whose caller and JUnit test are both in
  unchanged files.

Phase 1 exit decision: **open** until direct-call precision is at least 90% on
selected Java repositories and unchanged-file caller/test discovery is proven.

Foundation status on 2026-09-26: B1–B4 are implemented behind standalone
contracts and tests. Synthetic truth-set and self-repository acceptance evidence
pass. A fixed-revision Gson source-file truth set also passes 16/16, but was
labelled by the implementer and covers only one repository. B5 remains open
because independently labelled, selected real Java repository evidence is still
required, and the semantic path is not yet connected to GitHub publication.

## Merge sequence

1. Baseline/ADR/evidence report and regression baseline.
2. Contracts, build model, and S1 local materializer.
3. Full-repository Java adapter and synthetic truth-set tests.
4. Persistent snapshot cache and head incremental update.
5. Production worker integration behind a disabled-by-default semantic feature
   flag with explicit coverage output.
6. Real-repository Phase 1 evaluation and continue/adjust/stop decision.

## Definition of evidence for this iteration

This iteration may claim “foundation implemented” only when:

- all existing Java and TypeScript tests still pass;
- new safety, build-model, full-index, resolved-call, inheritance, and test-map
  tests pass;
- the fallback path remains distinguishable in output;
- the evidence report lists every unmet product exit criterion explicitly.
