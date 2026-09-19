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

The release gate defaults also require at least 20 positive cases (one or more
confirmed findings) and 20 negative cases (no confirmed findings). Override the
counts only through the documented `BENCHMARK_MIN_POSITIVE_CASES` and
`BENCHMARK_MIN_NEGATIVE_CASES` environment variables when running a deliberately
different evaluation profile.
