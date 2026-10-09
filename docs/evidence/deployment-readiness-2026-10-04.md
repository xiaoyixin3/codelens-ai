# Deployment readiness — 2026-10-04

Status: PC-dependent public trial reachable after the user manually started ngrok;
one controlled real Java PR has subsequently passed the review/publication smoke.
**Not a production rollout approval or independent quality evaluation**.

## Live read-only checks

- No running Docker services were found at initial inspection. After the user's
  explicit choice of PC-dependent public testing, a separate Compose project
  `codelens-java12-public-trial` was started with new persistent volumes.
- The active checkout has no `.env`; the older user-supplied checkout retains
  GitHub App configuration. Only configuration presence was inspected; no
  secret values were printed or copied into this checkout.
- Read-only GitHub preflight using that existing configuration succeeded:
  authenticated App `codelens-ai-xiaoyixin3-beta`, two installations, selected
  sandbox `codelens-ai-lab/codelens-beta-test`, required permissions satisfied.
  This is authentication/access evidence, not a successful Review delivery.
- The historical ngrok origin's `/readyz` returned HTTP 404. It is not a ready
  deployment. The old endpoint depended on this PC and its local supervisor.
- No always-on server address, deployment connection, approved target database,
  or migration maintenance window has been established in this task.

## Changes and verification

- Production API port now defaults to host loopback rather than all interfaces.
- Proxy headers are untrusted unless explicitly enabled after network review.
- Container health probes `/readyz`, with a four-second HTTP timeout inside its
  five-second container timeout; liveness alone does not imply schema readiness.
- Installation guidance requires maintenance, verified backup/restore and
  reconciliation before migrations 015–019. Legacy API/workers must not mix with
  the new publication/lease protocol. Digest configuration now persists across
  both `pull` and `up`, not only the first shell command.
- Four deployment-configuration tests passed; complete compatibility/tooling
  suite: 90 tests, 17 suites, all passed. Type checking and `git diff --check`
  passed. Final secret scan: 320 files, no findings. Complete Java test execution:
  191 tests, 163 passed, 28 skipped, no failures/errors; the opt-in PostgreSQL
  tests were not run in that invocation. The real container migration/readiness
  smoke below is separate evidence, not a replacement for skipped tests.
- Compose validation correctly refused missing `POSTGRES_PASSWORD`; structural
  validation with a non-secret validation-only password then passed. No service
  was started by either validation command.

## Local trial actually started

- Current Java pipeline is `java.12`. Opt-in `CODELENS_PUBLIC_TRIAL=true` requires
  a nonempty, exact repository allowlist. Both API ingress and worker execution
  enforce it; out-of-scope feedback/rerun events are also ignored at ingress.
- Only `codelens-ai-lab/codelens-beta-test` is admitted. Whole-repository Java
  semantics are enabled for that same repository. No repository builds are run.
- Existing repository policy is reused. The effective trial policy disables
  blocking, retains existing guidance/rules/scope, adds an advisory warning and
  has a separate deterministic hash. Normal results and terminal review errors
  produce neutral Checks, not quality approval or blocking failure. Superseded
  Checks still use cancelled. Branch-protection policy is not controlled here.
- Initial real API startup exposed a final `@Repository` class that Spring could
  not proxy. Removing only `final` from `ReuseDecisionStore` fixed startup; a
  real Spring exception-translation context test guards that regression.
- Candidate image `codelens-ai:java12-public-trial-fixed-20261004` was built from
  current sources. Local image ID:
  `sha256:8c7dc8fe8190c5bcb8b7cbd613c9c7a4c739d51a4d26daf4c68a8f0c0d5ed578`.
  This is a local image ID, **not** a published registry digest or signed release.
- Migrate container exited 0; API and PostgreSQL containers became healthy;
  worker startup succeeded. Local `/readyz` returned 200, a signed sandbox ping
  returned 202/ignored, and an invalid signature returned 401. No real PR was
  submitted by this smoke test; it does not prove end-to-end Review publication.
- Existing LLM configuration is absent. Trial uses deterministic summaries and
  Java semantic analysis, not a connected live model.
- GitHub's current webhook URL already matches the historical fixed ngrok URL,
  content type is JSON and TLS verification is enabled. No webhook PATCH was
  issued. Public signed delivery and a real PR review remain unverified.
- The execution environment rejected launching the ngrok process by policy.
  No alternative launcher was used to bypass that restriction. The user must
  start the tunnel locally before public connectivity can be verified.
- Temporary smoke database/network were stopped and removed. The actual local
  trial containers and their persistent volumes remain running.

## Explicit remaining gates

### Subsequent real Java PR acceptance

The later [Java PR acceptance evidence](java-public-pr-acceptance-2026-10-04.md)
supersedes the earlier real-PR-unverified status for the controlled sandbox
fixture only. It records the discovered persistence repair, Java.13 deployment,
GitHub-originated rerun, semantic direct-caller retrieval, confirmed Check/summary
and actual packaged read-only inspection. Broader quality and rollout gates below
remain open. The project's name still contains java12 to retain its isolated data;
its running image is now `java13-public-trial-inspect1-20261004`.

### Same-day public connectivity verification

After the user supplied a screenshot showing their manually started ngrok session,
live HTTPS checks confirmed the fixed origin's `/readyz` returned HTTP 200 with
`{"status":"ready"}`. A correctly signed sandbox ping sent through the public
webhook returned HTTP 202/ignored. API and PostgreSQL remained healthy and the
Java worker remained running. This supersedes the earlier tunnel-not-started
status; the agent did not bypass the launcher restriction. The ping was synthetic,
not a GitHub-originated PR event, and did not create a Check/comment. No webhook
configuration was changed. Real Java PR review publication is still unverified.

- PC-dependent public testing was selected. Public readiness, synthetic signed
  delivery and one controlled Java PR delivery/review passed. This is not a
  production substitute or broad PR-quality evaluation. A managed
  always-on target, migration/restore plan and monitoring remain unestablished.
- Configure target secrets, fixed HTTPS ingress and repository allowlist; verify
  proxy header sanitization, model budget and monitoring/alert destinations.
- Freeze a candidate image and its provenance, validate it on isolated data,
  rehearse migration/restore and rollback to schema-compatible Java binaries.
- Finish frozen publication artifacts and audited recovery/rollout drills before
  admitting live review traffic under the current operations restrictions.
- Phase 0 independent labels/time study and Phase 1 real Java semantic precision
  calibration remain open; no claim that Review time or quality has been proven.
- No broad rollout, blocking review, release tag or production-readiness claim.

The initial deployment checks did not modify the old database, webhook URL,
GitHub Check/comment, source remote or release tag. Subsequent sandbox acceptance
created fixture branches/PR and bot outputs as recorded in its separate evidence;
the old database and CodeLens source remote/tag remain untouched.
GitHub authentication may mint installation
tokens for read access. Existing task changes remain uncommitted.
