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
 * Registers PostgreSQL types, codecs and converters, and reads back what is registered.
 *
 * This class provides a high-level API over [CatalogHolder], making it easier to
 * register custom mappings between Kotlin types and PostgreSQL database types.
 *
 * The reading half of it is [TypeLookup], which this delegates to and which is what a conversion is handed:
 * registration is reached from a session, not from inside a converter.
 *
 * @property holder The cell holding the catalog this manager reads and replaces.
 */
class TypeManager internal constructor(
    private val holder: CatalogHolder,
    private val searchPathProvider: () -> List<String> = { emptyList() }
) {
    /**
     * The reading half, following the store rather than any one catalog.
     *
     * Everything on it is re-exposed here, so this is not a second way to read - it is the read half as a value,
     * for the places that are handed one rather than a manager: the mappers, and the loader that builds one
     * before there is a session to reach.
     */
    internal val lookup: TypeLookup = TypeLookup({ holder.catalog }, searchPathProvider)

    /**
     * Everything the driver knows about this database: dictionaries, converters and registrations, as one value.
     */
    val catalog: TypeCatalog get() = lookup.catalog

    /**
     * The dictionary mapping PostgreSQL type names to their OIDs and vice versa.
     */
    val dictionary get() = lookup.dictionary

    /**
     * The dictionary maintaining [TypeCodec] implementations.
     */
    val codecs get() = lookup.codecs

    /**
     * Factory for creating container types (like composites).
     */
    val containers get() = lookup.containers

    /**
     * A lookup reading [catalog] and nothing else, for as long as it is held.
     *
     * What makes a query's reads repeatable: converters reach the type system through the lookup the context
     * hands them, so pinning it pins every dictionary and every registration they can see.
     */
    internal fun pinnedTo(catalog: TypeCatalog): TypeLookup = TypeLookup({ catalog }, searchPathProvider)

    /**
     * A lookup on the same database with no session behind it.
     *
     * [TypeLookup.catalog] reads live, as it does here, but nothing holds the connection this one was reached
     * through - which is what makes it safe to keep past the session that handed it out. The catalog is one
     * object per database, so what it reads is what every session on that database reads.
     *
     * It has no search path of its own, so [TypeLookup.resolveOid] on it resolves only fully qualified names.
     * For reading registrations, which is what something outliving a session wants it for.
     */
    fun detached(): TypeLookup = TypeLookup({ holder.catalog }, { emptyList() })

    /**
     * Resolves an OID for a given type name, considering the current search path.
     */
    fun resolveOid(
        typeName: String,
        schema: String = "",
        isArray: Boolean = false
    ): Int {
        return lookup.resolveOid(typeName, schema, isArray)
    }

    /**
     * Registers a custom [ResultConverter] for mapping PostgreSQL database types to Kotlin types.
     *
     * @param converter The converter instance to register.
     */
    fun registerResultConverter(converter: ResultConverter<*, *>) = holder.update { it.withResultConverter(converter) }

    /**
     * Registers a custom [ParameterConverter] for mapping Kotlin types to PostgreSQL database types.
     *
     * @param converter The converter instance to register.
     */
    fun registerParameterConverter(converter: ParameterConverter<*>) = holder.update { it.withParameterConverter(converter) }

    /**
     * Registers a custom [TypeCodec] for encoding and decoding database types at the lowest level.
     *
     * What the codec declares decides what it binds to. Naming a `pgTypeName` and a `pgSchema` binds it to that
     * one type. Naming a `pgTypeName` and no schema binds it to every type of that name in the catalog, whichever
     * schema they are in, and the OID a parameter goes out as is resolved in flight against the session's search
     * path - which is what makes one codec serve the same type defined per tenant schema. Declaring an `oid`
     * binds it to that number and nothing else, which is the brittle one: a type dropped and recreated has a new
     * OID, and the codec is then bound to a number the catalog no longer describes.
     *
     * A schema-qualified type this database does not have is refused here, where the call that named it is still
     * on the stack. A catalog reload that later leaves a registered codec bound to nothing does not raise -
     * the database having changed is not the registration's fault.
     *
     * @param codec The codec instance to register.
     * @throws io.github.octaviusframework.driver.exception.TypeException `TYPE_NOT_FOUND` where [codec] names a
     *   schema-qualified type the catalog does not describe.
     */
    fun registerCodec(codec: TypeCodec<*>) = holder.update { it.withCodec(codec) }

    /**
     * Registers a composite type mapped reflectively onto the data class [T].
     *
     * Property names are matched to attribute names by converting `camelCase` to `snake_case`;
     * [PgName][io.github.octaviusframework.annotation.PgName] overrides that per property.
     *
     * @param T The Kotlin data class representing the composite type.
     * @param typeName Optional custom type name in the database. If empty, the name is derived from the class name
     *   by converting `PascalCase` to `snake_case`.
     * @param schema Optional schema where the type is defined. If empty, the type is resolved through the search path.
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException if [T] is not a data class.
     */
    inline fun <reified T : Any> registerAutoComposite(
        typeName: String = "",
        schema: String = ""
    ) {
        registerAutoComposite(T::class, typeName, schema)
    }

    /**
     * Registers a composite type mapped reflectively onto the data class [kClass].
     *
     * @param kClass The Kotlin data class representing the composite type.
     * @param typeName Optional custom type name in the database. If empty, the name is derived from the class name
     *   by converting `PascalCase` to `snake_case`.
     * @param schema Optional schema where the type is defined. If empty, the type is resolved through the search path.
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException if [kClass] is not a data class.
     */
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

        // Reflective mapping reads every primary constructor parameter back as a property, which is exactly what
        // a data class guarantees. Rejecting anything else here beats a null property lookup at query time.
        if (!kClass.isData) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                "Class ${kClass.qualifiedName ?: kClass.simpleName} is not a data class and cannot be registered " +
                        "as composite type '$qName'. Reflective mapping reads every primary constructor parameter " +
                        "back as a property, which only a data class guarantees. Write a ResultConverter and " +
                        "ParameterConverter pair for any other shape."
            )
        }

        // Warms the metadata cache before the registration is visible, so the first query does not pay for it.
        ReflectionCache.getOrCreateDataObjectMetadata(kClass)

        holder.update { it.withComposite(kClass, QualifiedName(schema, qName)) }
    }

    /**
     * Registers an enum type, creating both parameter and result converters.
     *
     * @param T The Kotlin enum class.
     * @param typeName Optional custom type name in the database.
     * @param schema Optional schema where the enum is defined.
     * @param pgConvention The naming convention used for enum values in PostgreSQL.
     * @param kotlinConvention The naming convention used for enum values in Kotlin.
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
     * Registers an enum type, creating both parameter and result converters.
     *
     * Takes a plain [KClass] rather than one bound to `Enum<T>`, because the callers that reach this overload
     * rather than the reified one are holding a class they found - a classpath scan, a configuration file - and
     * cannot name its type. The bound bought them nothing but a cast at every call site; being an enum is
     * checked here instead, once, and reported as the bad argument it is.
     *
     * @param enumClass The Kotlin enum class.
     * @param typeName Optional custom type name in the database.
     * @param schema Optional schema where the enum is defined.
     * @param pgConvention The naming convention used for enum values in PostgreSQL.
     * @param kotlinConvention The naming convention used for enum values in Kotlin.
     * @throws InvalidOperationException `INVALID_ARGUMENT` if [enumClass] is not an enum class.
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

        // The converters do want the concrete enum type - they read `enumConstants` and answer with
        // `supportedClass`, which is what keeps them from claiming every value - so the cast happens, once,
        // here rather than in every caller holding a class it cannot name.
        @Suppress("UNCHECKED_CAST")
        val typed = enumClass as KClass<UnnamedEnum>

        // The two converters and the record of what they were built from go out as one catalog: a query that
        // took its snapshot between them would hold a converter whose registration TypeCatalog.registeredEnums
        // does not describe - which is what the layer holding JSON reads to decide what an enum means inside it.
        holder.update {
            it.withParameterConverter(EnumParameterConverter(typed, qualifiedName, pgConvention, kotlinConvention))
                .withResultConverter(EnumResultConverter(typed, qualifiedName, pgConvention, kotlinConvention))
                .withEnum(enumClass, PgEnumRegistration(qualifiedName, pgConvention, kotlinConvention))
        }
    }
}

/**
 * Stands in for the enum type a [KClass] found at runtime cannot name, so that one cast inside
 * [TypeManager.registerEnum] spares every caller one of their own. Never instantiated; erased before anything
 * could observe it.
 */
private enum class UnnamedEnum
