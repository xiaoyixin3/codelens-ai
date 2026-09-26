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

Run the fail-closed evidence check with:

```bash
npm run phase0:gate -- \
  --cases=benchmarks/candidates/phase0-root-cause-cases.jsonl \
  --sessions=benchmarks/candidates/phase0-reviewer-sessions.jsonl
```

Both inputs remain ignored until their provenance, redistribution permission, and
privacy review permit publication.
