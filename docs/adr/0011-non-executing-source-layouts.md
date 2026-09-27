# ADR 0011: Non-executing custom Java source layouts

Status: accepted
Date: 2026-09-27
Baseline: `docs/technical-baseline-v2.md`

## Context

The S1 build model discovered only conventional `src/main/java` and
`src/test/java` directories. Repositories that replace those roots in Maven or
Gradle were therefore reported as having no Java sources even when the declared
directories were present in the exact commit archive. Invoking a build tool to
discover source sets would violate the S1 no-execution boundary.

## Decision

- Maven `<build><sourceDirectory>` and `<testSourceDirectory>` values are read
  from XXE-safe XML. Literal project properties and `${project.basedir}` aliases
  may be substituted without evaluating Maven.
- Gradle support is restricted to explicit `sourceSets.main/test.java.srcDir(s)`
  forms containing only quoted literal paths. Assignment replaces the conventional
  root; additive calls retain it. Block DSLs, providers, catalogs, functions, and
  other dynamic expressions remain unsupported and visible as degradation.
- A source root must be relative, exist in the materialized repository, remain
  inside the repository after canonicalization, avoid excluded build/output
  directories, and not be a final symlink. Invalid, absolute, missing, excluded,
  dynamic, unreadable, and escaping roots are never indexed.
- Explicit Maven roots replace Maven defaults even when the declaration is
  invalid or missing. This prevents indexing files the declared build would not
  compile. Any material source-layout degradation publishes
  `semantic/partial` rather than full semantic coverage.
- A known Maven/Gradle build with no accepted source root never falls back to a
  whole-repository scan. It yields zero indexed files and fails closed; only a
  repository with no recognized build system may use the legacy bounded scan,
  and that result is always `semantic/partial`.

## Consequences

Custom literal Java layouts can now participate in module discovery, full-source
indexing, test classification, build-model hashing, and snapshot reuse without
executing repository code. More complex layouts still require a later trusted
build-model source and cannot be inferred at S1. The adapter advances to
`javaparser-3.28.2-v4`, and the pipeline advances to
`v1.0.0-beta.1-java.4`.
