/*
 *                      ____   _____ _______  __      _______ _    _  _____
 *                     / __ \ / ____|__   __|/\ \    / /_   _| |  | |/ ____|
 *                    | |  | | |       | |  /  \ \  / /  | | | |  | | (___
 *                    | |  | | |       | | / /\ \ \/ /   | | | |  | |\___ \
 *                    | |__| | |____   | |/ ____ \  /   _| |_| |__| |____) |
 *                     \____/ \_____|  |_/_/    \_\/   |_____|\____/|_____/
 *                   --------------------------------------------------------
 *                                      OCTAVIUS MIGRATIONS
 *                   --------------------------------------------------------
 */
package io.github.octaviusframework.migrations

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.TransactionState
import io.github.octaviusframework.migrations.discovery.MigrationDiscovery
import io.github.octaviusframework.migrations.discovery.label
import io.github.octaviusframework.migrations.execution.MigrationInfo
import io.github.octaviusframework.migrations.execution.MigrationReport
import io.github.octaviusframework.migrations.execution.MigrationRunner
import io.github.octaviusframework.migrations.execution.MigrationStatus
import io.github.octaviusframework.migrations.execution.MigrationValidation
import io.github.octaviusframework.migrations.history.MigrationHistory
import io.github.octaviusframework.migrations.history.MigrationLock
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

/**
 * Brings a database up to date with the migrations in this application.
 *
 * ```kotlin
 * val report = OctaviusMigrator(
 *     dataSource,
 *     MigratorConfig(
 *         sqlLocations = listOf("db/migration"),
 *         codePackages = listOf("com.roma.migrations")
 *     )
 * ).migrate()
 *
 * logger.info { "Octavius: $report" }
 * ```
 *
 * A run holds one session, and an advisory lock from before the history table is read until after the last
 * migration, so two instances starting together run once and wait once.
 *
 * There is no `undo`: a migration that turns out to be wrong is corrected by one that comes after it.
 */
class OctaviusMigrator private constructor(
    private val dataSource: DataSource?,
    private val borrowedSession: OctaviusSession?,
    private val config: MigratorConfig
) {

    /**
     * @param dataSource Where to get the session from. One is borrowed for the run and returned after it.
     * @param config Where to look for migrations, and what to allow.
     */
    constructor(dataSource: DataSource, config: MigratorConfig = MigratorConfig()) :
        this(dataSource, null, config)

    private val history = MigrationHistory(config.historySchema, config.historyTable)
    private val lock = MigrationLock(config.historySchema, config.historyTable, config.lockTimeout)
    private val runner = MigrationRunner(history)

    // Parsed by the config, which refuses a value that is not a version at the point it was written down.
    private val baselineVersion = config.parsedBaselineVersion
    private val target = config.parsedTarget

    /**
     * Applies every migration this database has not run, in version order.
     *
     * Refuses before touching anything where the migrations and the history disagree - see
     * [MigrationStatus] for what each disagreement is called and [MigrationException] for what is raised.
     *
     * @return What was applied, read back from the history table. Empty where the database was already up
     * to date.
     * @throws MigrationException for anything that stops the run.
     */
    fun migrate(): MigrationReport = withSession { session ->
        val startedAt = System.nanoTime()
        val discovered = MigrationDiscovery.discover(config)

        lock.withLock(session) {
            // Asked before the bootstrap, because "this database has no history table" is what makes it a
            // database that can still be adopted at a baseline.
            val fresh = !history.exists(session)
            history.bootstrap(session)
            if (fresh && baselineVersion != null) {
                logger.info { "No history table here yet - adopting this database at version ${baselineVersion.canonical}" }
                history.recordBaseline(session, baselineVersion)
            }

            val infos = MigrationValidation.merge(discovered, history.readAll(session), target)
            MigrationValidation.refuse(infos, config.outOfOrder)

            val runnable = MigrationValidation.toRun(infos).map { it.script }.toSet()
            val toRun = discovered.filter { it.script in runnable }

            if (toRun.isEmpty()) {
                logger.info { "Database is up to date; nothing to apply" }
                return@withLock MigrationReport(emptyList(), elapsedMs(startedAt))
            }

            for (migration in toRun) {
                logger.info { "Applying ${migration.label} (${migration.origin})" }
                runner.run(session, migration)
            }

            // Read back: `installed_on` and `installed_by` are the database's answers, not ours.
            val applied = history.readAll(session).filter { it.script in runnable }
            val report = MigrationReport(applied, elapsedMs(startedAt))
            logger.info { "Octavius applied ${applied.size} migrations in ${report.durationMs}ms" }
            report
        }
    }

    /**
     * What the run would do, without doing any of it.
     *
     * Takes no lock and creates nothing - a database with no history table answers with every migration
     * [MigrationStatus.PENDING] rather than getting one. Nothing here refuses, either: a checksum that has
     * drifted comes back as [MigrationStatus.CHANGED] to be looked at rather than thrown.
     *
     * @return Every migration this application knows about and every one the database has run, merged.
     */
    fun info(): List<MigrationInfo> = withSession { session ->
        val discovered = MigrationDiscovery.discover(config)
        val applied = if (history.exists(session)) history.readAll(session) else emptyList()
        MigrationValidation.merge(discovered, applied, target)
    }

    private fun <T> withSession(block: (OctaviusSession) -> T): T {
        if (borrowedSession != null) {
            requireAutoCommit(borrowedSession)
            return block(borrowedSession)
        }
        return dataSource!!.getOctaviusSession().use { session ->
            requireAutoCommit(session)
            block(session)
        }
    }

    private fun requireAutoCommit(session: OctaviusSession) {
        if (!session.autoCommit || session.transactionState != TransactionState.IDLE) {
            throw MigrationException(
                MigrationExceptionReason.CONFIGURATION,
                "This session already has a transaction open. The run opens and commits transactions of its " +
                    "own - one per migration - so it cannot be nested inside one: a migration would be " +
                    "recorded as applied while its work sat uncommitted in somebody else's transaction."
            )
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        /**
         * A migrator running on a session you already hold, rather than one borrowed from a `DataSource`.
         *
         * For tests, and for code that has a session open and wants the run on that one. The session must be
         * in auto-commit: the run manages transactions itself, and nesting it inside one of yours would let
         * a migration be recorded as applied while its work was still uncommitted.
         */
        fun onSession(session: OctaviusSession, config: MigratorConfig = MigratorConfig()): OctaviusMigrator =
            OctaviusMigrator(null, session, config)
    }
}
