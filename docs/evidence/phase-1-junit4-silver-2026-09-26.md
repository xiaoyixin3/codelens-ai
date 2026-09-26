# Phase 1 JUnit 4 compiler-oracle silver evidence — 2026-09-26

Status: development silver evidence; not product-exit gold

- Repository: `junit-team/junit4`
- License: EPL-1.0
- Tag: `r4.13.2`
- Pinned commit: `05fe2a64f59127c02135be22f416e91260d6ede6`
- Scope: `src/main/java/org/junit/runners/BlockJUnit4ClassRunner.java`
- Adapter: `javaparser-3.28.2-v2`

## Why this repository was added

The first Gson scope contained straightforward calls and produced complete tool
agreement. JUnit 4 adds inheritance, explicit superclass constructors, annotation
members, generic APIs, nested classes, and anonymous classes. It was selected as
a second engineering corpus before viewing CodeLens output.

## Observed failures and fixes

The first raw comparison reported only 29.30% agreement. Inspection showed that
most differences were representation mismatches: JavaParser retained generic
parameters, javac used erased signatures, and anonymous classes used incompatible
owner names. The silver comparator now canonicalizes generic signatures and
scores target resolution by source call site. Exact caller identity remains part
of the gold evaluator rather than being silently removed from the product gate.

After representation normalization, agreement increased to 84.55%. The seven
remaining javac-only facts exposed real adapter gaps:

- explicit `super(...)`/`this(...)` constructor calls were not indexed;
- annotation members were not represented as callable symbols;
- uniquely identifiable annotation/member calls could remain unresolved.

Adapter v2 adds those relationships, a fail-closed unique repository-symbol
fallback, and deterministic anonymous-class symbol owners. New regression tests
cover all three behaviors.

## Final silver result

- JavaParser resolved calls in scope: 105
- JDK compiler-oracle calls in scope: 105
- Agreements: 105
- Adapter-only: 0
- Oracle-only: 0
- Tool agreement: 100.00%
- Deterministic one-in-ten review packet: 11 items
- Repository Java files indexed: 471
- JavaParser parse failures: 0
- Compiler diagnostics: 100

Compiler diagnostics remain visible because the non-executing oracle does not
load every external/generated input. They prevent treating this result as
compiler-certified gold. The final number is also a development result after
using this corpus to repair adapter v2, so JUnit 4 must not be reused as a sealed
holdout for a product claim.

## Interpretation

The workflow accomplished its intended purpose: automated disagreement isolated
seven actionable gaps, fixes were validated against the unchanged scope, and the
human-facing packet fell from 105 relations to 11 deterministic agreement samples.
Phase 1 remains open until separate qualified, prediction-blind gold evidence is
available from at least two repositories.
