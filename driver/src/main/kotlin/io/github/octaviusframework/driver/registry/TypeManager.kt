package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.codec.TypeCodec
import io.github.octaviusframework.driver.converter.EnumParameterConverter
import io.github.octaviusframework.driver.converter.EnumResultConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.util.reflection.ReflectionCache
import io.github.octaviusframework.identifier.CaseConvention
import io.github.octaviusframework.identifier.CaseConverter
import kotlin.reflect.KClass

/**
 * Registers codecs, converters and type mappings for this session's database, and reads back what is registered.
 * A registration belongs to the database and every session on it sees it. A converter is handed only the reading
 * half, [TypeLookup].
 */
class TypeManager internal constructor(
    private val holder: CatalogHolder,
    private val searchPathProvider: () -> List<String> = { emptyList() }
) {
    /** The reading half as a value, for what is handed one rather than a manager. Follows the current catalog. */
    internal val lookup: TypeLookup = TypeLookup({ holder.catalog }, searchPathProvider)

    /** Everything the driver knows about this database: dictionaries, converters and registrations. */
    val catalog: TypeCatalog get() = lookup.catalog

    /** Type names to OIDs and back. */
    val dictionary get() = lookup.dictionary

    /** The codecs for this database's types. */
    val codecs get() = lookup.codecs

    /** Builds composites, ranges and multiranges of a type named or given by OID. */
    val containers get() = lookup.containers

    /** A lookup reading [catalog] alone, so the converters of one execution see one catalog. */
    internal fun pinnedTo(catalog: TypeCatalog): TypeLookup = TypeLookup({ catalog }, searchPathProvider)

    /**
     * A lookup on this database's current catalog that holds no connection, safe to keep past the session. With
     * no search path, its [TypeLookup.resolveOid] resolves only schema-qualified names.
     */
    fun detached(): TypeLookup = TypeLookup({ holder.catalog }, { emptyList() })

    /**
     * The OID of a type, by name through the session's search path or in [schema].
     *
     * @throws io.github.octaviusframework.driver.exception.TypeException `TYPE_NOT_FOUND` if [typeName] is not in
     *   [schema] or on the search path.
     */
    fun resolveOid(
        typeName: String,
        schema: String = "",
        isArray: Boolean = false
    ): Int {
        return lookup.resolveOid(typeName, schema, isArray)
    }

    /** Registers [converter] ahead of those registered before it. */
    fun registerResultConverter(converter: ResultConverter<*, *>) = holder.update { it.withResultConverter(converter) }

    /** Registers [converter] ahead of those registered before it. */
    fun registerParameterConverter(converter: ParameterConverter<*>) = holder.update { it.withParameterConverter(converter) }

    /**
     * Registers [codec] for what it declares: a `pgTypeName` with a `pgSchema` is that one type; a `pgTypeName`
     * alone is every type of that name, a parameter going out as the one the search path finds; an `oid` is that
     * number, which a recreated type no longer has.
     *
     * @throws io.github.octaviusframework.driver.exception.TypeException `TYPE_NOT_FOUND` where [codec] names a
     *   schema-qualified type the catalog does not describe.
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `INVALID_ARGUMENT` where
     *   [codec] declares OID `0`.
     */
    fun registerCodec(codec: TypeCodec<*>) = holder.update { it.withCodec(codec) }

    /**
     * Keeps a value in the catalog under [type], with the scope of a registration, for a layer built on the
     * driver. A converter reads it back with [TypeCatalog.attachment]. Keep it immutable.
     *
     * @param transform Given the value kept now, or `null`, returns the one to keep. Runs once, under the
     *   registration lock; a throw leaves the catalog as it was.
     */
    fun <T : Any> attach(type: KClass<T>, transform: (T?) -> T) =
        holder.update { it.withAttachment(type, transform(it.attachment(type))) }

    /**
     * Registers the data class [T] as the composite [typeName]: a [T] is written as that type, and a value of that
     * type read as `Any` comes back as a [T]. Each property is matched to the attribute named after it in
     * `snake_case`, or by its [PgName][io.github.octaviusframework.annotation.PgName]. Reading the composite into a
     * [T] asked for by name needs no registration.
     *
     * @param typeName Defaults to the class name in `snake_case`.
     * @param schema Empty for the search path.
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `INVALID_ARGUMENT` if [T] is
     *   not a data class, the type name is registered for another class, or [T] under another type name.
     */
    inline fun <reified T : Any> registerAutoComposite(
        typeName: String = "",
        schema: String = ""
    ) {
        registerAutoComposite(T::class, typeName, schema)
    }

    /** [registerAutoComposite] for a class found at runtime. Registering the same pair again changes nothing. */
    fun registerAutoComposite(
        kClass: KClass<*>,
        typeName: String = "",
        schema: String = ""
    ) {
        val qName = typeName.takeIf { it.isNotEmpty() } ?: CaseConverter.convert(
            kClass.simpleName!!,
            CaseConvention.PASCAL_CASE,
            CaseConvention.SNAKE_CASE_LOWER
        )

        // Refuses a class that is not a data class, and warms the metadata cache before the registration is
        // visible, so the first query does not pay for it.
        ReflectionCache.getOrCreateDataObjectMetadata(kClass)

        holder.update { it.withComposite(kClass, QualifiedName(schema, qName)) }
    }

    /**
     * Maps the PostgreSQL enum [typeName] onto [T] both ways, each label converted between [pgConvention] and
     * [kotlinConvention].
     *
     * @param typeName Defaults to the class name in `snake_case`.
     * @param schema Empty for the search path.
     * @throws InvalidOperationException `INVALID_ARGUMENT` if the type name is registered for another enum, or [T]
     *   under another type name or conventions.
     */
    inline fun <reified T : Enum<T>> registerEnum(
        typeName: String = "",
        schema: String = "",
        pgConvention: CaseConvention = CaseConvention.SNAKE_CASE_UPPER,
        kotlinConvention: CaseConvention = CaseConvention.PASCAL_CASE
    ) {
        registerEnum(T::class, typeName, schema, pgConvention, kotlinConvention)
    }

    /**
     * [registerEnum] for a class found at runtime, which is why it takes any [KClass]. Registering the same enum
     * the same way again changes nothing.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` if [enumClass] is not an enum class, the type name is
     *   registered for another enum, or [enumClass] under another type name or conventions.
     */
    fun registerEnum(
        enumClass: KClass<*>,
        typeName: String = "",
        schema: String = "",
        pgConvention: CaseConvention = CaseConvention.SNAKE_CASE_UPPER,
        kotlinConvention: CaseConvention = CaseConvention.PASCAL_CASE
    ) {
        val actualTypeName = typeName.takeIf { it.isNotEmpty() } ?: CaseConverter.convert(
            enumClass.simpleName!!, CaseConvention.PASCAL_CASE, CaseConvention.SNAKE_CASE_LOWER
        )

        if (!enumClass.java.isEnum) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${enumClass.qualifiedName} was registered as a PostgreSQL enum but is not an enum class."
            )
        }

        val actualSchema = schema.takeIf { it.isNotEmpty() } ?: ""
        val qualifiedName = QualifiedName(actualSchema, actualTypeName)

        // The converters want the concrete enum type for `enumConstants` and `supportedClass`.
        @Suppress("UNCHECKED_CAST")
        val typed = enumClass as KClass<UnnamedEnum>

        // One catalog, so no snapshot holds a converter that TypeCatalog.registeredEnums does not describe.
        holder.update {
            it.withEnum(
                enumClass,
                PgEnumRegistration(qualifiedName, pgConvention, kotlinConvention),
                EnumParameterConverter(typed, qualifiedName, pgConvention, kotlinConvention),
                EnumResultConverter(typed, qualifiedName, pgConvention, kotlinConvention)
            )
        }
    }
}

/** Stands in for the enum type a [KClass] found at runtime cannot name. Never instantiated. */
private enum class UnnamedEnum
