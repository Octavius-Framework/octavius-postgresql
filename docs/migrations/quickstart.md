# Quickstart

*Each evening a tribune handed out the tessera — a wooden tablet with the night's watchword on it — and it went
down the maniples and back up again. Short, fixed, and done before anything else the camp did after dark: until
it had come back round, the camp was not yet set for the night.*

> **Note:** `migrations` is a layer over the [driver](../driver/README.md). If you have not connected yet, start
> with the driver's [Quickstart](../driver/quickstart.md) — everything below assumes a working `DataSource`.

## Add it

```kotlin
dependencies {
    implementation("io.github.octavius-framework:migrations:1.0.0")
}
```

It brings the driver with it, and [ClassGraph](https://github.com/classgraph/classgraph) for walking the
classpath. It does not bring the client.

## Put a migration somewhere

By default that is `db/migration` on the classpath — `src/main/resources/db/migration`:

```
src/main/resources/db/migration/
├── V1__create_castra.sql
└── V2__add_nomen_index.sql
```

```sql
-- V1__create_castra.sql
CREATE TABLE castra (
    id    serial PRIMARY KEY,
    nomen text NOT NULL
);
```

## Run it at startup

```kotlin
val report = OctaviusMigrator(dataSource).migrate()
logger.info { "Octavius: $report" }
```

That is the whole entry point. It borrows one session, takes the migration lock, creates its history table if
this database has never seen it, applies what is missing, and gives the session back.

The report is worth logging either way: `MigrationReport(nothing to do, 3ms)` is an answer too.

## Point it somewhere else

```kotlin
val report = OctaviusMigrator(
    dataSource,
    MigratorConfig(
        sqlLocations = listOf("db/migration", "filesystem:./ops/sql"),
        codePackages = listOf("com.roma.migrations")
    )
).migrate()
```

`sqlLocations` takes classpath paths — the `classpath:` prefix is optional, being the usual case — and
directories under `filesystem:`. Subdirectories are searched too, and anything that is not a `.sql` file is
walked past, so a `README.md` next to the migrations is not a problem.

**A relative `filesystem:` path resolves against the working directory of the process**, which is not always
the directory you are looking at. A location that is not there is refused by name rather than quietly found
empty.

`codePackages` is for migrations written in Kotlin — see [Writing Migrations](writing-migrations.md#in-kotlin).
Either list may be empty; both empty is refused, there being nothing to do.

## Ask before doing

```kotlin
val info = OctaviusMigrator(dataSource, config).info()
info.forEach { logger.info { "$it" } }
// 1 create castra [APPLIED]
// 2 add nomen index [PENDING]
```

`info()` takes no lock and creates nothing — a database with no history table answers with every migration
`PENDING` rather than getting one. It refuses nothing either: a checksum that has drifted comes back as
`CHANGED` to be looked at, where `migrate()` would stop. It is what to put behind a health check or a
`--dry-run` flag.

## On a session you already hold

```kotlin
dataSource.getOctaviusSession().use { session ->
    OctaviusMigrator.onSession(session, config).migrate()
}
```

For tests, and for code that has a session open already. **The session has to be in auto-commit** — the run
opens and commits a transaction per migration, so a session already inside one is refused.

## What the first run leaves behind

One table, `public.octavius_migration_history`, with a row per migration it applied. Both the schema and the
table name are configurable, and the schema is created if it is missing.

Nothing else. No types, no functions, no triggers.

## A migration that creates a type

The driver reads a database's type catalogue **once**, when the first connection to it is opened, and DDL that
runs afterwards is invisible to it until it is told. For most applications that is a footnote. Here it is not:
running DDL after the pool is up is the whole job. `CREATE TYPE ... AS ENUM`, `CREATE TYPE ... AS (...)` and —
because a table has a row type — a plain `CREATE TABLE` all leave the catalogue a version behind.

So a run is followed by one call:

```kotlin
val report = OctaviusMigrator(dataSource).migrate()
dataSource.getOctaviusSession().use { it.reloadTypes() }
```

[`reloadTypes()`](../driver/type-system.md#keeping-the-catalog-fresh--reloadtypes) refreshes the registry for
the **whole database** rather than for the session that called it, so one call covers every pool in the
process. It also wants to be made while nothing else is querying — which is exactly where this one is, at
startup, before the application has served anything.

Without it, the first query against a type the migration just created fails to map, and the
`TypeException(TYPE_NOT_FOUND)` it raises
[says nothing about migrations](../driver/exceptions.md#13-typeexception) — it is a question about a registry,
asked hours after the answer was decided.

### Where it goes in a startup sequence

An application that also registers annotated types has three things to do and one order that makes them read
correctly:

```kotlin
OctaviusMigrator(dataSource).migrate()                   // 1. the schema, types included
dataSource.getOctaviusSession().use { it.reloadTypes() } // 2. the catalogue catches up
db.registerAnnotatedTypes("com.roma.domain")             // 3. mappings, against types that now exist
```

Scanning first also works — [registration is not checked against the
database](../client/scanner.md#what-a-scan-reports), and converters survive a reload untouched — but every
type the migration was about to create comes back in `ScanReport.unresolved`, and a warning that is expected
every single startup is one nobody reads. Migrating first is what makes that list mean something.

## In a Spring application

One bean, and whatever needs the schema depends on it:

```kotlin
@Bean
fun migrations(dataSource: DataSource): MigrationReport =
    OctaviusMigrator(dataSource).migrate()
        .also { dataSource.getOctaviusSession().use { session -> session.reloadTypes() } }
```

Returning the report rather than `Unit` is what gives the ordering a handle: a bean that must not start against
a schema it is older than takes `MigrationReport` in its constructor, and Spring works the rest out. An
`ApplicationRunner` or an `ApplicationReadyEvent` listener is too late for that — the context is already up by
then, and anything that queried on the way there queried the old schema.

There is no starter and no auto-configuration to add; the driver's own is
[Spring Integration](../driver/spring-integration.md), and this sits beside it rather than inside it.

## Configuration reference

`MigratorConfig` has a default for every field, so a config states only what differs from it. `baselineVersion`
and `target` are parsed when the config is built rather than when a run reaches them, so a `"1.x"` is refused
by whoever wrote it instead of by the first `migrate()` that gets there.

| Option            | Default                        | Meaning                                                                                                                                                                                                                              |
|:------------------|:-------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sqlLocations`    | `["db/migration"]`             | Where the `.sql` files are — a classpath path, `classpath:` optional because it is the usual case, or a directory under `filesystem:`. Subdirectories are searched, and anything that is not a `.sql` file is walked past.            |
| `codePackages`    | empty                          | Packages holding [migrations written in Kotlin](writing-migrations.md#in-kotlin), subpackages included.                                                                                                                              |
| `placeholders`    | empty                          | Values pasted into `.sql` before it runs — see [Placeholders](writing-migrations.md#placeholders). Empty means nothing is scanned for, so a migration carrying a `${` of its own is left alone.                                       |
| `classLoader`     | `null` — the scan's own        | Where to look for classes and classpath resources, for an application whose own are not on the loader the scan would otherwise find: an OSGi container, a plugin host.                                                               |
| `historySchema`   | `public`                       | Schema the history table lives in, created if it is not there.                                                                                                                                                                      |
| `historyTable`    | `octavius_migration_history`   | What that table is called. Worth changing only where two applications keep separate histories in one database — and then they take separate locks too, the key being a CRC32 of `schema.table`.                                      |
| `lockTimeout`     | 30 seconds                     | How long to wait for the migration lock. Applied as the session's `lock_timeout` and put back afterwards; running out is [`LOCK_NOT_ACQUIRED`](exceptions.md).                                                                       |
| `outOfOrder`      | `false`                        | Whether to apply a migration whose version is below one already applied — see [`target` and `outOfOrder`](history-and-validation.md#target-and-outoforder).                                                                          |
| `baselineVersion` | none                           | The version an existing database is taken to already be at, written once when this migrator first meets a database with no history table — see [Adopting a database that already exists](history-and-validation.md#adopting-a-database-that-already-exists). |
| `target`          | none — all of them             | The highest version to apply, for a release that ships the migrations before the code that needs them.                                                                                                                               |

`sqlLocations` and `codePackages` may each be empty, for a project whose migrations are all of the other kind.
Both empty is refused with `MigrationException(CONFIGURATION)`: there is nothing to do.

## Where next

- [Writing Migrations](writing-migrations.md) — the naming rules, and what to do about `CREATE INDEX CONCURRENTLY`
- [History and Validation](history-and-validation.md) — what stops a run, and how to adopt a database that already exists
- [Logging](logging.md) — what a run narrates at each level, and why a run that says nothing is not reassuring
- [When a Run Is Refused](exceptions.md) — the seven reasons, and which one a retry can do anything about
