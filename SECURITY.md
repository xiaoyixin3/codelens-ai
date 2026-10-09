# Security policy

## Reporting

Do not open a public issue for a suspected vulnerability. Report it privately to the project maintainers through the repository's private security advisory channel.

Include affected version, reproduction steps, impact, and any suggested mitigation. Do not include live credentials or customer source code.

## Supported version

During beta, only the latest `1.0.0-beta.x` release is supported. Security fixes may require immediate upgrade.

## Trust boundaries

- GitHub webhook bodies are authenticated before processing.
- Repository content and model output are untrusted.
- CodeLens does not execute repository code.
- Model prompts are redacted and full prompts/completions are not stored in telemetry.
- Published findings require exact added-line evidence.
- Administrative deletion is a local operator action requiring explicit confirmation.
- Production startup rejects example credentials and incomplete GitHub App identity.
- Applied migrations are content-addressed; unknown or changed SQL prevents readiness.
- Forwarded client addresses are ignored unless trusted-proxy mode is explicitly enabled.
- Production application containers run read-only with dropped capabilities and no-new-privileges.
