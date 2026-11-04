package io.github.octaviusframework.migrations.discovery

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.octaviusframework.migrations.MigrationException
import io.github.octaviusframework.migrations.MigrationExceptionReason
import io.github.octaviusframework.migrations.MigratorConfig
import io.github.octaviusframework.migrations.OctaviusMigration
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Finds every migration the configuration points at, and puts them in the order they would run.
 *
 * Nothing here touches the database and nothing here runs a line of anybody's code - a class is loaded and
 * read, never constructed, and loading is not initialising. Everything that can be refused is refused at this
 * point, which is the only point where refusing costs nothing: past it, a migration that fails has already
 * had migrations committed in front of it.
 */
internal object MigrationDiscovery {

    /**
     * @return Versioned migrations in ascending version order, then repeatable ones by description.
     * Repeatables come last because they are the ones that lean on the schema the versioned ones just built.
     * @throws MigrationException `CONFIGURATION` where there is nowhere to look, `INVALID_MIGRATION` for a
     * migration that cannot be used, `DUPLICATE_MIGRATION` where two claim the same identity.
     */
    fun discover(config: MigratorConfig): List<DiscoveredMigration> {
        if (config.sqlLocations.isEmpty() && config.codePackages.isEmpty()) {
            throw MigrationException(
                MigrationExceptionReason.CONFIGURATION,
                "Neither sqlLocations nor codePackages names anywhere to look, so there is nothing this " +
                    "could find. Name at least one."
            )
        }

        val migrations = discoverSql(config) + discoverCode(config)

        refuseDuplicates(migrations)

        if (migrations.isEmpty()) {
            // Warned rather than refused: no migrations yet is a real state, but a mistyped path that finds
            // nothing should not pass in silence.
            logger.warn {
                "Found no migrations in ${describeLocations(config)}. If that is a surprise, the paths are " +
                    "the first thing to check: a location that matches nothing is not an error by itself."
            }
        } else {
            logger.info { "Found ${migrations.size} migrations in ${describeLocations(config)}" }
            logger.debug { migrations.joinToString(", ") { "${it.label} (${it.origin})" } }
        }

        return migrations.sortedWith(RUN_ORDER)
    }

    /**
     * Versioned first in version order, then repeatable ones by description - not by script, which is a file
     * name for one kind and a class name for the other - so `R__01_base_views` runs before `R__02_derived_views`.
     * Case is ignored, and script breaks a tie. The version key needs no null handling: the first key already
     * keeps versioned and repeatable apart.
     */
    private val RUN_ORDER = compareBy<DiscoveredMigration> { if (it.version == null) 1 else 0 }
        .thenBy { it.version }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.description }
        .thenBy { it.script }

    // ------------------------------------------------------------------ sql

    private fun discoverSql(config: MigratorConfig): List<DiscoveredMigration> =
        SqlSources.findAll(config.sqlLocations, config.classLoader).map { found ->
            val bareName = found.fileName.dropLast(SqlSources.SUFFIX.length)
            val parsed = MigrationNames.parse(bareName, found.origin)

            // Substituted first, so that every rule below reads the text the server is going to be sent and
            // not a template of it - a value carrying a COMMIT is refused here rather than found out later.
            val content = MigrationPlaceholders.resolve(found.content, found.origin, config.placeholders)
            val transactional = SqlMigrationRules.readTransactionality(content, found.origin)
            SqlMigrationRules.refuseTransactionControl(content, found.origin)

            DiscoveredMigration.Sql(
                version = parsed.version,
                description = parsed.description,
                script = found.fileName,
                origin = found.origin,
                // The file as it was written, not as it was filled in: a value that differs between two
                // deployments is not an edit to the migration.
                checksum = MigrationChecksum.of(found.content),
                transactional = transactional,
                content = content
            )
        }

    // ------------------------------------------------------------------ code

    private fun discoverCode(config: MigratorConfig): List<DiscoveredMigration> {
        if (config.codePackages.isEmpty()) return emptyList()

        val graph = ClassGraph()
            .enableClassInfo()
            .acceptPackages(*config.codePackages.toTypedArray())
            .let { if (config.classLoader != null) it.overrideClassLoaders(config.classLoader) else it }

        return graph.scan().use { result ->
            result.getClassesImplementing(OctaviusMigration::class.java.name)
                .filter { !it.isAbstract && !it.isInterface }
                .map { classInfo ->
                    // Loaded by ClassGraph with the loaders it scanned with, so the class that runs is the one
                    // found. Loading is not initialising: nothing of yours runs until the instance is built.
                    val migrationClass = loadClass(classInfo)
                    refuseWithoutNoArgConstructor(migrationClass)

                    val parsed = MigrationNames.parse(simpleNameOf(classInfo), classInfo.name)
                    DiscoveredMigration.Code(
                        version = parsed.version,
                        description = parsed.description,
                        script = classInfo.name,
                        origin = classInfo.name,
                        migrationClass = migrationClass
                    )
                }
        }
    }

    private fun loadClass(classInfo: ClassInfo): Class<*> =
        try {
            classInfo.loadClass()
        } catch (e: Throwable) {
            throw MigrationException(
                MigrationExceptionReason.INVALID_MIGRATION,
                "${classInfo.name} was found by the scan but could not be loaded. Something it refers to is " +
                    "not on the classpath beside it.",
                cause = e
            )
        }

    /** The name without its package or its outer classes, which is what the naming convention applies to. */
    private fun simpleNameOf(classInfo: ClassInfo): String =
        classInfo.name.substringAfterLast('.').substringAfterLast('$')

    /**
     * Refuses a class nothing can build.
     *
     * Asked of the loaded class rather than of ClassGraph's method index, which is the point of asking at
     * all: this is the very call the run will make when the migration's turn comes, so an answer here is the
     * answer there. It also lets the scan skip `enableMethodInfo()`, which is not free.
     */
    private fun refuseWithoutNoArgConstructor(migrationClass: Class<*>) {
        try {
            migrationClass.getDeclaredConstructor()
        } catch (e: NoSuchMethodException) {
            throw MigrationException(
                MigrationExceptionReason.INVALID_MIGRATION,
                "${migrationClass.name} has no constructor that takes no arguments, and a migration is built " +
                    "with one immediately before it runs. Said here rather than when its turn comes, because " +
                    "by then the migrations in front of it would already be committed.",
                cause = e
            )
        }
    }

    // ------------------------------------------------------------------ duplicates

    private fun refuseDuplicates(migrations: List<DiscoveredMigration>) {
        migrations.groupBy { it.script }
            .forEach { (script, sharing) ->
                if (sharing.size > 1) {
                    throw MigrationException(
                        MigrationExceptionReason.DUPLICATE_MIGRATION,
                        "\"$script\" was found in more than one place: ${sharing.joinToString(", ") { it.origin }}. " +
                            "The history records a migration by its name, so there would be no telling which " +
                            "of these had run."
                    )
                }
            }

        migrations.filter { it.version != null }
            .groupBy { it.version }
            .forEach { (version, sharing) ->
                if (sharing.size > 1) {
                    throw MigrationException(
                        MigrationExceptionReason.DUPLICATE_MIGRATION,
                        "Version $version is claimed by ${sharing.joinToString(" and ") { it.origin }}. Note " +
                            "that trailing zeroes do not make a version different - 1, 1.0 and 1.0.0 are one " +
                            "version."
                    )
                }
            }
    }

    private fun describeLocations(config: MigratorConfig): String {
        val parts = buildList {
            if (config.sqlLocations.isNotEmpty()) add(config.sqlLocations.joinToString(", "))
            if (config.codePackages.isNotEmpty()) add(config.codePackages.joinToString(", "))
        }
        return parts.joinToString(" and ")
    }
}
