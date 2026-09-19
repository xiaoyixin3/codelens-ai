# GitHub App end-to-end smoke test

This temporary document verifies the CodeLens AI beta path from a signed GitHub
pull-request webhook through queue processing to the published check run and
summary comment.

Expected behavior:

- the webhook signature is accepted;
- one review job is queued;
- CodeLens publishes a completed check run;
- CodeLens publishes one idempotent summary comment.
