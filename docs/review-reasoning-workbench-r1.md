# R1 Review Reasoning Workspace

Status: implemented on 2026-10-02  
Baseline: [`review-reasoning-and-reuse-first-improvement-plan.md`](review-reasoning-and-reuse-first-improvement-plan.md)

## Outcome

The historical diff labeler is now a root-cause reasoning workspace. A Reviewer
can inspect changed lines, unchanged base/head files, tests, symbol definitions,
and caller relationships without leaving the page, then record:

- a root-cause claim, trigger, and observable impact;
- diff or cross-file evidence on either base or head;
- affected symbols, an acceptable fix direction, and a verification method;
- confidence and an explicit uncertainty note.

A complete root cause is the saved unit. A line-level warning is not.

## Formal gold versus assisted mode

`--mode=gold` is the formal path. The server does not instantiate the risk
reviewer, does not generate suggestions, and removes prediction metadata from
all API responses. A gold case cannot be frozen unless its content-addressed
neutral context packet exists and matches the replay case SHAs. Insufficient
context is a valid reason to defer; the UI does not force a guess.

`--mode=assisted` is for product exploration and silver labels. It may expose
machine suggestions. Each mode requires its own version-3 decision store, and
the server rejects a store whose mode does not match the current process.

Do not use assisted decisions as independent gold evidence.

## Frozen neutral context

Generate a packet from clean base/head worktrees whose `HEAD` values exactly
match the replay case:

```bash
npm run benchmark:context -- \
  --input=benchmarks/candidates/public-prs.jsonl \
  --case=<case-id> \
  --base-root=<base-worktree> \
  --head-root=<head-worktree> \
  --base-semantic=<optional-base-index.json> \
  --head-semantic=<optional-head-index.json>
```

The builder refuses dirty or SHA-mismatched worktrees, symlinks, unsafe paths,
oversized repositories, and binary/build output. It freezes full bounded text
content and optionally ingests neutral semantic facts using this shape:

```json
{
  "symbols": [
    {
      "stableKey": "pkg.Type#method(java.lang.String)",
      "kind": "METHOD",
      "qualifiedName": "pkg.Type.method",
      "path": "src/main/java/pkg/Type.java",
      "startLine": 10,
      "endLine": 18
    }
  ],
  "relationships": [
    {
      "fromStableKey": "pkg.Caller#run()",
      "toStableKey": "pkg.Type#method(java.lang.String)",
      "type": "calls",
      "sourcePath": "src/main/java/pkg/Caller.java",
      "sourceLine": 24
    }
  ]
}
```

When no semantic snapshot is supplied, full source remains available and the
packet records the missing symbol/relationship coverage as a limitation. The
workbench verifies the packet digest, case ID, and both SHAs before serving it.

## Run and export

```bash
npm run benchmark:label -- --mode=gold
```

Open `http://127.0.0.1:4310`. Selecting any red or green diff line creates LEFT
or RIGHT diff evidence. Selecting a line in the context browser creates base or
head source evidence; symbol and relationship buttons navigate to their frozen
locations. A frozen decision is immutable. Export stays blocked until every
case has a frozen decision.

Legacy `blind-v1` stores are read only. Their line findings appear as incomplete
drafts and must be enriched with trigger, impact, verification, and confidence.
The old file is never modified.

## R1 exit-condition mapping

| Requirement | Enforced by |
| --- | --- |
| Trigger, impact, verification, acceptable fix | `RootCauseLabelSchema` and the reasoning form |
| Cross-file/base/head evidence | neutral context packet and server-side path/line validation |
| No added-line-only restriction | LEFT/RIGHT diff mapping plus base/head source evidence |
| Symbol, caller, and test navigation | packet symbols, relationships, and file roles |
| Gold prediction isolation | mode-specific reviewer construction and response redaction |
| Frozen evidence integrity | clean SHA checks, SHA-256 digest, case/SHA binding |
| Safe old-data migration | separate `--legacy-decisions` read-only import |

The R1 implementation changes the labelling instrument; it does not by itself
complete the Phase 0 real-sample evidence gate or replace qualified independent
Reviewers.

The next implemented product layer is documented in
[`change-brief-guided-review-r2.md`](change-brief-guided-review-r2.md). R2 adds
the evidence-traceable change map and guided behavior review while preserving
this R1 gold-isolation boundary.
