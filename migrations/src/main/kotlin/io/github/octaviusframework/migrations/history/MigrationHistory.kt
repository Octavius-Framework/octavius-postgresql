package io.github.octaviusframework.migrations.history

import io.github.octaviusframework.driver.identifier.quoteAsPgIdentifier
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.migrations.MigrationVersion
import io.github.octaviusframework.migrations.discovery.DiscoveredMigration
import kotlin.time.Instant

/**
 * The table recording what has run, and the only place this module writes anything of its own.
 */
internal class MigrationHistory(private val schema: String, private val table: String) {

    private val qualifiedName = "${schema.quoteAsPgIdentifier()}.${table.quoteAsPgIdentifier()}"

    /**
     * Creates the table if it is not there, and brings an older one up to date.
     *
     * The statements below are the whole upgrade path, which holds as long as three rules do: **a new column
     * is nullable or has a default**, **an existing column never changes meaning**, and **the set of `state`
     * values only ever grows**. Break one of those and an older migrator reading this table starts guessing.
     *
     * The schema is created too, for the reason given on `MigratorConfig.historySchema`.
     */
    fun bootstrap(session: OctaviusSessionOperations) {
        session.createNativeQuery(
            """
            CREATE SCHEMA IF NOT EXISTS ${schema.quoteAsPgIdentifier()};

            CREATE TABLE IF NOT EXISTS $qualifiedName (
                id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                version           text,
                description       text        NOT NULL,
                type              text        NOT NULL,
                script            text        NOT NULL,
                checksum          bigint,
                state             text        NOT NULL,
                failed_statement  int,
                execution_time_ms bigint      NOT NULL,
                installed_by      text        NOT NULL DEFAULT current_user,
                installed_on      timestamptz NOT NULL DEFAULT now()
            );

            -- One row per version, and one per repeatable script.
            --
            -- Both partial, for different reasons. The version one narrows to the rows the rule is about:
            -- NULLs are distinct in a unique index by default, so the repeatable rows would not collide
            -- either way, and there is no reason to index them here. The script one has to be partial - it
            -- is the arbiter `ON CONFLICT (script) WHERE version IS NULL` infers when a repeatable migration
            -- is written again, and a versioned migration's script is deliberately not unique.
            CREATE UNIQUE INDEX IF NOT EXISTS ${indexName("version")}
                ON $qualifiedName (version) WHERE version IS NOT NULL;

            CREATE UNIQUE INDEX IF NOT EXISTS ${indexName("script")}
                ON $qualifiedName (script) WHERE version IS NULL;

            -- A column added later goes here, as ALTER TABLE ... ADD COLUMN IF NOT EXISTS. There are none
            -- yet; this is the shape the table was born in.
            """.trimIndent()
        ).execute()
    }

    /** Whether this database has a history table at all - asked before bootstrap, to recognise a fresh one. */
    fun exists(session: OctaviusSessionOperations): Boolean =
        session.createNativeQuery("SELECT to_regclass($1) IS NOT NULL").fetchFieldStrict(qualifiedName)

    /** Every row, oldest first. */
    fun readAll(session: OctaviusSessionOperations): List<AppliedMigration> =
        session.createNativeQuery(
            """
            SELECT version, description, type, script, checksum, state, failed_statement,
                   execution_time_ms, installed_by, installed_on
            FROM $qualifiedName
            ORDER BY id
            """.trimIndent()
        ).fetchRows().map { row ->
            AppliedMigration(
                version = row.get<String?>("version")?.let { MigrationVersion.parse(it) },
                description = row.get<String>("description"),
                type = MigrationType.valueOf(row.get<String>("type")),
                script = row.get<String>("script"),
                checksum = row.get<Long?>("checksum"),
                state = MigrationState.valueOf(row.get<String>("state")),
                failedStatement = row.get<Int?>("failed_statement"),
                executionTimeMs = row.get<Long>("execution_time_ms"),
                installedBy = row.get<String>("installed_by"),
                installedOn = row.get<Instant>("installed_on")
            )
        }

    /**
     * Files a migration under the state it reached, and answers with the row's id.
     *
     * Both paths come through here. A transactional migration calls it once, with [MigrationState.SUCCESS],
     * inside the transaction that ran the migration - so the row and the work it describes commit together or
     * neither does. One running without a transaction calls it before it starts, with
     * [MigrationState.RUNNING], and then [complete] afterwards.
     *
     * @param checksum What to record, which is the migration's own except for a class, where the number
     * belongs to the instance and the instance is what the caller has.
     */
    fun record(
        session: OctaviusSessionOperations,
        migration: DiscoveredMigration,
        state: MigrationState,
        executionTimeMs: Long,
        checksum: Long? = migration.checksum
    ): Long =
        if (migration.version == null) {
            recordRepeatable(session, migration, state, executionTimeMs, checksum)
        } else {
            recordVersioned(session, migration, state, executionTimeMs, checksum)
        }

    private fun recordVersioned(
        session: OctaviusSessionOperations,
        migration: DiscoveredMigration,
        state: MigrationState,
        executionTimeMs: Long,
        checksum: Long?
    ): Long = session.createNativeQuery(
        """
        INSERT INTO $qualifiedName (version, description, type, script, checksum, state, execution_time_ms)
        VALUES ($1, $2, $3, $4, $5, $6, $7)
        RETURNING id
        """.trimIndent()
    ).fetchFieldStrict<Long>(
        migration.version?.canonical,
        migration.description,
        typeOf(migration).name,
        migration.script,
        checksum,
        state.name,
        executionTimeMs
    )

    /**
     * A repeatable migration is updated in place, by its script, rather than added again: one row saying where
     * it got to last time is all of its history worth keeping.
     */
    private fun recordRepeatable(
        session: OctaviusSessionOperations,
        migration: DiscoveredMigration,
        state: MigrationState,
        executionTimeMs: Long,
        checksum: Long?
    ): Long = session.createNativeQuery(
        """
        INSERT INTO $qualifiedName (version, description, type, script, checksum, state, execution_time_ms)
        VALUES (NULL, $1, $2, $3, $4, $5, $6)
        ON CONFLICT (script) WHERE version IS NULL DO UPDATE
            SET description = EXCLUDED.description,
                checksum = EXCLUDED.checksum,
                state = EXCLUDED.state,
                execution_time_ms = EXCLUDED.execution_time_ms,
                failed_statement = NULL,
                installed_by = current_user,
                installed_on = now()
        RETURNING id
        """.trimIndent()
    ).fetchFieldStrict<Long>(
        migration.description,
        typeOf(migration).name,
        migration.script,
        checksum,
        state.name,
        executionTimeMs
    )

    /**
     * Writes the marker saying this database was adopted at [version], everything below it taken as run.
     *
     * Written once, when this migrator first meets a database that has no history table at all. A database
     * that already has one has already been adopted, and a second baseline would be rewriting that answer.
     */
    fun recordBaseline(session: OctaviusSessionOperations, version: MigrationVersion) {
        session.createNativeQuery(
            """
            INSERT INTO $qualifiedName (version, description, type, script, state, execution_time_ms)
            VALUES ($1, 'baseline', $2, '<< baseline >>', $3, 0)
            """.trimIndent()
        ).executeUpdate(version.canonical, MigrationType.BASELINE.name, MigrationState.SUCCESS.name)
    }

    /** Closes off a row [record] opened as [MigrationState.RUNNING]. */
    fun complete(
        session: OctaviusSessionOperations,
        id: Long,
        state: MigrationState,
        executionTimeMs: Long,
        failedStatement: Int? = null
    ) {
        session.createNativeQuery(
            """
            UPDATE $qualifiedName
            SET state = $1, execution_time_ms = $2, failed_statement = $3
            WHERE id = $4
            """.trimIndent()
        ).executeUpdate(state.name, executionTimeMs, failedStatement, id)
    }

    private fun typeOf(migration: DiscoveredMigration) = when (migration) {
        is DiscoveredMigration.Sql -> MigrationType.SQL
        is DiscoveredMigration.Code -> MigrationType.CODE
    }

    /**
     * An index name derived from the table's, so two history tables in one schema do not collide.
     *
     * Cut to what PostgreSQL keeps: it truncates an identifier at 63 bytes without a word, and two long
     * table names sharing a prefix would otherwise end up asking for one index between them.
     */
    private fun indexName(column: String): String =
        "${table}_${column}_uq".take(63).quoteAsPgIdentifier()
}
