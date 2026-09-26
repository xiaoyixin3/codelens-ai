# Phase 0 baseline evidence — 2026-09-25

Status: engineering baseline captured; product baseline incomplete  
Source commit: `1a161c4bf5027ca431a9417e3ebaa94dfea8fc74`  
Branch used for the work: `codex/phase0-phase1-foundation`

## Reproducible checks

The checks below were run in a clean clone of the source repository before the
Phase 0/1 implementation changed production code.

| Check | Result |
|---|---|
| `mvn -q test` | Passed |
| `npm ci` | Completed; npm reported 1 low and 2 moderate dependency vulnerabilities |
| `npm test` | 11 files, 62 tests passed |
| `npm run typecheck` | Passed |
| `npm run security:scan` | Passed; 181 files scanned, 0 findings |

The dependency warnings are recorded as baseline facts. They are not silently
fixed in this work because a forced dependency upgrade would be a separate change
with its own compatibility and security evidence.

## Current intelligence capability

- The Java production worker indexes only the bounded list of changed PR files.
- Declarations and calls are inferred through regular expressions and name
  uniqueness rather than a compiler/AST type model.
- The persisted scope is `pull_request_delta`.
- The product explicitly warns that callers in unchanged files are invisible.
- The repository has blind-labelling and replay tooling, but the committed
  `sample-replay.jsonl` is only a smoke fixture.

## Claims that cannot yet be made

No evidence currently supports a product-level claim for:

- high-risk finding precision or recall on the required real corpus;
- false-positive rate on a clean PR set;
- first-review time reduction;
- patch adoption rate;
- Phase 0 completion;
- Phase 1 direct-call precision on selected real Java repositories.

## Missing Phase 0 evidence

1. At least 50 permissioned real PRs.
2. At least 20 positive root causes.
3. Two independent blind labels per case and third-person adjudication.
4. Frozen labels defined at root-cause level rather than only file/line matching.
5. Current-version replay with language/repository/category/size strata.
6. Active first-review timing and crossover study data.
7. Model/tool cost and latency captured against the same corpus.

## Decision

Continue with the non-executing Phase 1 foundation while corpus selection and
human labelling proceed as a separately gated workstream. Phase 0 remains open;
no benchmark threshold is waived and no smoke result is promoted to product
evidence.

## Follow-up implemented on 2026-09-26

- Added a root-cause-level Phase 0 schema with immutable provenance.
- Enforced two distinct blind reviewers and a third reviewer for conflicts.
- Added active-time session records for `codelens` and `control` arms.
- Added a fail-closed `npm run phase0:gate` command with the default 50 PR,
  20 positive-root-cause, and timed-session evidence thresholds.
- Added tests proving invalid reviewer independence and missing evidence cannot
  pass the gate.

These changes make evidence insufficiency executable; they do not manufacture
the missing human dataset. Phase 0 therefore remains open.
