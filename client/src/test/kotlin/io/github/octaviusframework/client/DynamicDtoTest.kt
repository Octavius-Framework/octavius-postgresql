package io.github.octaviusframework.client

import io.github.octaviusframework.annotation.PgEnumType
import io.github.octaviusframework.client.dynamic.DynamicDto
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.type.BigDecimal
import io.github.octaviusframework.type.datetime.DISTANT_FUTURE
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers `dynamic_dto` end to end: one column holding several unrelated shapes, written from Kotlin and built
 * in SQL, and read back as the classes registered for them.
 */
class DynamicDtoTest : AbstractClientIntegrationTest() {

    @OptIn(ExperimentalSerializationApi::class)
    private val snakeCase = Json { namingStrategy = JsonNamingStrategy.SnakeCase }

    sealed interface Benefit

    @Serializable
    data class LandGrant(val province: String, val iugera: Int) : Benefit

    @Serializable
    data class MilitaryPension(val legion: String, val annual: Int) : Benefit

    @Serializable
    data class Citation(val text: String)

    /** camelCase properties, so a payload SQL built the way SQL names things does not fit the default Json. */
    @Serializable
    data class Stipend(val provinceName: String, val annualAmount: Int)

    /** Labels are `SNAKE_CASE_UPPER` in the database and `PascalCase` here, which is what the defaults assume. */
    @PgEnumType(name = "magistrature")
    enum class Magistrature { Quaestor, Aedile, Praetor, Consul }

    /** Registered late, to prove the module is read per conversion and not composed once at startup. */
    @PgEnumType(name = "legion_status")
    enum class LegionStatus { Garrisoned, OnMarch, InBattle }

    /** No `@Serializable` on either enum, and no serializer written by hand: `@Contextual` is the whole of it. */
    @Serializable
    data class Appointment(@Contextual val office: Magistrature) : Benefit

    @Serializable
    data class Deployment(@Contextual val status: LegionStatus) : Benefit

    /**
     * The two types whose JSON form is not their column form. Neither encodes at all under a stock `Json`:
     * `BigDecimal` has no serializer, and `@Contextual` finds nothing for `LocalDate` either.
     */
    @Serializable
    data class TributeAssessment(
        val province: String,
        @Contextual val denarii: BigDecimal,
        @Contextual val until: LocalDate
    ) : Benefit

    data class Decorated(val id: Int, val name: String, val award: LandGrant)

    data class Awarded(val id: Int, val name: String, val awards: List<LandGrant>)

    data class Archived(val id: Int, val record: Benefit)

    override val schema = "CREATE TYPE public.magistrature AS ENUM ('QUAESTOR', 'AEDILE', 'PRAETOR', 'CONSUL')"

    @BeforeAll
    fun setUp() {
        // Installing after the pool was built is the harder case: the driver read its type catalogue when
        // it connected, so this only works if install() reloads it.
        db.dynamicTypes.install()
        db.dynamicTypes.install() // twice, because the DDL claims to be safe run twice

        db.dynamicTypes.register<LandGrant>("land_grant")
        db.dynamicTypes.register<MilitaryPension>("military_pension")
        db.dynamicTypes.register<Citation>("citation")
        db.dynamicTypes.register<Stipend>("stipend")
        db.dynamicTypes.register<TributeAssessment>("tribute_assessment")
        db.dynamicTypes.register<Appointment>("appointment")
        db.dynamicTypes.register<Deployment>("deployment")

        // After the client was built, which is the only order there is: a client is constructed before
        // anything is registered on it.
        db.execute { typeManager.registerEnum<Magistrature>("magistrature") }

        db.rawQuery(
            """
            CREATE TABLE dyn_veterans (
                id       SERIAL PRIMARY KEY,
                name     TEXT NOT NULL,
                benefit  public.dynamic_dto,
                benefits public.dynamic_dto[],
                office   public.magistrature
            );
            CREATE TABLE dyn_grants (
                veteran_id INT  NOT NULL,
                province   TEXT NOT NULL,
                iugera     INT  NOT NULL
            );
            CREATE TABLE dyn_archives (
                id          SERIAL PRIMARY KEY,
                record_type TEXT  NOT NULL,
                payload     JSONB NOT NULL
            )
            """.trimIndent()
        ).execute()
    }

    @BeforeEach
    fun clearTable() {
        db.rawQuery("TRUNCATE dyn_veterans, dyn_grants, dyn_archives RESTART IDENTITY").execute()
    }

    // Unwrapped, which is what the default mode makes possible and what nearly every test here goes through.
    // Wrapping is covered on its own below.
    private fun record(name: String, benefit: Any) {
        db.insertInto("dyn_veterans").values(listOf("name", "benefit"))
            .update("name" to name, "benefit" to benefit)
    }

    // --- The round trip ---------------------------------------------------------------------------

    @Test
    fun `a value written from Kotlin comes back as the class it was`() {
        val grant = LandGrant("Gallia Narbonensis", 120)
        record("Marcus", grant)

        val read = db.select("benefit").from("dyn_veterans").fetchFieldStrict<LandGrant>()

        assertEquals(grant, read)
    }

    @Test
    fun `one column holds several shapes and reads back as their common supertype`() {
        // This is what a COMPOSITE cannot do: the column's shape is decided per row, not per schema.
        record("Marcus", LandGrant("Gallia Narbonensis", 120))
        record("Lucius", MilitaryPension("X Fretensis", 900))
        record("Gaius", LandGrant("Hispania", 40))

        val benefits = db.select("benefit").from("dyn_veterans").orderBy("id")
            .fetchFields<Benefit>()

        assertEquals(
            listOf(
                LandGrant("Gallia Narbonensis", 120),
                MilitaryPension("X Fretensis", 900),
                LandGrant("Hispania", 40)
            ),
            benefits
        )
    }

    @Test
    fun `a wrapped value writes the same as an unwrapped one`() {
        // toDynamicDto stops being required under the default mode, but it does not stop working: it is still
        // what settles a class registered as a composite as well, and it takes the same path here.
        val grant = LandGrant("Gallia Narbonensis", 120)
        db.insertInto("dyn_veterans").values(listOf("name", "benefit"))
            .update("name" to "Marcus", "benefit" to db.dynamicTypes.toDynamicDto(grant))

        assertEquals(grant, db.select("benefit").from("dyn_veterans").fetchFieldStrict<LandGrant>())
    }

    @Test
    fun `a value built in SQL reads back the same way`() {
        // Nothing about the read path needs the value to have come from Kotlin: SQL naming the type and
        // building the payload is the whole contract, which is what makes ad-hoc projections work.
        val benefit = db.rawQuery(
            "SELECT dynamic_dto('land_grant', jsonb_build_object('province', @p, 'iugera', @i))"
        ).fetchFieldStrict<Benefit>("p" to "Britannia", "i" to 75)

        assertEquals(LandGrant("Britannia", 75), benefit)
    }

    @Test
    fun `a class with no relation to its stored name works the same`() {
        record("Marcus", Citation("ob civem servatum"))

        assertEquals(
            Citation("ob civem servatum"),
            db.select("benefit").from("dyn_veterans").fetchFieldStrict<Citation>()
        )
    }

    @Test
    fun `the raw form is still reachable for anyone who wants the payload`() {
        record("Marcus", LandGrant("Gallia Narbonensis", 120))

        val raw = db.select("benefit").from("dyn_veterans").fetchFieldStrict<DynamicDto>()

        assertEquals("land_grant", raw.typeName)
        // The payload comes back as the text the column holds, so it is JSON and it parses - which is what
        // says the read handed over jsonb's own output rather than something's toString().
        assertEquals(
            "Gallia Narbonensis",
            Json.parseToJsonElement(raw.dataPayload).jsonObject.getValue("province").jsonPrimitive.content
        )
    }

    @Test
    fun `asking for a Map gets the composite's own attributes`() {
        record("Marcus", LandGrant("Gallia Narbonensis", 120))

        val asMap = db.select("benefit").from("dyn_veterans").fetchFieldStrict<Map<String, Any?>>()

        assertEquals(setOf("type_name", "data_payload"), asMap.keys)
        assertEquals("land_grant", asMap["type_name"])
    }

    @Test
    fun `a null column is still null`() {
        db.insertInto("dyn_veterans").values(listOf("name")).update("name" to "Sine Praemio")

        assertEquals(null, db.select("benefit").from("dyn_veterans").fetchFieldStrict<Benefit?>())
    }

    // --- Where else it goes -----------------------------------------------------------------------

    @Test
    fun `several shapes in one array column come back as a list of their supertype`() {
        val benefits = listOf(LandGrant("Gallia Narbonensis", 120), MilitaryPension("X Gemina", 300))
        db.insertInto("dyn_veterans").values(listOf("name", "benefits"))
            .update("name" to "Marcus", "benefits" to benefits)

        val asSupertype: List<Benefit> = db.select("benefits").from("dyn_veterans").fetchFieldStrict()
        val asAny: List<Any> = db.select("benefits").from("dyn_veterans").fetchFieldStrict()

        assertEquals(benefits, asSupertype)
        assertEquals(benefits, asAny)
    }

    @Test
    fun `an object built in the projection lands in a property`() {
        db.rawQuery("INSERT INTO dyn_veterans (name) VALUES ('Marcus'), ('Gaius')").execute()
        db.rawQuery("INSERT INTO dyn_grants VALUES (2, 'Asia', 7)").execute()

        val decorated = db.rawQuery(
            """
            SELECT v.id, v.name,
                   dynamic_dto('land_grant', jsonb_build_object('province', g.province, 'iugera', g.iugera)) AS award
            FROM dyn_veterans v JOIN dyn_grants g ON g.veteran_id = v.id
            """
        ).fetchObjects<Decorated>()

        assertEquals(listOf(Decorated(2, "Gaius", LandGrant("Asia", 7))), decorated)
    }

    @Test
    fun `an array built in the projection is a list property, children and parent in one query`() {
        db.rawQuery("INSERT INTO dyn_veterans (name) VALUES ('Marcus'), ('Gaius'), ('Sine Praemio')").execute()
        db.rawQuery("INSERT INTO dyn_grants VALUES (1, 'Gallia', 120), (1, 'Hispania', 40), (2, 'Asia', 7)").execute()

        val awarded = db.rawQuery(
            """
            SELECT v.id, v.name,
                   ARRAY(
                       SELECT dynamic_dto('land_grant', jsonb_build_object('province', g.province, 'iugera', g.iugera))
                       FROM dyn_grants g WHERE g.veteran_id = v.id ORDER BY g.province
                   ) AS awards
            FROM dyn_veterans v ORDER BY v.id
            """
        ).fetchObjects<Awarded>()

        assertEquals(
            listOf(
                Awarded(1, "Marcus", listOf(LandGrant("Gallia", 120), LandGrant("Hispania", 40))),
                Awarded(2, "Gaius", listOf(LandGrant("Asia", 7))),
                Awarded(3, "Sine Praemio", emptyList())
            ),
            awarded
        )
    }

    @Test
    fun `plain columns take the type apart on the way in and put it back together on the way out`() {
        val benefits = listOf(LandGrant("Gallia Narbonensis", 120), MilitaryPension("X Gemina", 300))
        db.rawQuery("INSERT INTO dyn_archives (record_type, payload) SELECT type_name, data_payload FROM UNNEST(@records)")
            .update("records" to benefits)
        db.rawQuery("INSERT INTO dyn_archives (record_type, payload) SELECT (@r).type_name, (@r).data_payload")
            .update("r" to LandGrant("Asia", 7))

        // Stored as nothing but text and jsonb, which is the point: the table reads without the type.
        assertEquals(
            listOf("land_grant", "military_pension", "land_grant"),
            db.select("record_type").from("dyn_archives").orderBy("id").fetchFields<String>()
        )

        val archived = db.rawQuery("SELECT id, dynamic_dto(record_type, payload) AS record FROM dyn_archives ORDER BY id")
            .fetchObjects<Archived>()

        assertEquals(
            listOf(
                Archived(1, LandGrant("Gallia Narbonensis", 120)),
                Archived(2, MilitaryPension("X Gemina", 300)),
                Archived(3, LandGrant("Asia", 7))
            ),
            archived
        )
    }

    @Test
    fun `inside an anonymous record, however deep, each one reads as its class`() {
        // The anonymous record of the driver's ComplexDataIntegrationTest without dynamic_dto, which the driver
        // does not know: a record read as a map hands every value to the chain as Any, and that is enough for each
        // discriminator to be resolved where it sits - beside an enum, in a nested record, in an array, in a
        // record in an array.
        val grant = "dynamic_dto('land_grant', jsonb_build_object('province', 'Asia', 'iugera', 7))"
        val pension = "dynamic_dto('military_pension', jsonb_build_object('legion', 'X Fretensis', 'annual', 900))"

        val rec = db.rawQuery(
            """
            SELECT ROW(
                'plain', 'insanity'::text,
                'office', 'PRAETOR'::magistrature,
                'benefit', $grant,
                'nested', ROW(
                    'appointment', dynamic_dto('appointment', jsonb_build_object('office', 'CONSUL')),
                    'benefits', ARRAY[$grant, $pension]
                ),
                'rows', ARRAY[
                    ROW('id', 1, 'benefit', $pension),
                    ROW('id', 2, 'benefit', NULL::public.dynamic_dto)
                ]::record[]
            )
            """
        ).fetchFieldStrict<Map<String, Any?>>()

        assertEquals("insanity", rec["plain"])
        assertEquals(Magistrature.Praetor, rec["office"])
        assertEquals(LandGrant("Asia", 7), rec["benefit"])

        @Suppress("UNCHECKED_CAST")
        val nested = rec["nested"] as Map<String, Any?>
        assertEquals(Appointment(Magistrature.Consul), nested["appointment"])
        assertEquals(listOf(LandGrant("Asia", 7), MilitaryPension("X Fretensis", 900)), nested["benefits"])

        @Suppress("UNCHECKED_CAST")
        val rows = rec["rows"] as List<Map<String, Any?>>
        assertEquals(listOf(mapOf("id" to 1, "benefit" to MilitaryPension("X Fretensis", 900)), mapOf("id" to 2, "benefit" to null)), rows)
    }

    @Test
    fun `and as the key of that map, it is its class as well`() {
        // A record read as a map converts its keys through the chain too, against the key type asked for - so
        // a key can be a dynamic_dto, and what it becomes is an object with equals, which a map can look up.
        val grant = "dynamic_dto('land_grant', jsonb_build_object('province', 'Asia', 'iugera', 7))"
        val pension = "dynamic_dto('military_pension', jsonb_build_object('legion', 'X Gemina', 'annual', 300))"

        val byBenefit = db.rawQuery("SELECT ROW($grant, 1, $pension, 2)").fetchFieldStrict<Map<Benefit, Int>>()
        assertEquals(mapOf(LandGrant("Asia", 7) to 1, MilitaryPension("X Gemina", 300) to 2), byBenefit)
        assertEquals(1, byBenefit[LandGrant("Asia", 7)])

        val both = db.rawQuery("SELECT ROW($grant, $pension)").fetchFieldStrict<Map<Any, Any?>>()
        assertEquals(mapOf<Any, Any?>(LandGrant("Asia", 7) to MilitaryPension("X Gemina", 300)), both)
    }

    @Test
    fun `and two keys that decode to the same object are one key too many`() {
        val grant = "dynamic_dto('land_grant', jsonb_build_object('province', 'Asia', 'iugera', 7))"

        val thrown = assertFailsWith<MappingException> {
            db.rawQuery("SELECT ROW($grant, 1, $grant, 2)").fetchFieldStrict<Map<Benefit, Int>>()
        }
        assertTrue(thrown.details.contains("Duplicate key"), thrown.details)
    }

    // --- A different Json, for one query --------------------------------------------------------------

    @Test
    fun `a payload named the way SQL names things reads under a Json given to that query alone`() {
        val out = db.rawQuery(
            "SELECT dynamic_dto('stipend', jsonb_build_object('province_name', @p, 'annual_amount', @a))"
        )
            .registerResultConverter(db.dynamicTypes.resultConverter(snakeCase))
            .fetchFieldStrict<Stipend>("p" to "Aegyptus", "a" to 500)

        assertEquals(Stipend("Aegyptus", 500), out)
    }

    @Test
    fun `and the client's own Json still refuses it everywhere else`() {
        // Which is what says the converter was the query's and not the connection's: the same SQL, one line
        // shorter, has to fail - the default Json being strict about a key the class does not declare.
        assertFailsWith<MappingException> {
            db.rawQuery(
                "SELECT dynamic_dto('stipend', jsonb_build_object('province_name', @p, 'annual_amount', @a))"
            ).fetchFieldStrict<Stipend>("p" to "Aegyptus", "a" to 500)
        }
    }

    @Test
    fun `the write side takes a Json the same two ways`() {
        val stipend = Stipend("Cyrenaica", 220)

        // Once through the wrapper, which takes one directly...
        db.insertInto("dyn_veterans").values(listOf("name", "benefit"))
            .update("name" to "Marcus", "benefit" to db.dynamicTypes.toDynamicDto(stipend, snakeCase))
        // ...and once unwrapped, through a converter given to this query alone.
        db.insertInto("dyn_veterans").values(listOf("name", "benefit"))
            .registerParameterConverter(db.dynamicTypes.parameterConverter(snakeCase))
            .update("name" to "Lucius", "benefit" to stipend)

        assertEquals(
            listOf("Cyrenaica", "Cyrenaica"),
            db.select("(benefit).data_payload ->> 'province_name'").from("dyn_veterans").orderBy("id")
                .fetchFields<String>()
        )
    }

    // --- Types JSON does not carry --------------------------------------------------------------------

    @Test
    fun `a BigDecimal and an unbounded date round-trip through the column`() {
        // Under a stock Json this class does not encode at all, which is what the client's default fixes.
        val assessment = TributeAssessment("Aegyptus", BigDecimal("12345678901234567890.99"), LocalDate.DISTANT_FUTURE)
        record("Marcus", assessment)

        assertEquals(assessment, db.select("benefit").from("dyn_veterans").fetchFieldStrict<TributeAssessment>())
    }

    @Test
    fun `the decimal is stored as a JSON number, at full precision`() {
        // A serializer writing it as a string would answer 'string' here, and one going through a Double
        // would come back short: 20 significant digits is past what a binary64 carries.
        record("Marcus", TributeAssessment("Aegyptus", BigDecimal("12345678901234567890.99"), LocalDate(44, 3, 15)))

        val payload = "(benefit).data_payload"
        assertEquals(
            "number",
            db.select("jsonb_typeof($payload -> 'denarii')").from("dyn_veterans").fetchFieldStrict<String>()
        )
        assertEquals(
            true,
            db.select("($payload ->> 'denarii')::numeric = 12345678901234567890.99")
                .from("dyn_veterans").fetchFieldStrict<Boolean>()
        )
    }

    @Test
    fun `the unbounded date is the same value in the payload as it is in a date column`() {
        // Without the marker branch the payload would hold the year 999999999, and this cast would not come
        // back false - it would error outright, that year being past the 5874897 a `date` reaches.
        record("Marcus", TributeAssessment("Aegyptus", BigDecimal("1"), LocalDate.DISTANT_FUTURE))

        val payload = "(benefit).data_payload"
        assertEquals(
            "infinity",
            db.select("$payload ->> 'until'").from("dyn_veterans").fetchFieldStrict<String>()
        )
        assertEquals(
            true,
            db.select("($payload ->> 'until')::date = 'infinity'::date")
                .from("dyn_veterans").fetchFieldStrict<Boolean>()
        )
    }

    @Test
    fun `a year past four digits casts back out of the payload`() {
        // ISO writes +10000-01-02 and PostgreSQL reads that leading sign as a timezone offset, so the payload
        // has to carry its spelling and not ISO's. 10000 is an ordinary storable year - this is not about the
        // markers.
        val year10000 = LocalDate(10000, 1, 2)
        record("Marcus", TributeAssessment("Aegyptus", BigDecimal("1"), year10000))

        assertEquals(
            "10000-01-02",
            db.select("(benefit).data_payload ->> 'until'").from("dyn_veterans").fetchFieldStrict<String>()
        )
        // Against the driver's own binary encoding of the same value, which is the column's answer.
        assertEquals(
            true,
            db.select("((benefit).data_payload ->> 'until')::date = @d")
                .from("dyn_veterans").fetchFieldStrict<Boolean>("d" to year10000)
        )
    }

    @Test
    fun `a date before year one casts back out of the payload`() {
        // ISO counts through a year zero and PostgreSQL counts BC from one, so -0001-01-02 is 2 BC there.
        val twoBc = LocalDate(-1, 1, 2)
        record("Marcus", TributeAssessment("Aegyptus", BigDecimal("1"), twoBc))

        assertEquals(
            "0002-01-02 BC",
            db.select("(benefit).data_payload ->> 'until'").from("dyn_veterans").fetchFieldStrict<String>()
        )
        assertEquals(
            true,
            db.select("((benefit).data_payload ->> 'until')::date = @d")
                .from("dyn_veterans").fetchFieldStrict<Boolean>("d" to twoBc)
        )
    }

    @Test
    fun `and both come back as the dates they were`() {
        val far = TributeAssessment("Aegyptus", BigDecimal("1"), LocalDate(5874897, 12, 31))
        val old = TributeAssessment("Hispania", BigDecimal("2"), LocalDate(-4712, 1, 1))
        record("Marcus", far)
        record("Lucius", old)

        assertEquals(
            listOf(far, old),
            db.select("benefit").from("dyn_veterans").orderBy("id").fetchFields<TributeAssessment>()
        )
    }

    // --- Enums, under the labels they were registered with ---------------------------------------------

    @Test
    fun `an enum in a payload carries the same label the enum column holds`() {
        // The whole claim in one row: one value, written twice, once through each path.
        db.insertInto("dyn_veterans").values(listOf("name", "benefit", "office"))
            .update(
                "name" to "Marcus",
                "benefit" to Appointment(Magistrature.Praetor),
                "office" to Magistrature.Praetor
            )

        assertEquals(
            true,
            db.select("(benefit).data_payload ->> 'office' = office::text")
                .from("dyn_veterans").fetchFieldStrict<Boolean>()
        )
        assertEquals(
            "PRAETOR",
            db.select("(benefit).data_payload ->> 'office'").from("dyn_veterans").fetchFieldStrict<String>()
        )
    }

    @Test
    fun `and a payload built in SQL under those labels reads back as the constant`() {
        // Reading its own writes would pass under the default serializer too - Consul out, Consul back. This
        // is the asymmetric direction: SQL puts the database's label in the payload, and only a serializer
        // that knows the conventions turns CONSUL back into Consul.
        val read = db.rawQuery("SELECT dynamic_dto('appointment', jsonb_build_object('office', @o))")
            .fetchFieldStrict<Appointment>("o" to Magistrature.Consul)

        assertEquals(Appointment(Magistrature.Consul), read)
    }

    @Test
    fun `an enum registered after the first query still takes effect`() {
        // The reason the module is resolved per conversion rather than composed when the client was built:
        // this write goes through a Json that did not exist when the one above ran.
        record("Marcus", Appointment(Magistrature.Aedile))
        assertEquals(1, db.select("count(*)").from("dyn_veterans").fetchFieldStrict<Long>())

        db.execute { typeManager.registerEnum<LegionStatus>("legion_status") }
        record("Lucius", Deployment(LegionStatus.OnMarch))

        assertEquals(
            "ON_MARCH",
            db.select("(benefit).data_payload ->> 'status'").from("dyn_veterans").where("name = @n")
                .fetchFieldStrict<String>("n" to "Lucius")
        )
    }

    @Test
    fun `the module is there for a Json of your own`() {
        val api = Json { serializersModule = db.dynamicTypes.enumSerializers }

        assertEquals("""{"office":"PRAETOR"}""", api.encodeToString(Appointment(Magistrature.Praetor)))
    }

    @Test
    fun `a client that registered no dynamic type at all still hands out the module`() {
        // It reads the driver's registry, which a dynamic type happens to be the usual way of reaching - but
        // the case this exists for is a jsonb column written through the driver, where there is no
        // dynamic_dto anywhere.
        OctaviusClient.fromDataSource(dataSource).use { fresh ->
            val api = Json { serializersModule = fresh.dynamicTypes.enumSerializers }

            assertEquals("""{"office":"CONSUL"}""", api.encodeToString(Appointment(Magistrature.Consul)))
        }
    }

    // --- What it refuses --------------------------------------------------------------------------

    @Test
    fun `a name nothing was registered under says so`() {
        val thrown = assertFailsWith<MappingException> {
            db.rawQuery("SELECT dynamic_dto('triumph', jsonb_build_object('x', 1))")
                .fetchFieldStrict<Benefit>()
        }
        assertTrue(thrown.details.contains("'triumph'"), "the message should name the type it could not find")
    }

    @Test
    fun `a value that is not what the caller asked for says which is which`() {
        record("Lucius", MilitaryPension("X Fretensis", 900))

        val thrown = assertFailsWith<MappingException> {
            db.select("benefit").from("dyn_veterans").fetchFieldStrict<LandGrant>()
        }
        assertTrue(thrown.details.contains("MilitaryPension"))
        assertTrue(thrown.details.contains("LandGrant"))
    }

    @Test
    fun `a payload that does not fit the class says so rather than half-filling it`() {
        val thrown = assertFailsWith<MappingException> {
            db.rawQuery("SELECT dynamic_dto('land_grant', jsonb_build_object('province', 'Gallia'))")
                .fetchFieldStrict<Benefit>()
        }
        assertTrue(thrown.details.contains("land_grant"))
    }

    @Test
    fun `wrapping an unregistered class points at the fix`() {
        data class Unregistered(val x: Int)

        val thrown = assertFailsWith<InvalidOperationException> {
            db.dynamicTypes.toDynamicDto(Unregistered(1))
        }
        assertTrue(thrown.details!!.contains("register"))
    }

    @Test
    fun `two classes cannot take one name`() {
        assertFailsWith<InvalidOperationException> {
            db.dynamicTypes.register<Citation>("land_grant")
        }
    }

    @Test
    fun `registering the same class twice is harmless`() {
        db.dynamicTypes.register<LandGrant>("land_grant")
        record("Marcus", LandGrant("Gallia Narbonensis", 120))
        assertIs<LandGrant>(db.select("benefit").from("dyn_veterans").fetchFieldStrict<Benefit>())
    }
}
