# Phase 1 custom Java source-layout evidence — 2026-09-27

Status: implemented for safe literal layouts; product exit open

## Implemented

- Shared hardened Maven XML reader used by dependency and source-layout parsing.
- Maven main/test source overrides with bounded local property substitution.
- Common explicit Gradle `sourceSets.main/test.java.srcDir(s)` literal forms.
- Repository containment, canonical-path, excluded-directory, existence, and
  final-symlink checks before a directory enters the build model.
- Correct Maven override semantics and Gradle assignment-versus-additive behavior.
- Coverage degradation for dynamic, unsupported, missing, absolute, excluded,
  unreadable, or escaping source declarations.

## Automated evidence

The acceptance tests demonstrate that:

1. Maven property-based custom main/test roots replace existing conventional roots;
2. Gradle literal main/test roots are discovered without running Gradle;
3. provider/function-based Gradle paths are rejected instead of guessed;
4. an existing directory outside the repository and a missing directory are both
   excluded with distinct degradation reasons; and
5. Java files under custom Maven main/test roots are indexed, classified, and
   connected by a type-resolved call relationship; and
6. a known build with invalid explicit roots indexes zero files instead of
   silently scanning unrelated Java files across the repository.

## Boundary

No Maven/Gradle process, wrapper, plugin, build extension, generated-source task,
or repository code is executed. Gradle block DSLs and computed providers remain
unsupported. This increment improves engineering coverage but does not alter the
sealed B5 truth-set requirement or require project-owner labelling.
