# Octavius — Migrations

[![Maven Central](https://img.shields.io/maven-central/v/io.github.octavius-framework/migrations)](https://central.sonatype.com/artifact/io.github.octavius-framework/migrations)

Brings a database up to date with the migrations in your application, and keeps the record of what it did.

> Part of [Octavius for PostgreSQL](../README.md), released with it and on the same version. Needs the
> [driver](../driver/README.md) and nothing else — the client is neither required nor consulted.

```kotlin
val report = OctaviusMigrator(dataSource).migrate()
logger.info { "Octavius: $report" }
```

Versioned migrations run once, in version order. Repeatable ones run again whenever they change. They are
`.sql` files, Kotlin classes, or both.

```
src/main/resources/db/migration/
├── V1__create_castra.sql
├── V2.1__add_nomen_index.sql
└── R__rebuild_views.sql
```

## What it does

**A migration runs in a transaction, and its history row goes in the same one.** A failure takes both: the
database is as it was, and the migration is pending again. Nothing to repair, and no `repair` command.

**A file whose header says `-- octavius:no-transaction` runs statement by statement** — what
`CREATE INDEX CONCURRENTLY`, `VACUUM` and `ALTER SYSTEM` need. A failure there records which statement stopped
it, and the next run refuses until somebody has looked.

**Checksums ignore what a checkout changes** — byte-order mark, line endings, trailing whitespace — and notice
everything else. A migration written in Kotlin has none unless it declares one.

**`${name}` in a `.sql` file is filled in from `placeholders`** — a schema, a role, a tablespace, whatever
genuinely differs between environments. Off until the map has an entry, and the checksum is of the file as
written, so changing a value is not a change to the migration.

**Two instances starting together wait rather than race**, on an advisory lock keyed to the history table, so
two applications sharing a database do not wait for each other.

## What it deliberately does not do

**Undo.** Correct a migration with one that comes after it.

**Configurable naming.** `V`, `R`, `__` and the `.sql` suffix are fixed.

**Repair.** A transactional failure leaves nothing to repair; the other kind is fixed by hand, because only a
person can say what state the database is in.

## Documentation

- [Quickstart](../docs/migrations/quickstart.md) — one call at startup, and where migrations live
- [Writing Migrations](../docs/migrations/writing-migrations.md) — naming, `.sql` and Kotlin, placeholders,
  and the no-transaction directive
- [History and Validation](../docs/migrations/history-and-validation.md) — the table, checksums, what stops a
  run, baseline

## License

Licensed under the [Apache License, Version 2.0](../LICENSE).
