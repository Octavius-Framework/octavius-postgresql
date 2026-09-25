package io.github.octaviusframework.driver.deserialization

import io.github.octaviusframework.annotation.PgName
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.type.withPgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class DeserializationIntegrationTest : AbstractIntegrationTest() {

    data class Domicile(val street: String, val city: String)
    data class Patron(val id: Int, val name: String, val domicile: Domicile)

    override val schema = """
        CREATE TYPE domicile AS (street text, city text);
        CREATE TYPE patron AS (id int, name text, domicile domicile);
        CREATE TYPE freedman AS (id int, full_name text, home_address domicile);

        CREATE TYPE allegiance AS ENUM ('LOYAL', 'REBEL', 'UNKNOWN');
        CREATE TYPE client_king AS (realm text, allegiance allegiance);
        CREATE TYPE frontier AS (standing allegiance, king client_king);

        CREATE DOMAIN legion_number AS int CHECK (VALUE > 0);
        CREATE TYPE legion AS (number legion_number, cohorts legion_number);
    """.trimIndent()

    @Test
    fun testRealDatabaseDeserialization() {
        openSession().use { session ->
            session.typeManager.registerAutoComposite<Domicile>("domicile")
            session.typeManager.registerAutoComposite<Patron>("patron")

            val result = session.createNativeQuery(
                "SELECT ROW(10, 'Marcus Tullius Cicero', ROW('Clivus Palatinus', 'Roma')::domicile)::patron AS patron"
            ).fetchRowStrict()

            // The default deserializer in Row.get should be picked automatically
            val patron = result.get<Patron>("patron")

            assertNotNull(patron)
            assertEquals(10, patron.id)
            assertEquals("Marcus Tullius Cicero", patron.name)
            assertEquals("Clivus Palatinus", patron.domicile.street)
            assertEquals("Roma", patron.domicile.city)
        }
    }

    @Test
    fun testRealDatabaseArrayDeserialization() {
        openSession().use { session ->
            session.typeManager.registerAutoComposite<Domicile>("domicile")

            val result = session.createNativeQuery(
                "SELECT ARRAY[ROW('Via Appia', 'Capua')::domicile, ROW('Via Flaminia', 'Ariminum')::domicile] AS stations"
            ).fetchRowStrict()

            // The default deserializer in Row.get should be picked automatically
            val stations = result.get<List<Domicile>>("stations")

            assertNotNull(stations)
            assertEquals(2, stations.size)
            assertEquals("Via Appia", stations[0].street)
            assertEquals("Ariminum", stations[1].city)
        }
    }

    @Test
    fun testJsonDeserialization() {
        openSession().use { session ->
            val result = session.createNativeQuery(
                "SELECT '{\"legio\": \"X Equestris\"}'::json AS js, '{\"castra\": \"Vetera\"}'::jsonb AS jsb"
            ).fetchRowStrict()

            val js = result.get<JsonElement>("js")
            val jsb = result.get<JsonElement>("jsb")
            val jsAny = result.get<Any>("js")
            val jsbAny = result.get<Any>("jsb")

            assertNotNull(js)
            assertNotNull(jsb)

            val jsonObjectJs = js as JsonObject
            val jsonObjectJsb = jsb as JsonObject

            assertEquals("X Equestris", jsonObjectJs["legio"]?.let { (it as JsonPrimitive).content })
            assertEquals("Vetera", jsonObjectJsb["castra"]?.let { (it as JsonPrimitive).content })

            if (jsAny is JsonObject) {
                assertEquals("X Equestris", jsAny["legio"]?.let { (it as JsonPrimitive).content })
            } else {
                fail("jsAny is not a JsonObject, it is ${jsAny.javaClass.name}")
            }
            if (jsbAny is JsonObject) {
                assertEquals("Vetera", jsbAny["castra"]?.let { (it as JsonPrimitive).content })
            } else {
                fail("jsbAny is not a JsonObject, it is ${jsbAny.javaClass.name}")
            }
        }
    }

    enum class Allegiance { LOYAL, REBEL, UNKNOWN }
    data class ClientKing(val realm: String, val allegiance: Allegiance)

    @Test
    fun testExplicitEnumAndCompositeConverters() {
        openSession().use { session ->
            // Register explicit converters of our own
            session.typeManager.registerResultConverter(object : ResultConverter<Any, Allegiance> {
                override val supportedSourceClass = Any::class
                override fun canConvert(sourceClass: kotlin.reflect.KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
                    return expectedType.classifier == Allegiance::class || sourceType.name == "allegiance"
                }
                override fun convert(
                    source: Any,
                    expectedType: KType,
                    sourceType: PgType,
                    context: DeserializationContext
                ): Allegiance {
                    val str = source.toString()
                    return Allegiance.entries.find { it.name.equals(str, ignoreCase = true) } ?: Allegiance.UNKNOWN
                }
            })

            session.typeManager.registerResultConverter(object : ResultConverter<PgComposite, ClientKing> {
                override val supportedSourceClass = PgComposite::class
                override fun canConvert(sourceClass: kotlin.reflect.KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
                    return expectedType.classifier == ClientKing::class || sourceType.name == "client_king"
                }
                override fun convert(
                    source: PgComposite,
                    expectedType: KType,
                    sourceType: PgType,
                    context: DeserializationContext
                ): ClientKing {
                    val realm = source.get<String>("realm")
                    val allegianceRaw = source.get<Any?>("allegiance")
                    val allegiance = if (allegianceRaw != null) {
                        context.convert(allegianceRaw, typeOf<Allegiance>(), source.getAttributeOid("allegiance"))
                    } else {
                        Allegiance.UNKNOWN
                    }
                    return ClientKing(realm, allegiance)
                }
            })

            val result = session.createNativeQuery(
                "SELECT ROW('LOYAL'::allegiance, ROW('Armenia', 'REBEL')::client_king)::frontier AS frontier"
            ).fetchRowStrict()

            // Read the 'frontier' column as Map<String, Any?>
            val frontier = result.get<Map<String, Any?>>("frontier")

            assertNotNull(frontier)
            assertEquals(2, frontier.size)

            val standing = frontier["standing"]
            assertTrue(standing is Allegiance)
            assertEquals(Allegiance.LOYAL, standing)

            val kingVal = frontier["king"]
            assertTrue(kingVal is ClientKing)
            val king = kingVal as ClientKing
            assertEquals("Armenia", king.realm)
            assertEquals(Allegiance.REBEL, king.allegiance)
        }
    }

    data class Legion(val number: Int, val cohorts: Int)

    @Test
    fun testDomainTypeHandling() {
        openSession().use { session ->
            session.typeManager.registerAutoComposite<Legion>("legion")

            // Test deserialization of pure domain
            val res1 = session.createNativeQuery("SELECT 13::legion_number AS numeral").fetchRowStrict()
            assertEquals(13, res1.get<Int>("numeral"))

            // Test deserialization of array of domains
            val res2 = session.createNativeQuery("SELECT ARRAY[10, 20]::legion_number[] AS numerals").fetchRowStrict()
            val list = res2.get<List<Int>>("numerals")
            assertEquals(listOf(10, 20), list)

            // Test deserialization of composite with domains
            val res3 = session.createNativeQuery("SELECT ROW(13, 10)::legion AS legion").fetchRowStrict()
            val legion = res3.get<Legion>("legion")
            assertEquals(13, legion.number)
            assertEquals(10, legion.cohorts)

            // Test serialization of domains (implicit, mapped as underlying type since JDBC sends parameters with matching format/Oid if we specify it or just sends integer)
            // If we send it via composite
            val res4 = session.createNativeQuery("SELECT $1 AS legion_back")
                .fetchRowStrict(Legion(14, 10).withPgType("legion"))

            val legionBack = res4.get<Legion>("legion_back")
            assertEquals(14, legionBack.number)
            assertEquals(10, legionBack.cohorts)
        }
    }

    data class Freedman(
        val id: Int,
        @PgName("full_name") val name: String,
        @PgName("home_address") val address: Domicile
    )

    @Test
    fun testRealDatabaseMapKeyDeserializationAndSerialization() {
        openSession().use { session ->
            session.typeManager.registerAutoComposite<Domicile>("domicile")
            session.typeManager.registerAutoComposite<Freedman>("freedman")

            // Test deserialization
            val result = session.createNativeQuery(
                "SELECT ROW(15, 'Marcus Tullius Tiro', ROW('Via Sacra', 'Roma')::domicile)::freedman AS freedman"
            ).fetchRowStrict()

            val tiro = result.get<Freedman>("freedman")

            assertNotNull(tiro)
            assertEquals(15, tiro.id)
            assertEquals("Marcus Tullius Tiro", tiro.name)
            assertEquals("Via Sacra", tiro.address.street)
            assertEquals("Roma", tiro.address.city)

            // Test serialization
            val resBack = session.createNativeQuery("SELECT $1 AS freedman_back")
                .fetchRowStrict(tiro)

            val back = resBack.get<Freedman>("freedman_back")
            assertEquals(15, back.id)
            assertEquals("Marcus Tullius Tiro", back.name)
            assertEquals("Via Sacra", back.address.street)
        }
    }

    @Test
    fun testRecordTypeHandling() {
        openSession().use { session ->
            val result = session.createNativeQuery(
                "SELECT ROW('legio', ROW('cohortes', 10), 'signa', '[\"aquila\",\"vexillum\"]'::json) AS rec"
            ).fetchRowStrict()

            val map = result.get<Map<String, Any?>>("rec")
            assertNotNull(map)
            assertEquals(2, map.size)

            assertTrue(map["legio"] is Map<*, *>)
            @Suppress("UNCHECKED_CAST")
            val innerMap = map["legio"] as Map<String, Any?>
            assertEquals(10, innerMap["cohortes"])
            assertEquals(Json.decodeFromString<JsonArray>("[\"aquila\",\"vexillum\"]"), map["signa"])
        }
    }
}
