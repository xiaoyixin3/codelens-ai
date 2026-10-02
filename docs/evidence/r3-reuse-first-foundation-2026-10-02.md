# R3 Reuse-first Foundation Evidence — 2026-10-02

Status: implementation evidence accepted; R3 exit gate open

## Automated evidence

Focused tests cover:

- evidence-bound same-contract and test-fixture retrieval;
- patch gate closed before a decision;
- valid reuse decision and solution option generation;
- missing decision rejection;
- file-budget rejection;
- incomplete-coverage rejection for a `new` implementation;
- explicit rejection of every candidate before a bounded `new` option;
- gold API isolation and assisted API option generation.

Full release check result:

- Java tests passed;
- TypeScript typecheck passed;
- 15 test files and 78 tests passed;
- Java package and legacy TypeScript bundle builds passed;
- repository secret scan inspected 271 files with zero findings;
- release manifest reported `automatedReady: true` with only the documented
  external/manual rollout gates remaining;
- `npm audit --omit=dev` reported zero production vulnerabilities.

## Browser evidence

Environment: local loopback assisted-mode server, Microsoft Edge through
Playwright, frozen five-file semantic packet, 1280×720, 1280×1000, and
1180×800 viewports.

Verified flow:

`overview -> reuse investigation -> select same-contract candidate -> server validates ReuseDecision -> solution option renders -> patch remains blocked -> candidate evidence opens frozen source`

Observed results:

- candidate list showed relationship, fit, score, rationale, path, and evidence;
- selecting `LegacyDispatcher.dispatch` created a bounded reuse option;
- the first browser pass exposed contradictory stale gate copy, which was fixed;
- final state distinguished “decision validated” from “patch still blocked”;
- candidate evidence opened `LegacyDispatcher.java` at its frozen symbol line;
- checked layouts remained usable without overlap or clipping that blocked the
  flow;
- browser console reported zero errors and zero warnings.

## Security position

- candidate retrieval consumes only the authorized frozen context packet;
- no cross-repository or cross-tenant lookup exists;
- gold responses never include assisted candidates or solution options;
- no code is executed and no patch is applied;
- a patch cannot pass `verifyPatchRelease` without a decision, selected option,
  local diff, verification method, and budget compliance;
- production dependency audit reports zero known runtime vulnerabilities.

## Open evidence

This implementation does not prove whole-repository recall quality or patch
adoption. Those claims require a production whole-repository graph, persisted
audit records, local preview generation, R4 validation, and calibrated real
samples.

