# Phase 1 sealed holdout preparation — 2026-09-27

Status: prediction-free packets sealed; independent labels still pending

## Selection and sealing procedure

The `java-holdout-source-only-v1` policy was implemented and tested before either
repository was cloned. It does not run the JavaParser adapter, the JDK compiler
oracle, or any repository build. It considers conventional `src/main/java`
files, excludes tests/generated/package metadata, requires 60–240 lines and at
least three lexical call-shaped expressions, then ranks eligible paths by a hash
of policy version, repository, commit, and path. This makes selection repeatable
without consulting semantic predictions.

`SemanticGoldPacketCli` additionally requires a clean checkout at the exact full
commit SHA, writes outside that checkout, records the complete safe repository
context digest, includes the selected source hashes and target prefixes, and
rejects prediction-bearing JSON fields. Each packet includes a deterministic
repository context archive, its SHA-256 checksum, reviewer instructions, and an
empty independent-submission template. `.git`, build outputs, IDE state,
dependency directories, and symlinks are excluded.

## Sealed repositories

### Apache Commons Lang

- Repository: `apache/commons-lang`
- License: Apache-2.0
- Commit: `29624cdb50ecd794207d561345b2fc9ca3a9d326`
- Eligible files: 71
- Selected scope: `src/main/java/org/apache/commons/lang3/text/translate/LookupTranslator.java`
- Scope size: 95 lines, 3,737 bytes, 13 lexical call-shaped expressions
- Packet ID: `13e1a789bd4d13290b984c29ce7b3159fe8ccb9fafec17323cc8f68ea4fa0738`
- Context: 718 files, 10,778,250 bytes
- Context digest: `d46c03f1c6dcd29d51ccfe671ed0962018c6afa52cc4d673b0c57af97ce43d04`
- Archive digest: `6cce56ee1e6d45498d53f902522452ecda2b5ed357492f24e649511117efe950`

### jsoup

- Repository: `jhy/jsoup`
- License: MIT
- Commit: `81718491972c21a911c5964b2cae06fdc201dfb3`
- Eligible files: 36
- Selected scope: `src/main/java/org/jsoup/nodes/DataNode.java`
- Scope size: 63 lines, 1,956 bytes, 7 lexical call-shaped expressions
- Packet ID: `d84423a06aef51465ffa2e247dedffdf153f38338895ecf10d1c5df75e1b084d`
- Context: 319 files, 3,203,673 bytes
- Context digest: `4337339bf603d96a6f8a24cf4fcf1ebdfbe96bac38c7fc8633fd7c4444963cbc`
- Archive digest: `5c006caad74f417db9d413b82c97143d1a7eadbc5e158ff5663ea4fc1ec74650`

## Leakage check and interpretation

Both packet trees passed a scan for `adapterTargets`, `oracleTargets`,
`toolAgreement`, and `reviewQueue`; both context archive checksums were verified
after writing. No CodeLens index, compiler-oracle comparison, or manual answer set
has been produced for either repository or selected file.

These packets reduce the independent work to two small source files while still
giving reviewers complete frozen context. They do not themselves satisfy B5:
two distinct calibrated Java reviewers must label each packet independently,
and a third qualified person must adjudicate any disagreement before the adapter
is run against the sealed truth sets.
