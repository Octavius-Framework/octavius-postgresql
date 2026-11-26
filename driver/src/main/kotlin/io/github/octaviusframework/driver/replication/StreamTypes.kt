package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.registry.CatalogHolder
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.registry.TypeLookup
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionImpl
import javax.sql.DataSource

/** The cluster and database a session reaches, to hold against those of a replication connection. */
internal class DatabaseIdentity(val systemIdentifier: Long, val database: String)

/**
 * The type catalog a pgoutput stream decodes with, and the sessions that read it where this JVM has not and that a new
 * slot's copy runs in. Each session is opened for that alone and closed after it.
 */
internal class StreamTypes private constructor(
    private val holder: CatalogHolder,
    private val sessions: () -> OctaviusSession,
    private val lookupOrder: List<String>
) {
    /** The stream's own converters for the reading under way, asked ahead of the catalog's; set as it starts. */
    var localConverters: List<ResultConverter<*, *>>? = null

    private var mapper: ResultMapper? = null
    private var mapperConverters: List<ResultConverter<*, *>>? = null

    /** A mapper over the current catalog and [localConverters], the same one until either changes. */
    fun mapper(): ResultMapper {
        val catalog = holder.catalog
        val local = localConverters
        mapper?.let { if (it.catalog === catalog && mapperConverters === local) return it }
        mapperConverters = local
        return ResultMapper(catalog, local, TypeLookup({ catalog }, { lookupOrder })).also { mapper = it }
    }

    /** A session on the catalog's database, for the caller to close. */
    fun session(): OctaviusSession = sessions()

    /**
     * Checks that the catalog describes every one of [oids]; it is not read again for them, as it is not for a query.
     *
     * @throws TypeException `TYPE_NOT_FOUND` if it does not.
     */
    fun requireDescribed(oids: IntArray) {
        val missing = oids.firstOrNull { !holder.catalog.dictionary.describes(it) } ?: return
        throw TypeException(
            TypeExceptionReason.TYPE_NOT_FOUND,
            oid = missing,
            details = "A published column's type is not in the catalog. One created after the catalog was read is in " +
                "it once reloadTypes() reads it again, and the next stream from the slot sends this transaction again; " +
                "one dropped after this change was written is gone for good, and no stream from the slot gets past it."
        )
    }

    companion object {
        /**
         * Through sessions on [properties], the replication connection's own, opened only to read the catalog where
         * this JVM has not read it yet, and for a new slot's copy.
         */
        fun direct(properties: OctaviusProperties, lookupOrder: List<String>): StreamTypes {
            val open = { getOctaviusSession(properties) }
            val holder = GlobalCatalogStore.holderFor(DatabaseKey.from(properties))
            // A connection reads the catalog as it opens.
            if (!holder.isLoaded) open().close()
            return StreamTypes(holder, open, lookupOrder)
        }

        /** Through sessions of [dataSource], with its catalog, and which database that catalog is of. */
        fun pooled(dataSource: DataSource, lookupOrder: List<String>): Pair<StreamTypes, DatabaseIdentity> =
            dataSource.getOctaviusSession().use { session ->
                val holder = (session as OctaviusSessionImpl).octaviusConnection.catalogHolder
                val row = session.createNativeQuery(
                    "SELECT system_identifier, current_database() FROM pg_catalog.pg_control_system()"
                ).fetchRows().single()
                StreamTypes(holder, { dataSource.getOctaviusSession() }, lookupOrder) to
                    DatabaseIdentity(row.get<Long>(0), row.get<String>(1))
            }
    }
}
