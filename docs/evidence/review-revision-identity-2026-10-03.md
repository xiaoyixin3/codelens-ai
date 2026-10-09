# Review revision identity — 2026-10-03

Scope: first bounded implementation from the user-supplied desktop proposal.
Formal Phase 0/1 exit gates and the R3 patch gate remain open.

## Behavior

- Base SHA and an application-preserved enqueue configuration hash participate in
  Java, TypeScript, and Go task deduplication alongside the existing identity.
- Updating the resolved policy hash no longer changes the enqueue lookup key.
- Java refuses mismatched job/run versions before remote side effects and
  mismatched enqueue payloads before transaction writes.
- Java checks both base/head after diff retrieval and immediately before result
  publication. Revision drift completes the check as stale, without normal
  findings/summary publication. This is not an atomic GitHub compare-and-publish.

## Executed database evidence

An isolated, disposable PostgreSQL 17 container was used; existing database
services and data were not changed.

1. The Java migration runner applied migrations 001–015 on an empty database.
2. The actual PostgreSQL integration test verified same-key idempotency,
   enqueue-hash preservation after policy resolution, distinct Base/config jobs,
   queue claiming, semantic audit, and append-only reuse decision revisions.
3. A second isolated database was populated under schema 014 with a historical
   completed review, then upgraded to 015 in one transaction. Its resolved hash
   was retained and copied into the enqueue column; the new unique index was
   present and the old index absent.

The live run also exposed a pre-existing integration fixture defect: choosing
`new` without recording reasons for retrieved candidates. The fixture now records
each rejection; the production validation was not weakened.

## Automated results

`npm run release:check` passed with `CODELENS_INTEGRATION_TESTS=true` against the
isolated database:

- Java: 89 tests across 33 suites, 85 passed, 4 skipped, zero failures/errors.
  The PostgreSQL test ran and passed rather than being skipped.
- TypeScript: typecheck passed; 16 files, 86 tests passed.
- Java package and legacy bundles built; release manifest `automatedReady: true`.
- `go test ./...` passed for historical runtime compatibility; its store package
  has no unit tests, so this is not independent live Go SQL evidence.
- Secret scan and whitespace checks passed.

PostgreSQL schema guidance informed the separate enqueue key and matching
composite unique index. Migration 015 remains transactional, forward-only, and
explicitly requires a maintenance window rather than claiming online safety.

## Remaining limits

- Historical enqueue hashes that were overwritten cannot be reconstructed.
- `default-v1` is not a content-addressed snapshot of the effective configuration.
- This change does not add lease fencing, durable chunk jobs, atomic delivery/task
  receipt, automatic target-branch-triggered re-review, or remote publication recovery.
- Only Java Worker gains dual-revision checks; historical runtimes receive schema
  compatibility changes, not equivalent production review guarantees.
- No repository code execution, automatic code patching, remote push, or deployment
  was enabled. No precision, recall, or review-time reduction claim is justified.

Rollout requires stopping old binaries and applying migration 015 before starting
the updated services. See `../desktop-proposal-alignment-2026-10-03.md` for the
maintenance-window, backfill, and rollback constraints.
