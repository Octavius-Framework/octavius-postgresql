package io.github.octaviusframework.client.dynamic

import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.registry.TypeCatalog
import io.github.octaviusframework.driver.registry.PgEnumRegistration
import io.github.octaviusframework.identifier.CaseConverter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlin.reflect.KClass

/**
 * Writes a registered enum into JSON under its PostgreSQL label, as the driver writes it in an enum column, and
 * reads it back. Built from the registration, so an enum named at `registerEnum` and one found by `@PgEnumType`
 * are covered alike.
 *
 * @param registration Where the two naming conventions come from.
 */
internal class PgEnumSerializer(
    private val enumClass: KClass<*>,
    registration: PgEnumRegistration
) : KSerializer<Any> {

    private val enumToPg: Map<Any, String> = enumClass.java.enumConstants.associate { constant ->
        constant as Any to CaseConverter.convert(
            (constant as Enum<*>).name,
            registration.kotlinConvention,
            registration.pgConvention
        )
    }

    private val pgToEnum: Map<String, Any> = enumToPg.entries.associate { (constant, label) -> label to constant }

    private val typeName: QualifiedName = registration.qualifiedName

    // The class's own name, which is what keeps two enums' descriptors from colliding in one format.
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        enumClass.qualifiedName ?: typeName.toString(),
        PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: Any) {
        val label = enumToPg[value] ?: throw SerializationException(
            "$value is not a constant of ${enumClass.simpleName}, which is what this serializer writes."
        )
        encoder.encodeString(label)
    }

    override fun deserialize(decoder: Decoder): Any {
        val label = decoder.decodeString()
        return pgToEnum[label] ?: throw SerializationException(
            "'$label' is not a label of ${enumClass.simpleName}, which is registered as $typeName with " +
                "labels ${enumToPg.values.sorted().joinToString(", ")}."
        )
    }
}

/**
 * The registered enums' serializers, as a module and as a [Json] with it folded in, resolved per conversion and
 * rebuilt only when the registrations change. The driver replaces [TypeCatalog.registeredEnums] whole on every
 * registration, so its identity is the version and checking it is a reference comparison.
 *
 * A serializer [base] already has for an enum wins over the generated one.
 *
 * @property base The [Json] to fold them into, handed back as it is where no enum is registered.
 */
internal class EnumAwareJson(private val base: Json) {

    @Volatile
    private var cached: Triple<Map<KClass<*>, PgEnumRegistration>, SerializersModule, Json>? = null

    /** The module on its own, for a [Json] the caller is building rather than one being converted with. */
    fun module(catalog: TypeCatalog): SerializersModule = derive(catalog).second

    /** [base] with the module folded in, or [base] itself where there is nothing to fold. */
    fun resolve(catalog: TypeCatalog): Json =
        if (catalog.registeredEnums.isEmpty()) base else derive(catalog).third

    private fun derive(catalog: TypeCatalog): Triple<Map<KClass<*>, PgEnumRegistration>, SerializersModule, Json> {
        val source = catalog.registeredEnums
        cached?.let { if (it.first === source) return it }

        @Suppress("UNCHECKED_CAST")
        val module = SerializersModule {
            source.forEach { (kClass, registration) ->
                contextual(kClass as KClass<Any>, PgEnumSerializer(kClass, registration) as KSerializer<Any>)
            }
        }
        val json = Json(base) { serializersModule = module.overwriteWith(base.serializersModule) }

        return Triple(source, module, json).also { cached = it }
    }
}
