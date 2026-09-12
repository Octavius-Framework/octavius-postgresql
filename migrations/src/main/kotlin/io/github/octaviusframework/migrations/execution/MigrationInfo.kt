package io.github.octaviusframework.migrations.execution

import io.github.octaviusframework.migrations.MigrationException
import io.github.octaviusframework.migrations.MigrationVersion
import io.github.octaviusframework.migrations.MigratorConfig
import io.github.octaviusframework.migrations.discovery.label
import io.github.octaviusframework.migrations.history.AppliedMigration

/**
 * What is going to happen to one migration, or what already has.
 *
 * Wider than the reason enum on [MigrationException] on purpose. That one is keyed on in a log; this one is
 * the answer to "what about this migration", and every value here is a different answer.
 */
enum class MigrationStatus {
    /** Not applied yet. The next run will apply it. */
    PENDING,

    /**
     * Not applied yet, and its version is below one that already has been - somebody merged a branch late.
     * The run refuses unless [MigratorConfig.outOfOrder] says to allow it, in which case it simply runs.
     */
    OUT_OF_ORDER,

    /** Applied, unchanged since. Nothing to do. */
    APPLIED,

    /**
     * Applied and changed since, which the run refuses. A migration the database has already run is a
     * migration whose text is a record of what was done, not a draft.
     */
    CHANGED,

    /** The database has run it and there is no file or class for it any more, which the run refuses. */
    MISSING,

    /** A previous run died part-way through it, and the run refuses until somebody has looked. */
    INCOMPLETE,

    /** Below the version this database was adopted at, so it is taken as already run. */
    BELOW_BASELINE,

    /** Above [MigratorConfig.target], so this run stops before it. */
    ABOVE_TARGET
}

/**
 * One migration, as the run sees it: what it is, where it came from, and what is going to happen to it.
 *
 * @property version The version, or `null` for a repeatable migration.
 * @property description What its name said.
 * @property script The file name or class name that identifies it.
 * @property origin Where the file or class was found, or `null` for one that exists only in the history -
 * a migration whose file has been deleted has nowhere to point at.
 * @property checksum What the file hashes to now, or `null` for a class and for a migration that exists
 * only in the history. The counterpart to [AppliedMigration.checksum], which is what it hashed to when
 * it ran - the two side by side are what a `CHANGED` status is reporting.
 * @property status What is going to happen to it.
 * @property applied The history row, where there is one.
 */
class MigrationInfo internal constructor(
    val version: MigrationVersion?,
    val description: String,
    val script: String,
    val origin: String?,
    val checksum: Long?,
    val status: MigrationStatus,
    val applied: AppliedMigration?
) {
    /** How this migration is named in a message: `2 add indexes`, or `R rebuild views`. */
    val label: String get() = "${version?.canonical ?: "R"} $description"

    override fun toString(): String = "$label [$status]"
}

/**
 * What a run did.
 *
 * Worth logging at startup, and the empty case is the one worth logging too - "the database was already up to
 * date" is an answer, and a run that says nothing at all leaves you wondering whether it happened.
 *
 * @property applied The migrations this run applied, in the order it applied them, read back from the history
 * table rather than assembled from what was intended.
 * @property durationMs How long the whole run took, waiting for the lock included.
 */
class MigrationReport internal constructor(
    val applied: List<AppliedMigration>,
    val durationMs: Long
) {
    /** Whether the database was already up to date. */
    fun isEmpty(): Boolean = applied.isEmpty()

    override fun toString(): String =
        if (applied.isEmpty()) "MigrationReport(nothing to do, ${durationMs}ms)"
        else "MigrationReport(${applied.size} applied in ${durationMs}ms: " +
            "${applied.joinToString(", ") { "${it.version?.canonical ?: "R"} ${it.description}" }})"
}
