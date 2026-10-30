package io.github.octaviusframework.migrations.discovery

import io.github.classgraph.ClassGraph
import io.github.octaviusframework.migrations.MigrationException
import io.github.octaviusframework.migrations.MigrationExceptionReason
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A `.sql` file as it was found, before its name has been read or its checksum taken.
 *
 * @property fileName The name on its own, which is the migration's identity.
 * @property origin Where it was found, which is not - see [DiscoveredMigration.origin].
 * @property content The text, decoded as UTF-8. Migrations are read as UTF-8 wherever they come from; a file
 * saved in something else arrives with replacement characters in it, and its checksum records them.
 */
internal data class FoundFile(val fileName: String, val origin: String, val content: String)

/**
 * The two places `.sql` migrations come from, behind one call: ClassGraph for the classpath, where a path can
 * be a jar inside a jar or a Spring Boot fat jar, and `Files.walk` for a directory, which has to be able to say
 * it is not there.
 */
internal object SqlSources {

    /**
     * What a migration file is called. Fixed, like the `V`, `R` and `__` of the naming rule.
     */
    const val SUFFIX = ".sql"

    private const val CLASSPATH_PREFIX = "classpath:"
    private const val FILESYSTEM_PREFIX = "filesystem:"

    /** Every migration file in [locations], in no particular order - ordering is decided later, by version. */
    fun findAll(locations: List<String>, classLoader: ClassLoader?): List<FoundFile> {
        val classpathPaths = mutableListOf<String>()
        val directories = mutableListOf<String>()

        for (location in locations) {
            when {
                location.startsWith(FILESYSTEM_PREFIX) -> directories += location.removePrefix(FILESYSTEM_PREFIX)
                else -> classpathPaths += location.removePrefix(CLASSPATH_PREFIX)
            }
        }

        return fromClasspath(classpathPaths, classLoader) + directories.flatMap { fromDirectory(it) }
    }

    private fun fromClasspath(paths: List<String>, classLoader: ClassLoader?): List<FoundFile> {
        if (paths.isEmpty()) return emptyList()

        val graph = ClassGraph()
            .acceptPaths(*paths.toTypedArray())
            .let { if (classLoader != null) it.overrideClassLoaders(classLoader) else it }

        return graph.scan().use { result ->
            result.allResources
                .filter { it.path.endsWith(SUFFIX, ignoreCase = true) }
                .map { resource ->
                    FoundFile(
                        fileName = resource.path.substringAfterLast('/'),
                        origin = "$CLASSPATH_PREFIX${resource.path}",
                        content = resource.use { it.load().decodeToString() }
                    )
                }
        }
    }

    private fun fromDirectory(path: String): List<FoundFile> {
        val root: Path = Paths.get(path).toAbsolutePath().normalize()

        // Refused rather than scanned into nothing, which would surface as a run that applied no migrations
        // and reported success.
        if (!Files.isDirectory(root)) {
            throw MigrationException(
                MigrationExceptionReason.CONFIGURATION,
                "$FILESYSTEM_PREFIX$path is not a directory. It resolved to $root - a relative location is " +
                    "resolved against the working directory of the process, which is not always the one you " +
                    "are looking at."
            )
        }

        return Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() }
                .filter { it.name.endsWith(SUFFIX, ignoreCase = true) }
                .map { file -> FoundFile(file.name, file.toString(), file.readText()) }
                .toList()
        }
    }
}
