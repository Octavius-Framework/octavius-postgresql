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
 * A query with named parameters, `@name`, whose values are given by name without the `@`, as a `Map` or as
 * `Pair`s. A repeated name is one parameter. An `@` in a literal, a quoted identifier, a comment, a
 * dollar-quoted body or an `@`-operator is not a parameter.
 *
 * A name the statement uses but the values omit raises [InvalidOperationException] `MISSING_NAMED_PARAMETER`;
 * a value the statement does not use is ignored.
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

    /** Runs the query and returns every row. */
    fun fetchRows(params: Map<String, Any?>): List<Row> {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            queryExecutor.query(transformedSql, listParams, execution.parameterSerializer, execution.resultMapper)
        }
    }

    /** Same as [fetchRows], with the values given as `Pair`s. */
    fun fetchRows(vararg params: Pair<String, Any?>): List<Row> = fetchRows(params.toMap())

    /**
     * Runs the query and returns its one row, or `null` when nothing matched. At most two rows are fetched.
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
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
     * Runs the query and returns its one row.
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` unless exactly one row matched.
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
     * Hands each row to [block] as it arrives, keeping none. The server sends [fetchSize] rows at a time, or all
     * of them under `0`. The iteration is one running statement: `statement_timeout` covers [block], and [block]
     * must not query this session.
     *
     * @throws MappingException `BLOCK_FAILED` wrapping anything [block] throws that is not an [OctaviusException].
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
     * Runs the query and maps every row onto [T]: a data class, a `Map<String, Any?>`, or whatever a registered
     * converter produces.
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
     * Runs the query and maps its one row onto [T], or returns `null` when nothing matched.
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
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
     * Runs the query and maps its one row onto [T].
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` unless exactly one row matched.
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

    /** [forEachRow] with each row mapped onto [T]. */
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
     * Runs the query and returns the first column of every row as [T].
     *
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
     * Runs the query and returns the first column of its one row as [T]. Under a nullable [T] no row and a
     * `NULL` both come back as `null`.
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` if more than one row matched.
     * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if [T] is not nullable and no row matched or the
     *   value was `NULL`.
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

    /** Same as [fetchField], with the values given as `Pair`s. */
    inline fun <reified T> fetchField(vararg params: Pair<String, Any?>): T = fetchField(params.toMap())

    /**
     * Runs the query and returns the first column of its one row as [T]. Under a nullable [T] a `NULL` comes back as
     * `null`.
     *
     * @throws InvalidOperationException `INCORRECT_RESULT_SIZE` unless exactly one row matched.
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

    /** [forEachRow] with the first column of each row as [T]. */
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
     * Runs a statement that changes rows without returning any, and returns how many it changed. One with
     * `RETURNING` goes through the `fetch*` terminals.
     *
     * @throws InvalidOperationException `UNEXPECTED_RESULT` if the statement returned rows.
     */
    fun executeUpdate(params: Map<String, Any?>): Long {
        val execution = beginExecution()
        return withPreparedQuery(params) { transformedSql, listParams ->
            queryExecutor.executeUpdate(transformedSql, listParams, execution.parameterSerializer)
        }
    }

    /** Same as [executeUpdate], with the values given as `Pair`s. */
    fun executeUpdate(vararg params: Pair<String, Any?>): Long = executeUpdate(params.toMap())
}
