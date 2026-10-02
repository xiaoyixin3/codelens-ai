# ADR 0013: Require a ReuseDecision before patch publication

Status: accepted

- Date: 2026-10-02
- Refines: ADR 0001 and the R3 section of the reuse-first improvement plan

## Context

A free-text instruction such as “prefer reuse” does not stop an agent from
rewriting a function, duplicating an existing abstraction, or bypassing a
repository interface. A plausible patch can therefore look helpful while
increasing review cost and architectural drift.

The repository already exposes symbols and relationships, but the historical
finding contract contains only a textual suggestion. It has no auditable record
of what was searched, which candidates were considered, why a candidate was
selected or rejected, or how large the proposed change may be.

## Decision

Every future code patch publication path must fail closed unless it receives:

1. a valid `ReuseDecision` bound to one behavior change;
2. a selected `SolutionOption` derived from that decision;
3. a bounded `PatchProposal` with touched files, changed symbols, contract
   declaration, verification steps, and a local unified diff;
4. a successful `verifyPatchRelease` result.

`ReuseDecision` records the search scope, candidates, evidence, decision,
justification, and change budget. `reuse`, `extend`, and `extract` require a
selected non-rejected candidate. `new` requires complete semantic coverage, a
non-empty search, and an explicit rejection reason for every retrieved
candidate. “No suitable implementation” is not accepted as sufficient proof.

The release gate rejects mismatched behavior IDs, missing decisions or options,
missing verification, file or symbol counts above budget, and undeclared public
contract changes. Coverage limitations block a “new implementation” claim;
they are never converted into evidence that no reusable code exists.

Formal gold evaluation does not expose reuse candidates, decisions, or solution
options. Those are assisted-mode predictions and remain separated from gold
labels.

## Consequences

- Patch generation can be added later without weakening the gate.
- Agents must explain reuse choices before they write code.
- Incomplete semantic coverage produces an actionable blocked state rather
  than a fabricated “nothing reusable found” conclusion.
- The current release has no automatic patch publisher; therefore the gate is
  enforced in the planning contract and tests now, and must be injected into
  any future publisher before that publisher is enabled.
- R4 still owns rewrite/churn, duplicate implementation, contract-diff, build,
  and directed-test verification.

