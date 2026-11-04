package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.codec.TypeCodec
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.util.reflection.sealedFamily
import io.github.octaviusframework.identifier.CaseConvention
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Everything the driver knows about a database's types, as one immutable value. A registration replaces it whole,
 * so no reader sees one half-applied, and each execution reads one catalog throughout.
 *
 * @property dictionary The types, by OID and by name.
 * @property codecs The codecs for those types.
 * @property registeredComposites The composite type each registered class maps onto.
 * @property compositeClassByName [registeredComposites] the other way round.
 * @property registeredEnums How each registered enum was registered.
 */
class TypeCatalog internal constructor(
    val dictionary: TypeDictionary,
    val codecs: CodecDictionary,
    /**
     * Result converters by `supportedSourceClass`, and by every class below a sealed one, newest first. Those for
     * `Any::class` are in [anyResultConverters].
     */
    internal val resultConverters: Map<KClass<*>, List<ResultConverter<*, *>>>,
    /** Result converters that named `Any::class`, consulted once every class-specific one has declined. */
    internal val anyResultConverters: List<ResultConverter<*, *>>,
    /** Parameter converters in one flat list, most recently registered first. */
    internal val parameterConverters: List<ParameterConverter<*>>,
    val registeredComposites: Map<KClass<*>, QualifiedName>,
    val compositeClassByName: Map<QualifiedName, KClass<*>>,
    val registeredEnums: Map<KClass<*>, PgEnumRegistration>,
    /** [registeredEnums] read the other way, from the PostgreSQL type name. */
    internal val enumClassByName: Map<QualifiedName, KClass<*>>,
    /** What layers built on the driver keep here, each value under the class it is read back as. */
    internal val attachments: Map<KClass<*>, Any>
) {

    private fun with(
        dictionary: TypeDictionary = this.dictionary,
        codecs: CodecDictionary = this.codecs,
        resultConverters: Map<KClass<*>, List<ResultConverter<*, *>>> = this.resultConverters,
        anyResultConverters: List<ResultConverter<*, *>> = this.anyResultConverters,
        parameterConverters: List<ParameterConverter<*>> = this.parameterConverters,
        registeredComposites: Map<KClass<*>, QualifiedName> = this.registeredComposites,
        compositeClassByName: Map<QualifiedName, KClass<*>> = this.compositeClassByName,
        registeredEnums: Map<KClass<*>, PgEnumRegistration> = this.registeredEnums,
        enumClassByName: Map<QualifiedName, KClass<*>> = this.enumClassByName,
        attachments: Map<KClass<*>, Any> = this.attachments
    ) = TypeCatalog(
        dictionary, codecs, resultConverters, anyResultConverters, parameterConverters,
        registeredComposites, compositeClassByName, registeredEnums, enumClassByName, attachments
    )

    internal fun <T : Any> withAttachment(type: KClass<T>, value: T) =
        with(attachments = attachments + (type to value))

    /** This catalog over re-read types, with the codecs rebuilt against them. */
    internal fun withTypes(newTypes: Map<Int, PgType>): TypeCatalog {
        val newDictionary = TypeDictionary.build(newTypes)
        return with(dictionary = newDictionary, codecs = codecs.buildUpdated(newDictionary))
    }

    /** This catalog with [codec] registered against its types. */
    internal fun withCodec(codec: TypeCodec<*>): TypeCatalog =
        with(codecs = codecs.withRegisteredCodec(codec, dictionary))

    internal fun withResultConverter(converter: ResultConverter<*, *>): TypeCatalog {
        val sourceClass = converter.supportedSourceClass
        if (sourceClass == Any::class) {
            return with(anyResultConverters = prepend(converter, anyResultConverters))
        }
        val newMap = HashMap(resultConverters)
        for (kClass in sourceClass.sealedFamily()) {
            newMap[kClass] = prepend(converter, resultConverters[kClass] ?: emptyList())
        }
        return with(resultConverters = newMap)
    }

    internal fun withParameterConverter(converter: ParameterConverter<*>) =
        with(parameterConverters = prepend(converter, parameterConverters))

    /**
     * This catalog with [kClass] registered as the composite [qualifiedName]. A name stands for one class and a
     * class for one name; the same pair again changes nothing. Names compare as written, so `address` and
     * `provincia.address` are two.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` where the name or the class is taken otherwise.
     */
    internal fun withComposite(kClass: KClass<*>, qualifiedName: QualifiedName): TypeCatalog {
        val underName = compositeClassByName[qualifiedName]
        if (underName != null && underName != kClass) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "The composite type '$qualifiedName' is already registered for ${underName.displayName}; " +
                        "${kClass.displayName} cannot take it as well."
            )
        }

        val existing = registeredComposites[kClass] ?: return with(
            registeredComposites = registeredComposites + (kClass to qualifiedName),
            compositeClassByName = compositeClassByName + (qualifiedName to kClass)
        )

        if (existing != qualifiedName) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${kClass.displayName} is already registered as the composite type '$existing'; it " +
                        "cannot go under '$qualifiedName' as well."
            )
        }
        return this
    }

    /**
     * This catalog with [kClass] registered as [registration] and its two converters put first. Refused as in
     * [withComposite], and for other conventions; the same registration again changes nothing.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` where the name or the enum is taken otherwise, or the
     *   enum is registered with other conventions.
     */
    internal fun withEnum(
        kClass: KClass<*>,
        registration: PgEnumRegistration,
        parameterConverter: ParameterConverter<*>,
        resultConverter: ResultConverter<*, *>
    ): TypeCatalog {
        val name = registration.qualifiedName
        val underName = enumClassByName[name]
        if (underName != null && underName != kClass) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "The enum type '$name' is already registered for ${underName.displayName}; " +
                        "${kClass.displayName} cannot take it as well."
            )
        }

        val existing = registeredEnums[kClass] ?: return with(
            registeredEnums = registeredEnums + (kClass to registration),
            enumClassByName = enumClassByName + (name to kClass)
        ).withParameterConverter(parameterConverter).withResultConverter(resultConverter)

        if (existing.qualifiedName != name) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${kClass.displayName} is already registered as the enum type '${existing.qualifiedName}'; " +
                        "it cannot go under '$name' as well."
            )
        }
        if (existing != registration) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${kClass.displayName} is already registered as '$name' with labels in " +
                        "${existing.pgConvention} and constants in ${existing.kotlinConvention}; it cannot be " +
                        "registered again with labels in ${registration.pgConvention} and constants in " +
                        "${registration.kotlinConvention}."
            )
        }
        return this
    }

    /** The class a composite of [type] reads as under `Any`: registered under its qualified name, else its bare one. */
    internal fun compositeClassFor(type: PgType): KClass<*>? =
        compositeClassByName[QualifiedName(type.schema, type.name)] ?: compositeClassByName[QualifiedName("", type.name)]


    /** The enum a value of [type] reads as under `Any`: registered under its qualified name, else its bare one. */
    internal fun enumClassFor(type: PgType): KClass<*>? =
        enumClassByName[QualifiedName(type.schema, type.name)] ?: enumClassByName[QualifiedName("", type.name)]

    /** The value [TypeManager.attach] kept under [type], or `null`. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> attachment(type: KClass<T>): T? = attachments[type] as T?

    /** The first converter to claim a decoded value of [sourceClass] as [expectedType], or `null`. */
    @Suppress("UNCHECKED_CAST")
    internal fun findResultConverter(
        sourceClass: KClass<*>,
        expectedType: KType,
        sourceType: PgType,
        context: DeserializationContext
    ): ResultConverter<Any, *>? {
        val specific = resultConverters[sourceClass]
        if (specific != null) {
            for (i in specific.indices) {
                val converter = specific[i] as ResultConverter<Any, *>
                if (converter.canConvert(sourceClass, expectedType, sourceType, context)) return converter
            }
        }
        for (i in anyResultConverters.indices) {
            val converter = anyResultConverters[i] as ResultConverter<Any, *>
            if (converter.canConvert(sourceClass, expectedType, sourceType, context)) return converter
        }
        return null
    }

    /** The first converter to claim a value of [sourceClass] going out as [expectedOid] (`0` if unknown), or `null`. */
    @Suppress("UNCHECKED_CAST")
    internal fun findParameterConverter(
        sourceClass: KClass<*>,
        expectedOid: Int,
        context: SerializationContext
    ): ParameterConverter<Any>? {
        for (i in parameterConverters.indices) {
            val converter = parameterConverters[i]
            if (converter.canConvert(sourceClass, expectedOid, context)) return converter as ParameterConverter<Any>
        }
        return null
    }

    internal companion object {
        private fun <T> prepend(element: T, list: List<T>): List<T> {
            val result = ArrayList<T>(list.size + 1)
            result.add(element)
            result.addAll(list)
            return result
        }
    }
}

/** Qualified, because the classes that collide over one type name tend to share a simple name - two `Address`es. */
private val KClass<*>.displayName: String get() = qualifiedName ?: simpleName ?: toString()

/**
 * How an enum was registered.
 *
 * @property qualifiedName The PostgreSQL enum type.
 * @property pgConvention How the labels are written.
 * @property kotlinConvention How the constants are written.
 */
data class PgEnumRegistration(
    val qualifiedName: QualifiedName,
    val pgConvention: CaseConvention,
    val kotlinConvention: CaseConvention
)
