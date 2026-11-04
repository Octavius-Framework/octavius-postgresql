package io.github.octaviusframework.migrations.execution

import io.github.octaviusframework.migrations.MigrationException
import io.github.octaviusframework.migrations.MigrationExceptionReason
import io.github.octaviusframework.migrations.MigrationVersion
import io.github.octaviusframework.migrations.discovery.DiscoveredMigration
import io.github.octaviusframework.migrations.discovery.label
import io.github.octaviusframework.migrations.history.AppliedMigration
import io.github.octaviusframework.migrations.history.MigrationState
import io.github.octaviusframework.migrations.history.MigrationType

/**
 * Puts what was found next to what has run, and decides what each migration's situation is.
 *
 * [merge] refuses nothing, so `info()` can use it; [refuse] is what a run adds.
 */
internal object MigrationValidation {

    /**
     * @param discovered What the scan found, in run order.
     * @param applied What the history table holds, oldest first.
     * @param target The highest version this run will go to, or `null` for all of them.
     */
    fun merge(
        discovered: List<DiscoveredMigration>,
        applied: List<AppliedMigration>,
        target: MigrationVersion?
    ): List<MigrationInfo> {
        val baseline = applied.firstOrNull { it.type == MigrationType.BASELINE }?.version
        val realRows = applied.filter { it.type != MigrationType.BASELINE }

        // A versioned migration is looked up by version and a repeatable one by script: a versioned
        // migration's file name can change without changing which migration it is, and a repeatable one has
        // no version to be found by.
        val byVersion = realRows.filter { it.version != null }.associateBy { it.version }
        val byScript = realRows.filter { it.version == null }.associateBy { it.script }
        val highestApplied = byVersion.keys.filterNotNull().maxOrNull()

        val infos = discovered.map { migration ->
            val row = migration.version?.let { byVersion[it] } ?: byScript[migration.script]
            MigrationInfo(
                version = migration.version,
                description = migration.description,
                script = migration.script,
                origin = migration.origin,
                checksum = migration.checksum,
                status = statusOf(migration, row, baseline, target, highestApplied),
                applied = row
            )
        }

        // Rows with nothing on disk answering for them. The baseline marker is not one of these - it never
        // had a file, and reporting it as missing would make every adopted database look broken.
        val matched = infos.mapNotNull { it.applied }.toHashSet()
        val orphans = realRows.filterNot { it in matched }.map { row ->
            MigrationInfo(
                version = row.version,
                description = row.description,
                script = row.script,
                origin = null,
                checksum = null,
                status = if (row.state == MigrationState.SUCCESS) MigrationStatus.MISSING
                else MigrationStatus.INCOMPLETE,
                applied = row
            )
        }

        return infos + orphans
    }

    private fun statusOf(
        migration: DiscoveredMigration,
        row: AppliedMigration?,
        baseline: MigrationVersion?,
        target: MigrationVersion?,
        highestApplied: MigrationVersion?
    ): MigrationStatus {
        if (row != null && row.state != MigrationState.SUCCESS) return MigrationStatus.INCOMPLETE

        val version = migration.version
            ?: return repeatableStatus(row, migration.checksum)

        if (row != null) {
            // Either side missing a checksum means there is nothing to compare, not that they differ.
            val changed = row.checksum != null && migration.checksum != null && row.checksum != migration.checksum
            return if (changed) MigrationStatus.CHANGED else MigrationStatus.APPLIED
        }

        if (baseline != null && version <= baseline) return MigrationStatus.BELOW_BASELINE
        if (target != null && version > target) return MigrationStatus.ABOVE_TARGET
        if (highestApplied != null && version < highestApplied) return MigrationStatus.OUT_OF_ORDER

        return MigrationStatus.PENDING
    }

    /**
     * A repeatable migration runs again whenever it has changed - and whenever there is no telling whether it
     * changed, which is every one written in Kotlin that declared no checksum of its own.
     */
    private fun repeatableStatus(row: AppliedMigration?, checksum: Long?): MigrationStatus = when {
        row == null -> MigrationStatus.PENDING
        row.checksum == null || checksum == null -> MigrationStatus.PENDING
        row.checksum != checksum -> MigrationStatus.PENDING
        else -> MigrationStatus.APPLIED
    }

    /** The migrations a run would apply, in the order it would apply them. */
    fun toRun(infos: List<MigrationInfo>): List<MigrationInfo> =
        infos.filter { it.status == MigrationStatus.PENDING || it.status == MigrationStatus.OUT_OF_ORDER }

    /**
     * Stops the run where the picture says it should not go on.
     *
     * Ordered by what a person would want to hear first. An unfinished migration outranks everything: while
     * the database is between two states, no answer about checksums is worth reading.
     *
     * @throws MigrationException `HISTORY_INCOMPLETE` or `VALIDATION_FAILED`.
     */
    fun refuse(infos: List<MigrationInfo>, outOfOrder: Boolean) {
        val incomplete = infos.filter { it.status == MigrationStatus.INCOMPLETE }
        if (incomplete.isNotEmpty()) {
            throw MigrationException(
                MigrationExceptionReason.HISTORY_INCOMPLETE,
                incomplete.joinToString("; ") { info ->
                    val where = info.applied?.failedStatement?.let { " at statement $it" } ?: ""
                    "${info.label} is recorded as ${info.applied?.state}$where"
                } + ". That migration ran outside a transaction, so part of it is applied and part is not. " +
                    "Look at what it did, finish or undo it by hand, then delete its row from the history " +
                    "table so the run can go on."
            )
        }

        val missing = infos.filter { it.status == MigrationStatus.MISSING }
        if (missing.isNotEmpty()) {
            throw MigrationException(
                MigrationExceptionReason.VALIDATION_FAILED,
                "The database has run ${missing.joinToString(", ") { it.label }}, and there is no file or " +
                    "class for it any more. Either it was deleted, or this application is looking somewhere " +
                    "other than where its migrations are."
            )
        }

        val changed = infos.filter { it.status == MigrationStatus.CHANGED }
        if (changed.isNotEmpty()) {
            throw MigrationException(
                MigrationExceptionReason.VALIDATION_FAILED,
                changed.joinToString("; ") { info ->
                    "${info.label} (${info.origin}) ran with checksum ${info.applied?.checksum} and now " +
                        "hashes to ${info.checksum}"
                } + ". A migration the database has already run is a record of what was done, not a draft. " +
                    "Put the change in a new migration."
            )
        }

        if (!outOfOrder) {
            val late = infos.filter { it.status == MigrationStatus.OUT_OF_ORDER }
            if (late.isNotEmpty()) {
                throw MigrationException(
                    MigrationExceptionReason.VALIDATION_FAILED,
                    "${late.joinToString(", ") { it.label }} arrived with a version below one this database " +
                        "has already applied - usually a branch merged after a release. Set outOfOrder if " +
                        "applying it late is what you want; the cost is two databases that ran the same " +
                        "migrations in different orders."
                )
            }
        }
    }
}
