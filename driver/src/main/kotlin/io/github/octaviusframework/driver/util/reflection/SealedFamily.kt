package io.github.octaviusframework.driver.util.reflection

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

private val sealedFamilies = ConcurrentHashMap<KClass<*>, Set<KClass<*>>>()

/**
 * This class and, where it is sealed, every class below it - through sealed classes all the way down.
 *
 * A codec decodes to one concrete class, and a value is looked up by its own class: `PgInterval.Finite`, never
 * `PgInterval`. So what is registered under a sealed class is indexed under each of these, which keeps every
 * lookup an exact one. A class below one that is open rather than sealed cannot be listed, and is not.
 *
 * Cached for good, being asked again for every execution that carries converters of its own.
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
