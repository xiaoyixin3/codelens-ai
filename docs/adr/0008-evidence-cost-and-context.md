# ADR 0008: Reduce evidence cost without weakening blind evaluation

Status: accepted

- Date: 2026-09-26
- Supersedes: no earlier decision; refines ADR 0007 and the v2 evaluation method

## Context

Blind evaluation was being interpreted as asking an unfamiliar person to inspect
code without repository context. That produces expensive, noisy labels and
unfairly shifts specialist work to the project owner. Blindness must apply to the
system prediction, not to the evidence needed to understand the code.

The product also needs a fast engineering feedback loop. Requiring two people to
discover every relation or root cause from scratch is inappropriate for daily
development, while using machine suggestions as final truth contaminates product
claims.

## Decision

Use two strictly separated evidence tracks.

### Development silver evidence

- Generate large-scale call labels with the JDK compiler oracle, independently
  of the JavaParser production adapter.
- Use historical review decisions, later fix commits, compiler diagnostics, and
  deterministic analyzers to triage Phase 0 candidates.
- Compare independent tools automatically and send only disagreements,
  unresolved cases, and a random agreement sample to people.
- Use this track for debugging, regression prevention, prioritization, and model
  development only. It cannot pass a product exit gate.

### Exit gold evidence

- Reviewers receive a frozen context packet containing the full repository at
  the selected SHA, build descriptors, tests, project documentation, and any
  approved PR/Issue intent.
- Reviewers are blind only to CodeLens predictions and to each other's answers.
- Reviewers must demonstrate Java/task calibration before touching the holdout.
- The project owner is not assumed to be a Reviewer. Qualified maintainers,
  contributors, or calibrated external Java reviewers perform the work.
- Review is bounded to selected source paths and a clear question; nobody is
  asked to understand an entire unfamiliar repository unaided.
- Adjudication records disagreement categories so failures become actionable
  engineering feedback.

The v2 two-reviewer and third-person conflict rules remain in force for gold
evidence. Any future statistical-audit replacement is a baseline change and must
be approved separately with power/error analysis; it must not be introduced by
quietly changing a threshold.

## Leakage controls

- Silver and gold datasets have distinct identifiers and storage locations.
- Gold predictions remain hidden until labels and adjudication are frozen.
- Adapter changes may use silver failures, never held-out gold answers.
- After a gold set is opened for diagnosis, it becomes a development set; a new
  sealed holdout is required for the next product claim.
- Context packet hashes, adapter versions, commit SHAs, and Reviewer calibration
  versions are preserved with every report.

## Consequences

Most routine feedback is automated, and human effort is concentrated where two
independent systems disagree or where a product claim genuinely requires human
judgement. This reduces cost without asking a less-experienced project owner to
manufacture expert labels and without turning assisted labels into circular
evidence.
