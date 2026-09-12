# Logging

*A magistrate's office kept two kinds of writing and never confused them. The tabulae were cut and set up where
anyone could read them in a hundred years; the commentarii were the day-book, filled in as the work went on and
useful mostly to whoever was in the room. The history table is the first of those. Nothing on this page is —
these lines are written for somebody watching a deployment, and are worth what that person reads out of them.*

`migrations` logs through [SLF4J](https://www.slf4j.org/), like the driver beneath it, and is configured the
same way: not on the migrator at all. There is no level on `MigratorConfig` and no flag that makes a run
louder, because an entry in your logging config says everything such a property would. A run that appears
silent at every level is usually a missing backend rather than a missing statement — see [You supply the
backend](../driver/logging.md#you-supply-the-backend).

## The log is not the record

What a run leaves behind is the [history table](history-and-validation.md#the-table), and no line written here
is ever read back. Delete every one of them and the next run behaves identically; keep them all and no run
consults them. That is worth settling before deciding how much of this to keep — the log is for the person
watching, and the table is for the migrator.

The return value is the other half of it, and it carries what no line here does. `MigrationReport` holds the
history rows as the database answered them: `installedBy`, `installedOn` and `executionTimeMs` per migration.
The migrator's summary line has the count and the total and stops there, so logging the report yourself — [as
the Quickstart does](quickstart.md#run-it-at-startup) — is not a duplicate of it:

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

All four sit under `io.github.octaviusframework.migrations`, so one entry configures the lot — and none of them
sit under the driver's name, so turning the driver down leaves the run narrating, and turning the run down
leaves the driver where it was.

## What each level says

A migration run is not a loop that repeats a thousand times a second, so the split here is by **how much of the
run you want narrated** rather than by how often a line appears. The whole of `info` is a handful of lines,
once, at startup.

| Level   | How much          | What you get                                                                                                   |
|:--------|:------------------|:---------------------------------------------------------------------------------------------------------------|
| `error` | Practically never | A history row that could not be closed off and stays `RUNNING`                                                 |
| `warn`  | Practically never | Nothing found to run; a lock or a `lock_timeout` that could not be restored                                    |
| `info`  | A few lines a run | The run narrated: what was found, what is being applied, what it came to                                       |
| `debug` | Two lines more    | Every migration found, by name and origin, and the wait for the lock                                           |
| `trace` | Nothing           | The migrator writes none. The statements are the driver's — [see below](#following-a-run-into-the-drivers-log) |

### `info` is the run, narrated

Everything the migrator considers worth an unprompted line is here, and a whole run reads as a paragraph:

```
Found 3 migrations in db/migration
Applying 1 create castra (classpath:db/migration/V1__create_castra.sql)
Applying 2 add nomen index (classpath:db/migration/V2__add_nomen_index.sql)
Applying R rebuild views (classpath:db/migration/R__rebuild_views.sql)
Octavius applied 3 migrations in 412ms
```

A migration is named the way the rest of the module names it — `1 create castra`, or `R rebuild views` for a
repeatable one — and followed by where it was found. The two are deliberately not the same string: the name is
what the history files it under, and the origin is the path, so a file that moved between configured locations
is still the migration it was. For a Kotlin migration both are the class's full name, a classpath resolving one
class per name.

The `Found` line echoes the locations **as you configured them** and the origins say what was resolved under
them, which is why one carries a `classpath:` prefix that the other, written without it, does not.

The `Applying` line is written **before** the migration runs rather than after it. A run that dies part-way
therefore names the migration it died in, which is the only moment that line is worth anything.

Two more appear only in their own circumstances:

```
No history table here yet - adopting this database at version 7
Database is up to date; nothing to apply
```

The first is written **once in a database's life** and never again — see [Adopting a database that already
exists](history-and-validation.md#adopting-a-database-that-already-exists). Seeing it twice for what should be
one database means two databases. The second is the ordinary outcome of every start after the first, and it is
the reason a silent run is not reassuring: this is what "there was nothing to do" is supposed to look like.

### `debug` is what was found, and the wait

Two lines, both of them spelling out something `info` summarized. The first is the scan:

```
1 create castra (classpath:db/migration/V1__create_castra.sql), 2 add nomen index (classpath:db/migration/V2__add_nomen_index.sql), R rebuild views (classpath:db/migration/R__rebuild_views.sql)
```

The whole list rather than the count, which settles the question `info` cannot: whether the file you are
looking for was found at all. A migration missing from this line was never seen, and no amount of reading the
history table afterwards says why.

The second is the lock, written before the wait rather than after it:

```
Waiting for the migration lock (1445965678, -1505473738), up to 30s
```

The pair is the advisory lock's key: the first half says Octavius, the second is derived from the history
table's name, which is what keeps two applications with separate histories out of each other's way. A run that
is stuck has this as its last line, and [Two instances starting
together](history-and-validation.md#two-instances-starting-together) is what it is stuck on.

### There is no `trace`

Nothing in `migrations` is logged at `trace`, at any point. The statements a migration runs are the driver's to
report and it already reports them — writing them here as well would print every migration twice, once without
the timing and the backend id that make the driver's version worth reading.

## `info()` logs too

[`info()`](quickstart.md#ask-before-doing) takes no lock and writes nothing to the database, but it runs the
same discovery `migrate()` does — so **every call writes the `Found N migrations` line**, and the `debug` list
under it.

That matters in the one place `info()` belongs: a health check or a readiness probe, called on a schedule
forever. A line a run writes once at startup becomes a line per poll. The scan has a logger of its own
precisely so this can be turned down without silencing the run:

```xml
<logger name="io.github.octaviusframework.migrations.discovery.MigrationDiscovery" level="WARN"/>
```

At `WARN` it still says the one thing worth being woken for — that it found nothing — and stops saying what it
found the rest of the time.

## The migrator does not log what it throws

The rule the driver states, [and for the same
reason](../driver/logging.md#the-driver-does-not-log-what-it-throws), holds here too. Every refusal — a
checksum that drifted, two migrations claiming one version, a history row left `INCOMPLETE`, a lock never
granted — is a `MigrationException` carrying the whole explanation, and logging it here as well would put the
same diagnostic in the log twice, once without the context of the startup it stopped.

So if you swallow a `MigrationException`, **nothing anywhere records that the run failed.** No level changes
that.

What is logged is the short list of things the migrator handled itself and carried on from, because those reach
nobody at all:

| What happened                                             | Level   | Why it is not thrown                                                                    |
|:----------------------------------------------------------|:--------|:----------------------------------------------------------------------------------------|
| Nothing was found to run                                  | `warn`  | An application whose migrations are all still ahead of it is a real state               |
| A history row could not be closed off and stays `RUNNING` | `error` | Something is already failing, and this would replace its reason with a network error    |
| The migration lock could not be released                  | `warn`  | It goes when the session does, and raising would bury whatever the run was failing with |
| `lock_timeout` could not be put back to what it was       | `warn`  | The same, and the session is on its way back to a pool that resets it                   |

The first is the one to configure for. A location with a typo in it finds nothing, refuses nothing, and the
first sign of it is a table that was never created — so it is a warning rather than silence, and it is why
`WARN` is a defensible level for this module where `OFF` is not.

The `error` is the one that leaves work for a person: the migration ran, its row still says `RUNNING`, and the
next run will refuse on it. [A migration left half-applied](history-and-validation.md#a-migration-left-half-applied)
is the recovery, and this line is the only place the *reason* the row was never updated is written down.

## Following a run into the driver's log

A run borrows **one** session and holds it from its first statement to its last. That has a pleasant
consequence for reading: every driver line belonging to a migration run carries the same backend process id, so
one `[PID: …]` is the whole run with nothing else in the application mixed into it.

```
[PID: 41288] Connected to localhost:5432/curia as 'octavius' (PostgreSQL 18.1, TLS, sslmode=verify-full)
Found 3 migrations in db/migration
Applying 1 create castra (classpath:db/migration/V1__create_castra.sql)
[PID: 41288] Auto-commit disabled; transaction started
[PID: 41288] Transaction committed; new transaction started
[PID: 41288] Auto-commit enabled; open transaction committed
Applying 2 add nomen index (classpath:db/migration/V2__add_nomen_index.sql)
```

Three driver lines per migration, because a transaction here is a scope rather than a statement: the session
leaves auto-commit, commits, and is put back. That is the driver's ordinary
[`transaction.required`](../driver/transactions.md) sequence and not something the migrator does differently.

The migrator's own lines carry no such prefix and do not need one — there is only ever one session in a run, so
there is nothing to tell apart. Reading the two together is positional: the driver's lines following an
`Applying` line belong to the migration it named, until the next one.

At the driver's `trace` you get the statements themselves, and the two ways a migration can run look different
there on purpose:

* **In a transaction — the default.** The whole file goes to the server in one message, so it is *one* traced
  statement carrying every line of it.
* **[Outside one](writing-migrations.md#the-migration-that-cannot-run-in-a-transaction).** The file is split
  and sent statement by statement, so it is one traced line each, with a duration each.

That asymmetry is the same one behind `failed_statement`: the path without a transaction knows which statement
it stopped on because it sent them one at a time, and the path with one does not, because it did not.

The lock is visible there too, and is the one statement in a run that can hang:

```
[PID: 41288] > (0 params)
SELECT pg_advisory_lock(1445965678, -1505473738)
```

A `>` line with no `<` under it is a statement that has not come back, which for this one means another
instance is still migrating — the driver's [trace pairing](../driver/logging.md#trace-is-every-statement) doing
exactly what it is for.

## What reaches the log

The migrator writes migration names, descriptions, origins and counts, and no values out of anybody's database.
It never writes the SQL of a migration; that is the driver's `trace`, where everything [What never reaches the
log](../driver/logging.md#what-never-reaches-the-log) says applies unchanged.

One thing is particular to migrations, and is worth knowing before turning the driver up during a deployment:

> [!IMPORTANT]
> **A placeholder is pasted, not bound.** `${schema}` and `${app_role}` are part of the statement text before
> it is sent, so at the driver's `trace` they appear filled in, as ordinary SQL. There is no parameter, so
> [`logParameterValues`](../driver/initialization.md#network-and-limits) has nothing to do with it and does not
> gate it.

Which cuts the other way as well, and that half is the more useful one: the [history records the file as
written, not as it expanded](writing-migrations.md#placeholders). A traced run is therefore **the only record
anywhere of what a migration actually became on one deployment** — worth knowing both when you need that and
when you would rather it were not written down.

A `filesystem:` origin is an absolute path on the machine that ran the migration, and it goes in at `info`.

## Configuration recipes

**Out of the box**, with no configuration at all, you get the run narrated at `info` — a handful of lines once
at startup, which is what this module is tuned for. Most applications want nothing else.

**Down to failures only**, for one that logs its own `MigrationReport` and wants no second voice:

```xml
<logger name="io.github.octaviusframework.migrations" level="WARN"/>
```

That is not silence, and should not be: a run that found no migrations still says so.

**A health check on `info()`**, without a line per poll — see [`info()` logs too](#info-logs-too):

```xml
<logger name="io.github.octaviusframework.migrations.discovery.MigrationDiscovery" level="WARN"/>
```

**Watching what a migration actually does** — which statement is slow, or which one a stuck run is on. This is
the driver's dial rather than the migrator's, and it is worth turning back down afterwards:

```xml
<logger name="io.github.octaviusframework.migrations" level="DEBUG"/>
<logger name="io.github.octaviusframework.driver.execution.QueryExecutor" level="TRACE"/>
```

## Next

- [History and Validation](history-and-validation.md) — the table these lines are about, and what stops a run
- [When a Run Is Refused](exceptions.md) — what the migrator throws instead of logging it
- [The driver's Logging](../driver/logging.md) — the statements, the timings, and the backend id under all of it
