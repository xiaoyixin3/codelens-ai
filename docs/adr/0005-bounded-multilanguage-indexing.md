# ADR 0005: Bounded multi-language indexing

Status: accepted, 2026-09-22

## Context

CodeLens must review repositories written in more than one implementation
language before V2 model-provider onboarding is useful. The V1 Go-native indexer
recognized Go, TypeScript, and JavaScript. Requiring a compiler toolchain for every
repository language would make the worker image large, increase startup and
security complexity, and risk executing language-specific build behavior.

## Decision

The Go worker uses `go-native-multilang-v2`, a bounded, non-executing parser for:

- Go;
- Java and Kotlin;
- Python;
- TypeScript and JavaScript;
- C#, C, and C++;
- Rust;
- PHP;
- Ruby;
- Swift.

The parser recognizes common type, function, and method declarations, records
nested qualified names, distinguishes common overloads by parameter arity, and
extracts direct parenthesized calls with line evidence. Calls resolve automatically
only when their target name is unique in the changed-file snapshot. Other calls
remain explicit unresolved edges with reduced confidence.

Each language is covered by a source fixture that verifies both symbol extraction
and a direct caller-to-callee relationship. Parser versioning prevents V1 snapshots
from being reused as V2 snapshots.

## Consequences

- Mainstream repositories receive baseline symbol and impact analysis without
  installing or running their compiler toolchains.
- The production worker remains a single Go runtime with bounded file and depth
  budgets.
- Exact language semantics are intentionally not claimed. Overload dispatch,
  reflection, generated code, macros, dynamic calls, and some cross-file imports
  may remain unresolved.
- Full compiler, language-server, or Tree-sitter adapters can be added behind the
  same snapshot contract for languages where beta evidence shows the bounded
  parser is insufficient.
