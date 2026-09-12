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

## Where next

- [Writing Migrations](writing-migrations.md) — the naming rules, and what to do about `CREATE INDEX CONCURRENTLY`
- [History and Validation](history-and-validation.md) — what stops a run, and how to adopt a database that already exists
- [Logging](logging.md) — what a run narrates at each level, and why a run that says nothing is not reassuring
