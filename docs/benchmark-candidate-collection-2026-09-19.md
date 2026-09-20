# Historical PR candidate intake — 2026-09-19

Status: candidate collection complete; human labelling and approval pending.

The collector validated each repository's live SPDX license, selected merged
non-bot pull requests with reviewable JavaScript or TypeScript patches, rejected
oversized or secret-like content, and stored the resulting dataset locally in
the gitignored `benchmarks/candidates/` directory.

## Intake result

| Repository | License | Requested | Collected |
| --- | --- | ---: | ---: |
| `fastify/fastify` | MIT | 20 | 20 |
| `vitest-dev/vitest` | MIT | 20 | 20 |
| `axios/axios` | MIT | 20 | 20 |
| `sindresorhus/p-limit` | MIT | 20 | 19 |
| `microsoft/TypeScript` | Apache-2.0 | 21 | 21 |
| **Total** |  | **101** | **100** |

Validation results:

- 100 of 100 records passed `ReplayCaseSchema`.
- 100 of 100 records have historical-PR provenance and candidate status.
- Duplicate case IDs: 0.
- Duplicate source PR URLs: 0.
- Secret-redaction changes required after collection: 0.
- Approved historical cases: 0.

The release-mode benchmark intentionally rejected this candidate file because
all 100 records are unapproved. This demonstrates that collection alone cannot
satisfy the release gate. Before approval, reviewers must label expected
findings and ensure the final set contains at least 20 positive and 20 negative
cases.

## Stratified review queue — 2026-09-20

The positive collector traced risky lines through file history to the merged PR
that introduced them, then rebuilt a human-review queue containing:

- 100 unique historical PRs across 17 public repositories.
- 20 machine-suggested positive cases and 80 machine-suggested negative cases.
- 28 verified suggestions: 27 `concurrency/no-async-foreach` and 1
  `security/no-eval`.
- Duplicate case IDs: 0; duplicate source PR URLs: 0.
- Human approvals: 0/100.

The 20/80 split is a discovery queue, not benchmark ground truth. Reviewers must
inspect every diff in `npm run benchmark:label`; suggestions never become
`expectedFindings` without an explicit human decision.
