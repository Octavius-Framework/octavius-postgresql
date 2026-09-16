package io.github.octaviusframework.driver.registry

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one mutable thing in the type system: which [TypeCatalog] is the current one for a database.
 *
 * A catalog is a value and is replaced whole rather than edited, so something has to hold *which* value is
 * current, and that something cannot be the catalog. This is it, and it is deliberately the whole of it - it
 * does not know that a converter or a codec exists, only how to put one catalog in the place of another.
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
     * Puts the catalog [transform] derives in the place of the current one.
     *
     * It runs under [lock], which a query load elsewhere may be holding.
     */
    fun update(transform: (TypeCatalog) -> TypeCatalog) = lock.withLock {
        catalog = transform(catalog)
    }
}
