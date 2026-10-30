package io.github.octaviusframework.migrations.execution

import io.github.octaviusframework.driver.parser.SqlScript
import io.github.octaviusframework.driver.parser.SqlStatement
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.migrations.MigrationException
import io.github.octaviusframework.migrations.MigrationExceptionReason
import io.github.octaviusframework.migrations.OctaviusMigration
import io.github.octaviusframework.migrations.discovery.DiscoveredMigration
import io.github.octaviusframework.migrations.discovery.label
import io.github.octaviusframework.migrations.history.MigrationHistory
import io.github.octaviusframework.migrations.history.MigrationState
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Runs one migration, by whichever of the two paths it asked for.
 *
 * They differ in where the history row sits relative to the transaction. A transactional migration writes its
 * row **inside** the transaction that did the work, so a failure takes both. One without a transaction writes
 * the row first as [MigrationState.RUNNING] and closes it off afterwards, because a failure there really does
 * leave the database part-way.
 */
internal class MigrationRunner(private val history: MigrationHistory) {

    /** Applies one migration and records it, by the SQL or the Kotlin path. */
    fun run(session: OctaviusSession, migration: DiscoveredMigration) {
        when (migration) {
            is DiscoveredMigration.Sql -> runSql(session, migration)
            is DiscoveredMigration.Code -> runCode(session, migration)
        }
    }

    // ------------------------------------------------------------------ sql

    private fun runSql(session: OctaviusSession, migration: DiscoveredMigration.Sql) {
        if (migration.transactional) {
            inTransaction(session, migration, migration.checksum) { operations ->
                // The whole file in one message. Sending it statement by statement would buy nothing here -
                // the transaction already makes it all-or-nothing - and would cost the error position, which
                // this way points into the file the author is looking at rather than into a fragment of it.
                operations.createNativeQuery(migration.content).execute(ignoreRows = true)
            }
        } else {
            runSqlWithoutTransaction(session, migration)
        }
    }

    private fun runSqlWithoutTransaction(session: OctaviusSession, migration: DiscoveredMigration.Sql) {
        val statements = SqlScript.split(migration.content)
        val id = history.record(session, migration, MigrationState.RUNNING, executionTimeMs = 0)
        val startedAt = System.nanoTime()
        var reached = 0

        try {
            for ((index, statement) in statements.withIndex()) {
                reached = index
                session.createNativeQuery(statement.sql).execute(ignoreRows = true)
            }
        } catch (e: Throwable) {
            closeOff(session, id, MigrationState.FAILED, elapsedMs(startedAt), reached + 1)
            throw failedAt(migration, statements.getOrNull(reached), reached + 1, statements.size, e)
        }

        history.complete(session, id, MigrationState.SUCCESS, elapsedMs(startedAt))
    }

    // ------------------------------------------------------------------ code

    private fun runCode(session: OctaviusSession, migration: DiscoveredMigration.Code) {
        // Built here and nowhere earlier: the scan reads names, and this is the first moment anything of
        // yours is allowed to run. Its flags are read off the instance because that is where they live.
        val instance = instantiate(migration)

        if (instance.transactional) {
            inTransaction(session, migration, instance.checksum) { operations -> instance.migrate(operations) }
        } else {
            val id = history.record(session, migration, MigrationState.RUNNING, 0, instance.checksum)
            val startedAt = System.nanoTime()
            try {
                instance.migrate(session)
            } catch (e: Throwable) {
                closeOff(session, id, MigrationState.FAILED, elapsedMs(startedAt), null)
                throw failed(migration, e)
            }
            history.complete(session, id, MigrationState.SUCCESS, elapsedMs(startedAt))
        }
    }

    /** Builds the migration from the class the scan loaded, which is also where its static initialiser runs. */
    private fun instantiate(migration: DiscoveredMigration.Code): OctaviusMigration =
        try {
            migration.migrationClass.getDeclaredConstructor().newInstance() as OctaviusMigration
        } catch (e: Throwable) {
            throw MigrationException(
                MigrationExceptionReason.INVALID_MIGRATION,
                "${migration.className} could not be constructed. The scan saw a constructor taking no " +
                    "arguments, so what failed is the constructor itself or a static initialiser.",
                cause = e
            )
        }

    // ------------------------------------------------------------------ the two shapes

    /**
     * Runs [body] and records the migration in one transaction, so that either both happened or neither did.
     */
    private fun inTransaction(
        session: OctaviusSession,
        migration: DiscoveredMigration,
        checksum: Long?,
        body: (OctaviusSessionOperations) -> Unit
    ) {
        val startedAt = System.nanoTime()
        try {
            session.transaction.required {
                body(this)
                history.record(this, migration, MigrationState.SUCCESS, elapsedMs(startedAt), checksum)
            }
        } catch (e: MigrationException) {
            throw e
        } catch (e: Throwable) {
            throw failed(migration, e)
        }
    }

    /**
     * Closes off a `RUNNING` row while the run is already failing.
     *
     * Its own failure is logged and goes no further: whatever is being reported is worth more than the news
     * that the history could not be updated, and a connection that has just died would otherwise replace the
     * real reason with a network error.
     */
    private fun closeOff(
        session: OctaviusSession,
        id: Long,
        state: MigrationState,
        elapsedMs: Long,
        failedStatement: Int?
    ) {
        try {
            history.complete(session, id, state, elapsedMs, failedStatement)
        } catch (e: Throwable) {
            logger.error(e) { "Could not mark migration row $id as $state; it stays as RUNNING" }
        }
    }

    private fun failed(migration: DiscoveredMigration, cause: Throwable) = MigrationException(
        MigrationExceptionReason.MIGRATION_FAILED,
        "${migration.label} (${migration.origin}) failed.",
        cause = cause
    )

    private fun failedAt(
        migration: DiscoveredMigration.Sql,
        statement: SqlStatement?,
        number: Int,
        outOf: Int,
        cause: Throwable
    ): MigrationException {
        val where = statement?.let { " Statement $number of $outOf, starting at line ${lineOf(migration.content, it.offset)}." }
            ?: ""
        return MigrationException(
            MigrationExceptionReason.MIGRATION_FAILED,
            "${migration.label} (${migration.origin}) failed and was not in a transaction, so the statements " +
                "before this one are applied and stay applied.$where",
            cause = cause
        )
    }

    /** Which line of the file an offset falls on, counting from one - a position somebody can navigate to. */
    private fun lineOf(content: String, offset: Int): Int {
        var line = 1
        for (i in 0 until minOf(offset, content.length)) {
            if (content[i] == '\n') line++
        }
        return line
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
}
