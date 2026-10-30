package io.github.octaviusframework.migrations

import io.github.octaviusframework.driver.exception.OctaviusException

/**
 * Why a migration run stopped, by kind of problem, for a log line or a metric to be keyed on. What is
 * actually wrong is in `details` and in the cause.
 */
enum class MigrationExceptionReason {
    /**
     * One migration cannot be used: its name carries no version, its script opens a transaction of its own,
     * it declares a directive nobody knows, or its class has no constructor to call.
     */
    INVALID_MIGRATION,

    /** Two migrations claim the same identity - the same version, or the same script in two places. */
    DUPLICATE_MIGRATION,

    /** The migrator was set up in a way it cannot work with: nowhere to look, or somewhere that is not there. */
    CONFIGURATION,

    /**
     * The wait for the migration lock ran out. Another migrator is still going, or a session is holding the
     * lock and not finishing - this is the one reason here that a caller might sensibly retry on.
     */
    LOCK_NOT_ACQUIRED,

    /**
     * The migrations on disk and the ones in the history disagree: one has changed since it ran, one the
     * database has run is gone, or one arrived with a version below what is already applied.
     */
    VALIDATION_FAILED,

    /**
     * A previous run died in the middle of a migration that was not in a transaction, so part of it is
     * applied and part is not. Only a person can say what to do about that.
     */
    HISTORY_INCOMPLETE,

    /** A migration ran and failed. What the server or the migration's own code said is the cause. */
    MIGRATION_FAILED
}

/**
 * Exception thrown when a migration run cannot go on.
 *
 * It carries no `sqlState`: where the server said something - a lock wait that ran out, a statement of a
 * migration it refused - its exception is the cause. Where the migrator has no opinion - the history table
 * failing to be read, a connection dying mid-run - the driver's own exception comes through instead.
 *
 * @property reason What kind of problem this is, for a log line to be keyed on.
 * @property details What is actually wrong, naming the file, class or version it is wrong about.
 * @param cause The failure underneath, where this one is standing in front of another.
 */
class MigrationException(
    val reason: MigrationExceptionReason,
    val details: String? = null,
    cause: Throwable? = null
) : OctaviusException("MIGRATION_EXCEPTION:${reason.name}", cause = cause) {

    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        if (details != null) appendLine("Details: $details")
    }
}

private fun generateDeveloperMessage(reason: MigrationExceptionReason): String =
    when (reason) {
        MigrationExceptionReason.INVALID_MIGRATION ->
            "A migration cannot be used as it stands - see the details for what is wrong with it."
        MigrationExceptionReason.DUPLICATE_MIGRATION ->
            "Two migrations claim the same identity, and there is no way to tell which one ran."
        MigrationExceptionReason.CONFIGURATION ->
            "The migrator has nowhere usable to look for migrations."
        MigrationExceptionReason.LOCK_NOT_ACQUIRED ->
            "Another migrator holds the lock on this database's migration history."
        MigrationExceptionReason.VALIDATION_FAILED ->
            "The migrations on disk do not line up with what this database has already run."
        MigrationExceptionReason.HISTORY_INCOMPLETE ->
            "A previous run stopped part-way through a migration and left the database between two states."
        MigrationExceptionReason.MIGRATION_FAILED ->
            "A migration failed. See the cause for what the database or the migration itself said."
    }
