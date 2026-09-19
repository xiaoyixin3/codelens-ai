## Summary

Describe the user-visible outcome and why the change is needed.

## Verification

- [ ] `npm run release:check` passes
- [ ] Behavior changes include focused tests
- [ ] SQL changes use a new ordered migration
- [ ] Webhook, retry, stale-SHA, and publication behavior remain idempotent
- [ ] No credentials, private repository content, or unapproved benchmark data are included
- [ ] Documentation and release notes are updated when behavior or operations change

## Risk and rollback

Describe failure modes, data compatibility, rollout scope, and the safe rollback procedure.
