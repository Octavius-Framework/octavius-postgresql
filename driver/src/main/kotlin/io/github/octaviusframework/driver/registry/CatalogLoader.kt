package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.type.PgType

/** Reads the database's types from `pg_catalog`. */
internal object CatalogLoader {

    /**
     * Reads the types into [holder]'s catalog. Temporary schemas are left out: each belongs to one session, while the
     * catalog is shared across the database.
     */
    fun load(holder: CatalogHolder, queryExecutor: QueryExecutor) {
        // typtype: b base, c composite, d domain, e enum, p pseudo, r range, m multirange.
        val typesSql = """
            SELECT 
                t.oid, t.typname, t.typelem, t.typarray, t.typtype, t.typbasetype, n.nspname,
                e.enumlabel,
                r.rngsubtype,
                a.attname, a.atttypid,
                r.rngmultitypid,
                t.typrelid, a.attnum
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            LEFT JOIN pg_catalog.pg_enum e ON t.oid = e.enumtypid
            LEFT JOIN pg_catalog.pg_range r ON t.oid = r.rngtypid
            LEFT JOIN pg_catalog.pg_attribute a ON t.typrelid = a.attrelid AND a.attnum > 0 AND a.attisdropped = false
            WHERE NOT (t.typtype = 'c' AND n.nspname IN ('pg_catalog', 'information_schema'))
            AND n.nspname !~ '^pg_(toast_)?temp_[0-9]+$'
            AND NOT (t.typtype = 'p' AND t.typname NOT IN ('void', 'record', '_record', 'unknown'))
            ORDER BY t.oid, e.enumsortorder, a.attnum
        """.trimIndent()

        val typeManager = TypeManager(holder) // Use only codecs for internal postgres types
        val resultMapper = ResultMapper(typeManager.catalog, null, typeManager.lookup)
        val result = queryExecutor.query(typesSql, mapper = resultMapper)

        val enumMap = mutableMapOf<Int, MutableList<String>>()
        val attrMap = mutableMapOf<Int, LinkedHashMap<String, Int>>()
        // Apart from attrMap: a dropped column leaves a gap, so a number is not a position.
        val attrNumberMap = mutableMapOf<Int, MutableList<Int>>()
        val rangeMap = mutableMapOf<Int, Int>()
        val multirangeMap = mutableMapOf<Int, Int>()

        class BaseTypeInfo(
            val name: String, val typelem: Int, val typarray: Int,
            val typtype: Char, val typbasetype: Int, val schema: String,
            val typrelid: Int
        )

        val parsedTypes = mutableMapOf<Int, BaseTypeInfo>()

        for (row in result) {
            val oid = row.getRaw(0) as Int

            // We collect the main type information only the first time for a given OID
            if (oid !in parsedTypes) {
                val name = row.getRaw(1) as String
                val typelem = row.getRaw(2) as Int
                val typarray = row.getRaw(3) as Int
                val typtypeString = row.getRaw(4) as String
                val typtype = typtypeString.first()
                val typbasetype = row.getRaw(5) as Int
                val schema = row.getRaw(6) as String
                val typrelid = row.getRaw(12) as Int

                parsedTypes[oid] = BaseTypeInfo(name, typelem, typarray, typtype, typbasetype, schema, typrelid)
            }

            val enumLabel = row.getRaw(7) as String?
            if (enumLabel != null) {
                val enumList = enumMap.getOrPut(oid) { mutableListOf() }
                if (!enumList.contains(enumLabel)) {
                    enumList.add(enumLabel)
                }
            }

            // Range
            val rngSubtype = row.getRaw(8) as Int?
            if (rngSubtype != null) {
                rangeMap[oid] = rngSubtype

                val multirangeOid = row.getRaw(11) as Int?
                if (multirangeOid != null && multirangeOid != 0) {
                    multirangeMap[multirangeOid] = oid
                }
            }

            // Composite
            val attName = row.getRaw(9) as String?
            val attTypid = row.getRaw(10) as Int?

            if (attName != null && attTypid != null) {
                val attrList = attrMap.getOrPut(oid) { LinkedHashMap() }
                if (!attrList.containsKey(attName)) {
                    attrList[attName] = attTypid
                    attrNumberMap.getOrPut(oid) { mutableListOf() }.add((row.getRaw(13) as Short).toInt())
                }
            }
        }

        val newTypes = mutableMapOf<Int, PgType>()

        // Final construction of correct instance objects for each detected type
        for ((oid, info) in parsedTypes) {
            val pgType =
                when {
                    info.typtype == 'e' -> PgType.Enum(oid, info.name, info.schema, enumMap[oid] ?: emptyList())
                    info.typtype == 'd' -> PgType.Domain(oid, info.name, info.schema, info.typbasetype)
                    info.typtype == 'r' -> PgType.Range(
                        oid,
                        info.name,
                        info.schema,
                        rangeMap[oid] ?: error("Missing rangeMap for oid $oid")
                    )

                    info.typtype == 'm' -> PgType.Multirange(
                        oid,
                        info.name,
                        info.schema,
                        multirangeMap[oid] ?: error("Missing multirangeMap for oid $oid")
                    )

                    info.typtype == 'c' -> {
                        val attrs = attrMap[oid] ?: LinkedHashMap()
                        val attrNumbers = attrNumberMap[oid] ?: emptyList()
                        PgType.Composite(oid, info.name, info.schema, attrs, info.typrelid, attrNumbers)
                    }

                    info.typtype == 'p' -> {
                        when (info.name) {
                            "record" -> PgType.Record
                            "void" -> PgType.Void
                            "_record" -> PgType.Array(oid, info.name, info.schema, info.typelem)
                            "unknown" -> PgType.Base(oid, info.name, info.schema)
                            else -> error("Unknown pseudo-type ${info.name}")
                        }
                    }

                    info.typelem != 0 && info.typarray == 0 -> PgType.Array(oid, info.name, info.schema, info.typelem)
                    else -> PgType.Base(oid, info.name, info.schema)
                }

            newTypes[oid] = pgType
        }

        holder.update { it.withTypes(newTypes) }
    }
}

