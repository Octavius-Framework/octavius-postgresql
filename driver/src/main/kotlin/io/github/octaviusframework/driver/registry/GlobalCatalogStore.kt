package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * The type catalogs of the JVM, one per database, keyed by its servers and name and not by the rest of the URL,
 * so credentials or timeouts do not split one database's catalog.
 */
object GlobalCatalogStore {
    /** The catalog holder of every database this JVM has connected to. */
    private val holders = ConcurrentHashMap<DatabaseKey, CatalogHolder>()

    /** The catalog holder of [key], created on first use. */
    internal fun holderFor(key: DatabaseKey): CatalogHolder {
        return holders.computeIfAbsent(key) { CatalogHolder() }
    }

    /**
     * Reads the database's types into [holder] unless they are there already, once however many connections open.
     * The line it logs calls the catalog a **R**elational-**O**bject **M**apping **E**ngine, which is all it is.
     */
    internal fun ensureLoaded(key: DatabaseKey, holder: CatalogHolder, executor: QueryExecutor) {
        if (holder.isLoaded) return

        // Only one thread at a time can enter this block for a given database
        holder.lock.withLock {
            if (holder.isLoaded) return
            logger.trace { "Thread ${Thread.currentThread().name} loading types from database for: $key..." }
            val startedAt = System.nanoTime()
            CatalogLoader.load(holder, executor)
            holder.isLoaded = true
            logger.info {
                "ROME (Relational-Object Mapping Engine) open for $key - " +
                    "${holder.catalog.dictionary.size} types read in ${(System.nanoTime() - startedAt) / 1_000_000}ms"
            }
        }
    }

    /**
     * Re-reads the database's types into [holder]. Where [removeCatalog] dropped it while the connection was open,
     * that connection alone sees the reload.
     */
    internal fun reload(key: DatabaseKey, holder: CatalogHolder, executor: QueryExecutor) {
        holder.lock.withLock {
            logger.trace { "Explicit reload of type dictionary for: $key..." }
            val startedAt = System.nanoTime()
            CatalogLoader.load(holder, executor)
            logger.info {
                "ROME rebuilt for $key - ${holder.catalog.dictionary.size} types re-read in ${(System.nanoTime() - startedAt) / 1_000_000}ms"
            }
        }
    }

    /**
     * Drops the catalog of the database [url] names, for an application that connects to many databases and is
     * done with this one. Connections still open keep the catalog they had; new ones read the types afresh, with
     * nothing registered.
     */
    fun removeCatalog(url: String) {
        holders.remove(DatabaseKey.from(OctaviusProperties.parse(url)))
    }
}
