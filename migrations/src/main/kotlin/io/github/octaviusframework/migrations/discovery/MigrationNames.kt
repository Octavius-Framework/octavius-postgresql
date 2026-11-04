package io.github.octaviusframework.migrations.discovery

import io.github.octaviusframework.migrations.MigrationException
import io.github.octaviusframework.migrations.MigrationExceptionReason
import io.github.octaviusframework.migrations.MigrationVersion

/**
 * What a migration's name says about it.
 *
 * @property version The version, or `null` where the name marked it repeatable.
 * @property description The words after `__`, with `_` read as a space.
 */
internal data class ParsedName(val version: MigrationVersion?, val description: String)

/**
 * Reads a migration's version and description out of its name.
 *
 * The same convention covers both kinds, so a class never has to be constructed to find out what it is.
 * `V2__add_indexes.sql`
 * and `V2__Add_indexes` are the same version and the same description, and `R__rebuild_views` is repeatable
 * either way. The one difference is forced by Kotlin rather than chosen - `.` cannot appear in a class name,
 * so a class writes `V2_1__…` where a file may write either.
 */
internal object MigrationNames {

    private const val SEPARATOR = "__"

    /**
     * Parses [name] - a file name with its suffix already off, or a class's simple name.
     *
     * @param name The bare name, `V<version>__<description>` or `R__<description>`.
     * @param origin What to call this in a refusal: the path it was found at, or the class's full name. The
     * message has to name something the reader can go and open.
     * @throws MigrationException `INVALID_MIGRATION` for anything that is not one of those two shapes.
     */
    fun parse(name: String, origin: String): ParsedName {
        val separatorAt = name.indexOf(SEPARATOR)
        if (separatorAt < 0) {
            throw malformed(
                origin,
                "there is no '__' in \"$name\". A name is V<version>__<description> or R__<description>, " +
                    "as in V2__add_indexes or R__rebuild_views."
            )
        }

        val description = name.substring(separatorAt + SEPARATOR.length).replace('_', ' ').trim()
        if (description.isEmpty()) {
            throw malformed(origin, "there is nothing after '__' in \"$name\"; a migration needs a description.")
        }

        return when (val prefix = name.first()) {
            'V' -> {
                val versionText = name.substring(1, separatorAt)
                if (versionText.isEmpty()) {
                    throw malformed(
                        origin,
                        "there is nothing between 'V' and '__' in \"$name\"; a versioned migration needs a " +
                            "version. Use 'R__' for one that has none."
                    )
                }
                ParsedName(MigrationVersion.parse(versionText, origin), description)
            }

            'R' -> {
                if (separatorAt != 1) {
                    throw malformed(
                        origin,
                        "\"$name\" puts something between 'R' and '__', and a repeatable migration carries no " +
                            "version. Drop it, or start the name with 'V' if it has one."
                    )
                }
                ParsedName(null, description)
            }

            else -> throw malformed(
                origin,
                "\"$name\" starts with '$prefix'. A migration starts with 'V' when it has a version, or 'R' " +
                    "when it is repeatable - both capital, both checked exactly."
            )
        }
    }

    private fun malformed(origin: String, problem: String) =
        MigrationException(MigrationExceptionReason.INVALID_MIGRATION, "$origin: $problem")
}
