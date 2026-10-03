# R3 Reuse-first Solution Planning

Status: production graph retrieval integrated on 2026-10-03; decision persistence and patch-preview exit evidence remain open
Baseline: [`review-reasoning-and-reuse-first-improvement-plan.md`](review-reasoning-and-reuse-first-improvement-plan.md)  
Architecture decision: [`ADR 0013`](adr/0013-reuse-decision-patch-gate.md)

## Outcome

CodeLens now has a deterministic, evidence-bound layer between repository
understanding and code generation. In assisted mode a Reviewer can open “复用调查”
to see repository candidates, why each candidate was recalled, its fit level,
and the exact frozen symbol/relationship evidence. Selecting a candidate creates
one or two structured solution options. The interface continues to show that
the patch is blocked.

Formal gold mode neither returns nor renders reuse investigations or solution
options.

The production Java worker now runs the same reuse-first policy directly over
the verified Base/Head whole-repository semantic index. The investigation is
bound to the commit SHAs, adapter version, Base/Head build-model hashes, search
counts, coverage limitations, and exact symbol/relationship evidence. It is
stored with the semantic review audit and summarized in the GitHub Check. This
production path exposes candidates only; it does not fabricate a decision or
open the patch gate.

## Implemented contracts

The `@codelens/solution-planning` package provides:

- `ReuseCandidate`: symbol, path, relationship, fit, score, evidence, rationale,
  and optional rejection reason;
- `ReuseSearchScope`: changed files, searched symbol/relationship counts,
  semantic coverage, evidence, and limitations;
- `ReuseDecision`: goal, candidates, decision, selected candidate,
  justification, and change budget;
- `SolutionOption`: strategy, expected files/symbols, verification, trade-offs,
  and evidence;
- `PatchProposal`: bounded local diff plus declared scope and verification;
- `verifyPatchRelease`: the fail-closed publication gate.

The Java `SemanticReusePlanner` provides the production graph adapter and
serializable investigation record. Migration `013_semantic_reuse_investigation`
stores that record on the review analysis without changing historical
migrations.

## Retrieval signals

The retriever currently uses deterministic frozen facts in this order:

1. same symbol name and kind as a same-contract candidate;
2. test symbols directly related to the behavior as reusable fixtures;
3. same-kind symbols sharing a caller as same-role candidates;
4. symbols used by the same caller as caller-pattern evidence;
5. same-kind name-token overlap as low-confidence similar-logic recall.

Name-token similarity can recall a candidate but cannot prove reuse. Every
candidate remains linked to frozen symbol and relationship evidence.

## Fail-closed rules

- no valid `ReuseDecision` → no patch publication;
- no selected `SolutionOption` → no patch publication;
- no local diff or verification method → no patch publication;
- file or symbol count above the change budget → no patch publication;
- undeclared public contract change → no patch publication;
- incomplete semantic coverage → the decision cannot be `new`;
- `new` with an unrejected candidate → invalid decision;
- candidate/decision/patch behavior IDs do not match → no publication.

## Mode separation

The gold `/api/state` response excludes `assistance` and
`reuseInvestigations`, and the gold solution-options endpoint returns 403.
Assisted mode exposes candidate investigation and validates every option request
against `ReuseDecisionSchema`. This preserves the R1/R2 gold boundary.

## R3 exit status

Implemented:

- deterministic `Reuse Candidate Retriever`;
- auditable `ReuseDecision` contract;
- 1–3 option contract and assisted option generation;
- explicit change budget;
- fail-closed patch publication verifier;
- gold/assisted API and UI separation;
- production Java whole-repository candidate retrieval;
- exact evidence for both sides of a shared-caller claim;
- persisted Base/Head SHA, adapter, build-model, coverage, and candidate audit data;
- GitHub summary visibility while the patch gate remains closed.

Still open before declaring R3 complete:

- persist reviewer decisions and selected options with SHA and index-version
  audit metadata (the investigation itself is now persisted);
- generate a genuinely local patch preview only after option approval;
- route that preview through the publication gate in the production worker;
- run calibrated samples proving every code modification suggestion has either
  a usable candidate or a valid “new” proof.

R3 is therefore **implemented as a safe planning foundation, not exited**.

