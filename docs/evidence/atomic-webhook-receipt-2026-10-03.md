# Atomic Java webhook receipt — 2026-10-03

Scope: the acceptance/redelivery half of reliability work item 3. Remote
publication recovery and every formal Phase 0/1 or R3 exit gate remain open.

## Source-verified gap

The Java API previously committed `claimDelivery` before the task transaction.
A crash or exception before enqueue left a received/failed row. The same
Delivery ID then returned a duplicate acknowledgement without creating its
missing task. A failure storing a rerun Check reference could also leave partial
acceptance effects.

## Implementation

The existing JdbcStore transaction template now wraps the verified receipt,
local event handler, task/run creation, optional rerun seed or feedback, and
processed outcome. Existing nested store transactions join this transaction.
No GitHub/LLM request runs inside it. This reuses Java/PostgreSQL rather than
adding an external queue or a second ingestion architecture.

Migration 017 adds a result status and nullable review-run link. A matching
processed row does not execute its handler again; duplicates return the stored
run ID if retained. Historical received/failed rows can process again. Event,
action and exact-byte SHA-256 are checked before accepting an existing ID.
Conflicts return 409 without replacing the original audit. Historical processed
rows are not assigned fabricated results.

Handler or final outcome-write failure rolls back all local effects. A separate
best-effort failure audit may record the error, but can only alter matching
received/failed rows, never a concurrently committed success. Transactions use
5-second lock timeouts and 10-second per-statement timeouts. The PostgreSQL skill
informed these bounded, local-only transaction boundaries.

Rerun reference seeding now uses insert-if-absent, including recovered historical
requests whose run already exists. It cannot overwrite a Worker's newer Check
or comment reference. Empty/non-object JSON is rejected before delivery writes;
signature validation remains before any database acceptance operation.

## Executed evidence

An isolated PostgreSQL 17 fixture received migrations 001–017. Live tests cover:

1. Failure after enqueue rolls back receipt, run, job and repository registration.
2. Final receipt FK/write failure rolls back the already-enqueued task.
3. A lost HTTP acknowledgement can be redelivered without executing the handler.
4. Two independent stores/connections handling one ID concurrently invoke the
   handler once and return the same committed run.
5. Historical received/failed rows recover; historical processed rows remain
   duplicates even without reconstructed result metadata.
6. Changed bytes/event/action under the same ID cannot mutate the original row.
7. Rerun seeding rolls back with acceptance failure and preserves newer Worker
   publication references.
8. Deleting retained Review data nulls the receipt link but does not re-execute
   its processed handler.

These are database fault/concurrency tests, not deployed multi-host or live GitHub
delivery experiments. Existing queue lease tests also remain enabled.

A second fixture database was populated under schema 016 with received, failed
and processed rows, then upgraded transactionally to 017. All three states were
preserved; no fake outcome/run link was backfilled; the new link index existed.

## Automated verification

The complete `npm run release:check` passed with real PostgreSQL tests enabled.
After adding the explicit retention/link regression, the full Java test suite
was run again: 121 tests in 37 suites, 117 passed, 4 skipped, no failures/errors.
All eight receipt integration tests and existing lease tests executed.

The release check also passed TypeScript typecheck, all 86 tests in 16 files,
Java packaging, legacy tooling bundles, secret scanning and release manifest
verification (`automatedReady: true`, migration 017 present). The legacy build
does not authorize mixing old APIs/Workers with the updated Java services.

## Publication gaps inspected, not implemented here

The GitHub client still creates a Check without a durable reconciliation identity.
Its request helper retries uncertain write outcomes; comment discovery checks
only one page for a generic summary marker without run/generation identity or
author ownership validation. Those are concrete next-work gaps, not proof of
safe exactly-once publication. No remote calls or remote writes were made here.

## Recovery and privacy limits

Only exact-byte payload hashes and processing metadata are persisted, never raw
webhook bodies. There is no automatic inbox replay without authorized redelivery
of the original request. Receipt success is acceptance, not Review completion.
Processed duplicates do not restart failed Workers. Review deletion nulls the
stored run link without replaying the delivery; receipt retention bounds the
deduplication window.

Use updated Java API/Worker services after the forward migrations and do not mix
old runtimes. No deployment, repository patching, or product accuracy/time-saving
claim is enabled by this change.
