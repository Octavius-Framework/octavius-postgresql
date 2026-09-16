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
 * Base class for executing queries with parameters.
 *
 * This class provides the foundational state and utilities needed for managing
 * type conversion registries and mappings localized to a single query instance.
 *
 * A converter registered here is consulted before the session's and is discarded with the query, which is
 * what makes a one-off mapping possible without disturbing anything else on the connection.
 *
 * @param T The concrete type of the query (used for fluent API return types).
 * @property typeManager The session's type manager, resolving OIDs and publishing the catalog an execution pins.
 */
@Suppress("UNCHECKED_CAST")
abstract class Query<T : Query<T>> internal constructor(
    internal val sql: String,
    internal val queryExecutor: QueryExecutor,
    internal val typeManager: TypeManager
) {
    // Null until something is registered: a query is a thing an application builds per request, and an empty
    // list apiece would be two allocations on every one of them for a feature most never use.
    private var localResultConverters: MutableList<ResultConverter<*, *>>? = null
    private var localParameterConverters: MutableList<ParameterConverter<*>>? = null

    /**
     * Registers a [ResultConverter] for this query only, ahead of any the session already holds.
     *
     * Later registrations take priority over earlier ones. A converter registered after a terminal has run
     * applies to the next one, not to rows already returned - those were mapped against the catalog their
     * execution pinned.
     *
     * @param converter The converter to register.
     * @return This query, for chaining.
     */
    fun registerResultConverter(converter: ResultConverter<*, *>): T {
        (localResultConverters ?: mutableListOf<ResultConverter<*, *>>().also { localResultConverters = it })
            .add(converter)
        return this as T
    }

    /**
     * Registers a [ParameterConverter] for this query only, ahead of any the session already holds.
     *
     * Later registrations take priority over earlier ones.
     *
     * @param converter The converter to register.
     * @return This query, for chaining.
     */
    fun registerParameterConverter(converter: ParameterConverter<*>): T {
        (localParameterConverters ?: mutableListOf<ParameterConverter<*>>().also { localParameterConverters = it })
            .add(converter)
        return this as T
    }

    /**
     * Pins the catalog this execution will read, and builds the mappers over it.
     *
     * Taken here rather than when the query was constructed, because [registerResultConverter] and its write-side
     * twin run in between; and per execution rather than once, because a query object can be run again after a
     * registration or a `reloadTypes()`, and the second run should see them.
     *
     * Everything downstream - the parameter serializer, the result mapper, the `Row`s it produces, the type
     * lookup converters are handed - reads this one catalog, so a registration on another thread cannot land in
     * the middle of a result and leave it mapped against two of them.
     */
    internal fun beginExecution(): Execution {
        val catalog = typeManager.catalog
        val pinned = typeManager.pinnedTo(catalog)
        return Execution(
            catalog = catalog,
            resultMapper = ResultMapper(catalog, localResultConverters, pinned),
            parameterSerializer = ParameterSerializer(
                pinned,
                ParameterMapper(catalog, localParameterConverters, pinned)
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
     * Executes a statement with no result and no row count — DDL, `SET`, administrative commands.
     *
     * This method speaks the Simple Query Protocol and takes **no parameters at all**: the SQL is sent
     * exactly as written, so any placeholders (like `$1` or `@name`) in it reach the server as literal text
     * rather than being substituted. It accepts a whole script of statements separated by `;` in a single
     * round trip, which PostgreSQL wraps in an implicit transaction.
     *
     * @param ignoreRows Whether a statement returning rows may pass. Rows are discarded either way and there
     * is no reading them from here; what this decides is whether their arrival is reported. Left `false`,
     * `execute("SELECT …")` — a query sent where `fetchRows` was meant — is caught instead of quietly doing
     * nothing, which is worth keeping. Set it where the SQL is a script somebody else wrote and a `SELECT`
     * in it is legitimate: `pg_dump` emits `SELECT pg_catalog.setval(...)` for every sequence it carries.
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `UNEXPECTED_RESULT` if any statement in the SQL returned rows and [ignoreRows] is `false`.
     */
    fun execute(ignoreRows: Boolean = false) {
        withQueryContext(sql, { emptyMap() }) {
            queryExecutor.execute(sql, ignoreRows)
        }
    }
}

/**
 * One run of a query, and the catalog it reads.
 *
 * Built by [Query.beginExecution] at the start of every terminal and handed down through it, so that a result
 * is mapped, and its parameters encoded, against a catalog that does not move underneath them.
 *
 * @property catalog The catalog pinned for this run.
 * @property resultMapper Maps rows and columns against [catalog]; the `Row`s produced hold on to it.
 * @property parameterSerializer Encodes the bound values against [catalog].
 */
internal class Execution(
    val catalog: TypeCatalog,
    val resultMapper: ResultMapper,
    val parameterSerializer: ParameterSerializer
)
