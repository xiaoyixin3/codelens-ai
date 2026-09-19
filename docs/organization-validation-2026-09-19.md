# GitHub organization validation — 2026-09-19

Status: passed.

The public beta GitHub App was installed on the `codelens-ai-lab`
organization with access limited to the private `codelens-beta-test`
repository.

## Installation

- App: `codelens-ai-xiaoyixin3-beta`
- Organization installation ID: `163015132`
- Repository selection: selected repositories only
- Repository: `codelens-ai-lab/codelens-beta-test`
- Verified permissions:
  - Checks: write
  - Contents: read
  - Issues: read
  - Metadata: read
  - Pull requests: write

## End-to-end review

- Test PR: [codelens-ai-lab/codelens-beta-test#1](https://github.com/codelens-ai-lab/codelens-beta-test/pull/1)
- Immutable head SHA: `3138fd5b1840ec136288b41744ea983081628fa8`
- Webhook result: accepted with HTTP 202
- Review Check: [CodeLens AI Review](https://github.com/codelens-ai-lab/codelens-beta-test/runs/105911692916)
- Summary comment: [Bot review summary](https://github.com/codelens-ai-lab/codelens-beta-test/pull/1#issuecomment-5742622361)
- Check conclusion: `neutral` with one verified high-severity finding
- Annotation: `src/jobs.ts:2`, `Async forEach is not awaited`
- Evidence verification: exact added-line annotation published successfully

The intentionally unsafe pull request remains unmerged and is labelled in its
description as organization-installation validation material.

## Controlled restart regression

The local beta supervisor was then restarted with Cloudflare transport pinned
to HTTP/2. It created and verified a new public Tunnel URL before updating the
GitHub App webhook. A follow-up commit on the same pull request produced a new
`pull_request.synchronize` delivery and completed a second review:

- Follow-up head SHA: `88adcecbd8ddff7c230f513e4621f40d099ce6ad`
- Review Check: [CodeLens AI Review after restart](https://github.com/codelens-ai-lab/codelens-beta-test/runs/105912967028)
- Result: completed, `neutral`, one verified annotation at `src/jobs.ts:2`

This verifies controlled URL rotation and review recovery for the local beta.
It does not satisfy the separate fixed-HTTPS release gate; a stable Named
Tunnel or managed deployment is still required.
