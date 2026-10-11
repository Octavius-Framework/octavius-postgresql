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
import io.github.octaviusframework.identifier.CaseConvention
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Everything the driver knows about a database's types, as one immutable value.
 *
 * The catalog is what a registration produces and what a query reads. It is replaced wholesale rather than
 * edited, and [CatalogHolder] publishes it through a single `@Volatile` field, so one read of that field hands
 * back a set of dictionaries, converters and registrations that were all true at the same moment. That is the
 * point of keeping them in one object: a registration that touches two of them - `registerEnum` writes a
 * parameter converter, a result converter and [registeredEnums] - cannot be observed half-applied, because
 * there is no moment at which half of it is published.
 *
 * A query pins one of these for the whole of an execution. See
 * [Query][io.github.octaviusframework.driver.query.Query].
 *
 * @property dictionary The PostgreSQL types this catalog describes, by OID and by name.
 * @property codecs The codecs that encode and decode those types.
 * @property registeredComposites What every Kotlin class registered as a composite was registered as.
 * @property compositeClassByName The same mapping read the other way, from the PostgreSQL type name.
 * @property registeredEnums What every registered Kotlin enum was registered as.
 */
class TypeCatalog internal constructor(
    val dictionary: TypeDictionary,
    val codecs: CodecDictionary,
    /**
     * Result converters indexed by `supportedSourceClass`, most recently registered first within a class.
     * Converters registered under `Any::class` are kept in [anyResultConverters] instead.
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

    /**
     * The catalog this one becomes when the database has been re-read: the types as they now are, and the
     * codecs rebuilt against them.
     */
    internal fun withTypes(newTypes: Map<Int, PgType>): TypeCatalog {
        val newDictionary = TypeDictionary.build(newTypes)
        return with(dictionary = newDictionary, codecs = codecs.buildUpdated(newDictionary))
    }

    /** The catalog this one becomes with [codec] registered, resolved against the types this one describes. */
    internal fun withCodec(codec: TypeCodec<*>): TypeCatalog =
        with(codecs = codecs.withRegisteredCodec(codec, dictionary))

    internal fun withResultConverter(converter: ResultConverter<*, *>): TypeCatalog {
        val sourceClass = converter.supportedSourceClass
        if (sourceClass == Any::class) {
            return with(anyResultConverters = prepend(converter, anyResultConverters))
        }
        val newMap = HashMap(resultConverters)
        newMap[sourceClass] = prepend(converter, resultConverters[sourceClass] ?: emptyList())
        return with(resultConverters = newMap)
    }

    internal fun withParameterConverter(converter: ParameterConverter<*>) =
        with(parameterConverters = prepend(converter, parameterConverters))

    /**
     * The catalog this one becomes with [kClass] registered as the composite [qualifiedName].
     *
     * A name stands for one class and a class goes under one name, so taking either twice is refused: a composite
     * read as `Any` becomes the class its name is registered for, and a class written where the server has not
     * said the type goes out as the name it is registered under - with a second registration, whichever came
     * last. The same class under the same name again is the registration it already is, and changes nothing.
     * Names are compared as written, so `address` and `provincia.address` are two and can stand for two classes;
     * [compositeClassFor] says which one a read as `Any` takes.
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
     * The catalog this one becomes with [kClass] registered as [registration], and the two converters built from
     * it put ahead of the rest.
     *
     * Refused on the terms [withComposite] refuses on, and also where the enum is registered under its name with
     * other conventions: the labels it writes and reads are theirs, so a second pair could only replace the first.
     * The same registration again changes nothing, converters included - they would be the ones already there.
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

    /**
     * The class a composite of [type] reads as when `Any` is asked for: the one registered under the type's
     * schema-qualified name, and failing that the one registered under its bare name.
     */
    internal fun compositeClassFor(type: PgType): KClass<*>? =
        compositeClassByName[QualifiedName(type.schema, type.name)] ?: compositeClassByName[QualifiedName("", type.name)]


    /**
     * The enum a value of [type] reads as when `Any` is asked for: the one registered under the type's
     * schema-qualified name, and failing that the one registered under its bare name.
     */
    internal fun enumClassFor(type: PgType): KClass<*>? =
        enumClassByName[QualifiedName(type.schema, type.name)] ?: enumClassByName[QualifiedName("", type.name)]

    /**
     * What a layer built on the driver keeps about this database under [type], or `null` where it keeps nothing.
     *
     * The driver reads none of it. It is here so that it has the scope the driver's own registrations have: one
     * per database, carried across a reload, dropped by [GlobalCatalogStore.removeCatalog] - and, read through
     * `context.types.catalog`, pinned for an execution like everything else a converter sees.
     * [TypeManager.attach] is what puts it here.
     *
     * @param type The class the value was attached under.
     * @return The value, or `null`.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> attachment(type: KClass<T>): T? = attachments[type] as T?

    /**
     * Finds the converter that claims a decoded value, in the order a registration establishes.
     *
     * @param sourceClass The class of the decoded value.
     * @param expectedType The Kotlin type wanted.
     * @param sourceType The PostgreSQL type of the value.
     * @param context Passed on to each candidate's `canConvert`.
     * @return The first converter to claim the value, or `null` if none does.
     */
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

    /**
     * Finds the converter that claims a value being sent.
     *
     * @param sourceClass The class of the value.
     * @param expectedOid The OID the server expects, or `0` when it is not known.
     * @param context Passed on to each candidate's `canConvert`.
     * @return The first converter to claim the value, or `null` if none does.
     */
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
 * What an enum was registered as: the type it stands for, and the two conventions that map one side's names
 * onto the other's.
 *
 * @property qualifiedName The PostgreSQL enum type this class stands for.
 * @property pgConvention How the labels are written in PostgreSQL.
 * @property kotlinConvention How the constants are written in Kotlin.
 */
data class PgEnumRegistration(
    val qualifiedName: QualifiedName,
    val pgConvention: CaseConvention,
    val kotlinConvention: CaseConvention
)
