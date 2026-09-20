# Fixed HTTPS endpoint validation — 2026-09-20

Status: passed for the local public-beta deployment.

## Endpoint

- Provider: ngrok assigned development domain
- Public origin: `https://cupbearer-handclasp-diffusion.ngrok-free.dev`
- GitHub webhook: `https://cupbearer-handclasp-diffusion.ngrok-free.dev/webhooks/github`
- Supervisor mode: `local-beta-fixed-ngrok`
- Local upstream: `http://127.0.0.1:3000`

The supervisor verified both the local and public `/readyz` endpoints before it
updated and read back the GitHub App webhook configuration.

## Pull request delivery before restart

- Test PR: [codelens-ai-lab/codelens-beta-test#1](https://github.com/codelens-ai-lab/codelens-beta-test/pull/1)
- Head SHA: `f04af40fba89f9ea6e98bf3ed6e46813954fb471`
- Webhook response: HTTP 202
- Review Check: [CodeLens AI Review](https://github.com/codelens-ai-lab/codelens-beta-test/runs/106017983881)
- Result: completed, `neutral`, with the verified `src/jobs.ts:2` annotation

## Controlled restart recovery

The API, worker, and ngrok agent were stopped and restarted by the local beta
supervisor. The public origin remained unchanged and `/readyz` returned HTTP
200 through the same hostname. A second pull request update then verified real
delivery after recovery:

- Post-restart head SHA: `118e15b7db8615bd2e4cc5bdf30b8c3df3b633f0`
- Webhook response: HTTP 202
- Review Check: [CodeLens AI Review after restart](https://github.com/codelens-ai-lab/codelens-beta-test/runs/106018148818)
- Result: completed, `neutral`, with the verified `src/jobs.ts:2` annotation

## Credential hygiene

An initial ngrok authtoken was accidentally included in a setup screenshot. It
was removed from the local configuration and rotated in the ngrok dashboard
before the endpoint was started. The replacement credential is stored only in
the user's local ngrok configuration and is not present in the repository.

## Operational boundary

The hostname is stable across supervisor restarts, but this beta endpoint is
available only while the host PC, Docker Desktop, and the supervisor are
running. A managed always-on deployment remains recommended before a broader
production rollout.
