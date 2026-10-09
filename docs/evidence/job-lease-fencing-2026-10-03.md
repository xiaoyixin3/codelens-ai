# Java job lease fencing — 2026-10-03

This implements the second reliability work item in the desktop-proposal
alignment. It reuses the Java Worker and PostgreSQL queue rather than replacing
them or introducing an external message broker. Phase 0/1 and R3 exit gates are
unchanged; no repository code execution or patch writes are enabled.

## Implemented

- Migration 016 adds a non-negative claim generation, a database-clock expiry,
  a processing/expiry constraint, and a partial index over processing leases.
- Claims keep the existing short `FOR UPDATE SKIP LOCKED` transaction and increment
  generation. New leases last 120 seconds; heartbeat interval is 20 seconds.
- Renew, complete, and retry match ID, run ID, generation, processing state and
  a still-live expiry. Expired claims cannot revive even before a recovery sweep.
- Recovery now runs periodically every 30 seconds, in bounded batches of 100,
  and consumes attempts. At three failures, queue and run failure are committed
  together; abandoned jobs no longer retry indefinitely.
- Review-specific publication metadata, run status/config, findings, fallback
  intelligence and semantic audit writes lock/validate the lease in a short local
  transaction. An expiry during that transaction rolls its writes back.
- Java checks ownership before remote creation, analysis, model calls, result
  Check publication, summary comment publication, and terminal failure reporting.
  Lost-lease signals do not become semantic fallback success or ordinary retries.
- A claim/dispatch exception no longer leaks a Worker concurrency slot. Heartbeat
  cancellation and graceful shutdown keep worker resources bounded.

The PostgreSQL skill informed short transaction boundaries, `SKIP LOCKED`, and
the filtered expiry index. External calls and indexing do not hold queue locks.

## Executed fault evidence

An isolated PostgreSQL 17 container received migrations 001–016. The live lease
tests exercise two independent Java stores concurrently over separate database
connections, not two deployed production processes.

Fault scenarios cover:

1. Simultaneous claims have exactly one owner for one available task.
2. Forced expiry cannot be renewed; recovery and re-claim increase generation.
3. The prior owner cannot complete, retry, renew or commit guarded run outputs.
4. Duplicate completion/retry is rejected.
5. Expiry inside a guarded local transaction rolls back both its expiry mutation
   and its run mutation.
6. Three simulated crashes exhaust the attempt budget and mark the run failed.
7. Normal terminal retry commits queue and run failure together.

Unit tests additionally cover heartbeat/verification uncertainty, permanent
fail-closed ownership loss, rejection of stale completion without retry, claim
slot recovery, ownership loss at analysis and remote publication boundaries,
and propagation of lease loss through semantic indexing without masking it.

A separate schema-015 database contained a processing job with an old locked_at.
Migration 016 preserved exactly locked_at + 15 minutes, backfilled generation 0,
and left the already-expired row eligible for recovery. The partial expiry index
was present after upgrade.

## Automated release results

The complete `npm run release:check` passed with the actual PostgreSQL tests
enabled against the isolated database:

- Java: 108 tests in 36 suites; 104 passed, 4 skipped, zero failures/errors.
  All five lease integration scenarios and the existing PostgreSQL contract ran.
- TypeScript: typecheck and all 86 tests in 16 files passed.
- Java package, legacy bundles, secret scan and release manifest checks passed;
  `automatedReady: true`, with migration 016 present.

This result does not satisfy the independent quality/time-saving or production
deployment gates. The legacy build is retained tooling, not approval to run a
legacy Worker against the new schema.

## Remaining limits

This is database fencing and application preflight for remote effects, not remote
exactly-once delivery. An already-issued HTTP request cannot be recalled. Durable
publication markers/reconciliation, delivery receipt recovery, progress deadlines,
deployment drills and operational SLO evidence remain open.

Commit-keyed caches and model telemetry are not per-run authoritative outputs;
an already-running operation may finish such work before its next checkpoint.
The operations guide records this distinction and the old-runtime incompatibility.

All database work here is confined to disposable fixture databases. Existing
services, user data, GitHub pull requests and remote deployments are not modified.
