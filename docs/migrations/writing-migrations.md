# Writing Migrations

*A citizen carried three names, and they were not decoration. The last of them said which family he belonged
to, and a freedman took his patron's, so that where he had come from travelled with him whether or not anyone
asked. The name was the record.*

A migration's name is its identity. Its version, its description and whether it runs once or every time are all
read out of the name, and out of nothing else.

## The naming rule

```
V<version>__<description>.sql        a versioned migration, run once
R__<description>.sql                 a repeatable migration, run again whenever it changes
```

| Kind             | File                    | Class               | Version    | Description   |
|:-----------------|:------------------------|:--------------------|:-----------|:--------------|
| versioned        | `V2__add_indexes.sql`   | `V2__Add_indexes`   | `2`        | add indexes   |
| version in parts | `V2.1__add_indexes.sql` | `V2_1__Add_indexes` | `2.1`      | add indexes   |
| timestamp        | `V20260827__seed.sql`   | `V20260827__Seed`   | `20260827` | seed          |
| repeatable       | `R__rebuild_views.sql`  | `R__Rebuild_views`  | —          | rebuild views |

`V` and `R` are capital and are checked exactly. Two underscores separate the version from the description;
single underscores in the description become spaces.

**In a version, `.` and `_` mean the same thing**, since a class name cannot hold a `.`. `V2_1__Add_indexes`
and `V2.1__add_indexes.sql` are both version 2.1.

Versions are compared as numbers, part by part: `1.9` comes before `1.10`. **Missing parts are zero**, so `1`,
`1.0` and `1.0.0` are one version, and two migrations claiming them are refused as a duplicate.

## In SQL

A `.sql` file is sent as it is written. It may hold several statements separated by `;`, and they run inside
one transaction, so the file is all-or-nothing:

```sql
CREATE TABLE castra (id serial PRIMARY KEY, nomen text NOT NULL);
CREATE INDEX idx_castra_nomen ON castra (nomen);
INSERT INTO castra (nomen) VALUES ('Vindobona');
```

A statement that returns rows is allowed — a script from `pg_dump` is full of them — and its rows are dropped.

**A migration may not control its own transaction.** A `BEGIN`, `COMMIT`, `END`, `ROLLBACK`, `ABORT` or
`START TRANSACTION` at the top level of a file is refused before anything runs, because the run's transaction
is what makes the file all-or-nothing and ending it early would leave a half-applied file recorded as applied
whole. `BEGIN` and `END` **inside** a function body — a PL/pgSQL one, or a standard-SQL `BEGIN ATOMIC` one — are
the block, not the transaction, and are fine.

## The migration that cannot run in a transaction

`CREATE INDEX CONCURRENTLY`, `VACUUM`, `ALTER SYSTEM` and `CREATE DATABASE` are refused by PostgreSQL inside a
transaction block. A file whose header asks for it runs statement by statement instead:

```sql
-- octavius:no-transaction
CREATE INDEX CONCURRENTLY idx_castra_nomen ON castra (nomen);
```

The directive goes in the **header** — above the first line that is neither blank nor a comment; below there it
is refused. Spacing and case are free (`--octavius:no-transaction` and `-- Octavius: No-Transaction` both
work), the name is not: a directive nobody knows is refused rather than ignored, so a typo cannot pass quietly.

> [!IMPORTANT]
> What it costs: a failure halfway leaves the statements before it applied and committed. The migrator records
> which statement stopped it and **the next run refuses to go on** until somebody has looked — see
> [History and Validation](history-and-validation.md#a-migration-left-half-applied). Use it for the statements
> that need it and not as a habit.

## In Kotlin

Implement `OctaviusMigration`, and let the class name say which migration it is:

```kotlin
class V3__Backfill_provinces : OctaviusMigration {
    override fun migrate(session: OctaviusSessionOperations) {
        session.createNativeQuery("UPDATE provinces SET tribute = 0 WHERE tribute IS NULL").update()
    }
}
```

It needs a constructor taking no arguments, and it is built once, immediately before it runs — it is not a
bean, and nothing of yours runs at startup for a migration that was applied months ago. A class with no such
constructor is refused by name during the scan rather than when its turn comes.

Inside `migrate` the session is the driver's own, and a transaction is already open unless the migration said
otherwise:

```kotlin
class V4__Reindex : OctaviusMigration {
    override val transactional: Boolean get() = false

    override fun migrate(session: OctaviusSessionOperations) {
        session.createNativeQuery("REINDEX INDEX CONCURRENTLY idx_castra_nomen").execute()
    }
}
```

### The checksum a class does not have

`OctaviusMigration.checksum` is `null` by default, and validation skips what it has no checksum for — a class
has no content to hash the way a file does. Declare one if you want the check:

```kotlin
override val checksum: Long get() = 3
```

On a **repeatable** migration written in Kotlin, `null` means it runs on every run, there being no way to tell
that it changed. A repeatable migration has to be idempotent regardless.

## Repeatable migrations

`R__` has no version and runs again whenever its checksum changes, which suits a definition rather than a
change — views, functions, grants:

```sql
-- R__rebuild_views.sql
CREATE OR REPLACE VIEW castra_nomina AS SELECT nomen FROM castra;
```

They run **after** every versioned migration, and among themselves in description order — so a numeric prefix
sets the order where one matters: `R__01_base_views` before `R__02_derived_views`.

The history keeps one row per repeatable migration, updated in place rather than added to.

## Placeholders

`MigratorConfig.placeholders` is a map, and `${name}` in a `.sql` file becomes what `name` maps to:

```kotlin
MigratorConfig(
    sqlLocations = listOf("db/migration"),
    placeholders = mapOf("schema" to "roma", "app_role" to "legio")
)
```

```sql
-- V5__grant.sql
GRANT USAGE ON SCHEMA ${schema} TO ${app_role};
```

**It is a paste and nothing more.** The value goes in as the text it is — no quoting, no escaping, no idea of
where in the statement it landed. That is what lets it name a schema, a role or a tablespace, which a
parameter never can, and it is why the values belong to the deployment: one fed from anything a user typed is
an injection with the migrator holding the door.

**With the map empty — the default — nothing is scanned for at all**, so a migration that holds a `${` of its
own costs nothing and needs no escaping. Once there is one entry, every `${name}` in every file has to have a
value: one that does not is refused by file and line before anything runs. Left standing it would reach the
server, and inside a string literal — `INSERT INTO templates VALUES ('${name}')` — it would be stored exactly
as written, with nothing ever saying so. For the file that means to hold that text, `\${name}` is the text and
not a placeholder.

Substitution happens **once**, and **before** the header directives are read and the transaction-control check
runs — so what those see is what the server will see, and a value carrying a `COMMIT` is refused like any
other. A value that itself contains `${...}` is left standing: one file, one round of substitution, and no
value reaches back for another.

**`.sql` only.** A migration written in Kotlin already has a language; if it needs a value from outside, it
reads it the way the rest of your application does.

> [!IMPORTANT]
> **The checksum is taken over the file as written, before substitution.** Changing a value is therefore not a
> change to the migration, and a database that ran it under one value does not refuse it under another — which
> is the point, the value being the thing that differs between environments. The cost is the other side of the
> same coin: the history records that the file ran, not what it expanded to. Keep `${}` for what genuinely
> differs per environment, because a migration that reads the same everywhere is one less thing to reconstruct
> from a deployment later.

## What is deliberately absent

**Undo.** Correct a migration with one that comes after it.

**Configurable naming.** `V`, `R`, `__` and the `.sql` suffix are all fixed. PostgreSQL does not care what a
file is called, so every spelling made configurable would be API to keep working for nothing.
