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
import java.util.concurrent.atomic.AtomicBoolean
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
 * The statements that create the `dynamic_dto` type and its constructors, written so that running them twice
 * is harmless.
 *
 * Put it in a migration where the schema is managed by one or run by [DynamicTypes.install].
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
 * The `dynamic_dto` types this client knows, and what you do with them: register, and - where [strategy] or
 * the class leaves the question open - wrap.
 *
 * Registration is explicit and states the name, rather than deriving it from the class. A discriminator is
 * **stored in the data**: derived from the class, renaming the class would change it silently and every row
 * written before the rename would stop being readable, at runtime, on whichever query reached one first. The
 * name can come from [DynamicallyMappable] instead, which is a declaration rather than a derivation and moves
 * with the class rather than tracking it.
 *
 * What registration buys is both directions. A `dynamic_dto` column carrying a registered name comes back as
 * that class, and a column carrying several different names comes back as whatever supertype was asked for -
 * which is how one column holds a sealed hierarchy. Going the other way, an instance of a registered class is
 * written as a `dynamic_dto` without being wrapped, on the terms [strategy] sets.
 *
 * ```kotlin
 * db.dynamicTypes.install()                      // or put DYNAMIC_DTO_DDL in a migration
 * db.dynamicTypes.register<LandGrant>("land_grant")
 * db.dynamicTypes.register<MilitaryPension>("military_pension")
 *
 * db.insertInto("veterans").values(listOf("id", "benefit"))
 *     .update("id" to 1, "benefit" to LandGrant("Gallia", 120))
 *
 * val benefits: List<Benefit> = db.select("benefit").from("veterans").fetchFields()
 * ```
 *
 * Registration is global to the database the client is connected to, the driver keeping one type catalog per
 * database, so it belongs at startup and not per request.
 *
 * @property json How payloads are read and written. The default is
 * [octaviusJson][io.github.octaviusframework.serializer.octaviusJson], which is strict - a payload carrying a
 * field the class does not declare is an error rather than something dropped - and carries
 * [octaviusSerializersModule][io.github.octaviusframework.serializer.octaviusSerializersModule], so a
 * `@Contextual BigDecimal` or date keeps in JSON what it would have kept in a column. Where the payload is
 * built in SQL with `jsonb_build_object`, its keys have to match the Kotlin property names - supply a [Json]
 * with `JsonNamingStrategy.SnakeCase` if the SQL side names them the way SQL usually does, and put that module
 * on it too.
 * @property strategy When an unwrapped instance of a registered class is written as a `dynamic_dto`.
 */
class DynamicTypes internal constructor(
    private val client: OctaviusClient,
    val json: Json = octaviusJson,
    val strategy: DynamicWriteStrategy = DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS
) {

    private val registrationLock = ReentrantLock()

    @Volatile
    private var byName: Map<String, Registration<*>> = emptyMap()

    @Volatile
    private var registeredClasses: Map<KClass<*>, Registration<*>> = emptyMap()

    private val convertersInstalled = AtomicBoolean(false)

    /** [json] with the driver's enum serializers folded in, which is what everything here actually encodes with. */
    private val enumAwareJson = EnumAwareJson(json)

    /**
     * A read handle on the driver's type catalog, remembered the first time anything here needs one.
     *
     * Detached on purpose: it follows the catalog, which is global to the database rather than to a session,
     * and holds no connection - so keeping it past the session that produced it keeps nothing but that.
     * It is what [toDynamicDto] and [enumSerializers] read the registered enums from, neither having a query
     * context to read them from.
     */
    @Volatile
    private var typeLookupHandle: TypeLookup? = null

    /**
     * The current catalog, opening a session to reach a handle the first time and not after.
     *
     * Both callers are public - [enumSerializers] and [toDynamicDto] - so two request threads can arrive here
     * at once and each open one. Harmless: the handles are two objects reading one catalog holder, the driver
     * keeping a single one per database, so whichever is stored second answers exactly as the first would.
     */
    private fun catalog(): TypeCatalog =
        (typeLookupHandle ?: client.execute { typeManager.detached() }.also { typeLookupHandle = it }).catalog

    /**
     * Creates the `dynamic_dto` type if the database does not have it.
     *
     * Nothing else here touches the schema, and this only does because it was asked to. Where migrations
     * manage the schema, put [DYNAMIC_DTO_DDL] in one instead and never call this.
     *
     * It also reloads the driver's type catalogue, which is loaded once per database on the first connection
     * opened to it and shared by every session after that: a type created later is one the driver has never
     * heard of, and opening a fresh connection does not help. A type that was already there before anything
     * connected needs no reload.
     */
    fun install() {
        client.rawQuery(DYNAMIC_DTO_DDL).execute()
        // The catalogue is loaded once per database and shared from then on, so a type created after the
        // first connection is one the driver has never heard of - and no fresh connection reloads it.
        client.execute { reloadTypes() }
    }

    /**
     * Registers [T] under [typeName].
     *
     * @param T The class, which has to be `@Serializable`.
     * @param typeName The discriminator, as the database stores it.
     */
    inline fun <reified T : Any> register(typeName: String) {
        register(T::class, serializer<T>(), typeName)
    }

    /**
     * Registers [T] under the name its [DynamicallyMappable] declares.
     *
     * The annotation is multiplatform, so it can sit on a class shared with another platform. Stating the name
     * at the call instead is always available and wins where both are present.
     *
     * @param T The class, which has to be `@Serializable` and carry [DynamicallyMappable].
     */
    inline fun <reified T : Any> register() {
        register(T::class, serializer<T>(), declaredTypeNameOf(T::class))
    }

    /**
     * Registers [kClass] with a serializer supplied by the caller.
     *
     * The reflective path, for a scanner that found the class rather than a call that named it.
     *
     * @param kClass The class, which has to be `@Serializable`.
     * @param serializer Its serializer.
     * @param typeName The discriminator, as the database stores it.
     * @throws InvalidOperationException `INVALID_ARGUMENT` where the name is blank, or is already taken by
     * another class.
     */
    fun <T : Any> register(kClass: KClass<T>, serializer: KSerializer<T>, typeName: String) {
        val name = typeName
        if (name.isBlank()) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "${kClass.simpleName} was registered under a blank type name."
            )
        }

        // Under the lock, so that the check and the write are one step: two threads claiming one name for two
        // classes could otherwise both find it free and the second would silently take it.
        registrationLock.withLock {
            val existing = byName[name]
            if (existing != null && existing.kClass != kClass) {
                throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "The dynamic type name '$name' is already registered for " +
                        "${existing.kClass.simpleName}; ${kClass.simpleName} cannot take it as well."
                )
            }

            val registration = Registration(name, kClass, serializer)
            byName = byName + (name to registration)
            registeredClasses = registeredClasses + (kClass to registration)
        }

        // Outside it: this opens a session, and a lock held across a round trip is a lock held for as long as
        // the database takes. The registration is already published, so a converter installed here can never
        // be reached before the class it was installed for.
        ensureConvertersInstalled()
    }

    /**
     * Registers [kClass] by looking its serializer up from its own type.
     *
     * The reflective path, for a caller holding a class rather than a type argument.
     *
     * @param kClass The class, which has to be `@Serializable`.
     * @param typeName The discriminator, as the database stores it.
     */
    @Suppress("UNCHECKED_CAST")
    fun register(kClass: KClass<*>, typeName: String) {
        val serializer = serializer(kClass.createType()) as KSerializer<Any>
        register(kClass as KClass<Any>, serializer, typeName)
    }

    /**
     * Registers [kClass] under the name its [DynamicallyMappable] declares.
     *
     * The path a scanner takes: it found the class by the annotation, so the annotation is what names it.
     *
     * @param kClass The class, which has to be `@Serializable` and carry [DynamicallyMappable].
     */
    fun register(kClass: KClass<*>) {
        register(kClass, declaredTypeNameOf(kClass))
    }

    /**
     * Wraps [value] for writing, saying that the `dynamic_dto` form is the one meant.
     *
     * Needed only where [strategy] leaves the question open - under
     * [EXPLICIT_ONLY][DynamicWriteStrategy.EXPLICIT_ONLY], or for a class registered as a composite as well
     * under [AUTOMATIC_WHEN_UNAMBIGUOUS][DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS]. A registered class
     * that is neither goes through unwrapped.
     *
     * @param value An instance of a registered class.
     * @return The value as a `dynamic_dto`.
     * @throws InvalidOperationException `INVALID_ARGUMENT` where [value]'s class was never registered.
     */
    fun toDynamicDto(value: Any, json: Json = this.json): DynamicDto {
        val registration = registrationFor(value::class) ?: throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "${value::class.simpleName} is not a registered dynamic type; call " +
                "dynamicTypes.register<${value::class.simpleName}>(\"…\") at startup."
        )
        val catalog = catalog()
        val effective =
            if (json === this.json) enumAwareJson.resolve(catalog) else EnumAwareJson(json).resolve(catalog)
        return DynamicDto(registration.name, registration.encode(value, effective))
    }

    /**
     * A read converter for these registrations that decodes payloads with [json] rather than with the
     * client's own.
     *
     * For the query whose payloads are shaped differently from the rest - built in SQL with
     * `jsonb_build_object` and therefore named the way SQL names things, or written by a service that is not
     * this one. Register it on a single query with
     * [registerResultConverter][io.github.octaviusframework.client.query.RunnableQuery.registerResultConverter]: query
     * registries sit ahead of the session's and are discarded with the query, so nothing else on the
     * connection reads that way.
     *
     * ```kotlin
     * db.select("benefit").from("veterans")
     *     .registerResultConverter(db.dynamicTypes.resultConverter(snakeCaseJson))
     *     .fetchFields<Benefit>()
     * ```
     *
     * @param json How to read the payloads.
     * @return The converter, registered nowhere until you register it.
     */
    fun resultConverter(json: Json): ResultConverter<*, *> = DynamicDtoResultConverter(this, json)

    /**
     * A write converter for these registrations that encodes payloads with [json] rather than with the
     * client's own, and the mirror of [resultConverter].
     *
     * [toDynamicDto] is the other half of the same question and takes a [Json] of its own, for the value
     * wrapped by hand rather than written straight.
     *
     * @param json How to write the payloads.
     * @return The converter, registered nowhere until you register it.
     */
    fun parameterConverter(json: Json): ParameterConverter<*> = DynamicDtoParameterConverter(this, json)

    /**
     * The contextual serializers writing each registered enum under the label PostgreSQL holds, rather than
     * under the Kotlin constant's own name.
     *
     * `registerEnum` teaches the driver that `Praetor` is `PRAETOR` in an enum **column**. A `jsonb` payload
     * never reaches that, so the same value would read two ways depending on where it was stored. Every
     * conversion this class runs folds these in as it goes - over [json], and over any [Json] handed to
     * [resultConverter], [parameterConverter] or [toDynamicDto] - so nothing there needs them added. This is
     * exposed for the [Json] built elsewhere: an HTTP layer, or a `jsonb` column written through the driver
     * rather than through a `dynamic_dto`.
     *
     * ```kotlin
     * val api = Json {
     *     serializersModule = octaviusSerializersModule + db.dynamicTypes.enumSerializers
     * }
     * ```
     *
     * `@Contextual` on the property is what selects one; the enum itself needs no `@Serializable` and no
     * serializer written by hand, whether it was named at `registerEnum` or found by a scan through
     * [PgEnumType][io.github.octaviusframework.annotation.PgEnumType].
     *
     * It answers for the enums registered at the moment it is read - registration being global to the
     * database and done at startup - so a `Json` built from it before startup has finished is a `Json` short
     * of whatever registered after. That is the difference between taking the module and letting this class
     * convert: a conversion folds the module in as it runs and so never goes stale, a module you folded into
     * a `Json` of your own is the set as it stood.
     *
     * Reading it opens a session if this client has not reached the driver's catalog yet, which is why it is
     * something to take once and keep rather than to reach for per request.
     */
    val enumSerializers: SerializersModule
        get() = enumAwareJson.module(catalog())

    /**
     * The [Json] a conversion should run on: the query's own where one was given, the client's otherwise, and
     * either way with the enums registered right now folded in.
     */
    internal fun jsonFor(catalog: TypeCatalog, override: EnumAwareJson?): Json =
        (override ?: enumAwareJson).resolve(catalog)

    internal fun forName(name: String): Registration<*>? = byName[name]

    /** The registration for an exact class, which is how a value being written finds its own. */
    internal fun registrationFor(kClass: KClass<*>): Registration<*>? = registeredClasses[kClass]

    /** Whether any registered class fits [kClass], which is what makes a supertype read work. */
    internal fun anyFits(kClass: KClass<*>): Boolean =
        kClass == Any::class || registeredClasses.keys.any { it.isSubclassOf(kClass) }

    /**
     * Puts the two converters on the type manager, once however many classes are registered.
     *
     * The composite half would not need the guard - registering the same auto-composite twice writes the same
     * entry to the same map. The converter halves would: registration appends without checking, and converters
     * are walked on every conversion, so a registry of ten classes would otherwise leave ten identical
     * converters in front of everything else.
     *
     * They read this registry when they run rather than holding a copy, so registering a class after they are
     * installed still takes effect.
     *
     */
    private fun ensureConvertersInstalled() {
        if (!convertersInstalled.compareAndSet(false, true)) return
        client.execute {
            // Primed here because a session is open anyway, so nothing later has to open one for it.
            this@DynamicTypes.typeLookupHandle = typeManager.detached()
            typeManager.registerResultConverter(DynamicDtoResultConverter(this@DynamicTypes, null))
            typeManager.registerParameterConverter(DynamicDtoParameterConverter(this@DynamicTypes, null))
        }
    }

    internal class Registration<T : Any>(
        val name: String,
        val kClass: KClass<T>,
        val serializer: KSerializer<T>
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
}

/**
 * Writes a registered class, or an already-wrapped [DynamicDto], as the composite the database stores.
 *
 * It builds the `PgComposite` itself rather than leaving [DynamicDto] to the driver's reflective composite
 * path, which would read the two properties back off the object through reflection and allocate a map of them
 * per parameter. Two attributes whose names [DYNAMIC_DTO_DDL] fixes do not need discovering.
 */
private class DynamicDtoParameterConverter(
    private val types: DynamicTypes,
    overrideJson: Json?
) : ParameterConverter<Any> {

    /** Set only where this converter was made for one query, which is the whole of what makes it different. */
    private val enumAwareJson = overrideJson?.let { EnumAwareJson(it) }

    override val supportedClass: KClass<Any> = Any::class

    override fun canConvert(sourceClass: KClass<*>, expectedOid: Int, context: SerializationContext): Boolean {
        // Where the destination's type is known - an attribute of a composite, an element of an array, a value
        // wrapped in PgTyped - that type is what the value has to fit, and no mode outranks it. A mode answers
        // the one question the driver cannot: what a top-level parameter, whose type nothing declares, was
        // meant to be. Reading has nothing to decide because the column has already said it, and this is the
        // same rule applied wherever the writing side happens to know as much.
        if (expectedOid.isKnownOid) {
            val target = context.types.dictionary.getPgType(expectedOid)
            return target.name == DYNAMIC_DTO_NAME && target.schema == DYNAMIC_DTO_SCHEMA
        }

        // Wrapping says which form was meant, so a wrapped value is claimed under every mode.
        if (sourceClass == DynamicDto::class) return true
        if (types.registrationFor(sourceClass) == null) return false
        if (types.strategy == DynamicWriteStrategy.PREFER_DYNAMIC_DTO) return true

        // The other two both leave a class that is also a registered composite to the composite path, which is
        // a real destination for it. They part company only over a class that has no other destination, and
        // that is settled in convert - claiming it there is what turns "you forgot to wrap this" into a
        // message saying so, rather than the MISSING_CODEC that declining would end in.
        return context.types.catalog.registeredComposites[sourceClass] == null
    }

    override fun convert(source: Any, expectedOid: Int, context: SerializationContext): Any {
        val typeName: String
        val payload: String

        if (source is DynamicDto) {
            typeName = source.typeName
            payload = source.dataPayload
        } else {
            val registration = types.registrationFor(source::class) ?: throw MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "${source::class.simpleName} is no longer a registered dynamic type."
            )
            if (types.strategy == DynamicWriteStrategy.EXPLICIT_ONLY) {
                throw MappingException(
                    MappingExceptionReason.CONVERSION_ERROR,
                    details = "${source::class.simpleName} is registered as the dynamic type " +
                        "'${registration.name}', and this client writes them on EXPLICIT_ONLY: wrap it in " +
                        "dynamicTypes.toDynamicDto(…), or build the client on another DynamicWriteStrategy."
                )
            }
            typeName = registration.name
            payload = registration.encode(source, types.jsonFor(context.types.catalog, enumAwareJson))
        }

        val composite = context.types.containers.createComposite(DYNAMIC_DTO_NAME, DYNAMIC_DTO_SCHEMA)
        composite[TYPE_NAME_ATTRIBUTE] = typeName
        composite[DATA_PAYLOAD_ATTRIBUTE] = payload
        return composite
    }
}

/**
 * Turns a `dynamic_dto` composite into whichever registered class its `type_name` names.
 *
 * It claims the value on the strength of the column's type alone, because the discriminator lives in the
 * value and [canConvert] does not get to see one. Everything that could still go wrong - a name nothing was
 * registered under, a payload that does not fit, a class that is not what the caller asked for - is settled in
 * [convert], where the value is in hand and the message can say which name and which class.
 */
private class DynamicDtoResultConverter(
    private val types: DynamicTypes,
    overrideJson: Json?
) : ResultConverter<PgComposite, Any> {

    /** Set only where this converter was made for one query, which is the whole of what makes it different. */
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
        return types.anyFits(kClass)
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

        val registration = types.forName(typeName) ?: throw MappingException(
            MappingExceptionReason.CONVERSION_ERROR,
            details = "No class is registered for the dynamic type '$typeName'. Register it at startup with " +
                "dynamicTypes.register<YourClass>().",
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

        val decoded = try {
            registration.decode(payload, types.jsonFor(context.types.catalog, enumAwareJson))
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
