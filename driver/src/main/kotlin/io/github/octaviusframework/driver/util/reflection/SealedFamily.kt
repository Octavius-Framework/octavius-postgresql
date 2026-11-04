package io.github.octaviusframework.driver.util.reflection

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

private val sealedFamilies = ConcurrentHashMap<KClass<*>, Set<KClass<*>>>()

/**
 * This class and, where it is sealed, every class below it, through sealed classes only: what a converter under it
 * is indexed by, so a lookup by a value's own class is exact. Cached for good.
 */
internal fun KClass<*>.sealedFamily(): Set<KClass<*>> = sealedFamilies.getOrPut(this) {
    if (!isSealed) return@getOrPut setOf(this)
    val family = LinkedHashSet<KClass<*>>()
    val pending = ArrayDeque<KClass<*>>().apply { add(this@sealedFamily) }
    while (pending.isNotEmpty()) {
        val kClass = pending.removeFirst()
        // A class sealed under two parents is reached twice
        if (family.add(kClass) && kClass.isSealed) pending.addAll(kClass.sealedSubclasses)
    }
    family
}
