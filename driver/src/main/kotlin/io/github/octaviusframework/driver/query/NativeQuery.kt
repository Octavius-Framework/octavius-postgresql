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

/** A query with positional parameters, `$1`, `$2`, bound in the order the values are given. The SQL goes as written. */
class NativeQuery internal constructor(
    sql: String,
    queryExecutor: QueryExecutor,
    typeManager: TypeManager
) : Query<NativeQuery>(sql, queryExecutor, typeManager) {

    /** Runs [block] with a [QueryContext] keying the values by their `$n`. */
    internal inline fun <R> withPositionalContext(params: Array<out Any?>, block: () -> R): R =
        withQueryContext(
            sql,
            { params.mapIndexed { i, p -> (i + 1).toString() to p }.toMap() },
            { sql },
            { params.toList() },
            block
        )

    //--------------------------------------------Row-based Methods-----------------------------------------------------

    /** Runs the query and returns every row. */
    fun fetchRows(vararg params: Any?): List<Row> {
        val execution = beginExecution()
        return withPositionalContext(params) {
            queryExecutor.query(sql, params, execution.parameterSerializer, execution.resultMapper)
        }
    }

    /**
     * Runs the query and returns its one row, or `null` when nothing matched. At most two rows are fetched.
     *
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
     * Runs the query and returns its one row.
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` unless exactly one row matched.
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
     * Hands each row to [block] as it arrives, keeping none. The server sends [fetchSize] rows at a time, or all
     * of them under `0`. The iteration is one running statement: `statement_timeout` covers [block], and [block]
     * must not query this session.
     *
     * @throws MappingException `BLOCK_FAILED` wrapping anything [block] throws that is not an [OctaviusException].
     */
    fun forEachRow(vararg params: Any?, fetchSize: Int, block: (Row) -> Unit) {
        val execution = beginExecution()
        withPositionalContext(params) {
            queryExecutor.queryForEach(sql, params, execution.parameterSerializer, execution.resultMapper, fetchSize, { it }, block)
        }
    }

    //----------------------------------------Object Mapping Methods----------------------------------------------------

    /**
     * Runs the query and maps every row onto [T]: a data class, a `Map<String, Any?>`, or whatever a registered
     * converter produces.
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
     * Runs the query and maps its one row onto [T], or returns `null` when nothing matched.
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
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
     * Runs the query and maps its one row onto [T].
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` unless exactly one row matched.
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

    /** [forEachRow] with each row mapped onto [T]. */
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
     * Runs the query and returns the first column of every row as [T].
     *
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
     * Runs the query and returns the first column of its one row as [T]. Under a nullable [T] no row and a
     * `NULL` both come back as `null`.
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
     * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if [T] is not nullable and no row matched or the
     *   value was `NULL`.
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
            // No row is the same absence as a NULL.
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
     * Runs the query and returns the first column of its one row as [T]. Under a nullable [T] a `NULL` comes back as
     * `null`.
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` unless exactly one row matched.
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

    /** [forEachRow] with the first column of each row as [T]. */
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
     * Runs a statement that changes rows without returning any, and returns how many it changed. One with
     * `RETURNING` goes through the `fetch*` terminals.
     *
     * @throws InvalidOperationException `UNEXPECTED_RESULT` if the statement returned rows.
     */
    fun executeUpdate(vararg params: Any?): Long {
        val execution = beginExecution()
        return withPositionalContext(params) {
            queryExecutor.executeUpdate(sql, params, execution.parameterSerializer)
        }
    }
}
