# Phase 1 dependency-aware Java evidence — 2026-09-27

Status: implemented at S1 with explicit partial-coverage boundary; product exit open

## Implemented

- Non-executing Maven and Gradle declaration parsing.
- DTD/external-entity/schema/XInclude rejection for Maven XML.
- Literal Gradle coordinate extraction without evaluating build scripts.
- Operator-only external Maven-layout cache; no downloads or repository builds.
- Count and byte limits, repository-containment rejection, symlink rejection, and
  canonical-path containment for admitted JARs.
- Dependency coordinates and JAR content digests participate in build-model and
  snapshot identity.
- Size and SHA-256 integrity are rechecked before JavaParser loads each JAR.
- Missing/incomplete/dynamic dependencies produce `semantic/partial` coverage
  reasons rather than a false full-semantic claim.

## Automated evidence

Tests cover:

1. Maven property substitution and deterministic coordinate inventory;
2. build-model hash changes when cached JAR content changes;
3. external method-call resolution from a generated, valid dependency JAR;
4. same-size JAR mutation between modeling and indexing fails the integrity check;
5. no-cache behavior remains `semantic/partial` and performs no download;
6. repository-owned caches are refused;
7. Maven DTD/XXE input is rejected without external entity expansion; and
8. comments cannot create Gradle dependencies while catalogs remain a visible
   unsupported declaration.

## Evidence boundary

This is direct-declaration classpath support, not an effective Maven/Gradle model.
No transitive graph, BOM, parent dependency management, profile activation,
Gradle variant, generated source, or annotation-processor output is claimed.
CodeLens never writes the cache; production operators are responsible for a
read-only mount. B5 still requires the sealed independent holdouts and remains
open. No human action from the project owner is required for this engineering
increment.
