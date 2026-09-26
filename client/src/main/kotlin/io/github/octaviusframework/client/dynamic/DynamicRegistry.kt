package io.github.octaviusframework.client.dynamic

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * The `dynamic_dto` names a database knows, and the class each one stands for.
 *
 * It is kept in the driver's type catalog, attached under its own class, rather than on the client that
 * registered it. A discriminator is stored in the data, so what it means is a property of the database: every
 * client on it has to read `land_grant` as the same class, and a converter on the catalog - which every client on
 * the database reaches - can only answer for names it can see. Kept in the catalog, the registry is also replaced
 * whole with it, dropped by `removeCatalog`, and pinned for an execution along with the converters reading it.
 */
internal class DynamicRegistry private constructor(
    private val byName: Map<String, Registration<*>>,
    private val byClass: Map<KClass<*>, Registration<*>>
) {
    /** The registration a stored discriminator names. */
    fun forName(name: String): Registration<*>? = byName[name]

    /** The registration for an exact class, which is how a value being written finds its own. */
    fun forClass(kClass: KClass<*>): Registration<*>? = byClass[kClass]

    /** Whether any registered class fits [kClass], which is what makes a supertype read work. */
    fun anyFits(kClass: KClass<*>): Boolean =
        kClass == Any::class || byClass.keys.any { it.isSubclassOf(kClass) }

    /**
     * This registry with [registration] in it.
     *
     * A name stands for one class and a class goes under one name, so taking either twice is refused. The same
     * class under the same name again is the registration it already is: it changes nothing, and in particular
     * not the [Json] the class was first registered with, which there is no telling apart from an equivalent
     * one built elsewhere. The write strategy can be told apart, and a second one is refused rather than left to
     * decide by which client registered first.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` where the name or the class is taken otherwise, or
     * the class is registered under another write strategy.
     */
    fun with(registration: Registration<*>): DynamicRegistry {
        val name = registration.name
        val kClass = registration.kClass

        val underName = byName[name]
        if (underName != null && underName.kClass != kClass) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "The dynamic type name '$name' is already registered for ${underName.kClass.simpleName}; " +
                    "${kClass.simpleName} cannot take it as well."
            )
        }

        val existing = byClass[kClass] ?: return DynamicRegistry(
            byName + (name to registration),
            byClass + (kClass to registration)
        )

        if (existing.name != name) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${kClass.simpleName} is already registered as the dynamic type '${existing.name}'; it " +
                    "cannot go under '$name' as well."
            )
        }
        if (existing.strategy != registration.strategy) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${kClass.simpleName} is already registered as '$name' by a client writing on " +
                    "${existing.strategy}, and this one writes on ${registration.strategy}. A class is registered " +
                    "once per database, so every client registering it has to be built on the same " +
                    "DynamicWriteStrategy."
            )
        }
        return this
    }

    companion object {
        val EMPTY = DynamicRegistry(emptyMap(), emptyMap())
    }
}

/**
 * One class registered as a `dynamic_dto`, on the terms of the client that registered it.
 *
 * The terms travel with the class rather than staying with that client, because the converters that apply them
 * belong to the database: a value of this class is written on [strategy] and its payload goes through [json]
 * whichever client the query runs through.
 *
 * @property name The discriminator, as the database stores it.
 * @property json The registering client's [Json], with the registered enums folded in as it is resolved.
 * @property strategy The registering client's write strategy.
 */
internal class Registration<T : Any>(
    val name: String,
    val kClass: KClass<T>,
    private val serializer: KSerializer<T>,
    val json: EnumAwareJson,
    val strategy: DynamicWriteStrategy
) {
    /**
     * The payload as the text a `jsonb` attribute is encoded from.
     *
     * `jsonb`'s codec encodes a `String`, so this is already what the wire wants and there is no tree to
     * build on the way there - which is why [DynamicDto] carries the text too, rather than making the
     * wrapped path pay for a structure the unwrapped one never builds.
     */
    @Suppress("UNCHECKED_CAST")
    fun encode(value: Any, json: Json): String = json.encodeToString(serializer, value as T)

    fun decode(payload: String, json: Json): Any = json.decodeFromString(serializer, payload)
}
