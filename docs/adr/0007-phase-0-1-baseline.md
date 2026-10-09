# ADR 0007: Adopt the evidence-driven Phase 0/1 baseline

Status: accepted, 2026-09-25

## Context

The production Java runtime is reliable, but the review intelligence engine only
indexes changed files and infers declarations and calls with regular expressions.
That implementation cannot establish complete repository impact, distinguish
overloaded dispatch reliably, or find callers and tests that live in unchanged
files. The project therefore cannot claim that it performs a trustworthy first
review pass.

[`../technical-baseline-v2.md`](../technical-baseline-v2.md) is the normative
technical and product baseline for subsequent work. If an older ADR, README
claim, implementation shortcut, or milestone conflicts with that document, the
baseline takes precedence until a later explicit ADR amends it.

## Decision

1. Work is limited to Phase 0 and Phase 1 until their documented exit conditions
   are met. New shallow language rules, new language claims, UI polish, autonomous
   agent loops, and automatic repair branches are deferred.
2. The current changed-file multi-language parser remains available only as a
   `fallback` path. It is not evidence of semantic coverage.
3. Java is the first deep adapter. Its production path must index a complete base
   repository snapshot and use resolved AST/type information for repository
   relationships.
4. Every snapshot is keyed by repository, commit SHA, adapter version, and build
   model hash. Coverage, failures, unresolved relationships, and fallback reasons
   are first-class output.
5. Materialization and indexing are S1 operations: repository content is treated
   as untrusted data and is never executed. Build plugins, tests, and package
   scripts remain outside this phase.
6. Phase 0 metrics are not inferred from smoke fixtures. The phase remains open
   until at least 50 permissioned real PRs and 20 positive root causes have been
   independently blind-labelled and the current product has been replayed against
   them.
7. Phase 1 remains open until direct, statically resolvable Java calls reach at
   least 90% precision on selected repositories and the system demonstrates that
   it finds direct callers and tests in unchanged files.

## Consequences

- ADR 0005's bounded parser is retained for honest degradation, but its broad
  language support is no longer a product-depth claim.
- Existing webhook, queue, persistence, publication, and audit contracts remain
  in place while the semantic subsystem is introduced behind new contracts.
- Stage completion is recorded only with reproducible evidence. A passing unit
  test suite is necessary engineering evidence, but does not by itself satisfy a
  product or benchmark exit condition.

