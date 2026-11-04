package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterMapper
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.execution.ParameterSerializer
import io.github.octaviusframework.driver.registry.TypeCatalog
import io.github.octaviusframework.driver.registry.TypeManager

/**
 * What every query shares: converters registered for it alone, and [execute].
 *
 * @param T The concrete query, returned for chaining.
 */
@Suppress("UNCHECKED_CAST")
abstract class Query<T : Query<T>> internal constructor(
    internal val sql: String,
    internal val queryExecutor: QueryExecutor,
    internal val typeManager: TypeManager
) {
    // Null until something is registered, so a query without converters of its own allocates no lists.
    private var localResultConverters: MutableList<ResultConverter<*, *>>? = null
    private var localParameterConverters: MutableList<ParameterConverter<*>>? = null

    /**
     * Registers [converter] for this query alone, ahead of the session's and of those registered before it. Rows
     * a terminal already returned are not affected.
     */
    fun registerResultConverter(converter: ResultConverter<*, *>): T {
        (localResultConverters ?: mutableListOf<ResultConverter<*, *>>().also { localResultConverters = it })
            .add(converter)
        return this as T
    }

    /** Registers [converter] for this query alone, ahead of the session's and of those registered before it. */
    fun registerParameterConverter(converter: ParameterConverter<*>): T {
        (localParameterConverters ?: mutableListOf<ParameterConverter<*>>().also { localParameterConverters = it })
            .add(converter)
        return this as T
    }

    /**
     * Pins the catalog this run reads and builds the mappers over it. Everything the run produces, `Row`s
     * included, reads that one catalog; a second run sees registrations made since. The query's converters are
     * copied, since the `Row`s outlive the run.
     */
    internal fun beginExecution(): Execution {
        val catalog = typeManager.catalog
        val pinned = typeManager.pinnedTo(catalog)
        return Execution(
            catalog = catalog,
            resultMapper = ResultMapper(catalog, localResultConverters?.toList(), pinned),
            parameterSerializer = ParameterSerializer(
                pinned,
                ParameterMapper(catalog, localParameterConverters?.toList(), pinned)
            )
        )
    }

    internal inline fun <R> withQueryContext(
        sql: String,
        crossinline paramsProvider: () -> Map<String, Any?>,
        crossinline dbSqlProvider: () -> String? = { null },
        crossinline dbParamsProvider: () -> List<Any?>? = { null },
        block: () -> R
    ): R {
        try {
            return block()
        } catch (e: OctaviusException) {
            if (e.queryContext == null) {
                e.queryContext = QueryContext(sql, paramsProvider(), dbSqlProvider(), dbParamsProvider())
            }
            throw e
        }
    }

    /**
     * Runs SQL through the Simple Query Protocol, with no parameters and no result: DDL, `SET`, or a `;`-separated
     * script in one round trip, which PostgreSQL runs in an implicit transaction. A placeholder in it reaches the
     * server as literal text.
     *
     * @param ignoreRows Whether a statement returning rows may pass, its rows discarded. Set it for a script
     * whose `SELECT`s are meant, such as a `pg_dump`.
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `UNEXPECTED_RESULT` if a
     * statement returned rows and [ignoreRows] is `false`.
     */
    fun execute(ignoreRows: Boolean = false) {
        withQueryContext(sql, { emptyMap() }) {
            queryExecutor.execute(sql, ignoreRows)
        }
    }
}

/** One run of a query and the catalog it reads, built by [Query.beginExecution] at the start of every terminal. */
internal class Execution(
    val catalog: TypeCatalog,
    val resultMapper: ResultMapper,
    val parameterSerializer: ParameterSerializer
)
