# R2 Change Brief and Guided Review

Status: product foundation implemented on 2026-10-02; calibrated exit evidence remains open  
Baseline: [`review-reasoning-and-reuse-first-improvement-plan.md`](review-reasoning-and-reuse-first-improvement-plan.md)

## Outcome

The review workspace now starts with a 30-second change map instead of a raw
patch. It groups related changed files into behavior cards and gives the
Reviewer a bounded path through intent, before/after definitions, unchanged
callers, tests, and neutral questions. Every displayed factual statement links
to frozen evidence. Missing facts are represented as `unknown`; the UI does not
invent an explanation.

The default flow is now:

1. read the PR intent and coverage status;
2. choose a behavior layer instead of hunting through file tabs;
3. compare Base and Head definitions;
4. inspect unchanged callers and tests through evidence links;
5. answer neutral contract, reuse, caller, and test questions;
6. record a verifiable root cause using the R1 contract.

## What was borrowed and what remains different

The interface combines useful interaction patterns from current review tools
without copying their conclusion-first behavior:

| Product pattern | Adopted in CodeLens | Deliberate constraint |
| --- | --- | --- |
| CodeRabbit-style walkthrough and review grouping | compact change overview and behavior layers | gold mode contains no generated risk conclusion |
| Qodo-style structured review dimensions | contract, caller, test, and coverage sections | each fact has evidence IDs validated by schema |
| Greptile-style repository context navigation | direct jumps from a question to source, symbol, relationship, or test | frozen context only; no hidden live retrieval in gold mode |
| GitHub Copilot-style low-friction review entry | overview is the default view; raw diff remains one click away | no suggestion is treated as proof |
| Cursor BugBot-style action orientation | questions are phrased around what must be checked next | automatic fixes remain blocked until the R3 reuse decision exists |

CodeLens' product distinction is not another list of AI comments. It is an
evidence-traceable reasoning path that can operate in prediction-blind gold
evaluation and later feed an assisted solution workflow.

## Data contract

`ChangeBriefSchema` defines the server/UI boundary:

- `intent`: a traceable PR-title/body statement or an explicit unknown;
- `coverage`: changed files, frozen context files, symbols, relationships, and
  recorded limitations;
- `behaviorCards`: behavior-level changed files, core symbols, Base/Head
  statements, unchanged callers, related tests, and guided questions;
- `evidence`: typed PR, diff, source, symbol, relationship, and test references.

Schema refinements enforce the evidence policy:

- a `fact` must have at least one evidence reference;
- every referenced evidence ID must exist in the brief;
- evidence IDs are unique;
- caller questions receive only caller relationships;
- test questions receive only test files and test relationships.

The builder uses deterministic frozen facts. It does not invoke the risk
reviewer and does not add model output to formal gold responses.

## Grouping and degradation rules

Changed files connected by frozen semantic relationships are grouped into one
behavior card. Unconnected files remain separate cards. If the packet has no
semantic symbols or relationships, the brief stays usable but marks the
coverage limitation and falls back to file-level groups. That fallback is
visible to the Reviewer; it is never labelled semantic coverage.

The reuse question is intentionally a checkpoint, not a result. R2 asks whether
an existing contract or role should be reused. Candidate retrieval,
`ReuseDecision`, solution options, and patch blocking belong to R3 and must not
be implied by this implementation.

## Browser acceptance flow

The checked interaction is:

`overview -> behavior layer -> caller evidence -> frozen source context`

At 1280×720 and 1180×800 the primary navigation, overview, behavior card, and
root-cause form remain usable. The tested page emitted no browser warnings or
errors. Evidence selection navigated to the intended unchanged caller and line.

## R2 exit status

Implemented product requirements:

- default 30-second change map;
- behavior-oriented grouping;
- Base/Head, caller, test, contract, and coverage labels;
- neutral evidence-bound guided questions;
- strict fact/inference/unknown representation;
- prediction-free gold API behavior inherited from R1.

Still required before declaring R2 complete:

- run predefined factual comprehension questions on calibrated real samples;
- record whether Reviewers can answer them without repository searching;
- measure time to first correct behavior explanation;
- audit all sampled summary sentences for evidence traceability;
- meet the baseline's quantitative exit threshold rather than relying on UI
  inspection.

Until that evidence is recorded, R2 is **implemented but not exited**.

