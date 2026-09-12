package io.github.octaviusframework.migrations.discovery

import io.github.octaviusframework.migrations.MigrationVersion
import io.github.octaviusframework.migrations.OctaviusMigration

/**
 * A migration the scan found, before anything about the database is known.
 *
 * The two kinds carry different things, so they are separate types rather than one with nullable fields: a
 * `.sql` file arrives with its text and checksum read, a class as a class that has not been constructed.
 */
internal sealed interface DiscoveredMigration {

    /** The version, or `null` where the name marked this repeatable. */
    val version: MigrationVersion?

    /** The words after `__`, as they will be recorded. */
    val description: String

    /**
     * What the history table files this under: a file's name, or a class's full name.
     *
     * The name, not the path - so moving a migration between configured locations does not make it a new one.
     */
    val script: String

    /**
     * Where this was found, for a log line and for the report.
     *
     * For a file that is a path, which [script] deliberately is not. For a class the two are the same string:
     * a classpath resolves one class per name, so there is no second place it could have come from.
     */
    val origin: String

    /** The number that decides whether this changed since it ran, or `null` for "do not check". */
    val checksum: Long?

    /** A migration read out of a `.sql` file. */
    class Sql(
        override val version: MigrationVersion?,
        override val description: String,
        override val script: String,
        override val origin: String,
        override val checksum: Long,

        /** Whether to wrap it in a transaction - `false` where the file asked not to be. */
        val transactional: Boolean,

        /**
         * The text that will be sent, read at the scan rather than when it runs and with any placeholders
         * already pasted into it.
         *
         * Read early so that a file that has gone missing is found while the database is still untouched.
         * Where placeholders are configured this is no longer the text [checksum] was taken over - that one
         * is the file as it was written, so that two deployments filling the same file in differently still
         * agree about which migration it is.
         */
        val content: String
    ) : DiscoveredMigration

    /** A migration read off an [OctaviusMigration] class, which the scan has not constructed. */
    class Code(
        override val version: MigrationVersion?,
        override val description: String,
        override val script: String,
        override val origin: String,

        /**
         * The class, loaded by the scan but not initialised, and constructed when this migration's turn
         * comes.
         *
         * Carried rather than looked up again by name: ClassGraph resolved it against the loaders it scanned
         * with, and a later `Class.forName` would have to guess at the same answer - wrongly, wherever this
         * module and the application sit on different loaders.
         */
        val migrationClass: Class<*>
    ) : DiscoveredMigration {

        /** Always `null` here: a class's checksum, if it declares one, is on the instance. */
        override val checksum: Long? get() = null

        /** The class's full name, which is what the history files it under. */
        val className: String get() = script
    }
}

/** How a migration is named in a message: `2 add indexes`, or `R rebuild views` for a repeatable one. */
internal val DiscoveredMigration.label: String
    get() = "${version?.text ?: "R"} $description"
