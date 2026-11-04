package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.util.formatDiagnosticValue

/**
 * The query a failure happened in, on
 * [OctaviusException.queryContext][io.github.octaviusframework.driver.exception.OctaviusException.queryContext].
 *
 * @property sql The statement as written.
 * @property parameters The values as supplied: by name, or by position as `"1"`, `"2"`.
 * @property dbSql The statement sent to the server, or `null` where the failure came first.
 * @property dbParameters The values in the order they were bound, or `null` where the failure came first.
 */
data class QueryContext(
    val sql: String,
    val parameters: Map<String, Any?>,
    val dbSql: String? = null,
    val dbParameters: List<Any?>? = null,
) {
    override fun toString(): String {
        val width = 80
        val line = "=".repeat(width)
        val thinLine = "-".repeat(width)

        // Through the shared formatter, so a large value does not reach the log whole.
        val paramsStr = if (parameters.isEmpty()) "none"
            else parameters.entries.joinToString("\n") { "${it.key} - ${formatDiagnosticValue(it.value)}" }
        val dbParamsStr = dbParameters
            ?.mapIndexed { index, value -> "${index + 1} - ${formatDiagnosticValue(value)}" }
            ?.joinToString("\n") ?: "none"

        return buildString {
            appendLine(line)
            appendLine("DATABASE EXECUTION CONTEXT")
            appendLine(line)

            appendLine("HIGH-LEVEL SQL:")
            appendLine(sql.trim())
            appendLine(thinLine)

            appendLine("PARAMETERS:")
            appendLine(paramsStr)

            if (dbSql != null) {
                appendLine(thinLine)
                appendLine("DATABASE-LEVEL SQL (SENT TO DB):")
                appendLine(dbSql.trim())
                appendLine(thinLine)

                appendLine("DATABASE-LEVEL PARAMETERS:")
                appendLine(dbParamsStr)
            }

            appendLine(line)
        }
    }
}