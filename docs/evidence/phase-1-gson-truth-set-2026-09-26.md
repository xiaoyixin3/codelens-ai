# Phase 1 Gson direct-call truth set — 2026-09-26

Status: engineering evidence; not independent product-exit evidence

- Repository: `google/gson`
- License: Apache-2.0
- Pinned commit: `854c8255b625cf1e13c701a83ea9ccb4caaa576a`
- Adapter: `javaparser-3.28.2-v1`

## Scope and method

The truth set was manually curated from
`gson/src/main/java/com/google/gson/JsonParser.java` at the pinned commit before
running the evaluator. It contains the 16 statically resolvable calls from that
file to Gson-owned methods and constructors. Calls to JDK types are outside this
truth-set boundary.

The executable test refuses to run if the checkout HEAD differs from the pinned
commit. It indexes the complete repository and then compares resolved `CALLS`
relationships for the reviewed source file by source line, caller stable key, and
target stable key. The expected set is stored in source, while the external
repository remains an explicit opt-in input and is never downloaded by the test.

## Result

- Command date: 2026-09-26
- Expected relationships: 16
- Emitted relationships in scope: 16
- Correct relationships: 16
- Precision: 100.00%
- Recall: 100.00%
- Indexed Java files: 263
- Parse failures: 0
- Repository-wide resolved relationships: 341,218
- Repository-wide unresolved relationships: 444,848

An independent JDK compiler-attribution oracle emitted the same 16 scoped call
facts, producing 100% tool agreement with the JavaParser adapter and no
disagreement queue. With a deterministic one-in-ten agreement audit, only two
of the 16 agreed facts enter the human review queue. The compiler reported 93
diagnostics elsewhere in the selected module because the non-executing run did
not load generated inputs or external dependencies; those diagnostics remain
visible and prevent treating the compiler output as gold evidence.

The large unresolved count is retained as degradation evidence. The current
non-executing S1 build model discovers module source roots but does not execute
Maven or load dependency classpaths. The exact scoped result therefore does not
support a claim that all Gson relationships resolve correctly.

## Reproduction

1. Obtain the Gson repository through an independently controlled process.
2. Check out exactly `854c8255b625cf1e13c701a83ea9ccb4caaa576a`.
3. Run `GsonSemanticTruthSetTest` with the absolute checkout path supplied through
   the `codelens.gson.repository` system property.

The regular test suite skips this test when the property is absent, so release
verification never downloads or executes third-party repository code.

## Exit-gate interpretation

This result clears the adapter's 90% threshold for one narrow, fixed real-source
truth set. It does **not** close Phase 1 because the same implementation work
produced the labels, only one external repository is represented, and the
baseline requires selected repositories plus independently labelled evidence.
The unchanged-file caller and test requirement is separately exercised against
the CodeLens repository and synthetic fixture.
