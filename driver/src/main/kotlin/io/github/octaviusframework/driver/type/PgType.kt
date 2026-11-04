package io.github.octaviusframework.driver.type

/** A PostgreSQL type: one subclass per kind, and one object each for the pseudo-types `record` and `void`. */
sealed class PgType(
    open val oid: Int,
    open val name: String,
    open val schema: String,
) {
    /** A base type: `int4`, `text`, `uuid`. */
    data class Base(
        override val oid: Int,
        override val name: String,
        override val schema: String
    ) : PgType(oid, name, schema)

    /** An array type. */
    data class Array(
        override val oid: Int,
        override val name: String,
        override val schema: String,
        val elementOid: Int
    ) : PgType(oid, name, schema)

    /**
     * A range type.
     *
     * @property subtypeOid The OID of the bounds' type.
     */
    data class Range(
        override val oid: Int,
        override val name: String,
        override val schema: String,
        val subtypeOid: Int
    ) : PgType(oid, name, schema)

    /**
     * A composite type: one declared with `CREATE TYPE ... AS`, or the row type of a table or view.
     *
     * @property attributes Each attribute's type OID by name, in declaration order.
     * @property relationOid The `pg_class.oid` this type describes.
     * @property attributeNumbers Each attribute's number, in the order of [attributes]. A dropped column leaves a
     *   gap.
     */
    data class Composite(
        override val oid: Int,
        override val name: String,
        override val schema: String,
        val attributes: LinkedHashMap<String, Int>,
        val relationOid: Int = 0,
        val attributeNumbers: List<Int> = emptyList()
    ) : PgType(oid, name, schema) {
        /** The attributes' type OIDs, in declaration order. */
        val attributeOids: List<Int> = attributes.values.toList()

        /** The attribute names, in declaration order. */
        val attributeNames: List<String> = attributes.keys.toList()

        /** Each attribute's zero-based index, by name. */
        val nameToIndex: Map<String, Int> = run {
            val map = HashMap<String, Int>()
            attributes.keys.forEachIndexed { index, name -> map[name] = index }
            map
        }

        /** The attribute numbered [attributeNumber], or `null` for a dropped or system column. */
        fun attributeNameByNumber(attributeNumber: Int): String? {
            val index = attributeNumbers.indexOf(attributeNumber)
            return if (index == -1) null else attributeNames[index]
        }
    }

    /** A domain over the type [baseTypeOid]. */
    data class Domain(
        override val oid: Int,
        override val name: String,
        override val schema: String,
        val baseTypeOid: Int
    ) : PgType(oid, name, schema)

    /**
     * An enum type.
     *
     * @property values The labels, in their declared order.
     */
    data class Enum(
        override val oid: Int,
        override val name: String,
        override val schema: String,
        val values: List<String>
    ) : PgType(oid, name, schema)

    /** A multirange of the range type [rangeOid]. */
    data class Multirange(
        override val oid: Int,
        override val name: String,
        override val schema: String,
        val rangeOid: Int
    ) : PgType(oid, name, schema)

    /** The `record` pseudo-type. */
    data object Record : PgType(2249, "record", "pg_catalog")

    /** The `void` pseudo-type. */
    data object Void : PgType(2278, "void", "pg_catalog")
}

