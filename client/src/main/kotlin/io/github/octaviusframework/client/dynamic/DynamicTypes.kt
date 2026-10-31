package io.github.octaviusframework.client.dynamic

import io.github.octaviusframework.annotation.DynamicallyMappable
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.registry.TypeCatalog
import io.github.octaviusframework.driver.registry.TypeLookup
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.isKnownOid
import io.github.octaviusframework.serializer.octaviusJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubclassOf

/** The name of the composite type, in the schema it is expected in. */
private const val DYNAMIC_DTO_NAME = "dynamic_dto"
private const val DYNAMIC_DTO_SCHEMA = "public"

/** Its two attributes, as [DYNAMIC_DTO_DDL] declares them. */
private const val TYPE_NAME_ATTRIBUTE = "type_name"
private const val DATA_PAYLOAD_ATTRIBUTE = "data_payload"

/**
 * Held while a registration installs the converters its database still lacks. One for the whole JVM, since
 * nothing here can tell databases apart; never held across a round trip.
 */
private val installLock = ReentrantLock()

/**
 * The statements that create the `dynamic_dto` type and its constructors, harmless to run twice. Put them in a
 * migration, or run them with [DynamicTypes.install].
 */
const val DYNAMIC_DTO_DDL: String = $$"""
DO $do$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE t.typname = 'dynamic_dto' AND n.nspname = 'public'
    ) THEN
        CREATE TYPE public.dynamic_dto AS (
            type_name    text,
            data_payload jsonb
        );
    END IF;
END
$do$;

CREATE OR REPLACE FUNCTION public.dynamic_dto(p_type_name text, p_data jsonb)
    RETURNS public.dynamic_dto AS $fn$
    SELECT ROW(p_type_name, p_data)::public.dynamic_dto;
$fn$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

CREATE OR REPLACE FUNCTION public.to_dynamic_dto(p_type_name text, p_value anyelement)
    RETURNS public.dynamic_dto AS $fn$
    SELECT ROW(p_type_name, to_jsonb(p_value))::public.dynamic_dto;
$fn$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

CREATE OR REPLACE FUNCTION public.to_dynamic_dto(p_type_name text, p_value text)
    RETURNS public.dynamic_dto AS $fn$
    SELECT ROW(p_type_name, to_jsonb(p_value))::public.dynamic_dto;
$fn$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE"""

/**
 * The discriminator [kClass] declares, for the overloads that take no name.
 *
 * @throws InvalidOperationException `INVALID_ARGUMENT` where the class carries no [DynamicallyMappable].
 */
@PublishedApi
internal fun declaredTypeNameOf(kClass: KClass<*>): String =
    kClass.annotations.filterIsInstance<DynamicallyMappable>().firstOrNull()?.typeName
        ?: throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "${kClass.simpleName} carries no @DynamicallyMappable and was registered without a " +
                "type name; one or the other has to say what the database calls it."
        )

/**
 * The `dynamic_dto` types this client knows: where they are registered, and where a value is wrapped for writing.
 *
 * A `dynamic_dto` carrying a registered name reads as that class, several names read as the supertype asked for,
 * and an instance of a registered class is written as a `dynamic_dto` unwrapped, on the terms [strategy] sets.
 * The name is stated at registration or by [DynamicallyMappable], never derived from the class: it is stored in
 * the data, and a renamed class must still read it.
 *
 * ```kotlin
 * db.dynamicTypes.install()                      // or put DYNAMIC_DTO_DDL in a migration
 * db.dynamicTypes.register<LandGrant>("land_grant")
 * db.dynamicTypes.register<MilitaryPension>("military_pension")
 *
 * db.insertInto("veterans").values(listOf("id", "benefit"))
 *     .executeUpdate("id" to 1, "benefit" to LandGrant("Gallia", 120))
 *
 * val benefits: List<Benefit> = db.select("benefit").from("veterans").fetchFields()
 * ```
 *
 * A registration belongs to the database, not to this client, so it is done at startup: every client on the
 * database reads the same names, and a class registered here is written and read with this client's [json] and
 * [strategy] whichever client runs the query.
 *
 * @property json How payloads of classes registered here are read and written. The default,
 * [octaviusJson][io.github.octaviusframework.serializer.octaviusJson], is strict and carries the serializers a
 * `@Contextual BigDecimal` or date needs. A payload built in SQL with `jsonb_build_object` has to use the Kotlin
 * property names, or be read with a [Json] using `JsonNamingStrategy.SnakeCase` - and carrying
 * [octaviusSerializersModule][io.github.octaviusframework.serializer.octaviusSerializersModule] too.
 * @property strategy When an unwrapped instance of a class registered here is written as a `dynamic_dto`.
 */
class DynamicTypes internal constructor(
    private val client: OctaviusClient,
    val json: Json = octaviusJson,
    val strategy: DynamicWriteStrategy = DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS
) {

    /** [json] with the driver's enum serializers folded in, which is what everything here actually encodes with. */
    private val enumAwareJson = EnumAwareJson(json)

    /**
     * A read handle on the driver's type catalog, taken the first time something here needs one. Detached, so it
     * holds no connection.
     */
    @Volatile
    private var typeLookupHandle: TypeLookup? = null

    /**
     * The current catalog. Two threads arriving first may each open a session for a handle; both handles read
     * the database's one catalog, so it does not matter which is kept.
     */
    private fun catalog(): TypeCatalog =
        (typeLookupHandle ?: client.execute { typeManager.detached() }.also { typeLookupHandle = it }).catalog

    /**
     * Creates the `dynamic_dto` type where the database lacks it, and reloads the driver's type catalog, which
     * no fresh connection would. Where migrations manage the schema, put [DYNAMIC_DTO_DDL] in one instead.
     */
    fun install() {
        client.rawQuery(DYNAMIC_DTO_DDL).execute()
        client.execute { reloadTypes() }
    }

    /** Registers [T], which has to be `@Serializable`, under [typeName]. */
    inline fun <reified T : Any> register(typeName: String) {
        register(T::class, serializer<T>(), typeName)
    }

    /**
     * Registers [T], which has to be `@Serializable`, under the name its [DynamicallyMappable] declares. The
     * overloads taking a name ignore the annotation.
     */
    inline fun <reified T : Any> register() {
        register(T::class, serializer<T>(), declaredTypeNameOf(T::class))
    }

    /**
     * Registers [kClass] with [serializer], for a caller that found the class rather than named it.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` where the name is blank, the name is taken by another
     * class, the class is registered under another name, or another client registered it on another
     * [DynamicWriteStrategy].
     */
    fun <T : Any> register(kClass: KClass<T>, serializer: KSerializer<T>, typeName: String) {
        if (typeName.isBlank()) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${kClass.simpleName} was registered under a blank type name."
            )
        }

        val registration = Registration(typeName, kClass, serializer, enumAwareJson, strategy)
        client.execute {
            // Primed while a session is open anyway.
            this@DynamicTypes.typeLookupHandle = typeManager.detached()

            // The converters go in once per database, ahead of the registry they read, so no name is ever
            // registered with nothing to answer for it. The lock keeps two first registrations from installing
            // two pairs; it is taken inside the session, so nothing holding it waits on a pool.
            installLock.withLock {
                if (typeManager.catalog.attachment(DynamicRegistry::class) == null) {
                    typeManager.registerResultConverter(DynamicDtoResultConverter(null))
                    typeManager.registerParameterConverter(DynamicDtoParameterConverter(null))
                }
                typeManager.attach(DynamicRegistry::class) { (it ?: DynamicRegistry.EMPTY).with(registration) }
            }
        }
    }

    /** Registers [kClass], which has to be `@Serializable`, with the serializer its type resolves to. */
    @Suppress("UNCHECKED_CAST")
    fun register(kClass: KClass<*>, typeName: String) {
        val serializer = serializer(kClass.createType()) as KSerializer<Any>
        register(kClass as KClass<Any>, serializer, typeName)
    }

    /** Registers [kClass], which has to be `@Serializable`, under the name its [DynamicallyMappable] declares. */
    fun register(kClass: KClass<*>) {
        register(kClass, declaredTypeNameOf(kClass))
    }

    /**
     * Wraps [value] so it is written as a `dynamic_dto` whatever [strategy] says. Needed under
     * [EXPLICIT_ONLY][DynamicWriteStrategy.EXPLICIT_ONLY], and for a class also registered as a composite under
     * [AUTOMATIC_WHEN_UNAMBIGUOUS][DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS].
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` where [value]'s class was never registered.
     */
    fun toDynamicDto(value: Any, json: Json = this.json): DynamicDto {
        val catalog = catalog()
        val registration = catalog.attachment(DynamicRegistry::class)?.forClass(value::class)
            ?: throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${value::class.simpleName} is not a registered dynamic type; call " +
                    "dynamicTypes.register<${value::class.simpleName}>(\"…\") at startup."
            )
        val effective =
            if (json === this.json) enumAwareJson.resolve(catalog) else EnumAwareJson(json).resolve(catalog)
        return DynamicDto(registration.name, registration.encode(value, effective))
    }

    /**
     * A read converter for the registered types that decodes payloads with [json] instead of each class's own,
     * for a query whose payloads are shaped differently - built with `jsonb_build_object`, or by another service.
     * Register it on that query alone with
     * [registerResultConverter][io.github.octaviusframework.client.query.RunnableQuery.registerResultConverter].
     *
     * ```kotlin
     * db.select("benefit").from("veterans")
     *     .registerResultConverter(db.dynamicTypes.resultConverter(snakeCaseJson))
     *     .fetchFields<Benefit>()
     * ```
     */
    fun resultConverter(json: Json): ResultConverter<*, *> = DynamicDtoResultConverter(json)

    /** The write-side mirror of [resultConverter]. [toDynamicDto] takes a [Json] of its own for the same purpose. */
    fun parameterConverter(json: Json): ParameterConverter<*> = DynamicDtoParameterConverter(json)

    /**
     * Contextual serializers writing each registered enum in JSON under its PostgreSQL label, as the driver
     * writes it in an enum column, instead of under the Kotlin constant's name.
     *
     * Every conversion here folds them in already, whatever [Json] it was given. This is for a [Json] built
     * elsewhere - an HTTP layer, a `jsonb` column written through the driver:
     *
     * ```kotlin
     * val api = Json {
     *     serializersModule = octaviusSerializersModule + db.dynamicTypes.enumSerializers
     * }
     * ```
     *
     * `@Contextual` on the property selects one; the enum needs no `@Serializable`. It holds the enums registered
     * when it is read, so take it once startup has registered them all. Reading it may open a session.
     */
    val enumSerializers: SerializersModule
        get() = enumAwareJson.module(catalog())
}

/**
 * Writes a registered class, or a [DynamicDto], as the `dynamic_dto` composite. Builds the `PgComposite` directly
 * rather than through the driver's reflective path, which would allocate a map per parameter.
 */
private class DynamicDtoParameterConverter(overrideJson: Json?) : ParameterConverter<Any> {

    /** Set only where this converter was made for one query. */
    private val enumAwareJson = overrideJson?.let { EnumAwareJson(it) }

    override val supportedClass: KClass<Any> = Any::class

    override fun canConvert(sourceClass: KClass<*>, expectedOid: Int, context: SerializationContext): Boolean {
        // A known destination type - a composite attribute, an array element, a PgTyped value - decides, and no
        // mode outranks it. A mode only answers for a top-level parameter, whose type nothing declares.
        if (expectedOid.isKnownOid) {
            val target = context.types.dictionary.getPgType(expectedOid)
            return target.name == DYNAMIC_DTO_NAME && target.schema == DYNAMIC_DTO_SCHEMA
        }

        // Wrapping says which form was meant, so a wrapped value is claimed under every mode.
        if (sourceClass == DynamicDto::class) return true
        val registration = context.types.catalog.attachment(DynamicRegistry::class)?.forClass(sourceClass)
            ?: return false
        if (registration.strategy == DynamicWriteStrategy.PREFER_DYNAMIC_DTO) return true

        // The other two leave a class that is also a registered composite to the composite path. One with no
        // other destination is claimed under both, so EXPLICIT_ONLY refuses it in convert with a message saying
        // to wrap it, rather than the MISSING_CODEC declining would end in.
        return context.types.catalog.registeredComposites[sourceClass] == null
    }

    override fun convert(source: Any, expectedOid: Int, context: SerializationContext): Any {
        val typeName: String
        val payload: String

        if (source is DynamicDto) {
            typeName = source.typeName
            payload = source.dataPayload
        } else {
            val registration = context.types.catalog.attachment(DynamicRegistry::class)?.forClass(source::class)
                ?: throw MappingException(
                    MappingExceptionReason.CONVERSION_ERROR,
                    details = "${source::class.simpleName} is not a registered dynamic type; call " +
                        "dynamicTypes.register<${source::class.simpleName}>(\"…\") at startup."
                )
            if (registration.strategy == DynamicWriteStrategy.EXPLICIT_ONLY) {
                throw MappingException(
                    MappingExceptionReason.CONVERSION_ERROR,
                    details = "${source::class.simpleName} is registered as the dynamic type " +
                        "'${registration.name}' on EXPLICIT_ONLY: wrap it in dynamicTypes.toDynamicDto(…), or " +
                        "register it through a client built on another DynamicWriteStrategy."
                )
            }
            typeName = registration.name
            payload = registration.encode(source, (enumAwareJson ?: registration.json).resolve(context.types.catalog))
        }

        val composite = context.types.containers.createComposite(DYNAMIC_DTO_NAME, DYNAMIC_DTO_SCHEMA)
        composite[TYPE_NAME_ATTRIBUTE] = typeName
        composite[DATA_PAYLOAD_ATTRIBUTE] = payload
        return composite
    }
}

/**
 * Turns a `dynamic_dto` composite into the registered class its `type_name` names. [canConvert] goes by the
 * column's type alone, the discriminator being in the value; [convert] refuses the rest with the value in hand to
 * name in its message.
 */
private class DynamicDtoResultConverter(overrideJson: Json?) : ResultConverter<PgComposite, Any> {

    /** Set only where this converter was made for one query. */
    private val enumAwareJson = overrideJson?.let { EnumAwareJson(it) }

    override val supportedSourceClass: KClass<PgComposite> = PgComposite::class

    override fun canConvert(
        sourceClass: KClass<*>,
        expectedType: KType,
        sourceType: PgType,
        context: DeserializationContext
    ): Boolean {
        if (sourceType.name != DYNAMIC_DTO_NAME || sourceType.schema != DYNAMIC_DTO_SCHEMA) return false
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        if (kClass == DynamicDto::class) return true
        return context.types.catalog.attachment(DynamicRegistry::class)?.anyFits(kClass) ?: false
    }

    override fun convert(
        source: PgComposite,
        expectedType: KType,
        sourceType: PgType,
        context: DeserializationContext
    ): Any {
        val typeName = source.get<String?>(TYPE_NAME_ATTRIBUTE)
            ?: throw MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "A dynamic_dto carried no type_name, so there is nothing to say what it is.",
                path = mutableListOf(TYPE_NAME_ATTRIBUTE)
            )

        val payload = source.get<Any?>(DATA_PAYLOAD_ATTRIBUTE) as? String
            ?: throw MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "The dynamic_dto named '$typeName' carried no readable data_payload.",
                path = mutableListOf(DATA_PAYLOAD_ATTRIBUTE)
            )

        val expectedClass = expectedType.classifier as? KClass<*>
        if (expectedClass == DynamicDto::class) return DynamicDto(typeName, payload)

        val registration = context.types.catalog.attachment(DynamicRegistry::class)?.forName(typeName)
            ?: throw MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "No class is registered for the dynamic type '$typeName'. Register it at startup with " +
                    "dynamicTypes.register<YourClass>().",
                path = mutableListOf(TYPE_NAME_ATTRIBUTE)
            )

        val decoded = try {
            registration.decode(payload, (enumAwareJson ?: registration.json).resolve(context.types.catalog))
        } catch (e: Exception) {
            throw MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "The payload of dynamic type '$typeName' does not fit " +
                    "${registration.kClass.simpleName}.",
                cause = e
            )
        }

        if (expectedClass != null && expectedClass != Any::class && !registration.kClass.isSubclassOf(expectedClass)) {
            throw MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "The column holds a '$typeName', which maps to ${registration.kClass.simpleName}, " +
                    "and that is not a ${expectedClass.simpleName}."
            )
        }

        return decoded
    }
}
