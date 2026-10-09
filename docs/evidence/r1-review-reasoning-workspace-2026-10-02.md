# R1 review-reasoning workspace evidence — 2026-10-02

Status: R1 implementation exit conditions passed; Phase 0 corpus gate remains open.

## Implemented evidence

- `RootCauseLabel` requires claim, trigger, impact, at least one evidence
  reference, verification, severity, and confidence. It can also retain affected
  symbols, an acceptable fix, uncertainty, and notes.
- Evidence supports LEFT and RIGHT diff lines plus base/head source, symbol,
  caller, test, configuration, build, and execution facts.
- Content-addressed context packets bind full bounded repository text and
  optional semantic facts to the case ID and exact base/head SHAs.
- Gold and assisted processes have isolated stores. Gold does not construct the
  deterministic reviewer and returns no prediction or assistance fields.
- `blind-v1` data is loaded only from a separate path and exposed only as an
  incomplete draft. The version-2 source is never written.
- Frozen decisions are immutable, and gold decisions without a valid context
  packet are rejected.

## Automated verification

Commands executed:

```text
npm run typecheck
npx vitest run tests/phase0-evaluation.test.ts tests/benchmark-labeler.test.ts
```

Result: typecheck passed; the complete suite passed with 14 test files and 73
tests. The integration test starts the real loopback server, loads a
digest-verified context packet, saves a root cause using unchanged caller
evidence, and asserts that both pre-save and post-save gold API responses
contain no prediction or assistance fields. A separate Git-worktree integration
test proves that the context builder checks exact SHAs, freezes both revisions,
classifies tests, verifies its digest, and records absent semantic coverage.

## Rendered UI verification

Browser plugin availability: absent. Fallback: Playwright CLI with the installed
Microsoft Edge channel.

- Page identity: passed (`CodeLens · Review Reasoning Workspace`).
- Meaningful render and no framework overlay: passed.
- Console errors/warnings: 0/0.
- Interaction: passed; opening the neutral context, selecting an unchanged
  caller, and selecting its source line populated `head_source`, path, and line
  evidence fields.
- Desktop viewports: 1280×720 and the declared minimum 1180×800 passed.
- A fixed-width context layout initially clipped the relationship panel at
  1280px. It was replaced by an elastic three-column layout and rechecked.

## Remaining evidence boundary

This proves the R1 instrument and isolation controls, not model accuracy or
Review-time reduction. Formal product claims still require the baseline's real,
permissioned corpus, qualified independent gold reviews, adjudication, and timed
control-versus-CodeLens study.
