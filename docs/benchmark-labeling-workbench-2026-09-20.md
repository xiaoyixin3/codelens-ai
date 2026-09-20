# Historical benchmark labelling workbench — 2026-09-20

Status: implemented and locally validated; human review is pending at 0/100.

## Outcome

`npm run benchmark:label` starts a loopback-only workbench at
`http://127.0.0.1:4310`. It presents each historical PR with its source link,
license, changed files, unified diff, and verified deterministic suggestions.
The reviewer can approve no findings, approve selected suggestions, or defer a
case.

The interface was designed from the checked-in concept reference at
[`docs/assets/benchmark-review-concept.png`](assets/benchmark-review-concept.png)
and verified at a 1440×900 viewport in a real Edge browser.

## Human gate safeguards

- Suggestions start unselected and are explicitly labelled as machine aids.
- A reviewer name or identifier is required before saving.
- Only verified suggestions belonging to the current case can be approved.
- Decisions are written atomically to the Git-ignored
  `benchmarks/candidates/review-decisions.json` file.
- Deferred cases cannot contain approved findings.
- Export returns HTTP 409 until all 100 cases have an approved human decision.
- The exported JSONL is revalidated with `ReplayCaseSchema`.
- The server binds to `127.0.0.1`, rejects foreign `Host` and `Origin` values,
  and sends a restrictive Content Security Policy.

## Validation evidence

- Queue: 100 cases, 20 suggested-positive, 80 suggested-negative.
- Sources: 17 repositories, 0 duplicate IDs, 0 duplicate PR URLs.
- Suggestions: 28 total across two deterministic rules.
- Browser console: 0 errors, 0 warnings.
- Selecting a suggestion enables the positive approval action; it is disabled
  when nothing is selected.
- Temporary valid decision: saved successfully.
- Unsupported/invented finding: HTTP 400.
- Incomplete 1/100 export attempt: HTTP 409.
- Cross-origin request: HTTP 403.
- Temporary test decisions were removed after validation; formal progress
  remains 0/100.

## Completion procedure

1. Run `npm run benchmark:label` and open `http://127.0.0.1:4310`.
2. Review all 100 cases; do not approve a machine suggestion without reading
   the cited diff.
3. Export the approved dataset from the workbench.
4. Run `npm run benchmark:gate -- benchmarks/candidates/approved-replay.jsonl`.
5. Copy the final permissioned dataset to the release-controlled location only
   after the benchmark gate passes and the maintainer approves publication.
