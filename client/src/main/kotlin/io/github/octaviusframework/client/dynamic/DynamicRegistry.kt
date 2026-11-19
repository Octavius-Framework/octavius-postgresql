package io.github.octaviusframework.client.dynamic

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * The `dynamic_dto` names a database knows and the class each stands for, attached to the driver's type catalog,
 * since a discriminator stored in the data has to mean the same class to every client on the database. It is
 * replaced and dropped with the catalog, and pinned for an execution with the converters reading it.
 */
internal class DynamicRegistry private constructor(
    private val byName: Map<String, Registration<*>>,
    private val byClass: Map<KClass<*>, Registration<*>>
) {
    /** The registration a stored discriminator names. */
    fun forName(name: String): Registration<*>? = byName[name]

    /** The registration for exactly [kClass]. */
    fun forClass(kClass: KClass<*>): Registration<*>? = byClass[kClass]

    /** Whether any registered class fits [kClass], so a read as a supertype works. */
    fun anyFits(kClass: KClass<*>): Boolean =
        kClass == Any::class || byClass.keys.any { it.isSubclassOf(kClass) }

    /**
     * This registry with [registration] in it. The same class under the same name again changes nothing, the
     * [Json] it was first registered with included.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` where the name or the class is taken otherwise.
     */
    fun with(registration: Registration<*>): DynamicRegistry {
        val name = registration.name
        val kClass = registration.kClass

        val underName = byName[name]
        if (underName != null && underName.kClass != kClass) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "The dynamic type name '$name' is already registered for ${underName.kClass.displayName}; " +
                    "${kClass.displayName} cannot take it as well."
            )
        }

        val existing = byClass[kClass] ?: return DynamicRegistry(
            byName + (name to registration),
            byClass + (kClass to registration)
        )

        if (existing.name != name) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${kClass.displayName} is already registered as the dynamic type '${existing.name}'; it " +
                    "cannot go under '$name' as well."
            )
        }
        return this
    }

    companion object {
        val EMPTY = DynamicRegistry(emptyMap(), emptyMap())
    }
}

private val KClass<*>.displayName: String get() = qualifiedName ?: simpleName ?: toString()

/**
 * One class registered as a `dynamic_dto`, with the [Json] of the client that registered it, which its values
 * are written and read through whichever client runs the query.
 *
 * @property name The discriminator, as the database stores it.
 * @property json The registering client's [Json], with the registered enums folded in as it is resolved.
 */
internal class Registration<T : Any>(
    val name: String,
    val kClass: KClass<T>,
    private val serializer: KSerializer<T>,
    val json: EnumAwareJson
) {
    /** The payload as JSON text, which is what the `jsonb` codec encodes. */
    @Suppress("UNCHECKED_CAST")
    fun encode(value: Any, json: Json): String = json.encodeToString(serializer, value as T)

    fun decode(payload: String, json: Json): Any = json.decodeFromString(serializer, payload)
}
