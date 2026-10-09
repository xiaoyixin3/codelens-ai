# R3 Audited Decision Persistence Evidence — 2026-10-03

Status: implementation and automated release evidence complete; calibrated R3 exit evidence remains open

## Delivered boundary

The Java API now provides an explicit approval boundary at:

- `GET /api/v2/reviews/{reviewRunId}/reuse-planning`;
- `POST /api/v2/reviews/{reviewRunId}/reuse-planning`.

Both operations use the existing constant-time administrative Bearer-token
check and require an installation ID. Database ownership joins prevent one
installation from reading or deciding another installation's review.

The POST operation does not trust client-supplied search results. Inside one
database transaction it locks and reloads the persisted production
investigation, then verifies:

- investigation ID, Base/Head SHA, adapter version, and both build-model hashes;
- decision strategy and selected candidate membership;
- complete, non-empty semantic search before `new`;
- an explicit reason for every candidate rejected by `new`;
- bounded files/symbols and safe repository-relative paths;
- option strategy and candidate consistency;
- non-empty verification and trade-off plans.

Evidence IDs on the selected option are derived from the frozen investigation,
not accepted from the request.

## Audit and concurrency behavior

Migration `014_reuse_decisions.sql` adds separate decision and selected-option
tables. Each accepted change appends a revision and marks the previous current
revision as superseded; historical rows are never overwritten.

Clients submit `expectedRevision`. The semantic analysis row is locked before
revision allocation, and a stale revision receives a conflict instead of
silently replacing another Reviewer's choice. Database constraints independently
enforce SHA format, strategy values, candidate presence, actor bounds, and one
current decision per review.

## Automated coverage

Focused tests cover valid reuse, server-derived evidence, forged provenance,
unknown candidates, unsafe paths, incomplete search, missing candidate
rejections, shared authorization, API error mapping, migration continuity, and
the production graph contract. The environment-gated PostgreSQL test covers two
append-only revisions, supersession, stale-write rejection, and installation
ownership on an actual migrated database.

The complete release gate passed:

- secret scan: 284 files inspected, zero findings;
- Java: 84 tests across 33 suites, zero failures/errors (5 environment-gated skips,
  including the PostgreSQL contract test);
- TypeScript: typecheck passed, 15 files and 78 tests passed;
- Java package and legacy TypeScript bundles built successfully;
- release manifest: `automatedReady: true`, migration 014 present, no missing
  workflow files, no unpinned actions, and read-only workflow permissions.

## Remaining R3 exit work

- generate a genuinely local patch preview from the approved option;
- pass the preview plus persisted decision through the production patch gate;
- collect calibrated evidence that every modification suggestion has a usable
  candidate or a valid audited `new` proof.

No patch generation or repository write permission was enabled by this change.
