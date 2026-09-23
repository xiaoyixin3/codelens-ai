# Blind benchmark protocol

Status: implemented as `blind-v1`.

## Why this exists

The earlier 100-case queue was mined with the same deterministic rules that it
evaluated. Reviewers could only accept or reject visible machine suggestions,
so they could not record a problem that the machine missed. A perfect score on
that queue therefore measures regression stability, not real-world review
quality. It must not be published as product precision or recall.

## Protocol

1. `npm run benchmark:collect -- --target=120` samples merged, non-bot pull
   requests without consulting detector output.
2. Sampling is stratified across TypeScript/JavaScript, Go, Java, and Python,
   with two independently maintained repositories per language.
3. `npm run benchmark:label` serves the candidate PRs only on
   `http://127.0.0.1:4310`.
4. Before submission, the API does not send machine predictions to the browser.
   The reviewer reads the PR and diff, then records zero or more independent
   findings on added right-side lines.
5. Approving a case freezes its human labels. Only after the freeze does the API
   reveal machine predictions and show matches, misses, and extra predictions.
   Frozen labels cannot be edited after reveal.
6. Export remains blocked until every case is frozen. The exported file is
   `benchmarks/candidates/blind-approved-replay.jsonl`.
7. `npm run benchmark:gate -- benchmarks/candidates/blind-approved-replay.jsonl`
   reports point estimates, Wilson 95% confidence intervals, per-category metrics,
   and every false positive and false negative.

## Interpretation rules

- The old assisted queue is a regression suite only.
- A finding matches only when normalized category, file, and right-side line
  match. Reviewers do not need to know an internal detector rule ID.
- `human/*` labels intentionally measure review risks beyond the current two
  deterministic rules. They will count as machine misses until a model or rule
  detects them.
- Tuning must use a separate development set. A held-out test set is evaluated
  once per release candidate and is never used to change rules or labels.
- Product claims include the sample size, class balance, language mix, point
  estimate, and 95% interval. Small-sample `100%` is never described as perfect
  real-world accuracy.
- For publication-quality results, two reviewers should label independently;
  disagreements should be adjudicated by a third reviewer before freezing the
  release dataset.

## Local files

The candidate queue, decisions, and exported labels remain Git-ignored because
public PR content may still require review before redistribution. The decision
store is versioned as protocol `blind-v1`; it is intentionally incompatible
with the old assisted-label decision file.
