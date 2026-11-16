package io.github.octaviusframework.driver.execution

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterMapper
import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.registry.CatalogHolder
import io.github.octaviusframework.driver.registry.TypeManager
import io.github.octaviusframework.driver.type.PgStandardType
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.withPgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/** The OIDs [ParameterSerializer] declares for the values it writes. */
class ParameterSerializerTest : AbstractIntegrationTest() {

    private fun serializer(typeManager: TypeManager) =
        ParameterSerializer(typeManager.lookup, ParameterMapper(typeManager.catalog, null, typeManager.lookup))

    @Test
    fun `each value is declared with the OID of its own type, and a null with none`() {
        val typeManager = TypeManager(CatalogHolder())

        val oids = serializer(typeManager).serializeAll(arrayOf<Any?>(123, "test", null, true), PgByteWriter())

        assertContentEquals(
            intArrayOf(PgStandardType.INT4.oid, PgStandardType.TEXT.oid, 0, PgStandardType.BOOL.oid),
            oids
        )
    }

    @Test
    fun `withPgType declares the type it names`() {
        val registry = CatalogHolder()
        registry.update {
            val int8 = PgStandardType.INT8
            it.withTypes(mapOf(int8.oid to PgType.Base(int8.oid, int8.typeName, "pg_catalog")))
        }
        // The name goes out unqualified, and a session's path always has pg_catalog on it.
        val typeManager = TypeManager(registry) { listOf("pg_catalog") }

        val typed = 123L.withPgType(PgStandardType.INT8)
        val oids = serializer(typeManager).serializeAll(arrayOf<Any?>(typed), PgByteWriter())

        assertContentEquals(intArrayOf(PgStandardType.INT8.oid), oids)
    }
}
