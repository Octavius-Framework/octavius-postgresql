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

    data class Dispatch(
        val id: Int,
        val report: JsonObject
    )

    class DispatchResultConverter : ResultConverter<PgComposite, Dispatch> {
        override val supportedSourceClass = PgComposite::class
        override fun canConvert(sourceClass: kotlin.reflect.KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
            return expectedType.classifier == Dispatch::class
        }

        private val jsonObjectType = typeOf<JsonObject>()

        override fun convert(
            source: PgComposite,
            expectedType: KType,
            sourceType: PgType,
            context: DeserializationContext
        ): Dispatch {
            return Dispatch(
                id = source.get("id"),
                report = context.convert(
                    source.get("report"),
                    jsonObjectType,
                    source.getAttributeOid("report")
                )
            )
        }
    }

    class DispatchParameterConverter : ParameterConverter<Dispatch> {
        override val supportedClass = Dispatch::class

        override fun convert(
            source: Dispatch,
            expectedOid: Int,
            context: SerializationContext
        ): Any {
            val composite = if (expectedOid.isKnownOid) {
                context.types.containers.createComposite(expectedOid)
            } else {
                context.types.containers.createComposite("dispatch")
            }
            composite["id"] = source.id
            composite["report"] = context.convert(source.report, composite.getAttributeOid("report"))
            return composite
        }
    }

    override val schema = """
        CREATE TABLE dispatches (id int PRIMARY KEY, data jsonb);
        CREATE TYPE dispatch AS (id int, report jsonb);
    """.trimIndent()

    @Test
    fun testJsonElementAsParameterAndResult() {
        val conn = openSession()
        try {
            val inputJson = buildJsonObject {
                put("legio", JsonPrimitive("X Equestris"))
                put("cohortes", JsonPrimitive(10))
            }

            conn.createNamedQuery("INSERT INTO dispatches (id, data) VALUES (@id, @data)")
                .update(mapOf("id" to 1, "data" to inputJson))

            val row = conn.createNamedQuery("SELECT data FROM dispatches WHERE id = @id")
                .fetchRowStrict(mapOf("id" to 1))

            val outputJson = row.get<JsonObject>("data")
            assertEquals("X Equestris", outputJson["legio"]?.let { (it as JsonPrimitive).content })
            assertEquals("10", outputJson["cohortes"]?.let { (it as JsonPrimitive).content })
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
            conn.typeManager.registerResultConverter(DispatchResultConverter())
            conn.typeManager.registerParameterConverter(DispatchParameterConverter())

            val inputJson = buildJsonObject {
                put("outcome", JsonPrimitive("victoria"))
            }
            val holder = Dispatch(100, inputJson)

            val row = conn.createNamedQuery("SELECT @holder as res")
                .fetchRowStrict("holder" to holder)

            val outputHolder = row.get<Dispatch>("res")
            assertEquals(100, outputHolder.id)
            val outputJson = outputHolder.report
            assertEquals("victoria", (outputJson["outcome"] as JsonPrimitive).content)
        } finally {
            conn.close()
        }
    }

    @Test
    fun testJsonElementListAsParameter() {
        val conn = openSession()
        try {
            val list = listOf(
                buildJsonObject { put("castra", JsonPrimitive("Vetera")) },
                buildJsonObject { put("castra", JsonPrimitive("Mogontiacum")) }
            )

            // Pass the list with no explicit type; it should be inferred as jsonb[]
            val row = conn.createNamedQuery("SELECT @list as res")
                .fetchRowStrict("list" to list)

            val outputList = row.get<List<JsonObject>>("res")
            assertEquals(2, outputList.size)
            assertEquals("Vetera", outputList[0]["castra"]?.let { (it as JsonPrimitive).content })
            assertEquals("Mogontiacum", outputList[1]["castra"]?.let { (it as JsonPrimitive).content })
        } finally {
            conn.close()
        }
    }

    @Test
    fun testJsonElementWithExplicitType() {
        val conn = openSession()
        try {
            val inputJson = buildJsonObject {
                put("nuntius", JsonPrimitive("veni vidi vici"))
            }

            val row = conn.createNamedQuery("SELECT pg_typeof(@data)::text as type_name, @data as res")
                .fetchRowStrict("data" to inputJson.withPgType(PgStandardType.JSON))

            val typeName = row.get<String>("type_name")
            assertEquals("json", typeName)

            val outputJson = row.get<JsonElement>("res")
            assertEquals("veni vidi vici", (outputJson as JsonObject)["nuntius"]?.let { (it as JsonPrimitive).content })
        } finally {
            conn.close()
        }
    }
}

