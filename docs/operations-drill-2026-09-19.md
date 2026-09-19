# Operations drill — 2026-09-19

Scope: retention, repository deletion, PostgreSQL backup, and PostgreSQL restore.

The drill ran against an isolated PostgreSQL 17 container and disposable
repository identifiers. It did not read, modify, or copy the live beta data.

## Migration baseline

- Applied migrations: 7/7
- Source database: `codelens_ops`
- Restore target: a newly created `codelens_restore` database

## Retention

The fixture contained one terminal review and its model telemetry older than
120 days, plus one snapshot and webhook delivery older than 45 days. A second
repository contained current data to verify the retention boundary.

Result:

- review runs deleted: 1
- snapshots deleted: 1
- webhook deliveries deleted: 1
- LLM telemetry rows deleted: 1
- current repository data retained: yes

## Backup and restore

A custom-format `pg_dump` backup was created after retention and restored into
an empty database.

- backup size: 38,696 bytes
- backup SHA-256: `545a3aa3adc532c43e280e68857619df80a837bd84b56c7eb6f1c658068e5b8f`
- restored migrations: 7/7
- source and restore row counts matched for review runs, snapshots, LLM calls,
  project rules, and webhook deliveries
- the restored review-run primary key matched the source

## Repository deletion

The deletion command ran against disposable repository ID `900002` in the
restored database with the required literal confirmation.

Result:

- review runs deleted: 1
- snapshots deleted: 1
- project rules deleted: 1
- LLM telemetry rows deleted: 1
- repository-scoped rows remaining: 0
- anonymous audit receipts created: 1
- raw repository ID retained by the audit row: no

The disposable databases, backup, and container were removed after the checks.
