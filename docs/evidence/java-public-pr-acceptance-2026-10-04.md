# Real Java PR public-trial acceptance — 2026-10-04

Status: one controlled Java fixture passed end-to-end in the PC-dependent public
trial. **Not independent quality calibration, time-saving evidence, or production approval.**

## Fixture and authorization boundary

- Existing sandbox PR #1 had zero Java files and could not verify whole-repository
  Java semantics. A deployment acceptance fixture was created only in the already
  allowlisted sandbox, using its existing authenticated repository-write access.
- PR: [codelens-ai-lab/codelens-beta-test#2](https://github.com/codelens-ai-lab/codelens-beta-test/pull/2).
- Base branch: `codelens/java12-smoke-base-20261004`;
  base SHA: `a9f2cf4f7b343dfa7f218c952d9be596eaa3772c`.
- Head branch: `codelens/java12-smoke-head-20261004`;
  head SHA: `45d7d12322f8012b8b052d3888f64ccef9e02d03`.
- The isolated base commit adds a small Maven-layout Java project. Only
  `PriceCalculator.subtotal` changes in the PR, to checked integer multiplication.
  `OrderService` and `OrderServiceTest` are unchanged. Neither branch was merged;
  the main branch and GitHub App permissions were not modified.
- Local fixture lives in `examples/public-trial-java/`. The explicit opt-in
  `scripts/java-public-trial-smoke.ts --create-sandbox-pr` creates only these
  sandbox refs/PR, refuses existing-ref collisions and does not automatically
  retry failed mutations. An existing open fixture PR is returned read-only.

## Failure discovered and minimal repair

The initial Java.12 review failed in `JdbcStore.saveIntelligence`: `jdbc.update`
received rows from an unnecessary `RETURNING id`. The method already queried the
stored ID immediately afterward. Removing only that return clause preserved the
existing transaction, snapshot reuse, analysis upsert and child-row replacement.
The PostgreSQL skill's short-transaction guidance was followed; no network call,
schema change or rewritten persistence flow was introduced.

A real PostgreSQL regression now saves the same result twice and verifies one
analysis ID and two reused snapshots. Initial run
`31b66831-479c-4123-a81b-d71fa307a3b3` remains failed, with its original neutral
failure Check intact; history was not erased or manually requeued.

The pipeline was advanced to Java.13. After updating the isolated deployment,
the owning App sent one [GitHub Check rerequest](https://docs.github.com/en/rest/checks/runs#rerequest-a-check-run).
GitHub returned 201 and delivered `check_run/rerequested` to the public endpoint.
This generated a new review run, not a blind replay of a possibly uncertain write.

## Verified result

- Delivery ID: `791432ca-bfb8-11f1-8d04-f2d90830e941`; locally `processed`.
- Successful run: `a487240e-20b8-4d59-831c-d8d4f80aadd4`, Java.13, `completed`.
- Queue: `completed`, attempts `0` for the new run.
- Coverage: semantic / S1, three Java files indexed; repository code, builds,
  processors and tests were **not executed**.
- The impact output identified unchanged direct caller
  `trial.OrderService.orderTotal` for `trial.PriceCalculator.subtotal`.
  This verifies cross-file direct-caller retrieval, not complete transitive impact.
  The summary also mentions the test file; this is not a claim of test execution
  or proof that all indirect test links were discovered.
- [Successful Check](https://github.com/codelens-ai-lab/codelens-beta-test/runs/111374461421):
  completed, neutral, `CodeLens review: low risk`.
- Exactly one owned summary was observed on PR #2:
  [bot summary](https://github.com/codelens-ai-lab/codelens-beta-test/pull/2#issuecomment-5977114864),
  comment ID `5977114864`, App bot author.
- Two Checks remain for two distinct runs: the original failed review and the
  successful rerun. They are not two Check creations for the same run.
- All three publication intents are locally confirmed: `check_start`,
  `check_result`, `summary_comment`. The summary reservation was released.

Actual packaged read-only inspection exposed a second configuration omission:
`publication-inspect` was absent from the runtime mode allowlist. It is now
accepted while enforcing the same production App identity requirements as the
worker; mixed-profile protections remain intact. Two configuration tests were
added. The corrected packaged inspector ran against the successful live run,
reported all three remote operations `verified_remote`, revision `current`,
latest-run true, no held slot, and `automaticRecoveryAllowed=false`. It exited
without confirming, releasing, requeueing or writing remote results.

## Final verification and deployment

- Full release check passed after the persistence repair on isolated PostgreSQL:
  191 Java tests, 187 passed, four skipped; 90 compatibility/tooling tests passed;
  type checking, builds, migration manifest and secret scan passed.
- After the inspection configuration repair, full Java tests/package passed on
  isolated PostgreSQL: 193 tests, 189 passed, four skipped, no failures/errors.
- Final deployed image: `codelens-ai:java13-public-trial-inspect1-20261004`.
  Local image ID:
  `sha256:e879e962186566274740478a5354adafa8f42cd01ddd71f9bbd68f8cc785227f`.
  This is not a registry-published digest or signed production release.
- Compose project remains `codelens-java12-public-trial` to preserve its separate
  persistent volumes. API/database healthy, worker running, public readiness 200
  after the final update. Temporary regression PostgreSQL was stopped/removed.

The trial still admits only the sandbox, ignores repository blocking requests,
has no connected live LLM, and depends on this PC, Docker Desktop and the user's
ngrok process. Independent Phase 0 review/time study, Java semantic precision
calibration, frozen publication artifacts, audited recovery, production monitoring
and managed rollout gates remain open. No merge, source push to the CodeLens
remote, release tag or broad rollout was performed.
