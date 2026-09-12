# History and Validation

*The censor's roll was what the state acted on. A citizen declared himself, the declaration went on the roll,
and from then on the roll was the answer — a man whose declaration disagreed with it had that settled before
anything else about him proceeded. And when a city was taken into the census for the first time, it was written
down as it already stood; nobody rebuilt it to match an empty page.*

The migrator keeps one table. What it holds is the answer to "has this run", and everything that stops a run is
a disagreement between that table and the migrations on disk.

## The table

`public.octavius_migration_history` by default; `MigratorConfig` takes `historySchema` and `historyTable`. The
schema is created if it is not there.

| Column              | Holds                                                                         |
|:--------------------|:------------------------------------------------------------------------------|
| `id`                | Order of application, which is not the same as version order                  |
| `version`           | `NULL` for a repeatable migration                                             |
| `description`       | What the name said                                                            |
| `type`              | `SQL`, `CODE`, or `BASELINE`                                                  |
| `script`            | The file name, or the class's full name                                       |
| `checksum`          | What the file hashed to when it ran; `NULL` where there is nothing to compare |
| `state`             | `SUCCESS`, `FAILED`, or `RUNNING`                                             |
| `failed_statement`  | Which statement stopped it, on a migration that ran outside a transaction     |
| `execution_time_ms` | How long it took                                                              |
| `installed_by`      | The database user that ran it                                                 |
| `installed_on`      | When                                                                          |

Every column is plain `text`, `bigint` or `timestamptz` — no enum types are created, so the table is safe to
read and query with nothing but the values above.

**`script` is the file name, not the path it was found at**, so moving a migration between two configured
locations does not make it a new one. Where it came from is in the log and in `MigrationInfo.origin`.

## Checksums

A `.sql` migration is hashed over its content. A byte-order mark, the line endings, and whitespace at the very
end of the file are normalised away first, so the same file checked out on Windows and on Linux hashes the
same. **Everything else counts, whitespace inside the file included** — reformatting a migration that has
already run is a change to it, and the run will say so.

A migration written in Kotlin records no checksum unless it
[declares one](writing-migrations.md#the-checksum-a-class-does-not-have); validation skips what it has no
checksum for.

**Placeholders do not enter into it.** A `.sql` file is hashed as it was written, before `${name}` is filled
in — so a value that differs between two deployments is not a change to the migration, and the same file runs
in both without either database refusing the other's. See
[Placeholders](writing-migrations.md#placeholders).

## What each migration's situation is called

`info()` gives one of these per migration, and never throws:

| Status           | Means                                                             | `migrate()` |
|:-----------------|:------------------------------------------------------------------|:------------|
| `PENDING`        | Not applied yet                                                   | applies it  |
| `OUT_OF_ORDER`   | Not applied, and below a version that already is                  | refuses¹    |
| `APPLIED`        | Applied, unchanged                                                | skips it    |
| `CHANGED`        | Applied, and the file has changed since                           | refuses     |
| `MISSING`        | The database ran it and there is no file or class for it any more | refuses     |
| `INCOMPLETE`     | A previous run died part-way through it                           | refuses     |
| `BELOW_BASELINE` | At or below the version this database was adopted at              | skips it    |
| `ABOVE_TARGET`   | Above `target`                                                    | skips it    |

¹ unless `outOfOrder` is set, in which case it applies it.

**Everything that refuses does so before a single migration runs**, so a run that stops on validation has left
the database untouched.

## When a migration fails

**In a transaction — the default.** The migration's work and the row recording it are in one transaction, so a
failure takes both. The database is exactly as it was, no row is written, and the migration is `PENDING` again.
Fix the file and run again; there is nothing to repair.

**Outside one.** A failure leaves the statements before it applied, the row `FAILED`, and `failed_statement`
saying which one stopped it.

### A migration left half-applied

The next run refuses, and says where it stopped:

```
MIGRATION_EXCEPTION:HISTORY_INCOMPLETE
Details: 4 add index concurrently is recorded as FAILED at statement 2. That migration ran outside a
transaction, so part of it is applied and part is not. Look at what it did, finish or undo it by hand,
then delete its row from the history table so the run can go on.
```

That is the whole recovery: look, fix by hand, delete the row. There is no setting that carries on past it.
The exception that says so is `MigrationException(HISTORY_INCOMPLETE)` — see [When a Run Is
Refused](exceptions.md) for the rest of them.

A row still saying `RUNNING` means the process died in the middle rather than a statement failing — same
refusal, same answer.

## Adopting a database that already exists

`baselineVersion` records that this database is already at that version, and everything at or below it is
skipped rather than run:

```kotlin
MigratorConfig(
    sqlLocations = listOf("db/migration"),
    baselineVersion = "7"
)
```

It is written **once**, the first time this migrator meets a database with no history table. A database that
already has one is left alone, so the setting is safe to leave in configuration afterwards.

## `target` and `outOfOrder`

**`target`** stops the run before a higher version, for a release that ships its migrations ahead of the code
needing them:

```kotlin
MigratorConfig(sqlLocations = listOf("db/migration"), target = "12")
```

**`outOfOrder`** applies a migration whose version is below one already applied — a branch merged after a
release. Off by default: with it on, two databases can end up having run the same migrations in different
orders.

## Two instances starting together

The run holds a PostgreSQL advisory lock for its whole length, and **waits** for it rather than failing: the
instance that lost the race starts a moment later. `lockTimeout` — thirty seconds by default — bounds the wait,
and running out of it raises `LOCK_NOT_ACQUIRED`, which is the one failure here worth retrying on.

The lock key comes from the history table's name, so two applications keeping separate histories in one
database do not wait for each other.
