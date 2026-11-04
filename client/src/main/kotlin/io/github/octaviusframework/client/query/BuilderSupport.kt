package io.github.octaviusframework.client.query

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * Refuses a query the builder cannot render, naming what is missing, as `INVALID_ARGUMENT` - which the result
 * style throws rather than returns.
 */
internal inline fun requireBuildable(value: Boolean, message: () -> String) {
    if (!value) {
        throw InvalidOperationException(InvalidOperationExceptionReason.INVALID_ARGUMENT, details = message())
    }
}

/**
 * The `WITH` clause every builder carries. A field rather than a base class, so `with` and `recursive` return
 * each builder's own type.
 */
internal class CteClause {

    private val entries = LinkedHashMap<String, String>()
    var recursive: Boolean = false

    /** Adds one common table expression; [query] is its body without the parentheses. */
    fun add(name: String, query: String) {
        requireBuildable(name.isNotBlank()) { "A common table expression needs a name." }
        requireBuildable(query.isNotBlank()) { "The common table expression '$name' has no query." }
        entries[name] = query
    }

    /** Copies [other]'s contents into this clause, so a builder's `copy()` shares no list with its source. */
    fun copyFrom(other: CteClause) {
        entries.putAll(other.entries)
        recursive = other.recursive
    }

    /** Renders the clause with its trailing newline, or nothing at all where no CTE was added. */
    fun render(): String {
        if (entries.isEmpty()) return ""
        return buildString {
            append("WITH ")
            if (recursive) append("RECURSIVE ")
            entries.entries.joinTo(this, ",\n     ") { (name, query) -> "$name AS ($query)" }
            append("\n")
        }
    }
}

/** Appends a clause where its body carries anything, so `where(null)` and `where("")` leave no `WHERE`. */
internal fun StringBuilder.appendClause(keyword: String, body: String?) {
    if (!body.isNullOrBlank()) {
        append('\n').append(keyword).append(' ').append(body)
    }
}
