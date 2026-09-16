package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.UNRESOLVED_OID

/**
 * A dictionary that holds PostgreSQL types definitions and allows querying them by OID or name.
 * 
 * It provides specialized methods to fetch specific type categories such as arrays, ranges,
 * and multiranges. Instances of this class are immutable and intended to be replaced 
 * entirely when types are reloaded.
 */
class TypeDictionary private constructor(
    private val types: IntObjectMap<PgType>,
    private val typesByName: Map<String, Map<String, Int>>,
    private val arrayTypesByElementOid: IntObjectMap<PgType.Array>,
    private val rangeTypesByElementOid: IntObjectMap<PgType.Range>,
    private val multirangeTypesByRangeOid: IntObjectMap<PgType.Multirange>,
    private val compositeTypesByRelationOid: IntObjectMap<PgType.Composite>
) {
    //--------------------------------------------Construction----------------------------------------------------------
    companion object {
        /**
         * Builds the dictionary the driver starts out with: one entry per codec it ships with an OID of its own.
         *
         * There is one query that has to run before any catalog has been read - the read itself - and its columns
         * are `oid`, `name`, `char` and `int2`, types the driver already names in the codecs it hardcodes them
         * for. Every entry here is replaced by the real catalog entry as soon as [build] runs.
         *
         * @param codecs the codec dictionary to take the names and OIDs from.
         */
        internal fun ofBuiltinCodecs(codecs: CodecDictionary): TypeDictionary {
            val types = HashMap<Int, PgType>()
            for (codec in codecs.registeredCodecs) {
                val oid = codec.oid ?: continue
                types[oid] = PgType.Base(oid, codec.pgTypeName, codec.pgSchema)
            }
            return build(types)
        }

        internal fun build(newTypes: Map<Int, PgType>): TypeDictionary {
            val intMap = IntObjectMap<PgType>((newTypes.size / 0.75).toInt() + 1)
            val newTypesByName = mutableMapOf<String, MutableMap<String, Int>>()
            val newArrayTypesByElementOid = IntObjectMap<PgType.Array>()
            val newRangeTypesByElementOid = IntObjectMap<PgType.Range>()
            val newMultirangeTypesByRangeOid = IntObjectMap<PgType.Multirange>()
            val newCompositeTypesByRelationOid = IntObjectMap<PgType.Composite>()

            for ((oid, type) in newTypes) {
                intMap[oid] = type
                newTypesByName.getOrPut(type.name) { mutableMapOf() }[type.schema] = oid
                when (type) {
                    is PgType.Array -> newArrayTypesByElementOid[type.elementOid] = type
                    is PgType.Range -> newRangeTypesByElementOid[type.subtypeOid] = type
                    is PgType.Multirange -> newMultirangeTypesByRangeOid[type.rangeOid] = type
                    is PgType.Composite -> if (type.relationOid != 0) newCompositeTypesByRelationOid[type.relationOid] = type
                    else -> {}
                }
            }

            return TypeDictionary(
                intMap,
                newTypesByName,
                newArrayTypesByElementOid,
                newRangeTypesByElementOid,
                newMultirangeTypesByRangeOid,
                newCompositeTypesByRelationOid
            )
        }
    }

    /**
     * Resolves the OID of a type given its name and an optional schema.
     *
     * @param typeName the name of the type.
     * @param requestedSchema the schema where the type belongs (can be empty).
     * @param isArray whether to resolve the OID for the array type of the given type.
     * @param searchPath the list of schemas to search if the schema is not explicitly provided.
     * @return the resolved OID.
     * @throws TypeException if the type cannot be found or is ambiguous.
     */
    internal fun resolveOid(
        typeName: String,
        requestedSchema: String,
        isArray: Boolean = false,
        searchPath: List<String>
    ): Int {
        val schemasForName = typesByName[typeName]
            ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, typeName = typeName, details = "Type '$typeName' not found")

        var resolvedOid: Int = UNRESOLVED_OID
        // 1. If schema is explicitly requested
        if (requestedSchema.isNotEmpty()) {
            resolvedOid = schemasForName[requestedSchema]
                ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, typeName = typeName, details = "Type '$typeName' not found in schema '$requestedSchema'")
        } else {
            // 2. If schema is empty, look in search_path (first match wins)
            for (i in searchPath.indices) {
                val oid = schemasForName[searchPath[i]]
                if (oid != null) {
                    resolvedOid = oid
                    break
                }
            }
            // 3. If not in search_path, check for unambiguous match
            if (resolvedOid == UNRESOLVED_OID) {
                if (schemasForName.size == 1) {
                    resolvedOid = schemasForName.values.first()
                } else {
                    throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, typeName = typeName, details = "Ambiguous type '$typeName'. Schema must be specified.")
                }
            }
        }

        return if (isArray) {
            arrayTypesByElementOid[resolvedOid]?.oid
                ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, typeName = typeName, details = "Array type for '$typeName' not found")
        } else {
            resolvedOid
        }
    }

    //----------------------------------------------------API-----------------------------------------------------------

    /**
     * How many types this dictionary holds.
     */
    val size: Int get() = types.size

    /**
     * Executes the given action on each registered type.
     *
     * @param action the action to perform on each type, taking the OID and the [PgType].
     */
    fun forEachType(action: (Int, PgType) -> Unit) {
        types.forEach(action)
    }

    /**
     * Retrieves a PostgreSQL type by its OID.
     *
     * @param oid the Object Identifier of the type.
     * @return the [PgType] associated with the given OID.
     * @throws TypeException if the type is not found.
     */
    fun getPgType(oid: Int): PgType = types[oid]
        ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, oid = oid, details = "Type with OID $oid not found")

    /**
     * Whether this dictionary describes [oid] at all - the question [getPgType] answers by raising.
     */
    internal fun describes(oid: Int): Boolean = types[oid] != null

    /**
     * The OID of one named type in one named schema, or `null` where this dictionary has no such type.
     *
     * What [resolveOid] does without the search path, the ambiguity and the raising: a fully qualified name
     * identifies at most one type, `pg_type` being unique on name and namespace.
     */
    internal fun findOid(typeName: String, schema: String): Int? = typesByName[typeName]?.get(schema)

    /**
     * Retrieves an array type by the OID of its elements.
     *
     * @param elementOid the OID of the array's elements.
     * @return the [PgType.Array] type.
     * @throws TypeException if the array type is not found.
     */
    fun getArrayType(elementOid: Int): PgType.Array = arrayTypesByElementOid[elementOid]
        ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, oid = elementOid, details = "Array type for element OID $elementOid not found")

    /**
     * Retrieves a range type by the OID of its elements.
     *
     * @param elementOid the OID of the range's subtype elements.
     * @return the [PgType.Range] type.
     * @throws TypeException if the range type is not found.
     */
    fun getRangeType(elementOid: Int): PgType.Range = rangeTypesByElementOid[elementOid]
        ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, oid = elementOid, details = "Range type for element OID $elementOid not found")

    /**
     * Retrieves a multirange type by the OID of its base range type.
     *
     * @param rangeOid the OID of the underlying range type.
     * @return the [PgType.Multirange] type.
     * @throws TypeException if the multirange type is not found.
     */
    fun getMultirangeType(rangeOid: Int): PgType.Multirange = multirangeTypesByRangeOid[rangeOid]
        ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, oid = rangeOid, details = "Multirange type for range OID $rangeOid not found")

    /**
     * Returns the row type of the relation with the given [relationOid], or `null` when this dictionary holds
     * none for it.
     *
     * Unlike the rest of the lookups here, a miss is an ordinary answer rather than a failure: the type load
     * skips composites in `pg_catalog` and `information_schema`, and a relation created since the load has no
     * row type here yet. Callers are naming something for a reader, not resolving a type they have to have.
     *
     * @param relationOid the OID of the relation (`pg_class.oid`).
     */
    fun findCompositeByRelation(relationOid: Int): PgType.Composite? = compositeTypesByRelationOid[relationOid]

}
