package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.codec.TypeCodec
import io.github.octaviusframework.driver.codec.dynamic.DynamicContainerCodec
import io.github.octaviusframework.driver.codec.dynamic.DynamicDomainCodec
import io.github.octaviusframework.driver.codec.dynamic.DynamicEnumCodec
import io.github.octaviusframework.driver.codec.standard.*
import io.github.octaviusframework.driver.container.*
import io.github.octaviusframework.driver.type.PgType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

/**
 * A dictionary that maps PostgreSQL OIDs and Kotlin classes to their corresponding [TypeCodec]s.
 * 
 * Provides methods for looking up codecs for parameters and results mapping, as well as 
 * registering custom codecs. Instances are immutable.
 */
class CodecDictionary private constructor(
    private val codecsByOid: IntObjectMap<TypeCodec<*>>,
    private val codecsByClass: Map<KClass<*>, TypeCodec<*>>,
    private val codecToOid: Map<TypeCodec<*>, Int>,
    val registeredCodecs: List<TypeCodec<*>>,
    /**
     * How many of [registeredCodecs] the driver ships with. They come first and a registration appends, so the
     * rest are the ones an application asked for - the only ones worth reporting on when they map to nothing.
     */
    private val builtinCount: Int
) {
    //-------------------------------------------Construction-----------------------------------------------------------
    companion object {

        internal fun createWithBuiltins(): CodecDictionary {
            val oidMap = IntObjectMap<TypeCodec<*>>()
            val classMap = mutableMapOf<KClass<*>, TypeCodec<*>>()
            val codecToOidMap = mutableMapOf<TypeCodec<*>, Int>()
            val registeredCodecsList = mutableListOf<TypeCodec<*>>()

            fun register(codec: TypeCodec<*>) {
                registeredCodecsList.add(codec)
                if (codec.isDefaultForKotlinType) {
                    classMap[codec.kotlinClass] = codec

                    if (codec.kotlinClass.isSealed) {
                        codec.kotlinClass.sealedSubclasses.forEach { subclass ->
                            classMap[subclass] = codec
                        }
                    }
                }
                if (codec.oid != null) {
                    oidMap[codec.oid!!] = codec
                    codecToOidMap[codec] = codec.oid!!
                }
            }

            // Postgres Internal Types
            register(OidCodec)
            register(NameCodec)
            register(CharCodec)

            // Number Types
            register(SmallIntCodec)
            register(IntCodec)
            register(BigIntCodec)
            register(RealCodec)
            register(DoubleCodec)
            register(NumericCodec)
            // Text Types
            register(TextCodec)
            register(VarcharCodec)
            register(BpcharCodec)
            register(UnknownCodec)
            // Json and XML
            register(JsonbCodec)
            register(JsonCodec)
            register(XmlCodec)
            // DateTime
            register(TimestamptzCodec)
            register(TimestampCodec)
            register(DateCodec)
            register(TimeCodec)
            register(IntervalCodec)
            // Other
            register(ByteaCodec)
            register(UuidCodec)
            register(VoidCodec)
            register(BooleanCodec)
            register(BitCodec)
            register(VarbitCodec)
            // Networking
            register(MacAddrCodec)
            register(MacAddr8Codec)
            register(InetCodec)
            register(CidrCodec)
            // Geometric Types
            register(PointCodec)
            register(LsegCodec)
            register(PathCodec)
            register(BoxCodec)
            register(PolygonCodec)
            register(LineCodec)
            register(CircleCodec)

            return CodecDictionary(oidMap, classMap, codecToOidMap, registeredCodecsList, registeredCodecsList.size)
        }
    }

    /**
     * Creates a new [CodecDictionary] by registering an additional [TypeCodec].
     *
     * @param codec the new codec to register.
     * @param dictionary the [TypeDictionary] used to resolve the type OID if not explicitly provided by the codec.
     * @return a new instance of [CodecDictionary] containing the newly registered codec.
     * @throws io.github.octaviusframework.driver.exception.TypeException `TYPE_NOT_FOUND` where [codec] names a
     *   schema-qualified type the catalog does not describe.
     */
    internal fun withRegisteredCodec(codec: TypeCodec<*>, dictionary: TypeDictionary): CodecDictionary {
        val result = build(this.registeredCodecs + codec, dictionary)

        // A schema-qualified type the catalog does not describe is a mistake in the registration, and this is the
        // one moment the caller is still on the stack to hear about it - a reload finding the same thing later is
        // the database having changed, and only warns. resolveOid is what raises it, naming the type and schema.
        if (codec.oid == null && codec.pgSchema.isNotBlank() && result.getOidForCodec(codec) == null) {
            dictionary.resolveOid(codec.pgTypeName, codec.pgSchema, searchPath = emptyList())
        }

        return result
    }

    /**
     * Rebuilds the dictionary against a catalog's types, keeping the codecs registered by hand and creating one
     * for every type they leave uncovered.
     *
     * @param dictionary the types this dictionary is to describe.
     * @return a new updated instance of [CodecDictionary].
     */
    internal fun buildUpdated(dictionary: TypeDictionary): CodecDictionary = build(this.registeredCodecs, dictionary)

    /**
     * The one way a dictionary with dynamic codecs in it comes to exist.
     *
     * Every codec it creates is bound to a [CodecScope] carrying [dictionary] and the dictionary being built, so
     * a codec never reads a catalog other than the one it belongs to. That is also why both callers come through
     * here rather than editing a copy of an existing dictionary: a dynamic codec kept from a previous one would
     * still be resolving nested values through the previous one's pair.
     */
    private fun build(registered: List<TypeCodec<*>>, dictionary: TypeDictionary): CodecDictionary {
        val newOidMap = IntObjectMap<TypeCodec<*>>()
        val newClassMap = mutableMapOf<KClass<*>, TypeCodec<*>>()
        val newCodecToOid = mutableMapOf<TypeCodec<*>, Int>()

        for ((index, codec) in registered.withIndex()) {
            if (codec.isDefaultForKotlinType) {
                newClassMap[codec.kotlinClass] = codec
                if (codec.kotlinClass.isSealed) {
                    codec.kotlinClass.sealedSubclasses.forEach { subclass ->
                        newClassMap[subclass] = codec
                    }
                }
            }

            val declaredOid = codec.oid
            val reachable: Boolean
            if (declaredOid != null) {
                newOidMap[declaredOid] = codec
                newCodecToOid[codec] = declaredOid
                reachable = dictionary.describes(declaredOid)
            } else if (codec.pgSchema.isNotBlank()) {
                val namedOid = dictionary.findOid(codec.pgTypeName, codec.pgSchema)
                if (namedOid != null) {
                    newOidMap[namedOid] = codec
                    newCodecToOid[codec] = namedOid
                }
                reachable = namedOid != null
            } else {
                var matched = false
                dictionary.forEachType { oid, type ->
                    if (type.name == codec.pgTypeName) {
                        newOidMap[oid] = codec
                        matched = true
                    }
                }
                reachable = matched
            }

            if (!reachable && index >= builtinCount) warnUnreachable(codec, declaredOid)
        }

        val scope = CodecScope(dictionary)
        dictionary.forEachType { oid, type ->
            if (!newOidMap.containsKey(oid)) {
                val codec = when (type) {
                    is PgType.Enum -> DynamicEnumCodec(oid, type.name, type.schema)
                    is PgType.Domain -> DynamicDomainCodec(oid, type.name, type.schema, type.baseTypeOid, scope)
                    is PgType.Array -> DynamicContainerCodec(oid, type.name, type.schema, PgArray::class, scope)
                    is PgType.Composite -> DynamicContainerCodec(
                        oid, type.name, type.schema, PgComposite::class, scope
                    )

                    is PgType.Record -> DynamicContainerCodec(oid, type.name, type.schema, PgRecord::class, scope)
                    is PgType.Range -> DynamicContainerCodec(oid, type.name, type.schema, PgRange::class, scope)
                    is PgType.Multirange -> DynamicContainerCodec(
                        oid, type.name, type.schema, PgMultirange::class, scope
                    )

                    else -> null
                }
                if (codec != null) {
                    newOidMap[oid] = codec
                    newCodecToOid[codec] = oid
                }
            }
        }

        return CodecDictionary(newOidMap, newClassMap, newCodecToOid, registered, builtinCount)
            .also { scope.bind(it) }
    }

    //----------------------------------------------API-----------------------------------------------------------------

    /**
     * Retrieves a [TypeCodec] suitable for the given PostgreSQL OID.
     *
     * @param oid the Object Identifier of the PostgreSQL type.
     * @return the corresponding [TypeCodec] or null if no codec is registered for this OID.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getCodecByOid(oid: Int): TypeCodec<T>? {
        return codecsByOid[oid] as TypeCodec<T>?
    }

    /**
     * Retrieves a default [TypeCodec] for the given Kotlin class.
     *
     * @param kClass the Kotlin class to find a codec for.
     * @return the corresponding [TypeCodec] or null if no default codec is registered for this class.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getCodecByClass(kClass: KClass<T>): TypeCodec<T>? {
        return codecsByClass[kClass] as TypeCodec<T>?
    }

    /**
     * Retrieves the mapped OID for a specific [TypeCodec].
     *
     * @param codec the codec to look up.
     * @return the OID associated with the codec or null if it cannot be determined.
     */
    /**
     * Says that a registered codec has nothing to decode.
     *
     * It stays registered - a later reload finds it again if the type comes back - but until then values of that
     * type go through whatever else the catalog binds to their OID, which for a composite or an array is the
     * driver's own container codec. That is a different shape coming back rather than an error, so it is worth a
     * line: nothing else in the driver will mention it.
     */
    private fun warnUnreachable(codec: TypeCodec<*>, declaredOid: Int?) {
        val name = codec::class.simpleName ?: codec::class
        if (declaredOid != null) {
            logger.warn {
                "Codec $name declares OID $declaredOid, which this database's catalog does not describe - " +
                    "nothing will reach it. A type dropped and recreated has a new OID; registering the codec " +
                    "under its type name rather than an OID survives that."
            }
        } else {
            val typeName = if (codec.pgSchema.isBlank()) codec.pgTypeName else "${codec.pgSchema}.${codec.pgTypeName}"
            logger.warn {
                "Codec $name is registered for type '$typeName', which this database's catalog does not " +
                    "describe - nothing will reach it."
            }
        }
    }

    fun getOidForCodec(codec: TypeCodec<*>): Int? {
        return codecToOid[codec]
    }
}
