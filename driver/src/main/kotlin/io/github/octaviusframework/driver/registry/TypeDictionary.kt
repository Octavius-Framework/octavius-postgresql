package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.UNRESOLVED_OID

/** The database's types, by OID and by name, immutable and replaced whole on a reload. */
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
         * The dictionary before the catalog is read: the built-in codecs' types, enough for the query that reads it.
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
     * The OID of [typeName], or of its array type, in [requestedSchema] or else the first schema of [searchPath]
     * that has it. A name off the path is refused, naming the schemas that have it.
     *
     * @param searchPath The server's lookup order for type names, not the setting.
     * @throws TypeException `TYPE_NOT_FOUND` if the type is not in the schema asked for or on the path.
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
            // 3. Off the path it is not found, whoever else has it
            if (resolvedOid == UNRESOLVED_OID) {
                val schemas = schemasForName.keys.sorted().joinToString { "'$it'" }
                val holders = if (schemasForName.size == 1) "schema $schemas has" else "schemas $schemas have"
                throw TypeException(
                    TypeExceptionReason.TYPE_NOT_FOUND,
                    typeName = typeName,
                    details = "Type '$typeName' is not on the search path $searchPath, but $holders it. " +
                        "Name the schema, or put it on the search path."
                )
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

    /** How many types this dictionary holds. */
    val size: Int get() = types.size

    /** Runs [action] on each type and its OID. */
    fun forEachType(action: (Int, PgType) -> Unit) {
        types.forEach(action)
    }

    /**
     * The type whose OID is [oid].
     *
     * @throws TypeException `TYPE_NOT_FOUND` if there is none.
     */
    fun getPgType(oid: Int): PgType = types[oid]
        ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, oid = oid, details = "Type with OID $oid not found")

    /**
     * The type whose OID is [oid], or for a domain the type under it, through every domain stacked on another. The
     * server describes a column this way; an OID inside an array or a composite can still name a domain.
     *
     * @throws TypeException `TYPE_NOT_FOUND` if there is none.
     */
    fun getBaseType(oid: Int): PgType {
        var type = getPgType(oid)
        while (type is PgType.Domain) type = getPgType(type.baseTypeOid)
        return type
    }

    /** Whether this dictionary describes [oid]. */
    internal fun describes(oid: Int): Boolean = types[oid] != null

    /** The OID of [typeName] in [schema], or `null`. */
    internal fun findOid(typeName: String, schema: String): Int? = typesByName[typeName]?.get(schema)

    /**
     * The array type over [elementOid].
     *
     * @throws TypeException `TYPE_NOT_FOUND` if there is none.
     */
    fun getArrayType(elementOid: Int): PgType.Array = arrayTypesByElementOid[elementOid]
        ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, oid = elementOid, details = "Array type for element OID $elementOid not found")

    /**
     * The range type over [elementOid].
     *
     * @throws TypeException `TYPE_NOT_FOUND` if there is none.
     */
    fun getRangeType(elementOid: Int): PgType.Range = rangeTypesByElementOid[elementOid]
        ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, oid = elementOid, details = "Range type for element OID $elementOid not found")

    /**
     * The multirange type over [rangeOid].
     *
     * @throws TypeException `TYPE_NOT_FOUND` if there is none.
     */
    fun getMultirangeType(rangeOid: Int): PgType.Multirange = multirangeTypesByRangeOid[rangeOid]
        ?: throw TypeException(TypeExceptionReason.TYPE_NOT_FOUND, oid = rangeOid, details = "Multirange type for range OID $rangeOid not found")

    /**
     * The row type of the relation [relationOid], or `null` for one the load skipped (`pg_catalog`,
     * `information_schema`, temporary) or one created since.
     */
    fun findCompositeByRelation(relationOid: Int): PgType.Composite? = compositeTypesByRelationOid[relationOid]

}
