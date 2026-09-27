# ADR 0010: Non-executing dependency classpath at S1

Status: accepted
Date: 2026-09-27
Baseline: `docs/technical-baseline-v2.md`

## Context

The Java whole-repository adapter previously solved symbols only against the JDK
and repository source roots. External calls therefore remained unresolved even
when a project declared exact dependencies. Running Maven or Gradle to construct
the classpath would execute untrusted plugins, scripts, extensions, annotation
processors, or repository-controlled configuration and would violate S1.

## Decision

- S1 reads build descriptors as data and never invokes Maven, Gradle, wrappers,
  repository scripts, plugins, tests, or network dependency resolution.
- Maven XML parsing disables DTDs, external entities, external schemas, and
  XInclude. Only direct project dependencies with literal coordinates or locally
  resolvable project properties are admitted. Gradle support is limited to
  literal string coordinates after comments are removed; catalogs, project
  dependencies, platforms, and dynamic expressions are reported as degradation.
- JARs may be loaded only from an operator-configured, external Maven-layout
  cache. A cache inside or above the analyzed repository is rejected. Missing,
  symlinked, outside-root, unreadable, oversized, and excess entries are not
  loaded and remain visible in coverage.
- The build-model hash includes normalized dependency coordinates plus each
  admitted JAR's size and SHA-256 digest. The adapter rechecks file type, size,
  and digest immediately before constructing a `JarTypeSolver`; a changed cache
  entry cannot be used under the prior snapshot identity.
- This parser does not claim an effective Maven/Gradle model. Transitive,
  dependency-management, active-profile, version-catalog, variant, and generated
  classpaths remain explicit limitations, so repositories with declarations are
  published as `semantic/partial` until a complete trusted classpath is available.

## Consequences

Exact direct dependencies can now improve external type and call resolution
without broadening execution authority. Empty or incomplete caches remain safe
and fail visibly rather than triggering a download or build. Operators must mount
the cache read-only and manage its contents outside CodeLens. The adapter advances
to `javaparser-3.28.2-v3`, and the pipeline advances to
`v1.0.0-beta.1-java.3`, invalidating earlier semantic snapshot identities.
