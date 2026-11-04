package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.properties.ServerAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Which [TypeCatalog] is current for a database, and which cluster it was read from. [lock] is held across a
 * catalog load as well as a swap, so connections opening together read the catalog once.
 */
internal class CatalogHolder {
    /** Held across a catalog load as well as a swap. */
    val lock = ReentrantLock()

    /** Whether the catalog has been read from the database yet. */
    @Volatile
    var isLoaded: Boolean = false

    /** The current catalog, read without a lock. */
    @Volatile
    var catalog: TypeCatalog = builtinCatalog()
        private set

    /** The system identifier of the cluster, fixed by the first server [admit]ted; `null` for a single server. */
    @Volatile
    var systemIdentifier: Long? = null
        private set

    /** The servers [admit] has let in, so that each is asked once. */
    private val admitted: MutableSet<ServerAddress> = ConcurrentHashMap.newKeySet()

    /** Replaces the catalog with what [transform] derives from it, under [lock]. */
    fun update(transform: (TypeCatalog) -> TypeCatalog) = lock.withLock {
        catalog = transform(catalog)
    }

    /** Whether [server] has been admitted already, and need not be asked again. */
    fun isAdmitted(server: ServerAddress): Boolean = server in admitted

    /**
     * Admits [server] if [identifier] is this catalog's cluster, which the first server admitted decides: only a
     * physical standby shares its primary's OIDs.
     *
     * @return The catalog's cluster identifier, [identifier] itself when [server] is admitted.
     */
    fun admit(server: ServerAddress, identifier: Long): Long = lock.withLock {
        val cluster = systemIdentifier ?: identifier.also { systemIdentifier = it }
        if (cluster == identifier) admitted += server
        cluster
    }
}
