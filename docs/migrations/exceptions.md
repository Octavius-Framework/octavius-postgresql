# When a Run Is Refused

*An augur who saw an unfavourable sign said so out loud and the day's business stopped where it stood — before
the vote, not halfway through it. What he announced was the sign itself and where he had seen it. Whether the
proposal was a good one he had no opinion about, that being somebody else's to hold.*

Everything that stops a migration run raises **one** exception type, `MigrationException`, carrying a reason
out of seven. It extends the driver's [`OctaviusException`](../driver/exceptions.md#the-base-class), so it
prints, logs and nests like every other exception in Octavius.

## Seven reasons, in the order a run can hit them

[A run has stages](README.md#what-a-run-does), and the reasons fall into them. Nothing below the line a run
stopped at has happened yet:

| Stage                        | Reason                | What it means                                                                                                                  |
|:-----------------------------|:----------------------|:-------------------------------------------------------------------------------------------------------------------------------|
| Before anything — the config | `CONFIGURATION`       | Nowhere to look, a `filesystem:` path that is not one, a version that is not one, a session already in a transaction           |
| Finding the migrations       | `INVALID_MIGRATION`   | One migration cannot be used: its name, its own `COMMIT`, an unknown directive, an unfilled placeholder, a missing constructor |
| Finding the migrations       | `DUPLICATE_MIGRATION` | Two claim one identity: the same version, or the same script found twice                                                       |
| Taking the lock              | `LOCK_NOT_ACQUIRED`   | The wait ran out — another migrator is still going                                                                             |
| Reading the history          | `VALIDATION_FAILED`   | Disk and history disagree: one changed, one is gone, one arrived below what is applied                                         |
| Reading the history          | `HISTORY_INCOMPLETE`  | A previous run died inside a migration that had no transaction                                                                 |
| Applying                     | `MIGRATION_FAILED`    | A migration ran and failed                                                                                                     |

The first three are decided **before the lock is taken and before the database is read** — they are about the
classpath and the disk, and a run that stops there has touched nothing. That is deliberate and it is why
discovery happens first: past that point, a migration that fails has migrations committed in front of it.

`VALIDATION_FAILED` and `HISTORY_INCOMPLETE` are the subject of
[History and Validation](history-and-validation.md#what-each-migrations-situation-is-called), which says what
each disagreement is called and what to do about it. Only `MIGRATION_FAILED` means a migration actually ran.

## What is not on it

**No `sqlState`.** Every reason above is decided before the database is touched, or after a failure has already
been rolled back, so there is no server error for one to come from. A `MigrationException` with a
[SQLSTATE](../driver/exceptions.md#sqlstate-routing) would be claiming the server said something it did not.

**No query context.** The driver attaches the SQL and the parameters to exceptions
[it raises itself](../driver/exceptions.md#query-context). These are not those: what is wrong is a file, a
class or a version, and `details` names it.

**Not everything a run throws is one of these.** The migrator raises a `MigrationException` for what it has an
opinion about. Where it has none — the history table itself failing to be read, a connection dying mid-run —
the driver's own exception comes through untouched, and it is the right one: it carries the SQLSTATE and the
statement, which is more than a wrapper could add.

## Reading one

`MIGRATION_FAILED` is the case where two exceptions matter, and only reading both gets you anywhere: the
`MigrationException` says *which migration*, its `cause` says *what the database thought of it*.

```text
--------------------------------------------------------------------------------
MESSAGE: MIGRATION_EXCEPTION:MIGRATION_FAILED
EXCEPTION DETAILS:
Reason: A migration failed. See the cause for what the database or the migration itself said.
Details: 7 add tribute column (classpath:db/migration/V7__add_tribute_column.sql) failed.
--------------------------------------------------------------------------------
CAUSE:
--------------------------------------------------------------------------------
MESSAGE: STATEMENT_EXCEPTION:UNDEFINED_COLUMN
SQLSTATE: 42703
…
```

Which is the ordinary Octavius rendering, so the ordinary Octavius advice holds:
[**log the exception, not `e.message`**](../driver/exceptions.md#message-format-and-logging). The `message` is
the identifier `MIGRATION_EXCEPTION:MIGRATION_FAILED` and nothing else; the block above is `toString()`, and
the cause only appears there.

A migration that ran **outside a transaction** says one thing more, because there the failure left work behind:

```text
Details: 4 add index concurrently (classpath:db/migration/V4__add_index_concurrently.sql) failed and was not
in a transaction, so the statements before this one are applied and stay applied. Statement 2 of 5, starting
at line 11.
```

The line number counts into the text that was **sent**, which is the file after
[placeholders were substituted](writing-migrations.md#placeholders) — the same line as in the file itself,
unless one of those values carried a newline of its own.

## The one worth retrying

`LOCK_NOT_ACQUIRED`, and only that one.

```kotlin
val report = try {
    OctaviusMigrator(dataSource, config).migrate()
} catch (e: MigrationException) {
    if (e.reason != MigrationExceptionReason.LOCK_NOT_ACQUIRED) throw e
    OctaviusMigrator(dataSource, config).migrate()   // the other instance has had its turn
}
```

It means another migrator held the lock for longer than `lockTimeout` — an instance that is still going, not
one that went wrong — so the same run a moment later is a different run. Every other reason describes something
a retry cannot change: a file does not stop being a duplicate, and a checksum does not drift back.

The alternative to catching it is a longer `lockTimeout`, which is the same wait with fewer moving parts. Reach
for the retry when the wait has an upper bound you would rather set yourself.

## Catching it

`reason` is an enum and is meant to be keyed on — it is what a log line or a metric should carry. `details`
is the part for a person, and names the file, class or version at fault.

```kotlin
try {
    OctaviusMigrator(dataSource, config).migrate()
} catch (e: MigrationException) {
    logger.error(e) { "Migrations did not run: ${e.reason}" }
    throw IllegalStateException("refusing to start against an unmigrated schema", e)
}
```

Catching it at all is a decision about startup rather than about migrations: a run that refused has left the
database exactly as it was, so the only question is whether this application is willing to serve traffic
against a schema it does not agree with. For almost every application the answer is no, and letting the
exception end the startup is the whole of the handling.

## Next

- [History and Validation](history-and-validation.md) — what `VALIDATION_FAILED` and `HISTORY_INCOMPLETE` are about
- [Logging](logging.md) — what a run says on its way to one of these, and what it never says
- [The driver's Error Handling](../driver/exceptions.md) — the base class, the rendering, and the cause underneath
