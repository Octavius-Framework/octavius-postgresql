package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.parser.SqlParameterParser

import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.registry.TypeManager
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Represents a query that accepts named parameters (e.g., `@name`, `@id`).
 *
 * `NamedParameterQuery` parses the SQL to extract named parameters and maps them to positional
 * parameters before delegating execution to the underlying [QueryExecutor].
 * It provides various execution methods to fetch rows, map results to Kotlin objects,
 * extract single fields, or perform data modifications (updates).
 *
 * A name repeated in the statement collapses to a single placeholder, so its value is supplied once.
 * The parser leaves the rest of the statement alone: an `@` inside a string literal, a quoted identifier,
 * a comment or a `$$ … $$` block is not a parameter, and neither are PostgreSQL's `@`-operators.
 *
 * Every method here takes values either as a `Map` or as `Pair`s; the `vararg` forms simply build the map.
 * A name the statement uses but the values omit raises
 * [InvalidOperationException] `MISSING_NAMED_PARAMETER`; an extra value the statement does not use is ignored.
 */
class NamedParameterQuery internal constructor(
    sql: String,
    queryExecutor: QueryExecutor,
    typeManager: TypeManager
) : Query<NamedParameterQuery>(sql, queryExecutor, typeManager) {

    internal fun prepareNamedQuery(params: Map<String, Any?>): Pair<String, Array<out Any?>> {
        val parsed = SqlParameterParser.parse(sql)
        val paramNames = parsed.paramNames
        val arrayParams = Array(paramNames.size) { i ->
            val name = paramNames[i]
            if (!params.containsKey(name)) {
                throw InvalidOperationException(InvalidOperationExceptionReason.MISSING_NAMED_PARAMETER, "Missing parameter: $name")
            }
            params[name]
        }
        return Pair(parsed.transformedSql, arrayParams)
    }

    internal inline fun <R> withPreparedQuery(
        params: Map<String, Any?>,
        block: (String, Array<out Any?>) -> R
    ): R {
        var transformedSql: String? = null
        var listParams: Array<out Any?>? = null
        return withQueryContext(sql, { params }, { transformedSql }, { listParams?.toList() }) {
            val (tSql, lParams) = prepareNamedQuery(params)
            transformedSql = tSql
            listParams = lParams
            block(tSql, lParams)
        }
    }

    //--------------------------------------------Row-based Methods-----------------------------------------------------

    /**
     * Executes the query and returns every row, undecoded into any particular shape.
     *
     * @param params Values by parameter name, without the leading `@`.
     * @return All matching rows; empty when nothing matched.
     * @throws InvalidOperationException `MISSING_NAMED_PARAMETER` if the statement names a parameter [params] omits.
     */
    fun fetchRows(params: Map<String, Any?>): List<Row> {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper)
        }
    }

    /** Same as [fetchRows], with the values given as `Pair`s. */
    fun fetchRows(vararg params: Pair<String, Any?>): List<Row> = fetchRows(params.toMap())

    /**
     * Executes the query and returns its single row, or `null` when nothing matched.
     *
     * At most two rows are requested, so a query that accidentally matches a million does not cross
     * the wire before failing.
     *
     * @param params Values by parameter name, without the leading `@`.
     * @return The single row, or `null` if there were none.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched,
     *   `MISSING_NAMED_PARAMETER` if the statement names a parameter [params] omits.
     */
    fun fetchRow(params: Map<String, Any?>): Row? {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            val rows = queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper, maxRows = 2)
            if (rows.size > 1) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 0 or 1, got at least 2 rows."
            )
            rows.firstOrNull()
        }
    }

    /** Same as [fetchRow], with the values given as `Pair`s. */
    fun fetchRow(vararg params: Pair<String, Any?>): Row? = fetchRow(params.toMap())

    /**
     * Executes the query and returns its single row, requiring exactly one.
     *
     * @param params Values by parameter name, without the leading `@`.
     * @return The single row.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if no row or more than one row matched,
     *   `MISSING_NAMED_PARAMETER` if the statement names a parameter [params] omits.
     */
    fun fetchRowStrict(params: Map<String, Any?>): Row {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            val rows = queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper, maxRows = 2)
            if (rows.isEmpty()) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 1, got 0 rows."
            )
            if (rows.size > 1) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 1, got at least 2 rows."
            )
            rows.first()
        }
    }

    /** Same as [fetchRowStrict], with the values given as `Pair`s. */
    fun fetchRowStrict(vararg params: Pair<String, Any?>): Row = fetchRowStrict(params.toMap())

    /**
     * Streams the result, handing each row to [block] as it arrives.
     *
     * Rows reach [block] one at a time and none are kept, so memory stays flat however large the result
     * is; [fetchSize] governs the other side of it - how many rows the server sends before pausing for
     * the next batch, or the whole result at once under `0`. The whole iteration is one running
     * statement: `statement_timeout` covers time spent inside [block], and [block] must not issue
     * another query on this same session.
     *
     * @param params Values by parameter name, without the leading `@`.
     * @param fetchSize Rows per batch, or `0` for the whole result in one. Required — there is no default.
     * @param block Invoked once per row, on the calling thread.
     * @throws MappingException `BLOCK_FAILED` wrapping anything [block] throws that is not an
     *   [OctaviusException], since the result has to be drained before it can be rethrown.
     */
    fun forEachRow(params: Map<String, Any?>, fetchSize: Int, block: (Row) -> Unit) {
        val execution = beginExecution()
        withPreparedQuery(params) { transformedSql, listParams ->
            queryExecutor.queryForEach(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper, fetchSize, { it }, block)
        }
    }

    /** Same as [forEachRow], with the values given as `Pair`s. */
    fun forEachRow(vararg params: Pair<String, Any?>, fetchSize: Int, block: (Row) -> Unit) = forEachRow(params.toMap(), fetchSize, block)

    //----------------------------------------Object Mapping Methods----------------------------------------------------

    /**
     * Executes the query and maps every row onto [T].
     *
     * Each row is treated as an anonymous record and handed to the result converters, so [T] can be a
     * data class, a `Map<String, Any?>`, or anything a registered converter produces.
     *
     * @param T The type each row is mapped to.
     * @param params Values by parameter name, without the leading `@`.
     * @return All matching rows, mapped; empty when nothing matched.
     * @throws MappingException if a row cannot be mapped onto [T].
     */
    inline fun <reified T : Any> fetchObjects(params: Map<String, Any?>): List<T> =
        fetchObjectsOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T : Any> fetchObjectsOf(params: Map<String, Any?>, targetType: KType): List<T> {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper) {
                execution.resultMapper.deserialize(it, targetType, PgType.Record)
            }
        }
    }

    /** Same as [fetchObjects], with the values given as `Pair`s. */
    inline fun <reified T : Any> fetchObjects(vararg params: Pair<String, Any?>): List<T> = fetchObjects(params.toMap())

    /**
     * Executes the query and maps its single row onto [T], or returns `null` when nothing matched.
     *
     * @param T The type the row is mapped to.
     * @param params Values by parameter name, without the leading `@`.
     * @return The mapped row, or `null` if there were none.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
     * @throws MappingException if the row cannot be mapped onto [T].
     */
    inline fun <reified T: Any> fetchObject(params: Map<String, Any?>): T? =
        fetchObjectOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T : Any> fetchObjectOf(params: Map<String, Any?>, targetType: KType): T? {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            val rows = queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper, maxRows = 2) {
                execution.resultMapper.deserialize<T>(it, targetType, PgType.Record)
            }
            if (rows.size > 1) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 0 or 1, got at least 2 rows."
            )
            rows.firstOrNull()
        }
    }

    /** Same as [fetchObject], with the values given as `Pair`s. */
    inline fun <reified T: Any> fetchObject(vararg params: Pair<String, Any?>): T? = fetchObject(params.toMap())

    /**
     * Executes the query and maps its single row onto [T], requiring exactly one row.
     *
     * @param T The type the row is mapped to.
     * @param params Values by parameter name, without the leading `@`.
     * @return The mapped row.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if no row or more than one row matched.
     * @throws MappingException if the row cannot be mapped onto [T].
     */
    inline fun <reified T : Any> fetchObjectStrict(params: Map<String, Any?>): T =
        fetchObjectStrictOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T : Any> fetchObjectStrictOf(params: Map<String, Any?>, targetType: KType): T {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            val rows = queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper, maxRows = 2) {
                execution.resultMapper.deserialize<T>(it, targetType, PgType.Record)
            }
            if (rows.size > 1) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 1, got at least 2 rows."
            )
            if (rows.isEmpty()) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 1, got 0 rows."
            )
            rows.first()
        }
    }

    /** Same as [fetchObjectStrict], with the values given as `Pair`s. */
    inline fun <reified T: Any> fetchObjectStrict(vararg params: Pair<String, Any?>): T = fetchObjectStrict(params.toMap())

    /**
     * Streams the result, mapping each row onto [T] and handing it to [block].
     *
     * Carries the same constraints as [forEachRow]: batches of [fetchSize], one running statement for the
     * whole iteration, and no re-entering this session from [block].
     *
     * @param T The type each row is mapped to.
     * @param params Values by parameter name, without the leading `@`.
     * @param fetchSize Rows per batch, or `0` for the whole result in one. Required — there is no default.
     * @param block Invoked once per mapped row, on the calling thread.
     * @throws MappingException if a row cannot be mapped onto [T], or wrapping anything [block] throws
     *   that is not an [OctaviusException].
     */
    inline fun <reified T : Any> forEachObject(params: Map<String, Any?>, fetchSize: Int, noinline block: (T) -> Unit) =
        forEachObjectOf(params, fetchSize, typeOf<T>(), block)

    @PublishedApi
    internal fun <T : Any> forEachObjectOf(params: Map<String, Any?>, fetchSize: Int, targetType: KType, block: (T) -> Unit) {
        val execution = beginExecution()
        withPreparedQuery(params) { transformedSql, listParams ->
            queryExecutor.queryForEach(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper, fetchSize, {
                execution.resultMapper.deserialize<T>(it, targetType, PgType.Record)
            }, block)
        }
    }

    /** Same as [forEachObject], with the values given as `Pair`s. */
    inline fun <reified T : Any> forEachObject(vararg params: Pair<String, Any?>, fetchSize: Int, noinline block: (T) -> Unit) = forEachObject(params.toMap(), fetchSize, block)

    //-----------------------------------------Single Column Methods----------------------------------------------------

    /**
     * Executes the query and returns the **first column** of every row, mapped to [T].
     *
     * Declare [T] nullable when the column can be SQL `NULL` — `fetchFields<String?>()`.
     *
     * @param T The type the column is mapped to.
     * @param params Values by parameter name, without the leading `@`.
     * @return The first column of all matching rows; empty when nothing matched.
     * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if a value is `NULL` and [T] is not nullable.
     */
    inline fun <reified T> fetchFields(params: Map<String, Any?>): List<T> =
        fetchFieldsOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T> fetchFieldsOf(params: Map<String, Any?>, targetType: KType): List<T> {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper) { it.get(0, targetType) }
        }
    }

    /** Same as [fetchFields], with the values given as `Pair`s. */
    inline fun <reified T> fetchFields(vararg params: Pair<String, Any?>): List<T> = fetchFields(params.toMap())

    /**
     * Executes the query and returns the first column of its single row.
     *
     * **How you declare [T] states whether a value has to be there at all**, and it covers both ways one
     * can be absent: no row matched, or a row matched carrying SQL `NULL`. Under a non-nullable [T] both
     * raise `REQUIRED_ATTRIBUTE_MISSING`; under a nullable one both come back as `null`. Declare
     * `fetchField<String?>()` for a lookup that is allowed to find nothing.
     *
     * How *many* rows came back is a separate question, governed by the `Strict` suffix: this variant
     * tolerates none, [fetchFieldStrict] insists on exactly one, and both reject more than one.
     *
     * @param T The type the column is mapped to. Its nullability is the whole contract: [T] comes back
     *   as declared, so a non-nullable one is guaranteed non-null and never needs unwrapping.
     * @param params Values by parameter name, without the leading `@`.
     * @return The value, which is `null` only where [T] is itself nullable.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
     * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if [T] is not nullable and no row matched, or
     *   the value was `NULL`.
     */
    inline fun <reified T> fetchField(params: Map<String, Any?>): T =
        fetchFieldOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T> fetchFieldOf(params: Map<String, Any?>, targetType: KType): T {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            val rows = queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper, maxRows = 2) {
                it.get<T>(
                    0,
                    targetType
                )
            }
            if (rows.size > 1) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 0 or 1, got at least 2 rows."
            )
            // A missing row and a NULL value are the same absence as far as the caller's type is
            // concerned: asking for a non-nullable T is asking for a value, and there is none.
            if (rows.isEmpty() && !targetType.isMarkedNullable) throw MappingException(
                MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING,
                details = "No rows returned, and the requested type $targetType is not nullable. " +
                        "Declare it nullable to receive null when nothing matched."
            )
            // Empty here means T is nullable, checked just above; a present row already holds a T.
            @Suppress("UNCHECKED_CAST")
            rows.firstOrNull() as T
        }
    }

    /** Same as [fetchField], with the values given as `Pair`s. */
    inline fun <reified T> fetchField(vararg params: Pair<String, Any?>): T = fetchField(params.toMap())

    /**
     * Executes the query and returns the first column of its single row, requiring exactly one row.
     *
     * `Strict` governs how many rows came back, not whether the value is `NULL`: a `NULL` under a nullable
     * [T] is still returned as `null` here.
     *
     * @param T The type the column is mapped to.
     * @param params Values by parameter name, without the leading `@`.
     * @return The value.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if no row or more than one row matched.
     * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if the value is `NULL` and [T] is not nullable.
     */
    inline fun <reified T> fetchFieldStrict(params: Map<String, Any?>): T =
        fetchFieldStrictOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T> fetchFieldStrictOf(params: Map<String, Any?>, targetType: KType): T {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            val rows = queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper, maxRows = 2) {
                it.get<T>(
                    0,
                    targetType
                )
            }
            if (rows.isEmpty()) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 1, got 0 rows."
            )
            if (rows.size > 1) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 1, got at least two rows."
            )
            rows.first()
        }
    }

    /** Same as [fetchFieldStrict], with the values given as `Pair`s. */
    inline fun <reified T> fetchFieldStrict(vararg params: Pair<String, Any?>): T = fetchFieldStrict(params.toMap())

    /**
     * Streams the result, handing the first column of each row to [block] as [T].
     *
     * Carries the same constraints as [forEachRow].
     *
     * @param T The type the column is mapped to.
     * @param params Values by parameter name, without the leading `@`.
     * @param fetchSize Rows per batch, or `0` for the whole result in one. Required — there is no default.
     * @param block Invoked once per value, on the calling thread.
     * @throws MappingException if a value cannot be mapped to [T], or wrapping anything [block] throws
     *   that is not an [OctaviusException].
     */
    inline fun <reified T> forEachField(params: Map<String, Any?>, fetchSize: Int, noinline block: (T) -> Unit) =
        forEachFieldOf(params, fetchSize, typeOf<T>(), block)

    @PublishedApi
    internal fun <T> forEachFieldOf(params: Map<String, Any?>, fetchSize: Int, targetType: KType, block: (T) -> Unit) {
        val execution = beginExecution()
        withPreparedQuery(params) { transformedSql, listParams ->
            queryExecutor.queryForEach(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper, fetchSize, {
                it.get<T>(0, targetType)
            }, block)
        }
    }

    /** Same as [forEachField], with the values given as `Pair`s. */
    inline fun <reified T> forEachField(vararg params: Pair<String, Any?>, fetchSize: Int, noinline block: (T) -> Unit) = forEachField(params.toMap(), fetchSize, block)

    //------------------------------------------Modification methods----------------------------------------------------

    /**
     * Executes a statement that changes rows without returning any — `INSERT`, `UPDATE`, `DELETE`.
     *
     * A statement with a `RETURNING` clause produces rows and belongs to the `fetch*` family instead.
     *
     * @param params Values by parameter name, without the leading `@`.
     * @return The number of rows affected.
     * @throws InvalidOperationException `UNEXPECTED_RESULT` if the statement returned rows.
     */
    fun update(params: Map<String, Any?>): Long {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            queryExecutor.update(transformedSql, listParams, execution.parameterSerializer)
        }
    }

    /** Same as [update], with the values given as `Pair`s. */
    fun update(vararg params: Pair<String, Any?>): Long = update(params.toMap())
}
