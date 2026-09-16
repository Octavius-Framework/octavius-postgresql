package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * Where the type catalogs live: one per physical database (host, port, database name), for the whole JVM.
 *
 * In standard JDBC environments, connection pools (like HikariCP) use URLs to identify different databases.
 * Keying on the database itself rather than on the URL is what keeps the parts that do not affect the catalog -
 * credentials, SSL settings, timeouts - from fragmenting it, so every session against one database reads the
 * same types and the same registrations however it was configured to get there.
 */
object GlobalCatalogStore {
    /**
     * The catalog holder of every database this JVM has connected to.
     */
    private val holders = ConcurrentHashMap<DatabaseKey, CatalogHolder>()

    /**
     * Retrieves or creates the catalog holder for the specified database.
     * This method is internal to the driver.
     */
    internal fun holderFor(key: DatabaseKey): CatalogHolder {
        return holders.computeIfAbsent(key) { CatalogHolder() }
    }

    /**
     * Ensures that the database's types have been read into its catalog.
     * This method is internal to the driver.
     *
     * The line this writes names the dictionary a **R**elational-**O**bject **M**apping **E**ngine, which is
     * the accurate description as well as the joke: a catalog read onto Kotlin types is the whole of what
     * Octavius maps, with no session tracked, nothing lazy-loaded and nothing dirty-checked either side of it.
     * Which is to say it is not an ORM - the letters only look that way from the other end.
     */
    internal fun ensureLoaded(key: DatabaseKey, executor: QueryExecutor) {
        val holder = holderFor(key)
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
     * Explicitly re-reads the database's types into its catalog.
     * This is internally invoked by driver connection mechanisms when a schema refresh is requested.
     */
    internal fun reload(key: DatabaseKey, executor: QueryExecutor) {
        val holder = holderFor(key)
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
     * Drops a database's catalog to prevent memory leaks if a connection (pool) pointing to dynamic URLs is
     * closed.
     *
     * In most standard backend applications, connection URLs are static and catalogs should not be dropped.
     * However, if your application dynamically connects to and disconnects from thousands of different
     * databases at runtime, you should call this method upon closing the data source to allow the JVM Garbage
     * Collector to free the catalog.
     *
     * @param url The JDBC connection URL of the database environment.
     */
    fun removeCatalog(url: String) {
        holders.remove(DatabaseKey.from(OctaviusProperties.parse(url)))
    }
}
