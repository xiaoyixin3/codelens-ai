# R2 Change Brief Foundation Evidence — 2026-10-02

Status: implementation evidence accepted; real-sample R2 exit gate open

## Scope

This evidence covers the deterministic Change Brief contract, behavior
grouping, guided-review UI, prediction isolation, and browser interaction. It
does not claim that calibrated Reviewers have met the timed R2 comprehension
threshold.

## Automated evidence

The repository passed:

```text
npm run typecheck
npm test
npm run security:scan
git diff --check
```

The focused evaluation tests prove that semantically connected changed files
become one behavior card; frozen unchanged callers and related tests are
present; the reuse checkpoint is neutral; factual intent retains PR evidence;
and gold API responses contain the neutral brief without leaking predictions.

## Browser evidence

Environment: local loopback server, formal gold mode, Microsoft Edge through
Playwright, a frozen four-file semantic packet, 1280×720 and 1180×800.

Observed results:

- overview was the default tab;
- semantic readiness and coverage counts matched the fixture;
- the behavior card showed one unchanged caller and one related test;
- the caller question contained only caller evidence after the classification
  fix, while the test question contained only test evidence;
- selecting caller evidence opened the frozen `PaymentController` source at
  the recorded relationship;
- the page reported zero console errors and zero warnings;
- overview and behavior layouts remained usable at both checked viewports.

## Safety evidence

- gold mode did not instantiate or expose machine predictions;
- all Change Brief facts were built from the replay case and its
  content-addressed context packet;
- invalid or dangling evidence IDs are rejected by `ChangeBriefSchema`;
- R2 does not generate patches or claim that reuse retrieval has run;
- repository paths continue through the R1 frozen-context safety boundary.

## Open gate

The baseline requires calibrated real-sample evidence that Reviewers answer the
predefined factual questions without repository search. That timed study has
not been run in this delivery, so R2 remains open even though the product
foundation and its deterministic checks are implemented.

