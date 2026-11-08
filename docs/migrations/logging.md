# Logging

*A magistrate's office kept two kinds of writing and never confused them. The tabulae were cut and set up where
anyone could read them in a hundred years; the commentarii were the day-book, filled in as the work went on and
useful mostly to whoever was in the room. The history table is the first of those. Nothing on this page is —
these lines are written for somebody watching a deployment, and are worth what that person reads out of them.*

`migrations` logs through [SLF4J](https://www.slf4j.org/), like the driver beneath it, and is configured the
same way: in your logging config, not on `MigratorConfig`. A run that is silent at every level is usually
missing a backend — see [You supply the backend](../driver/logging.md#you-supply-the-backend).

## The log is not the record

No line written here is ever read back. What a run leaves behind is the
[history table](history-and-validation.md#the-table), and what it returns is a `MigrationReport`, which holds
the history rows as the database answered them: `installedBy`, `installedOn` and `executionTimeMs` per
migration. The summary line has only the count and the total, so logging the report yourself — [as the
Quickstart does](quickstart.md#run-it-at-startup) — is not a duplicate of it:

```kotlin
val report = OctaviusMigrator(dataSource).migrate()
logger.info { "Octavius: $report" }
// Octavius: MigrationReport(2 applied in 412ms: 2 add nomen index, 3 backfill provinces)
```

## Logger names

Every name is the fully-qualified class it comes from.

| Logger                                                                | What it carries                                                                     |
|:----------------------------------------------------------------------|:------------------------------------------------------------------------------------|
| `io.github.octaviusframework.migrations.OctaviusMigrator`             | The run itself: adoption, nothing to do, each migration as it starts, the summary   |
| `io.github.octaviusframework.migrations.discovery.MigrationDiscovery` | What the scan found, and where it looked                                            |
| `io.github.octaviusframework.migrations.history.MigrationLock`        | The wait for the migration lock, and a lock or `lock_timeout` that was not put back |
| `io.github.octaviusframework.migrations.execution.MigrationRunner`    | One line only: a history row that could not be closed off                           |

All four sit under `io.github.octaviusframework.migrations`, so one entry configures the lot, and none under
the driver's name, so turning either down leaves the other as it was.

## What each level says

A run happens once, at startup, so the split is by how much of it you want narrated.

| Level   | How much          | What you get                                                                                                   |
|:--------|:------------------|:---------------------------------------------------------------------------------------------------------------|
| `error` | Practically never | A history row that could not be closed off and stays `RUNNING`                                                 |
| `warn`  | Practically never | Nothing found to run; a lock or a `lock_timeout` that could not be restored                                    |
| `info`  | A few lines a run | The run narrated: what was found, what is being applied, what it came to                                       |
| `debug` | Two lines more    | Every migration found, by name and origin, and the wait for the lock                                           |
| `trace` | Nothing           | The migrator writes none; the statements are the driver's — [see below](#following-a-run-into-the-drivers-log) |

### `info` is the run, narrated

```
Found 3 migrations in db/migration
Applying 1 create castra (classpath:db/migration/V1__create_castra.sql)
Applying 2 add nomen index (classpath:db/migration/V2__add_nomen_index.sql)
Applying R rebuild views (classpath:db/migration/R__rebuild_views.sql)
Octavius applied 3 migrations in 412ms
```

A migration is named as the history files it — `1 create castra`, or `R rebuild views` for a repeatable
one — and followed by where it was found. The `Applying` line is written before the migration runs, so a run
that dies part-way names the migration it died in.

Two more appear only in their own circumstances:

```
No history table here yet - adopting this database at version 7
Database is up to date; nothing to apply
```

The first is written once in a database's life — see [Adopting a database that already
exists](history-and-validation.md#adopting-a-database-that-already-exists). The second is every start after
the first, and is what a run with nothing to do looks like.

### `debug` is what was found, and the wait

The first line is the whole scan rather than its count, so a migration missing from it was never seen:

```
1 create castra (classpath:db/migration/V1__create_castra.sql), 2 add nomen index (classpath:db/migration/V2__add_nomen_index.sql), R rebuild views (classpath:db/migration/R__rebuild_views.sql)
```

The second is written before the wait for the lock, so a run stuck on the lock has it as its last line — see
[Two instances starting together](history-and-validation.md#two-instances-starting-together):

```
Waiting for the migration lock (1445965678, -1505473738), up to 30s
```

## `info()` logs too

[`info()`](quickstart.md#ask-before-doing) takes no lock and writes nothing to the database, but it runs the
same discovery `migrate()` does, so **every call writes the `Found N migrations` line**, and the `debug` list
under it. Called from a health check on a schedule, that is a line per poll. The scan's logger can be turned
down on its own:

```xml
<logger name="io.github.octaviusframework.migrations.discovery.MigrationDiscovery" level="WARN"/>
```

At `WARN` it still says when it found nothing.

## The migrator does not log what it throws

[As in the driver](../driver/logging.md#the-driver-does-not-log-what-it-throws), every refusal — a checksum
that drifted, two migrations claiming one version, a history row left `INCOMPLETE`, a lock never granted — is a
`MigrationException` carrying the whole explanation, and is not logged. **If you swallow a
`MigrationException`, nothing anywhere records that the run failed.**

What is logged is what the migrator handled itself and carried on from:

| What happened                                             | Level   |
|:----------------------------------------------------------|:--------|
| Nothing was found to run                                  | `warn`  |
| A history row could not be closed off and stays `RUNNING` | `error` |
| The migration lock could not be released                  | `warn`  |
| `lock_timeout` could not be put back to what it was       | `warn`  |

The first is the one to configure for. A location with a typo in it finds nothing and refuses nothing, so this
warning is the first sign of it, and it is why `WARN` suits this module where `OFF` does not.

The `error` leaves work for a person: the migration ran, its row still says `RUNNING`, and the next run will
refuse on it. [A migration left half-applied](history-and-validation.md#a-migration-left-half-applied) is the
recovery, and this line is the only place the reason the row was never updated is written down.

## Following a run into the driver's log

A run holds **one** session from its first statement to its last, so every driver line of a run carries the
same backend process id:

```
[PID: 41288] Connected to localhost:5432/curia as 'octavius' (PostgreSQL 18.1, TLS, sslmode=verify-full)
Found 3 migrations in db/migration
Applying 1 create castra (classpath:db/migration/V1__create_castra.sql)
[PID: 41288] Auto-commit disabled; the first statement begins the transaction
[PID: 41288] Transaction begun ahead of its first statement
[PID: 41288] COMMIT sent; the next statement begins the next transaction
[PID: 41288] Auto-commit enabled; no statement had begun a transaction, so none to commit
Applying 2 add nomen index (classpath:db/migration/V2__add_nomen_index.sql)
```

Those four driver lines are the driver's ordinary [`transaction.required`](../driver/transactions.md) around
one migration. The migrator's own lines carry no prefix; the driver lines after an `Applying` line belong to
the migration it named, until the next one.

At the driver's `trace` you get the statements themselves:

* **In a transaction — the default.** The whole file is one traced statement carrying every line of it.
* **[Outside one](writing-migrations.md#the-migration-that-cannot-run-in-a-transaction).** The file is split
  and traced statement by statement, each with its duration.

The lock is the one statement in a run that can hang:

```
[PID: 41288] >
SELECT pg_advisory_lock(1445965678, -1505473738)
```

A `>` line with no `<` under it is a statement that has not come back, which for this one means another
instance is still migrating.

## What reaches the log

The migrator writes migration names, descriptions, origins and counts, and no values out of anybody's
database. A `filesystem:` origin is an absolute path on the machine that ran the migration, and it goes in at
`info`. The SQL of a migration is only in the driver's `trace`, where [What never reaches the
log](../driver/logging.md#what-never-reaches-the-log) applies unchanged.

> [!IMPORTANT]
> **A placeholder is pasted, not bound.** `${schema}` and `${app_role}` are part of the statement text before
> it is sent, so at the driver's `trace` they appear filled in, and
> [`logParameterValues`](../driver/initialization.md#network-and-limits) does not gate them. The history records
> [the file as written](writing-migrations.md#placeholders), so a traced run is the only record of what a
> migration became on one deployment.

## Configuration recipes

**Out of the box** you get the run narrated at `info`, a handful of lines once at startup.

**Down to failures only**, for an application that logs its own `MigrationReport`. A run that found no
migrations still says so:

```xml
<logger name="io.github.octaviusframework.migrations" level="WARN"/>
```

**A health check on `info()`**, without a line per poll — see [`info()` logs too](#info-logs-too):

```xml
<logger name="io.github.octaviusframework.migrations.discovery.MigrationDiscovery" level="WARN"/>
```

**Watching what a migration does** — which statement is slow, or which one a stuck run is on. This is the
driver's dial, and worth turning back down afterwards:

```xml
<logger name="io.github.octaviusframework.migrations" level="DEBUG"/>
<logger name="io.github.octaviusframework.driver.execution.QueryExecutor" level="TRACE"/>
```

## Next

- [History and Validation](history-and-validation.md) — the table these lines are about, and what stops a run
- [When a Run Is Refused](exceptions.md) — what the migrator throws instead of logging it
- [The driver's Logging](../driver/logging.md) — the statements, the timings, and the backend id under all of it
