package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.execution.QueryExecutor

import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.registry.TypeManager
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Represents a query that uses standard PostgreSQL positional parameters (e.g., `$1`, `$2`).
 *
 * `NativeQuery` passes the provided SQL string and parameters directly to the underlying
 * [QueryExecutor] without any intermediate parsing or modification of the SQL statement.
 * It provides various execution methods to fetch rows, map results to Kotlin objects, 
 * extract single fields, or perform data modifications (updates).
 */
class NativeQuery internal constructor(
    sql: String,
    queryExecutor: QueryExecutor,
    typeManager: TypeManager
) : Query<NativeQuery>(sql, queryExecutor, typeManager) {

    /**
     * Names this query's parameters for a [QueryContext] and runs [block] under it.
     *
     * Positional parameters have no names to report, so they are keyed by the `$n` they were bound
     * to. The statement is its own transformed form and the values their own bound form - there is
     * no translation step here for the two to differ across, which is the whole difference from
     * [NamedParameterQuery][io.github.octaviusframework.driver.query.NamedParameterQuery], where
     * they do and the context carries both.
     */
    internal inline fun <R> withPositionalContext(params: Array<out Any?>, block: () -> R): R =
        withQueryContext(
            sql,
            { params.mapIndexed { i, p -> (i + 1).toString() to p }.toMap() },
            { sql },
            { params.toList() },
            block
        )

    //--------------------------------------------Row-based Methods-----------------------------------------------------

    /**
     * Executes the query and returns every row, undecoded into any particular shape.
     *
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return All matching rows; empty when nothing matched.
     */
    fun fetchRows(vararg params: Any?): List<Row> {
        val execution = beginExecution()
        return withPositionalContext(params) {
            queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper)
        }
    }

    /**
     * Executes the query and returns its single row, or `null` when nothing matched.
     *
     * At most two rows are requested, so a query that accidentally matches a million does not cross
     * the wire before failing.
     *
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return The single row, or `null` if there were none.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
     */
    fun fetchRow(vararg params: Any?): Row? {
        val execution = beginExecution()
        return withPositionalContext(params) {
            val rows = queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper, maxRows = 2)
            if (rows.size > 1) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 0 or 1, got at least 2 rows."
            )
            rows.firstOrNull()
        }
    }

    /**
     * Executes the query and returns its single row, requiring exactly one.
     *
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return The single row.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if no row or more than one row matched.
     */
    fun fetchRowStrict(vararg params: Any?): Row {
        val execution = beginExecution()
        return withPositionalContext(params) {
            val rows = queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper, maxRows = 2)
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

    /**
     * Streams the result, handing each row to [block] as it arrives.
     *
     * Rows reach [block] one at a time and none are kept, so memory stays flat however large the result
     * is; [fetchSize] governs the other side of it - how many rows the server sends before pausing for
     * the next batch, or the whole result at once under `0`. The whole iteration is one running
     * statement: `statement_timeout` covers time spent inside [block], and [block] must not issue
     * another query on this same session.
     *
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @param fetchSize Rows per batch, or `0` for the whole result in one. Required — there is no default.
     * @param block Invoked once per row, on the calling thread.
     * @throws MappingException `BLOCK_FAILED` wrapping anything [block] throws that is not an
     *   [OctaviusException], since the result has to be drained before it can be rethrown.
     */
    fun forEachRow(vararg params: Any?, fetchSize: Int, block: (Row) -> Unit) {
        val execution = beginExecution()
        withPositionalContext(params) {
            queryExecutor.queryForEach(sql, params, execution.parameterSerializer, execution.resultMapper, fetchSize, { it }, block)
        }
    }

    //----------------------------------------Object Mapping Methods----------------------------------------------------

    /**
     * Executes the query and maps every row onto [T].
     *
     * Each row is treated as an anonymous record and handed to the result converters, so [T] can be a
     * data class, a `Map<String, Any?>`, or anything a registered converter produces.
     *
     * @param T The type each row is mapped to.
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return All matching rows, mapped; empty when nothing matched.
     * @throws MappingException if a row cannot be mapped onto [T].
     */
    inline fun <reified T : Any> fetchObjects(vararg params: Any?): List<T> =
        fetchObjectsOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T : Any> fetchObjectsOf(params: Array<out Any?>, targetType: KType): List<T> {
        val execution = beginExecution()
        return withPositionalContext(params) {
            queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper) {
                execution.resultMapper.deserialize(it, targetType, PgType.Record)
            }
        }
    }

    /**
     * Executes the query and maps its single row onto [T], or returns `null` when nothing matched.
     *
     * @param T The type the row is mapped to.
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return The mapped row, or `null` if there were none.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
     * @throws MappingException if the row cannot be mapped onto [T].
     */
    inline fun <reified T : Any> fetchObject(vararg params: Any?): T? =
        fetchObjectOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T : Any> fetchObjectOf(params: Array<out Any?>, targetType: KType): T? {
        val execution = beginExecution()
        return withPositionalContext(params) {
            val rows = queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper, maxRows = 2) {
                execution.resultMapper.deserialize<T>(it, targetType, PgType.Record)
            }
            if (rows.size > 1) throw InvalidOperationException(
                InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE,
                details = "Expected 0 or 1, got at least 2 rows."
            )
            rows.firstOrNull()
        }
    }

    /**
     * Executes the query and maps its single row onto [T], requiring exactly one row.
     *
     * @param T The type the row is mapped to.
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return The mapped row.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if no row or more than one row matched.
     * @throws MappingException if the row cannot be mapped onto [T].
     */
    inline fun <reified T : Any> fetchObjectStrict(vararg params: Any?): T =
        fetchObjectStrictOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T : Any> fetchObjectStrictOf(params: Array<out Any?>, targetType: KType): T {
        val execution = beginExecution()
        return withPositionalContext(params) {
            val rows = queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper, maxRows = 2) {
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

    /**
     * Streams the result, mapping each row onto [T] and handing it to [block].
     *
     * Carries the same constraints as [forEachRow]: batches of [fetchSize], one running statement for the
     * whole iteration, and no re-entering this session from [block].
     *
     * @param T The type each row is mapped to.
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @param fetchSize Rows per batch, or `0` for the whole result in one. Required — there is no default.
     * @param block Invoked once per mapped row, on the calling thread.
     * @throws MappingException if a row cannot be mapped onto [T], or wrapping anything [block] throws
     *   that is not an [OctaviusException].
     */
    inline fun <reified T : Any> forEachObject(vararg params: Any?, fetchSize: Int, noinline block: (T) -> Unit) =
        forEachObjectOf(params, fetchSize, typeOf<T>(), block)

    @PublishedApi
    internal fun <T : Any> forEachObjectOf(params: Array<out Any?>, fetchSize: Int, targetType: KType, block: (T) -> Unit) {
        val execution = beginExecution()
        withPositionalContext(params) {
            queryExecutor.queryForEach(sql, params, execution.parameterSerializer, execution.resultMapper, fetchSize, {
                execution.resultMapper.deserialize<T>(it, targetType, PgType.Record)
            }, block)
        }
    }

    //-----------------------------------------Single Column Methods----------------------------------------------------

    /**
     * Executes the query and returns the **first column** of every row, mapped to [T].
     *
     * Declare [T] nullable when the column can be SQL `NULL` — `fetchFields<String?>()`.
     *
     * @param T The type the column is mapped to.
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return The first column of all matching rows; empty when nothing matched.
     * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if a value is `NULL` and [T] is not nullable.
     */
    inline fun <reified T> fetchFields(vararg params: Any?): List<T> =
        fetchFieldsOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T> fetchFieldsOf(params: Array<out Any?>, targetType: KType): List<T> {
        val execution = beginExecution()
        return withPositionalContext(params) {
            queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper) { it.get(0, targetType) }
        }
    }

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
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return The value, which is `null` only where [T] is itself nullable.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
     * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if [T] is not nullable and no row matched, or
     *   the value was `NULL`.
     */
    inline fun <reified T> fetchField(vararg params: Any?): T =
        fetchFieldOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T> fetchFieldOf(params: Array<out Any?>, targetType: KType): T {
        val execution = beginExecution()
        return withPositionalContext(params) {
            val rows = queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper, maxRows = 2) {
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

    /**
     * Executes the query and returns the first column of its single row, requiring exactly one row.
     *
     * `Strict` governs how many rows came back, not whether the value is `NULL`: a `NULL` under a nullable
     * [T] is still returned as `null` here.
     *
     * @param T The type the column is mapped to.
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return The value.
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if no row or more than one row matched.
     * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if the value is `NULL` and [T] is not nullable.
     */
    inline fun <reified T> fetchFieldStrict(vararg params: Any?): T =
        fetchFieldStrictOf(params, typeOf<T>())

    @PublishedApi
    internal fun <T> fetchFieldStrictOf(params: Array<out Any?>, targetType: KType): T {
        val execution = beginExecution()
        return withPositionalContext(params) {
            val rows = queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper, maxRows = 2) {
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
                details = "Expected 1, got at least 2 rows."
            )
            rows.first()
        }
    }

    /**
     * Streams the result, handing the first column of each row to [block] as [T].
     *
     * Carries the same constraints as [forEachRow].
     *
     * @param T The type the column is mapped to.
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @param fetchSize Rows per batch, or `0` for the whole result in one. Required — there is no default.
     * @param block Invoked once per value, on the calling thread.
     * @throws MappingException if a value cannot be mapped to [T], or wrapping anything [block] throws
     *   that is not an [OctaviusException].
     */
    inline fun <reified T> forEachField(vararg params: Any?, fetchSize: Int, noinline block: (T) -> Unit) =
        forEachFieldOf(params, fetchSize, typeOf<T>(), block)

    @PublishedApi
    internal fun <T> forEachFieldOf(params: Array<out Any?>, fetchSize: Int, targetType: KType, block: (T) -> Unit) {
        val execution = beginExecution()
        withPositionalContext(params) {
            queryExecutor.queryForEach(sql, params, execution.parameterSerializer, execution.resultMapper, fetchSize, {
                it.get<T>(0, targetType)
            }, block)
        }
    }

    //------------------------------------------Modification methods----------------------------------------------------

    /**
     * Executes a statement that changes rows without returning any — `INSERT`, `UPDATE`, `DELETE`.
     *
     * A statement with a `RETURNING` clause produces rows and belongs to the `fetch*` family instead.
     *
     * @param params Values for `$1`, `$2`, … in declaration order.
     * @return The number of rows affected.
     * @throws InvalidOperationException `UNEXPECTED_RESULT` if the statement returned rows.
     */
    fun update(vararg params: Any?): Long {
        val execution = beginExecution()
        return withPositionalContext(params) {
            queryExecutor.update(sql, params, execution.parameterSerializer)
        }
    }
}
