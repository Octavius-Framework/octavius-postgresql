package io.github.octaviusframework.migrations

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the migrator looks and what it accepts.
 *
 * @property sqlLocations Where the `.sql` files are. A location is either a classpath path -
 * `db/migration`, or `classpath:db/migration`, the prefix being optional because it is the usual case - or a
 * directory, `filesystem:./ops/sql`, resolved against the process's working directory. Subdirectories are
 * searched too. Empty is allowed for a project whose migrations are all Kotlin.
 * @property codePackages Packages holding [OctaviusMigration] classes, subpackages included. Empty is
 * allowed for a project whose migrations are all `.sql`; empty *along with* [sqlLocations] is not, there
 * being nothing to do.
 * @property placeholders Values pasted into `.sql` migrations before they run: `${name}` becomes the value
 * mapped to `name`. Empty - the default - and nothing is scanned for, so a migration holding a `${` of its
 * own is untouched. Once there is one, every `${name}` in every file has to have a value or the run is
 * refused; `\${name}` is that text rather than a placeholder. A paste, not a parameter: put the schema a
 * role is granted on in here, never anything that came from a user.
 * @property classLoader Where to look for classes and classpath resources, for an application whose classes
 * are not on the loader that loaded this one - an OSGi container, a plugin host. `null` uses the default.
 * @property historySchema Where the history table lives. Created if it is not there, which it has to be:
 * the table must exist before the first migration runs, so a schema a migration would have created is one
 * the history could never be kept in.
 * @property historyTable What the history table is called. Worth changing only where two applications
 * keep separate histories in one database - and then they hold separate locks too, the lock key being
 * derived from this name.
 * @property lockTimeout How long to wait for the migration lock before giving up. A second instance starting
 * alongside the first waits for it rather than failing; this bounds the wait.
 * @property outOfOrder Whether to apply a migration whose version is below one already applied. Off by
 * default, because the usual cause is a branch merged late and the usual consequence is two databases
 * that ran the same migrations in different orders.
 * @property baselineVersion The version an existing database is taken to already be at, written once when
 * this migrator first meets a database with no history table. Everything at or below it is skipped rather
 * than run - which is how a database that predates the migrator gets adopted instead of rebuilt.
 * @property target The highest version to apply, or `null` for all of them. For a release that ships the
 * migrations before the code that needs them.
 */
data class MigratorConfig(
    val sqlLocations: List<String> = listOf("db/migration"),
    val codePackages: List<String> = emptyList(),
    val placeholders: Map<String, String> = emptyMap(),
    val classLoader: ClassLoader? = null,
    val historySchema: String = "public",
    val historyTable: String = "octavius_migration_history",
    val lockTimeout: Duration = 30.seconds,
    val outOfOrder: Boolean = false,
    val baselineVersion: String? = null,
    val target: String? = null
) {
    /**
     * [baselineVersion] as a version, or `null` where none was given. Parsed when the config is built, so a
     * `baselineVersion = "1.x"` is refused there rather than by the first `migrate()`.
     */
    internal val parsedBaselineVersion: MigrationVersion? = parseConfigured(baselineVersion, "baselineVersion")

    /** [target] as a version, or `null` where none was given. Read on the same terms as [parsedBaselineVersion]. */
    internal val parsedTarget: MigrationVersion? = parseConfigured(target, "target")
}

/**
 * Reads a version out of a configured property, saying which property it was.
 *
 * @throws MigrationException `CONFIGURATION` where [text] is not a version.
 */
private fun parseConfigured(text: String?, property: String): MigrationVersion? {
    if (text == null) return null
    return try {
        MigrationVersion.parse(text)
    } catch (e: MigrationException) {
        throw MigrationException(
            MigrationExceptionReason.CONFIGURATION,
            "$property is \"$text\", which is not a version.",
            cause = e
        )
    }
}
