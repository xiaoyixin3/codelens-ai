# ADR 0001: Evidence-first findings

Status: Accepted

## Decision

Model and deterministic reviewer output are candidates, not publishable findings. Publication requires a current PR head SHA, an exact right-side added line, matching excerpt when supplied, a unique fingerprint, and the configured confidence threshold.

## Consequences

This intentionally sacrifices recall when GitHub omits or truncates a patch. It prevents unsupported comments and makes every annotation auditable.
