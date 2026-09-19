# ADR 0002: PR-delta code graph

Status: Accepted for beta

## Decision

Index base and head versions of changed TypeScript/JavaScript files, reuse commit-and-scope-addressed snapshots, and traverse incoming relationships to a maximum depth of two.

## Consequences

The beta is fast and bounded but cannot see callers that exist only in unchanged files. Every impact result exposes this coverage limitation. Full-repository indexing remains a post-beta option.
