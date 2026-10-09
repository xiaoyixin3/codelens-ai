# Historical PR replay dataset

Use one JSON object per line with an immutable PR context and human-labelled expected findings. Remove credentials, customer data, and proprietary source that is not approved for evaluation before committing a dataset.

The included `sample-replay.jsonl` is a format/runner smoke fixture, not a historical benchmark. A report is considered representative only after at least 100 real, permissioned PR cases are individually labelled and approved; smaller runs emit an explicit warning.

Every case carries provenance and approval metadata. `--gate` excludes fixtures
and unapproved candidates, so a downloaded or generated file cannot satisfy the
release gate without explicit review attribution.

Run:

```bash
npm run benchmark -- benchmarks/your-history.jsonl
```

To collect 100 local candidates from the collector's audited, permissively
licensed repository allowlist, provide a read-only GitHub token and run:

```bash
npm run benchmark:collect -- --target=100
```

Candidate output is ignored by Git and contains `approval.status="candidate"`.
Review each source PR and its diff, label `expectedFindings`, then set
`approval.status="approved"`, `approvedBy`, and `approvedAt`. Only copy reviewed
cases into `approved-replay.jsonl`.

The general collector may produce an all-negative sample because verified risk
patterns are rare in recent pull requests. Build a stratified review queue with
at least 20 machine-suggested positive candidates and 80 negative candidates:

```bash
npm run benchmark:collect-positive -- --target-positive=20 --target-total=100
```

This collector traces current risk-pattern lines back to the merged pull
requests that introduced them, validates each repository license, and preserves
the result under the ignored `benchmarks/candidates/` directory. Its suggestions
are discovery aids only; every case still requires human review and approval.

Freeze the neutral repository context for each case from two clean worktrees at
the exact replay SHAs. Supplying semantic snapshots adds symbol definitions and
call/test relationships; omitting them is recorded as a visible limitation.

```bash
npm run benchmark:context -- \
  --input=benchmarks/candidates/public-prs.jsonl \
  --case=<case-id> \
  --base-root=<clean-base-worktree> \
  --head-root=<clean-head-worktree> \
  --base-semantic=<optional-base-index.json> \
  --head-semantic=<optional-head-index.json>
```

Start the local-only formal gold workbench after collecting and freezing the
queue:

```bash
npm run benchmark:label -- --mode=gold
```

Open `http://127.0.0.1:4310`. Gold mode never generates or returns CodeLens
predictions. It exposes frozen base/head files, symbols, relationships, tests,
and the diff, and saves root-cause labels only after trigger, impact, evidence,
verification, and confidence are complete. Cases without a valid context packet
can only be deferred.

The workbench stores atomic decisions in
`benchmarks/candidates/review-reasoning-decisions.json` and exports
`benchmarks/candidates/review-reasoning-frozen.jsonl` only after every case is
frozen. Existing `blind-v1` decisions are read-only drafts: they can prefill a
form, but cannot be frozen until the missing reasoning fields are supplied, and
the original file is never overwritten. All candidate artifacts stay ignored by
Git.

For product exploration or silver labels, use a separate decision file and
explicit assisted mode. Assisted output must not be mixed into the formal gold
set.

```bash
npm run benchmark:label -- \
  --mode=assisted \
  --decisions=benchmarks/candidates/assisted-review-reasoning-decisions.json \
  --output=benchmarks/candidates/assisted-review-reasoning.jsonl
```

See [`docs/review-reasoning-workbench-r1.md`](../docs/review-reasoning-workbench-r1.md)
for the contract, mode isolation, and operating procedure.

The release gate defaults also require at least 20 positive cases (one or more
confirmed findings) and 20 negative cases (no confirmed findings). Override the
counts only through the documented `BENCHMARK_MIN_POSITIVE_CASES` and
`BENCHMARK_MIN_NEGATIVE_CASES` environment variables when running a deliberately
different evaluation profile.
