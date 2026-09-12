# Octavius Migrations Documentation

*Rome dated itself by its consuls. The Fasti were the list of them, year by year, kept as one continuous roll —
and the list was appended to, never rewritten. A year already on it was a year that had happened, whatever
anyone later wished had been done differently.*

`migrations` brings a database up to date with the migrations in your application, and keeps the record of what
it did in a table it owns. Versioned migrations run once in order; repeatable ones run again whenever they
change. There is no undo: a migration that turns out to be wrong is corrected by a migration that comes after
it.

It sits on the [driver](../driver/README.md) and nothing else. The [client](../client/README.md) is neither
required nor consulted.

| Document                                            | Description                                                                                 |
|:----------------------------------------------------|:--------------------------------------------------------------------------------------------|
| [Quickstart](quickstart.md)                         | One call at startup, where migrations live, what the first run does, and every config option |
| [Writing Migrations](writing-migrations.md)         | Naming, `.sql` and Kotlin, placeholders, and the migration that cannot run in a transaction |
| [History and Validation](history-and-validation.md) | The history table, checksums, what stops a run and why, baseline, `target`, out of order    |
| [Logging](logging.md)                               | What a run narrates at each level, and where the statements themselves are                  |
| [When a Run Is Refused](exceptions.md)              | `MigrationException`, its seven reasons, and the one worth retrying                         |

## What a run does

`migrate()` borrows one session and holds it throughout:

1. **Finds the migrations** — the `.sql` files in the configured locations and the classes in the configured
   packages. This happens before the lock, and touches nothing but the classpath and the disk.
2. **Takes the migration lock**, waiting if another instance is already running.
3. **Creates the history table** if this database has never seen one.
4. **Reads what has run** and puts it beside what was found.
5. **Refuses, or applies.** Everything that can stop a run stops it here, before a single migration runs: a
   name that is not a name, two migrations claiming one version, a file that has changed since it ran. What is
   left is applied in version order.
6. **Releases the lock** and reports what it applied.

Two instances of an application starting together therefore produce one migration run and one short wait,
rather than a race.

## API Reference

For signatures, properties and enum values, see the generated KDoc:

- [API Reference](https://octavius-framework.github.io/octavius-postgresql/)
