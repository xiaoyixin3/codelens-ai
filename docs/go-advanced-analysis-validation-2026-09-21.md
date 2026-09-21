# Go advanced-analysis validation — 2026-09-21

Status: passed against the real beta GitHub App installation.

## Scope

The production Go worker was restarted behind the fixed ngrok endpoint and a
signed Check Run rerequest was delivered for
[`codelens-ai-lab/codelens-beta-test#1`](https://github.com/codelens-ai-lab/codelens-beta-test/pull/1).

Validated behavior:

- `.codelens.yml` and `CODELENS.md` are read from the immutable PR head SHA, with
  safe defaults when the files are absent.
- Base and head files are fetched and indexed by `go-native-multilang-v1`.
- Both snapshots and the impact analysis are stored transactionally in the
  existing PostgreSQL schema.
- The test change produced two changed symbols and one supported impact path.
- The completed GitHub Check contained both `Impact analysis` and
  `Repository policy` sections.
- The review completed with no worker retry or failure.

## Evidence

- Review run: `1d8b2d95-6646-42c7-8caa-a1206a3f6edc`
- GitHub Check: [CodeLens AI Review](https://github.com/codelens-ai-lab/codelens-beta-test/runs/106018148818)
- Runtime result: completed, high aggregate risk, low blast-radius risk
- Indexed snapshots: 2
- Indexed files across snapshots: 2
- Changed symbols: 2
- Impacted symbols: 1

The seven-day beta counter moved to 13 completed eligible reviews with a 100%
success rate. This validation does not waive the separate 20-review release gate.
