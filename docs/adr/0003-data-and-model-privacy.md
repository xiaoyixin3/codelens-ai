# ADR 0003: Data and model privacy

Status: Accepted

## Decision

Repository content is never executed. Common secrets are redacted before model transmission. Model telemetry stores metadata and a redacted request hash, not full prompts or completions. Retention and repository deletion are explicit operator workflows.

## Consequences

Some findings that require the original secret-bearing text will be rejected by excerpt verification. This is preferred over exposing the secret to a provider or publication surface.
