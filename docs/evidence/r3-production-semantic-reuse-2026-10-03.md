# R3 Production Semantic Reuse Evidence — 2026-10-03

Status: production whole-repository retrieval implemented; R3 exit gate remains open

## Delivered path

The production Java worker now sends its verified Base/Head semantic indexes
directly to `SemanticReusePlanner` after impact analysis. The planner searches
unchanged Head symbols and resolved graph relationships for:

- inheritance and override contract candidates;
- same-name and same-kind contract candidates;
- directly related test fixtures;
- same-role and caller-pattern candidates with both graph edges preserved;
- low-confidence name-token candidates, explicitly marked as recall-only.

Each investigation records Base/Head SHA, adapter version, both build-model
hashes, changed files and symbols, searched symbol/relationship counts,
coverage limitations, exact evidence locations, ranked candidates, and the
closed patch gate. Migration `013_semantic_reuse_investigation.sql` persists the
record with the existing semantic review analysis. The GitHub summary shows at
most three candidates and states that patch publication remains blocked.

## Fail-closed behavior

- failed, skipped, partial, or unresolved semantic coverage makes
  `semanticCoverageComplete=false`;
- a change with no Head semantic anchor cannot justify a new implementation;
- lexical similarity never proves reuse;
- missing or inconsistent SHA, adapter, or build-model provenance rejects the
  audit write;
- the production path does not create a `ReuseDecision`, select a solution, or
  publish a patch;
- semantic materialization, indexing, planning, or audit failure retains the
  existing explicit diff-only fallback behavior.

## Automated evidence

Focused Java tests cover ranked contract/test/shared-caller candidates, exact
two-edge shared-caller evidence, incomplete coverage, missing semantic anchors,
production S1 integration, GitHub summary visibility, and migration loading.

The complete release gate passed:

- secret scan: 275 files inspected, zero findings;
- Java: 79 tests across 30 suites, zero failures/errors (5 environment-gated skips);
- TypeScript: typecheck passed, 15 files and 78 tests passed;
- Java package and legacy TypeScript bundles built successfully;
- release manifest: `automatedReady: true`, including migration 013 and no
  missing or unpinned workflow requirements.

## Remaining R3 exit work

- persist a human-approved `ReuseDecision` and selected `SolutionOption` with
  the same provenance;
- generate a genuinely local preview after approval;
- pass that preview through `verifyPatchRelease` in the production worker;
- run calibrated samples demonstrating a candidate or valid new-proof for
  every code modification suggestion.

This evidence closes the production-graph connection item. It does not claim
that R3 as a whole has exited.
