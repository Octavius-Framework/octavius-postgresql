package io.github.octaviusframework.driver.identifier

import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.StatementExceptionReason

/**
 * This string as a quoted identifier, its double quotes doubled.
 *
 * @throws StatementException if it holds a NUL character.
 */
fun String.quoteAsPgIdentifier(): String {
    // Always quoted, which also stops case folding. Capacity allows for some escaped quotes.
    val capacity = 2 + (this.length + 10) / 10 * 11

    return buildString(capacity) {
        append('"')
        for (c in this@quoteAsPgIdentifier) {
            if (c == '\u0000') {
                throw StatementException(
                    StatementExceptionReason.SYNTAX_ERROR,
                    details = "PostgreSQL identifiers cannot contain the NUL (\\0) character."
                )
            }
            if (c == '"') append('"')
            append(c)
        }
        append('"')
    }
}
