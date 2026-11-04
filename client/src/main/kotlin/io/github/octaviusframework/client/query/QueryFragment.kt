package io.github.octaviusframework.client.query

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * A piece of SQL and the parameters it names, kept together so that a `WHERE` assembled at runtime cannot name a
 * parameter nobody supplied. [join] combines them.
 *
 * ```kotlin
 * val filter = listOfNotNull(
 *     name?.let { "cognomen ILIKE @name" withParam ("name" to "%$it%") },
 *     minRank?.let { "rank_order >= @minRank" withParam ("minRank" to it) },
 *     province?.let { "home_province = @province" withParam ("province" to it) }
 * ).join(" AND ")
 *
 * val senators = db.select("*").from("senate")
 *     .where(filter.sql)
 *     .fetchObjects<Senator>(filter.params)
 * ```
 *
 * `filter.sql` goes to the clause and `filter.params` to the terminal, since a builder never carries parameters.
 *
 * @property sql The SQL text. Empty where the fragment contributes nothing.
 * @property params The parameters the text names, without the leading `@`.
 */
class QueryFragment(val sql: String, val params: Map<String, Any?> = emptyMap()) {

    /** Whether this fragment contributes nothing, so a clause leaves itself out. */
    val isEmpty: Boolean get() = sql.isBlank()

    override fun toString(): String = sql
}

/** Pairs this SQL with one parameter it names, given without the leading `@`. */
infix fun String.withParam(param: Pair<String, Any?>): QueryFragment = QueryFragment(this, mapOf(param))

/** Pairs this SQL with the parameters it names, given without the leading `@`. */
infix fun String.withParams(params: Map<String, Any?>): QueryFragment = QueryFragment(this, params)

/**
 * Joins the fragments that carry anything, merging their parameters. Joining none gives an empty fragment,
 * without [prefix] and [postfix], which a clause leaves out.
 *
 * Each fragment is parenthesised, so `"a = 1 OR b = 2"` joined to `"c = 3"` with `" AND "` keeps its meaning.
 * Turn [addParenthesis] off only where every fragment is a single term.
 *
 * @param prefix Rendered in front where anything renders at all, such as `"WHERE "` for a hand-written query;
 * the builders supply their own keyword.
 * @throws InvalidOperationException `INVALID_ARGUMENT` where two fragments name the same parameter with
 * different values.
 */
fun List<QueryFragment>.join(
    separator: String,
    prefix: String = "",
    postfix: String = "",
    addParenthesis: Boolean = true
): QueryFragment {
    val present = filterNot { it.isEmpty }
    if (present.isEmpty()) return QueryFragment("")

    val params = LinkedHashMap<String, Any?>()
    for (fragment in present) {
        for ((name, value) in fragment.params) {
            if (params.containsKey(name) && params[name] != value) {
                throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "Two fragments being joined both name the parameter '$name' with different " +
                        "values; one would replace the other. Rename one of them."
                )
            }
            params[name] = value
        }
    }

    val sql = present.joinToString(separator, prefix, postfix) { if (addParenthesis) "(${it.sql})" else it.sql }
    return QueryFragment(sql, params)
}
