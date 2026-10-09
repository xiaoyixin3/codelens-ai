# Phase 0 real-baseline evidence contract

Status: implemented; dataset and human review remain open

The legacy `blind-v1` replay remains a regression tool, but Phase 0 completion is
now governed by the root-cause contract exported from `@codelens/evaluation`.

## Required case evidence

Each real PR case records immutable base/head SHAs, source URL, repository
license or owner authorization, collection time, and:

1. two blind reviews from distinct reviewers;
2. zero or more findings labelled at root-cause level;
3. category, severity, claim, trigger, code evidence, and an optional acceptable
   fix for every root cause;
4. a frozen adjudication result;
5. a third reviewer whenever the blind reviews conflict.

Predictions are not part of this schema and therefore cannot be exposed to the
reviewer before labels are frozen.

Blind review is prediction-blind, not context-free. Every Reviewer must receive
the same frozen context packet: full repository at the recorded SHA, PR text,
approved linked Issue/incident material, relevant project documentation, build
descriptors, tests, and a bounded map of the changed subsystem. Reviewers may
navigate all of that material. They may not see CodeLens predictions or the other
Reviewer's labels before submission.

The project owner is not assumed to be a qualified Reviewer. Use maintainers,
contributors, or external reviewers who passed a separate task calibration set.
Calibration examples belong to the development set and must not reuse held-out
cases.

## Review-time evidence

Timed sessions record the case, reviewer, experiment arm (`codelens` or
`control`), active seconds, decision, and explicit exclusion reason. Phase 0
requires both arms. Reviewer assignment and crossover scheduling remain an
operational responsibility; the evidence gate rejects absent arms and sessions
that refer to an unknown case.

## Default hard gate

`evaluatePhase0Evidence` fails unless it receives:

- at least 50 valid real PR cases;
- at least 20 adjudicated positive root causes;
- independent dual review and adjudication for every case;
- at least 20 non-excluded timed sessions;
- at least one session in each experiment arm.

The gate validates evidence sufficiency only. Precision, recall, false-positive
rate, time reduction, latency, and cost must then be computed by versioned replay
and the crossover analysis. No fixture, mutation set, or machine suggestion can
satisfy the real-case gate.

## Cost-controlled feedback loop

- Historical review comments, later fix commits, incidents, compiler output, and
  deterministic analyzers may generate candidate “silver” labels.
- Machine assistance may prioritize cases and assemble context, but its proposed
  answer remains hidden during gold labelling.
- Routine development uses silver labels plus random human audits. Qualified
  people focus on tool disagreements, low-confidence cases, and a random sample
  of agreements rather than discovering every candidate from scratch.
- Silver data can guide implementation but cannot satisfy the 50-PR/20-root-cause
  product gate.
- Once gold answers are exposed for debugging, that set becomes development data
  and a new sealed holdout is required for the next product claim.

Run the fail-closed evidence check with:

```bash
npm run phase0:gate -- \
  --cases=benchmarks/candidates/phase0-root-cause-cases.jsonl \
  --sessions=benchmarks/candidates/phase0-reviewer-sessions.jsonl
```

Both inputs remain ignored until their provenance, redistribution permission, and
privacy review permit publication.
