package io.github.octaviusframework.migrations.discovery

import io.github.octaviusframework.migrations.MigrationException
import io.github.octaviusframework.migrations.MigrationExceptionReason

/**
 * Pastes configured values into a `.sql` migration, before anything else reads it.
 *
 * A paste and nothing more: the value goes in as the text it is, with no quoting, no escaping and no idea of
 * where in the statement it landed. That is the whole of what this does, and it is why the values belong in
 * the deployment rather than in a request - a placeholder is not a parameter, and a value that came from
 * outside the deployment would be an injection with the migrator holding the door.
 *
 * The syntax is `${name}`, fixed like the `V`, `R` and `__` of the naming rule. `\${name}` is that text and not
 * a placeholder, for the file that has to store one.
 *
 * Substitution happens **before** the header directives are read and before the transaction-control check
 * runs, so what those checks see is what the server will see - a value carrying a `COMMIT` is refused rather
 * than smuggled past them. It happens in one pass, so a value that itself contains `${...}` is left standing
 * as the text it is: one file, one round of substitution, and no value can reach back for another.
 */
internal object MigrationPlaceholders {

    /** `${name}`, with the backslash that turns it back into ordinary text captured in front of it. */
    private val PLACEHOLDER = Regex($$"""(\\?)\$\{([^{}]*)}""")

    /**
     * [content] with every `${name}` replaced by its configured value.
     *
     * With [placeholders] empty nothing is scanned and the content comes back as it was, `${` included.
     *
     * @param origin What to call this file in a refusal - the path it was found at.
     * @throws MigrationException `INVALID_MIGRATION` for a `${name}` no value was configured for. Refused
     * rather than left standing: outside a string literal the server would have refused it anyway, and
     * inside one - `INSERT INTO settings VALUES ('${tenant}')` - it would be stored exactly as written and
     * nothing would ever say so.
     */
    fun resolve(content: String, origin: String, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return content

        return PLACEHOLDER.replace(content) { match ->
            val name = match.groupValues[2]
            when {
                match.groupValues[1].isNotEmpty() -> "\${$name}"
                else -> placeholders[name]
                    ?: throw unknown(content, origin, name, match.range.first, placeholders.keys)
            }
        }
    }

    private fun unknown(content: String, origin: String, name: String, at: Int, configured: Set<String>) =
        MigrationException(
            MigrationExceptionReason.INVALID_MIGRATION,
            "$origin: line ${lineOf(content, at)} uses \${$name}, and no value is configured for it. " +
                "Configured here: ${configured.sorted().joinToString(", ")}. If the file means to hold that " +
                "text rather than a placeholder, write \\\${$name}."
        )

    /** Which line of the file an offset falls on, counting from one - a position somebody can navigate to. */
    private fun lineOf(content: String, offset: Int): Int =
        content.take(offset).count { it == '\n' } + 1
}
