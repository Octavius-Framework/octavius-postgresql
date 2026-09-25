package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.type.isKnownOid

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgStandardType
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.type.withPgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class JsonElementIntegrationTest : AbstractIntegrationTest() {

    data class MetadataHolder(
        val id: Int,
        val metadata: JsonObject
    )

    class MetadataHolderResultConverter : ResultConverter<PgComposite, MetadataHolder> {
        override val supportedSourceClass = PgComposite::class
        override fun canConvert(sourceClass: kotlin.reflect.KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
            return expectedType.classifier == MetadataHolder::class
        }

        private val jsonObjectType = typeOf<JsonObject>()

        override fun convert(
            source: PgComposite,
            expectedType: KType,
            sourceType: PgType,
            context: DeserializationContext
        ): MetadataHolder {
            return MetadataHolder(
                id = source.get("id"),
                metadata = context.convert(
                    source.get("metadata"),
                    jsonObjectType,
                    source.getAttributeOid("metadata")
                )
            )
        }
    }

    class MetadataHolderParameterConverter : ParameterConverter<MetadataHolder> {
        override val supportedClass = MetadataHolder::class

        override fun convert(
            source: MetadataHolder,
            expectedOid: Int,
            context: SerializationContext
        ): Any {
            val composite = if (expectedOid.isKnownOid) {
                context.types.containers.createComposite(expectedOid)
            } else {
                context.types.containers.createComposite("metadata_holder")
            }
            composite["id"] = source.id
            composite["metadata"] = context.convert(source.metadata, composite.getAttributeOid("metadata"))
            return composite
        }
    }

    override val schema = """
        CREATE TABLE test_json_elements (id int PRIMARY KEY, data jsonb);
        CREATE TYPE metadata_holder AS (id int, metadata jsonb);
    """.trimIndent()

    @Test
    fun testJsonElementAsParameterAndResult() {
        val conn = openSession()
        try {
            val inputJson = buildJsonObject {
                put("key", JsonPrimitive("value123"))
                put("number", JsonPrimitive(42))
            }

            conn.createNamedQuery("INSERT INTO test_json_elements (id, data) VALUES (@id, @data)")
                .update(mapOf("id" to 1, "data" to inputJson))

            val row = conn.createNamedQuery("SELECT data FROM test_json_elements WHERE id = @id")
                .fetchRowStrict(mapOf("id" to 1))

            val outputJson = row.get<JsonObject>("data")
            assertEquals("value123", outputJson["key"]?.let { (it as JsonPrimitive).content })
            assertEquals("42", outputJson["number"]?.let { (it as JsonPrimitive).content })
        } finally {
            conn.close()
        }
    }

    @Test
    fun testJsonElementInsideComposite() {
        val conn = openSession()
        try {
            conn.reloadTypes()

            // Register hand-written converters for the composite
            conn.typeManager.registerResultConverter(MetadataHolderResultConverter())
            conn.typeManager.registerParameterConverter(MetadataHolderParameterConverter())

            val inputJson = buildJsonObject {
                put("status", JsonPrimitive("active"))
            }
            val holder = MetadataHolder(100, inputJson)

            val row = conn.createNamedQuery("SELECT @holder as res")
                .fetchRowStrict("holder" to holder)

            val outputHolder = row.get<MetadataHolder>("res")
            assertEquals(100, outputHolder.id)
            val outputJson = outputHolder.metadata
            assertEquals("active", (outputJson["status"] as JsonPrimitive).content)
        } finally {
            conn.close()
        }
    }

    @Test
    fun testJsonElementListAsParameter() {
        val conn = openSession()
        try {
            val list = listOf(
                buildJsonObject { put("key1", JsonPrimitive("val1")) },
                buildJsonObject { put("key2", JsonPrimitive("val2")) }
            )

            // Pass the list with no explicit type; it should be inferred as jsonb[]
            val row = conn.createNamedQuery("SELECT @list as res")
                .fetchRowStrict("list" to list)

            val outputList = row.get<List<JsonObject>>("res")
            assertEquals(2, outputList.size)
            assertEquals("val1", outputList[0]["key1"]?.let { (it as JsonPrimitive).content })
            assertEquals("val2", outputList[1]["key2"]?.let { (it as JsonPrimitive).content })
        } finally {
            conn.close()
        }
    }

    @Test
    fun testJsonElementWithExplicitType() {
        val conn = openSession()
        try {
            val inputJson = buildJsonObject {
                put("key", JsonPrimitive("explicit"))
            }

            val row = conn.createNamedQuery("SELECT pg_typeof(@data)::text as type_name, @data as res")
                .fetchRowStrict("data" to inputJson.withPgType(PgStandardType.JSON))

            val typeName = row.get<String>("type_name")
            assertEquals("json", typeName)

            val outputJson = row.get<JsonElement>("res")
            assertEquals("explicit", (outputJson as JsonObject)["key"]?.let { (it as JsonPrimitive).content })
        } finally {
            conn.close()
        }
    }
}

