package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.properties.ServerAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one mutable thing in the type system: which [TypeCatalog] is the current one for a database.
 *
 * A catalog is a value and is replaced whole rather than edited, so something has to hold *which* value is
 * current, and that something cannot be the catalog. This is it, and it is deliberately the whole of it - it
 * does not know that a converter or a codec exists, only how to put one catalog in the place of another, and,
 * where the database is listed as several servers, which cluster those catalogs are read from.
 *
 * [lock] guards more than the swap, which is why it is a lock rather than a compare-and-set:
 * [GlobalCatalogStore.ensureLoaded] holds it across the round trip that reads the catalog out of the database,
 * so twenty connections opening at once read it once between them. A lock that spans that has to live on
 * something outlasting any single catalog, which is the second reason this class exists.
 */
internal class CatalogHolder {
    /**
     * Held across a catalog load as well as across a swap - see the note on the class.
     */
    val lock = ReentrantLock()

    /**
     * Whether the catalog has been read from the database yet, for the double-checked guard around that read.
     */
    @Volatile
    var isLoaded: Boolean = false

    /**
     * Everything the driver knows about this database, as one value. Read without a lock, from anywhere.
     */
    @Volatile
    var catalog: TypeCatalog = builtinCatalog()
        private set

    /**
     * The system identifier of the cluster this catalog belongs to, fixed by the first server [admit]ted. `null`
     * until then - and for good where the database is a single server, which is never asked.
     */
    @Volatile
    var systemIdentifier: Long? = null
        private set

    /** The servers [admit] has let in, so that each is asked once. */
    private val admitted: MutableSet<ServerAddress> = ConcurrentHashMap.newKeySet()

    /**
     * Puts the catalog [transform] derives in the place of the current one.
     *
     * It runs under [lock], which a query load elsewhere may be holding.
     */
    fun update(transform: (TypeCatalog) -> TypeCatalog) = lock.withLock {
        catalog = transform(catalog)
    }

    /** Whether [server] has been admitted already, and need not be asked again. */
    fun isAdmitted(server: ServerAddress): Boolean = server in admitted

    /**
     * Admits [server], which reported [identifier], if that is the cluster this catalog belongs to - which the first
     * server admitted decides.
     *
     * The catalog maps types by OID, and only a physical standby is sure to have its primary's: it replays the
     * primary's WAL, and PostgreSQL will not stream to a standby whose system identifier differs. So the identifier
     * tells a server this catalog describes from one it would mistranslate - a logical replica, another node of a
     * multi-master set, an unrelated server listed by mistake.
     *
     * Runs under [lock], so the first two servers asked cannot both decide.
     *
     * @return The identifier of the cluster this catalog belongs to - [identifier] itself when [server] is admitted.
     */
    fun admit(server: ServerAddress, identifier: Long): Long = lock.withLock {
        val cluster = systemIdentifier ?: identifier.also { systemIdentifier = it }
        if (cluster == identifier) admitted += server
        cluster
    }
}
