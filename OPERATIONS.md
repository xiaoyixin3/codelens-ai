# Operations guide

## Health and recovery

- `/healthz` confirms the API process is alive.
- `/readyz` verifies PostgreSQL connectivity and the exact migration checksum
  manifest. The worker performs the same check before recovering or claiming jobs.
- The GitHub webhook route is limited to `WEBHOOK_RATE_LIMIT_MAX` requests per source IP per minute (default 300).
- The Java worker retries PostgreSQL-backed review jobs three times. Review and publication IDs remain stable across retries.
- A push during analysis makes the old run stale and suppresses its PR comment.

## Local pipeline smoke test

Run this against disposable or development PostgreSQL and Redis instances before a release:

```bash
npm run smoke:pipeline
```

The command uses a signed synthetic pull-request webhook, a real BullMQ worker, and the real PostgreSQL store. A controlled GitHub gateway captures the Check Run and summary comment instead of contacting GitHub. It verifies accepted, duplicate, and invalid-signature webhook paths, waits for a completed review, then removes its synthetic queue and database records.

## Beta readiness window

Run `npm run beta:readiness` against the beta database. It evaluates Review Runs created during the configured rolling window. Completed and failed runs form the reliability denominator; stale, skipped, queued, and in-progress runs are reported but do not distort the success rate. Defaults require at least 20 eligible runs over seven days and a success rate of at least 95%.

## Data retention

Defaults are 90 days for terminal reviews and model telemetry, and 30 days for snapshots and webhook deliveries. Override them with the `RETENTION_*_DAYS` variables.

```bash
npm run data:retain
```

Schedule this command daily. Back up PostgreSQL before changing retention periods.

## Repository deletion

Find the numeric GitHub repository ID, then run:

```bash
CODELENS_OPERATOR=operator-name npm run data:delete -- 123456789 CONFIRM
```

This deletes review runs and cascaded findings/publications, associated model telemetry, code snapshots and graph data, and project rules. The command returns a receipt containing counts. The retained audit row stores only a receipt-salted SHA-256 identifier, not the numeric repository ID.

## Model budget

For Java.16, `LLM_MAX_CALLS_PER_RUN` and `LLM_MAX_INPUT_CHARS_PER_RUN` use durable, run-scoped reservations before every physical attempt, including retries and fallback providers. The second setting retains its compatibility name but counts the full serialized UTF-8 request **bytes**, not tokens. Failed, interrupted, crashed and uncertain attempts keep their charge; budgets cannot be reset by restarting a Worker. Changed limits or unplanned legacy telemetry for the same run are refused. Exceeding a limit or failing audit persistence stops further sends and uses an explicitly disclosed deterministic fallback. Use provider-side account budgets as the final monetary control.

An outstanding `reserved` attempt or missing telemetry stops all further model sends for that run. Request-plan metadata survives telemetry retention and is deleted with its review run; deleting telemetry must never restore budget. No automatic resolution/refund of uncertain attempts exists. Do not clear records or replay an old run to bypass this protection. The plan is currently single-block metadata, not persisted model results or independently resumable multi-block review. Restore remains quarantined: an older database snapshot may omit subsequent model spend, so reconcile with provider-side records before any new sends.

## S1 Java dependency cache

`CODELENS_SEMANTIC_DEPENDENCY_CACHE` may point to an operator-managed,
Maven-layout directory outside every analyzed repository. Mount it read-only.
CodeLens reads only exact literal direct dependencies, never writes the cache,
never downloads missing artifacts, and never invokes Maven or Gradle. Bound the
admitted set with `CODELENS_SEMANTIC_MAX_DEPENDENCY_JARS` and
`CODELENS_SEMANTIC_MAX_DEPENDENCY_JAR_BYTES`. Leave the path blank when no trusted
cache is available; affected runs report `semantic/partial` rather than silently
using the network or repository build code.

## Backup and rollback

### Atomic Java webhook receipt (migration 017)

Roll out the updated Java API after applying migration 017, with old APIs stopped.
Do not mix legacy API instances that separately claim a delivery and enqueue its
task. A verified delivery, its local handler effects (including queue insertion,
rerun seed or feedback), and its processed outcome now commit together. The
handler contains only local database work, never GitHub or model requests.

After rollback, an optional failure audit is written separately with the same
payload identity. Both receipt and failure-audit transactions bound lock waits
to 5 seconds and individual SQL statements to 10 seconds. A delayed failure
audit cannot downgrade a concurrent processed receipt. Errors return HTTP 500,
not a successful duplicate acknowledgement.

Redeliver the same signed payload/event/action under the same delivery ID after
a failed or interrupted request. Historical received/failed rows can be retried.
If processing already committed but its HTTP response was lost, the same delivery
returns `duplicate` and the stored run ID when still retained, without repeating
the handler. Reusing an ID with different bytes/event/action returns HTTP 409;
investigate rather than deleting the old row to bypass the identity check.

No raw payload is stored. There is no automatic database-only replay; redelivery
must come from an authorized upstream or operator holding the original request.
If those bytes are no longer available, use the authorized rerun workflow rather
than fabricating a successful receipt. A duplicate processed webhook also does
not retry a failed Worker; those are distinct mechanisms.

The committed run link uses `ON DELETE SET NULL`, so deleting retained review data
does not replay a previously processed delivery. Delivery retention limits the
deduplication window; investigate requests older than that window before replay.
Ignored events retain their outcome too. Old processed rows have no reconstructed
outcome/run link and continue to return a generic duplicate acknowledgement.

This only guarantees atomic local acceptance. It does not make subsequent GitHub
Check/comment publication atomic or exactly once. Unknown remote publication
outcomes still require the separate reconciliation workstream.

### Java job lease protocol (migration 016)

Deploy only the updated Java Worker after stopping all old Workers and applying
the forward migrations during a maintenance window. Old Go/TypeScript Workers
do not implement the lease protocol and must not be used as rollback Workers
against this schema. The processing-state database constraint rejects an old
Go claim that omits its expiry.

New claims have a 120-second database-clock lease and a monotonically increasing
generation. Heartbeats renew every 20 seconds; recovery scans every 30 seconds
(at most 100 expired jobs per scan). Recovery counts crashes against the same
three-attempt budget as normal failures. For example, without contention, an
unrenewed new claim becomes eligible for recovery after 120 seconds, followed by
the next sweep. This is a schedule, not a hard recovery-time SLA.

An expired generation cannot renew, complete, retry, or commit guarded review
outputs. A database error during heartbeat/ownership verification stops that
owner from further processing; it does not silently regain ownership later.
Local output transactions lock the queue row; GitHub/LLM calls and semantic
indexing are kept outside that lock. Immutable commit-keyed caches and model-call
telemetry may still finish during an in-flight operation, but subsequent run
outputs and publication steps require current ownership.

GitHub does not accept our fencing token. A request already sent before ownership
loss may still succeed. No exactly-once publication guarantee is provided; remote
marker reconciliation and uncertain-publication recovery remain separate work.
Repeated crash exhaustion records a failed run locally but cannot close a remote
Check on behalf of an absent owner. Inspect that Check during incident recovery.

Heartbeat liveness is not a progress deadline: a permanently stuck process that
can still renew needs operator intervention. Monitor processing age as well as
lease expiry. Do not manually extend an expired lease or reuse its generation.

Back up PostgreSQL volumes before deployment. Database migrations are forward-only. Roll back application images only when the older image understands the current schema; otherwise restore the matching database backup.

Migration files are immutable after release. Both migration runners serialize on
a PostgreSQL advisory lock and reject changed checksums, gaps, or unknown applied
migrations. Before the first checksum-aware rollout, take a backup; legacy rows
receive their checksum baseline once. A checksum failure is an incident—do not
edit the database row to force startup. Restore the matching artifact or backup.

Production containers use read-only root filesystems and dropped capabilities.
The worker's only persistent writable path is the `codelens-semantic` workspace
volume. Monitor its utilization and remove abandoned run directories only while
the worker is stopped and after confirming they are not active workspaces.

The latest isolated retention, deletion, backup, and restore exercise is recorded in
[`docs/operations-drill-2026-09-19.md`](docs/operations-drill-2026-09-19.md).

## Incident response

Pause the worker first, preserve logs and the affected run IDs, rotate any possibly exposed secret, and disable the GitHub App installation if publication safety is uncertain. Never paste raw repository content into an incident ticket without authorization.

## Publication uncertainty (Java worker, 2026-10-04)

`PUBLICATION_UNCERTAIN` means a GitHub write may already have committed. The Java
worker atomically marks the queue item and review run failed, clears its lease,
and stops automatic retry without posting another failure Check. This is a
reconciliation stop, not proof that the remote write failed. HTTP POST/PATCH
requests are sent once per client invocation; transient GET failures remain
bounded to three attempts, while permanent HTTP failures are not retried by the
transport. Remote error response bodies are not included in exception messages.

Do not blindly requeue these runs or press rerun: first inspect the relevant PR,
Check Run and summary comment, including App ownership and the exact revision.
Migration 018 adds a Check-only publication journal, a stable per-run external
identity and read-only reconciliation inside the Java worker. A recorded send
is never blindly replayed; missing or conflicting evidence pauses the run.
Discovery is bounded to 20 pages of 100 Checks and validates App/head/name.
Each rerun creates a new Check after validating its seed, preserving old output.
Confirmed result recovery checks the exact conclusion, title, summary fingerprint
and annotation count before continuing. Changed model output on replay pauses
rather than replacing the recorded result. A locally completed/stale run only
acknowledges the queue after a lost queue acknowledgement; it does not republish.

Migration 019 journals summary comments using the same effect records and adds a
durable per-installation/repository/PR reservation. Pending or uncertain comments
retain that reservation, preventing competing runs from creating another summary.
The authenticated App is read with its JWT, its bot user is resolved separately,
and comment author numeric ID, login, Bot type, marker prefix and issue URL must
match. App ID and bot user ID are not interchangeable. Discovery checks up to 20
pages of 100 comments; multiple owned summaries or exhausted pagination stop the
run. The bot identity cache expires after ten minutes.

Updates record the target ID before sending. Recovery requires the exact marked
body and the recorded target, without another POST/PATCH. Confirmation and slot
release commit together; failure retains the slot. Older runs are not admitted
when a newer run exists. If a newer run arrives while an older pending slot exists,
the conservative stop can require operator reconciliation instead of auto-release.
There is no operator reconciliation/requeue command yet for failed uncertain runs;
worker recovery only resumes jobs admitted by the existing lease protocol. Do not
manually delete a reservation or requeue a run without checking its remote write.
Keep rollout and blocking disabled until operational recovery and the original
evidence gates are closed. Do not claim exactly-once or an atomic GitHub update.

### Read-only publication inspection

The Java `publication-inspect` mode prints a compact JSON evidence report and
exits. It never confirms records, releases reservations, requeues jobs or writes
Checks/comments. Use the authorized installation/App credentials and a database
account with SELECT-only access where possible:

```text
java -Dcodelens.mode=publication-inspect -jar target/codelens-ai.jar --run-id=<review-run UUID>
```

Only one run ID is accepted. Extra flags, repeated IDs and mixed active profiles
(worker/api/migrate or others) are rejected before components initialize. Schema
checks must pass. Repository source and remote response bodies are not printed.
`verified_remote` means the observed body/annotations rehash to the local intent;
it is not a recovery approval. `not_visible` is not proof that no write occurred;
`lookup_failed` includes authorization failures, conflicts and exhausted budgets.
`content_conflict`/`remote_id_conflict` require investigation, never forced replay.
Reads are checked against a 120-second budget between calls and each HTTP request
uses the existing timeout/retry bounds; this is not an atomic snapshot or a hard
end-to-end deadline. Read authentication may mint an installation token.

Automatic recovery remains disabled. A write-enabled recovery path still needs
frozen output artifacts, audited authorization and transactional freshness checks.
The inspection change used `java.11`: superseded local runs remain `stale`, but their
GitHub Check conclusion is `cancelled`; Apps cannot set GitHub's `stale` conclusion.

The stop behavior uses migration 016's fencing and existing failed/error-code
fields. The Check and summary journals require migrations 018–019. The running
public trial remains `java.13`; the source candidate is `java.15` with migrations 020–021.
Stop all old workers and the API, back up, reconcile pre-journal in-flight runs,
test the forward migration, then start matching Java binaries. Do not manufacture
intents for historical writes or replay old unresolved runs as if they had one.
Old workers must not run alongside the new protocol. Journal rows cascade on
review-run deletion and retain only hashes and remote IDs, not repository output.

### Explicit local public trial (java.12)

The user opted into PC-dependent public testing on 2026-10-04, not a managed
production rollout. `CODELENS_PUBLIC_TRIAL=true` requires an exact nonempty
`CODELENS_PUBLIC_TRIAL_REPOSITORIES` allowlist. API ingress rejects out-of-scope
repository events before storage, and the worker independently refuses those
jobs before side effects. Trial policy reuses guidance/rules but disables blocking,
adds an advisory warning and derives a distinct hash. Completion/error Checks are
neutral, superseded ones cancelled. This does not change GitHub branch protection.

Use `infra/compose.local-public-trial.yml` only with the production Compose file:
it chooses a separate project and the sandbox repository, enables Java semantics,
and does not migrate the old database. `CODELENS_ENV_FILE` supports an existing
external secret file without copying credentials into the checkout. The actual
trial database uses separate persistent volumes. Do not delete them to restart.

Local health and signed ping were verified. The execution environment rejected
the tunnel launcher; the user subsequently started ngrok manually. Live public
HTTPS readiness returned 200 and a synthetic signed ping returned 202/ignored.
Subsequent sandbox Java PR #2 acceptance verified a GitHub-originated rerun,
semantic/S1 analysis, unchanged direct caller retrieval, one bot summary and a
neutral Check. The packaged inspector verified all three remote publication
effects. See `docs/evidence/java-public-pr-acceptance-2026-10-04.md` for the
discovered persistence/configuration repairs, exact run IDs and limitations.
The runtime has no live LLM. Availability depends on this PC, Docker Desktop and ngrok.
See `docs/local-public-trial-2026-10-04.md` for start/stop instructions and
`docs/evidence/deployment-readiness-2026-10-04.md` for evidence and open gates.
This explicitly limited trial does not close the broader rollout, recovery,
independent quality or time-saving gates described above.

### Frozen publication outputs (java.14 candidate, opt-in)

Migration 020 creates an empty, insert-only encrypted artifact table; it never
backfills historical outputs. `CODELENS_FREEZE_PUBLICATIONS` defaults to false.
Enabling it requires `CODELENS_PUBLICATION_KEY`, a separately generated random
32-byte key encoded in standard base64, supplied through secure configuration.
Do not paste keys into chat, put them in Git, or reuse the model credential key.
The existing credential vault supplies AES-256-GCM with installation/run-bound
authenticated context. Encryption occurs before the short fenced database
transaction; no GitHub call or model work is held inside that transaction.

With the switch enabled, the final Check title/conclusion/annotations, Markdown
and persisted summary JSON are frozen before the first result/comment write.
The maximum serialized plaintext size is 1 MiB; exceeding it pauses publication.
The first ciphertext is retained, even when a repeated encryption has a new nonce.
Updates are rejected by a database trigger. The plaintext hash and typed identity
are checked on read. Recovery reuses these exact values without fetching a new
diff, reloading policy, reindexing or calling the model. The current base/head and
confirmed Check-start ID must still match. Existing journal reconciliation and
summary reservations continue to protect remote effects; this is not exactly-once.

An existing artifact with the feature disabled, lost/wrong key, tampering, identity
conflict, or a result/comment intent without an artifact stops publication. Do not
regenerate or manually replace the row. Initial Check creation, stale cancellation
and terminal failure messages still use their existing journals, not this artifact.
Opt-out runs have no frozen-output guarantee. Failed uncertain jobs are still not
automatically requeued: audited recovery authorization remains unimplemented.

Treat derived review output as private: it can contain source excerpts even though
whole workspaces/prompts are not stored here. The artifact is not exposed by a new
API and decrypt errors do not log plaintext/ciphertext/key or parser causes. Rows
cascade when their review run is removed by retention or repository deletion.
Back up the database and publication key separately under access control. Key
rotation/multi-key decryption is not implemented; replacing a key makes retained
artifacts unreadable. Retain the matching key for retained rows/backups.

Before deployment: stop API/all workers, back up and verify restore, reconcile old
in-flight jobs, rehearse the forward migration on a restored database, apply 020–021,
then start matching binaries and verify readiness. Do not downgrade binaries on
schema 020 or mix old workers. This candidate has not changed the current public
trial database or Compose image reference; its tests are not production rollout
approval and do not close independent quality/time-saving gates.

### Isolated 019 → 021 backup/restore rehearsal

After building the Java JAR, run `npm run ops:rehearse-upgrade` with Docker Desktop
and Java available. This operator helper accepts only `--isolated-fixture`; it does
not accept external databases, container names, credentials or backup destinations.
It creates a random-named disposable PostgreSQL 17 container on a random loopback
port, uses the existing Java migrator with a temporary 001–019 manifest, and seeds
synthetic historical review/queue/publication/journal/reservation records.

The helper runs `pg_dump -Fc`, restores into a separate database, hashes all restored
table rows, applies 020–021, checks historical rows unchanged and no artifact/audit backfill,
repeats the migration, and confirms the old manifest specifically refuses 020.
No API/worker is started and there are no GitHub requests. Migration/bulk restore
are maintenance operations, not HTTP-held application transactions. Child output
and synthetic row bodies are not printed. A stage-only failure is not a passed drill.

The backup stays inside the disposable container. Cleanup stops only the exact
generated container and removes only the resolved generated temporary manifest
directory; inspect a failed cleanup report before repeating. No persistent volume
is attached. Passing proves the synthetic upgrade path, not backup of your real
trial database, publication-key recovery, RTO/RPO, a running-version downgrade or
GitHub delivery. A protected real-data backup/restore drill remains required before
the live upgrade; do not point this helper at production by editing target arguments.

### Read-only recovery preflight (source candidate only)

`publication-inspect` now includes `recovery` and UTC `observedAt`. The existing
single-run, read-only invocation is unchanged. Optional artifact validation uses
the frozen-output configuration/key; no key or body is printed. When validation
is disabled or the artifact is unavailable, recovery preflight is blocked.

The narrow candidate status requires a failed PUBLICATION_UNCERTAIN run/queue,
current pipeline, latest run, current revision, owned summary slot, three remotely
verified operations and exact agreement between decrypted original output and
journal fingerprints/Check IDs. A matching remote body alone is insufficient.
`candidate_for_authorized_local_confirmation` does not authorize an operation:
authorized, writesAllowed and automaticRecoveryAllowed remain false. The report
is a series of reads, not an atomic snapshot or a durable approval token.

No write-enabled recovery command exists. The intended first writable scope is
local confirmation of already verified remote writes, never resending uncertain
ones. See `docs/publication-recovery-safety-contract.md` for the still-unimplemented
authorization, audit, transactional freshness, concurrency and failure gates.
The running java.13 public trial image has not been replaced by this change.

### Recovery audit persistence foundation (migration 021, inactive)

The source candidate includes a non-bean internal audit store and migration 021.
There is no endpoint, CLI recovery action, default-on flag, authenticated operator
integration, approval issuance or task requeue. Supplying digests is not proof of
identity; the future protected authorization layer must verify the principal and
bind the evidence. The existing read-only inspector stays read-only.

The store accepts bounded actor/evidence digests, attempt/run UUIDs and fixed
reason codes only. Requested and denied events use independent REQUIRES_NEW short
transactions; a business rollback does not erase them. Successful audit requires
the caller's same-data-source transaction and a completed queue/run, cleared lease,
three confirmed effects, matching publication IDs and no owned summary slot.
Audit insert failure propagates so the caller rolls back the local confirmation.
No GitHub call belongs inside these transactions.

Database uniqueness permits one requested event and one terminal outcome per
attempt; terminal events require their requested event. UPDATE is rejected.
There is no store DELETE API; review-run retention/deletion cascades metadata.
Privileged database deletion remains possible: this is not a tamper-proof or
independently archived audit system. Identity digests are still sensitive metadata.
Independent audit writes need a spare connection; do not invoke them while holding
conflicting audit locks or exhaust the pool with nested business transactions.

Do not deploy migration 021 alone to the current schema-019 trial. The forward
maintenance/backup/restore/matching-binary requirements apply to 020–021 together.
Real-data and key recovery, authenticated one-time approval, recovery integration
with the shared PR mutex and the actual writable recovery operation remain unimplemented.

### Shared PR admission mutex (java.15 candidate, not deployed)

Java enqueue now acquires a transaction-scoped PostgreSQL advisory mutex before
repository metadata/run/job writes. `withReviewPrLock` exposes the same short
transaction scope for the future recovery implementation. Scope is repository ID
and pull number, deliberately independent of installation ownership or revision.
A dedicated two-integer advisory namespace and SHA-256-derived key are shared by
all Java participants; hash collisions only add serialization, never waive a lock.
Other PR mutexes can proceed, but existing metadata row locks can still contend.

`requireLatestReviewRunLocked` refuses calls without the actual matching mutex and
transaction, then checks latest-run identity. READ COMMITTED is required so a
transaction that began before waiting observes committed admissions after the
mutex becomes available; stale-snapshot isolation is rejected. Inserts timestamp
admission with `clock_timestamp()`, not transaction-start `now()`. Historical rows
and duplicate run identities are not rewritten.

Lock waits are bounded to five seconds and statements to ten seconds; these are
SQL limits, not a hard total callback deadline. Contention/timeout rolls back the
enqueue/receipt transaction and follows the existing redelivery protocol. No
GitHub/model/source execution is permitted inside the mutex. Lock PR before
run/job/journal rows; never request it from an already fenced job-row transaction.
The webhook receipt row can precede the mutex, but recovery must not take receipts.

Future recovery must use this scope before rechecking latest run and bound local
state. That recovery integration, per-row ownership checks and one-time approval
remain unimplemented. Raw SQL, old Java binaries and legacy Go/TypeScript writers
can bypass the protocol: stop them before enabling the new admission version.
Keep the current public trial image/reference unchanged until controlled deployment.

### One-attempt approval foundation (inactive, no new migration)

The source includes non-bean operator-authentication and approval services, with
no API/CLI or environment activation. The authenticator maps a separately
configured random 32-byte Base64 bearer to a fixed protected operator identity;
typing a name is not authentication. This is configured bearer authentication,
not OIDC/MFA or independently verified human identity. Future deployment must
protect provisioning, transport, credential rotation and operator access.

Approval uses the existing AES-256-GCM vault and a separately generated 32-byte
approval key. Reusing the operator bearer as that key is rejected; publication and
provider keys must also remain distinct in future configuration. Approvals last
at most five minutes, bind actor/run/job/repository/installation/PR/revision,
pipeline, frozen-output and journal hashes, target IDs, lease generation, slot
and local references, and only permit the local-confirmation purpose. Changed
bindings, key, actor, expiry or authenticated ciphertext reject verification.
Token values must never appear in chat, Git, logs or command-line arguments.
Returned secret objects redact their string representation.

First use independently inserts the existing audit requested event with the
approval's random UUID and actor/evidence digests. Unique audit identity makes
reservation one-attempt across shared-database replicas. A failed reservation
must propagate, not be ignored. Business rollback/crash does not unburn it: obtain
a fresh approval after investigating. This deliberately conservative policy is
not a resumable token and not exactly-once recovery. Retained audit rows enforce
single use; the five-minute window must be shorter than retention. Deleting the
review run removes dependent audit and prevents reservation for that run.

Issuer/consumer foundations do not verify live eligibility themselves and do not
confirm/requeue runs. Future orchestration must first verify remote and frozen
evidence, issue from a trusted fresh binding, reserve, reread GitHub, take the PR
mutex, recompute bindings/expiry and compare local rows, then atomically confirm
state and success audit. Terminal denial orchestration is also not wired yet.
No approval service or secret is enabled in the current public trial deployment.

### Internal local-confirmation executor (2026-10-04 increment)

`PublicationRecoveryService` now joins these foundations internally, not as a
Spring bean, API, CLI or enabled recovery flag. Older "not wired" statements above
describe earlier increments. Prepare uses fresh inspection and trusted database
bindings; recovery reserves the approval, rereads GitHub, then takes the shared
PR mutex and locks/compares local rows, frozen fingerprints and approval expiry.
No network call happens inside the transaction. Journal confirmations, original
frozen summary and target references, completed run/job, lease-generation advance,
exact slot deletion and success audit commit or roll back together. It never
requeues, regenerates or writes GitHub results. Current trial stays java.13/019.

Known state/authorization refusals append a durable denial after rollback.
Database/connection exceptions may indicate an unknown commit outcome: retain
requested for investigation, do not manufacture a denial, and do not retry the
burned approval. A crash or expiry immediately after reservation can likewise
leave requested. Pending-attempt investigation, protected operator transport and
pre-reservation failure audit, provisioning/key separation and rotation, real
backup/key recovery and crash/commit-response-loss drills remain activation gates.
Remote edits after the last read cannot be atomically fenced by a database lock;
no exactly-once guarantee is claimed. Do not mix bypassing legacy writers.

### Read-only recovery-attempt investigation (2026-10-04 increment)

Issued approvals now provide a non-secret `attemptId()` investigation handle;
save the handle before attempting recovery, never log the approval bearer.
Internal `PublicationRecoveryService.investigate` requires the configured
principal and only exposes that actor's attempts. It does not consume an
approval, call GitHub, update audit rows or recover anything. No new API/CLI or
public entry point is enabled. Reads use one statement in a separate read-only
transaction with a five-second timeout and reuse the success-audit structural
eligibility predicate.

Possible outcomes:

- `PENDING_INVESTIGATION`: a durable request exists without a matching terminal
  event. This can mean in-flight work, failure, crash or unresolved outcome. It
  is not proof that nothing happened and never allows token reuse.
- `DENIED`: the durable bounded denial is available; investigate the reason.
- `COMMITTED_LOCAL_STRUCTURE_MATCHES`: a matching committed event exists and
  current run/job/journal/reference/slot structure passes the local predicate.
- `COMMITTED_LOCAL_STRUCTURE_CHANGED`: committed audit remains, but the current
  local structure no longer passes. Investigate drift; do not overwrite history.
- `NOT_FOUND`: missing, another actor's attempt, or retention/deletion are
  intentionally indistinguishable. Never infer eligibility to retry.

All outcomes set automatic recovery and approval reuse to false. The structural
check does not decrypt the original artifact, reconstruct the original binding,
verify current GitHub content or rule out privileged database tampering. A past
committed event remains historical evidence, not an authorization to republish.
Use the existing fresh read-only publication inspection separately if needed.
Database unavailability propagates rather than returning a fabricated outcome.

Isolated real-PostgreSQL tests inject errors at the JDBC commit boundary, both
before commit (rollback) and after a real commit (caller acknowledgement lost).
They verify the latter keeps committed audit rather than inventing denial or
resending. This is not an OS process-kill, real packet-loss or DB failover drill;
those gates, protected transport/provisioning and pending-resolution policy
remain open. Current trial image and database are unchanged.

### Actual recovery-process termination drill (2026-10-04 increment)

`RecoveryProcessCrashIntegrationTest` now force-terminates an exact child JVM at
three test-only checkpoints: after the independent requested audit commit,
after business writes/success audit but before commit, and after the actual
business commit but before returning to the caller. Parent connections then
reacquire the PR mutex and check run/job, generation, journals, references, slot,
audit outcome and refusal to reuse the old approval. Pre-commit termination
retains requested, rolls back business/success audit and keeps the slot; post-
commit termination preserves completed state and committed audit.

These tests require both `CODELENS_INTEGRATION_TESTS=true` and
`CODELENS_CRASH_TESTS=true` against an isolated loopback PostgreSQL fixture with
001–021 already applied. The child also requires a matching fixture run handle
and checks the fixture repository identity. Never run the integration suite
against the trial/production database. It starts and kills only its own Process
object, not a PID discovered by process-name matching. Approval bearer enters
over private stdin; no approval secret appears in arguments or stdout. Checkpoint
stdout is bounded and contains only a fixed stage marker. Test helper classes
are under src/test and are not shipped in the production JAR. No runtime kill
switch or recovery endpoint was added.

This closes the bounded JVM process-termination case for the internal local-
confirmation executor, not whole-machine power loss, PostgreSQL crash/failover,
real network loss, or live GitHub availability. Remote reads remain mocked and
unstubbed remote methods fail the child. Real trial backup/key recovery,
protected operator entry/transport, key isolation/rotation, pre-reservation
failure audit and pending-attempt resolution remain separate activation gates.

### Frozen output / audit backup and external-key restore drill (2026-10-04)

`RecoveryBackupRestoreIntegrationTest` requires both integration and backup test
flags. It creates its own labelled PostgreSQL 17 container, a loopback random
port and synthetic credentials; accepts no external target; migrates with the
existing Java runner; creates paused, completed and denied recovery fixtures;
uses real `pg_dump -Fc` and `pg_restore` into a new database. Backup stays inside
the disposable container. Cleanup verifies exact ID, generated name and label
before stopping that container. Probe the permanent TCP server, not the initial
Unix-socket-only initialization server. Test helpers are not in the release JAR.

All public-table row fingerprints, frozen ciphertext, migration checksums,
audit/queue/journal/reference/slot state and immutability triggers survive restore.
Keys and approval bearer tokens are not in the fixture database: original
publication key must be supplied separately to read historical frozen output;
missing/wrong key refuses without regeneration. Matching consumed-at-backup
approvals remain consumed. Wrong approval key and expired approvals reject;
pending, denied and committed history remains distinguishable. Checks/rejected
writes do not change the source or restored snapshot in the preservation case.

**Point-in-time rollback limitation:** a backup taken before approval consumption
cannot remember a later requested audit event. The test deliberately shows that
the old still-valid token can reserve again on that older copy if its signing
key is reused. Database uniqueness only covers the retained database history,
not a rollback to a prior snapshot. Therefore a restore must NOT simply restore
the old approval signing key and resume recovery. A newly generated independent
approval key rejects all pre-restore approvals while the original publication
decryption key continues to read historical artifacts. This capability is
verified internally; protected rotation/provisioning automation is not enabled.

Required restore quarantine (runbook, not permission to modify a live system):

1. Restore into a private isolated target with API, Worker and recovery entry
   disabled; prevent old and restored writers from co-running. Do not route
   traffic or auto-resume restored queued jobs.
2. Verify the exact migration manifest and restored data. Retrieve the original
   publication key through protected key recovery, separately from the dump.
   If unavailable, keep tasks paused; never reconstruct historical artifacts.
3. Generate/provision a NEW approval signing key, distinct from operator bearer,
   publication/provider keys and all previous approval keys. Invalidate old
   approvals before any recovery entry is reopened, even when a backup contains
   some consumed-attempt records. Do not rely on token expiry or RPO assumptions.
4. Investigate restored attempts and recheck current GitHub revision/content with
   fresh read-only tools. Side effects, deletion/revocation or queue transitions
   newer than the backup may be absent locally. Reapply authorized deletion/
   retention controls; never blindly replay the restored queue or auto-clear
   reservations. A read-only report is not fresh authorization.
5. Obtain new approvals only after protected operator access and the unresolved
   state have been assessed. Keep blocking/rollout gates closed until separate
   real-data/key-recovery and operational acceptance is completed.

This verifies synthetic snapshot + separately supplied keys, not protected
recovery of the live trial's data/secrets, offsite/encrypted backup storage,
least-privilege backup roles, production-scale RTO/RPO, disaster failover or
fully automated anti-rollback protection. A database dump alone is not a
complete authorization-state backup. Current trial image/database are unchanged.

### Read-only approved Java patch preview (2026-10-05, candidate only)

`POST /api/v2/reviews/{reviewRunId}/reuse-planning/patch-preview` uses the existing
shared admin authorization and installation headers; a blank admin token keeps
it unavailable. Do not provision credentials or expose this route merely to try
a patch. The request carries `decisionId`, `decisionRevision`, `baseSha`,
`headSha`, and `files: [{path, source, edits: [{startLine, endLine, replacement}]}]`.
Ranges are inclusive, one-based original Head lines; replacement is a list of
lines without newline characters (empty deletes that range). Source must exactly
match the UTF-8 bytes hashed in the production Head index, including line endings.

Requires a current approved option, completed latest-known run, active selected
repository and matching ready Base/Head indices. Supports only existing Java
method/constructor interiors, 1–3 approved files, no declaration/field changes,
1 MiB JSON body, 256 KiB per file and 64 KiB returned diff. Approval scope and
actual AST-symbol counts also bound the edit. Parsing occurs outside bounded
read-only transactions; context is read again before returning. No code/draft,
approval or index writes, repository execution, LLM call or GitHub fetch occurs.

Success contains an ephemeral unified diff, hashes, actual changed symbols and
frozen approval/candidate evidence. Responses are `no-store`; exclude bodies
from proxy/client logs and retain existing protected transport/authentication,
rate limits, request timeouts and concurrency controls. Do not treat this as
stronger independent user identity or a production verification sandbox.

413 = body limit, 422 = invalid/refused edit, 404 = disabled/unavailable context,
409 = approval/context changed. Errors do not include code or provider details.
Publication/application/verified-fix/behavioral-contract flags always remain
false. No compilation, tests, candidate-use proof or live remote revision check.
A future apply/publish path must independently recheck current remote Base/Head,
fresh approval, verification evidence and the exact artifact. The current route
is not integrated into assisted UI, gold labeling or the production publisher.
No new migration; java.16 candidate still requires schema 022. Trial deployment
is not upgraded. See `docs/evidence/r3-local-patch-preview-2026-10-05.md`.

### Separate standard-library Java sandbox prototype (2026-10-05, unwired)

`infra/sandbox/Dockerfile` is NOT the API/Worker image. The internal Java runner
has no Spring bean, endpoint or worker call. Never give the ordinary API/Worker
a Docker socket or add prototype test flags to trial deployment configuration.
The runner only accepts internally prepared bounded Java source/test material,
not arbitrary commands, repository build plugins or live PR execution authority.

Explicit local fixture tests: build that separate image, obtain its full local
image ID, set `CODELENS_SANDBOX_TESTS=true` and `CODELENS_SANDBOX_FIXTURE_IMAGE`,
then run `DockerJavaSandboxIntegrationTest`. Use only synthetic fixtures. A
mutable tag, remote Docker context or DOCKER_HOST override is refused; no image
pull occurs during probes. No private keys or production data are needed.

Plans record source/test hashes and bounded runtime policy before each execution;
same-plan retries and unresolved stages refuse. Raw code/diffs/outputs are not
saved. No mounts, no network, non-root, read-only root, resource-limited tmpfs;
actual critical container settings are checked before start. Cleanup requires
matching full ID/name/plan label; unknown containers are not force-deleted.

Only standard-library Java 17 and fixed main-method probes are implemented.
before-fail/after-pass observation is NOT a verified fix or publishing permission.
Production S2 approval, trusted materialization, fresh remote/decision checks,
production plan/audit/budgets, independent host-crash timeouts, isolated runner
security review and Maven/Gradle/JUnit adapters remain unimplemented. Local file
force/CREATE_NEW does not prove power-loss durability or cross-backup anti-replay.
Do not replay/clear unresolved journals or run actual PRs through this prototype.
See `docs/java-sandbox-prototype.md` and its linked evidence report.
