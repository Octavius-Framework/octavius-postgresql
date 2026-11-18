package io.github.octaviusframework.client.dynamic

import io.github.octaviusframework.client.AbstractClientIntegrationTest
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.withPgType
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers which form an instance of a registered class is written in: a `dynamic_dto` where it is registered as
 * nothing else, the declared type where there is one, and a refusal where it is a registered composite too and
 * nothing declares which of the two it goes in as.
 */
class DynamicWriteTest : AbstractClientIntegrationTest() {

    /** Registered as a dynamic type and as nothing else. */
    @Serializable
    data class Triumph(val general: String, val enemy: String)

    /** Registered both ways. */
    @Serializable
    data class Honour(val title: String, val year: Int)

    /**
     * A composite whose two attributes declare one destination each: [award] a `public.honour`, [citation] a
     * `public.dynamic_dto`.
     */
    data class Decoration(val award: Honour, val citation: Triumph)

    override val schema = DYNAMIC_DTO_DDL + ";\n" + """
        CREATE TYPE public.honour AS (title text, year int);
        CREATE TYPE public.decoration AS (award public.honour, citation public.dynamic_dto);
        CREATE TABLE dyn_write (
            id            SERIAL PRIMARY KEY,
            as_dynamic    public.dynamic_dto,
            as_composite  public.honour,
            as_composites public.honour[],
            as_nested     public.decoration
        );
    """.trimIndent()

    @BeforeAll
    fun register() {
        db.dynamicTypes.register<Triumph>("triumph")
        db.dynamicTypes.register<Honour>("honour_dyn")
        db.execute {
            typeManager.registerAutoComposite<Honour>("honour", "public")
            typeManager.registerAutoComposite<Decoration>("decoration", "public")
        }
    }

    @BeforeEach
    fun clearTable() {
        db.rawQuery("TRUNCATE dyn_write RESTART IDENTITY").execute()
    }

    private fun OctaviusClient.write(column: String, value: Any) =
        insertInto("dyn_write").values(listOf(column)).executeUpdate(column to value)

    private inline fun <reified T> OctaviusClient.read(column: String): T =
        select(column).from("dyn_write").fetchFieldStrict<T>()

    private fun assertNamesBothTypes(thrown: MappingException) {
        assertEquals(MappingExceptionReason.CONVERSION_ERROR, thrown.reason)
        assertTrue(
            thrown.details.contains("public.honour") && thrown.details.contains("'honour_dyn'") &&
                thrown.details.contains("withPgType(\"dynamic_dto\", \"public\")"),
            "the message should name both registrations and the way to choose: ${thrown.details}"
        )
    }

    //----------------------------------------REGISTERED AS A DYNAMIC TYPE ONLY-----------------------------------------

    @Test
    fun `a registered class is written as a dynamic_dto without wrapping`() {
        val triumph = Triumph("Scipio", "Carthage")
        db.write("as_dynamic", triumph)

        assertEquals(triumph, db.read<Any>("as_dynamic"))
    }

    //-----------------------------------------------REGISTERED BOTH WAYS-----------------------------------------------

    @Test
    fun `a class registered both ways is refused where nothing declares its type, whichever column it goes to`() {
        // The composite column is the one a guess would have got right, and it is refused all the same.
        val honour = Honour("Corona Civica", 47)
        assertNamesBothTypes(assertFailsWith<MappingException> { db.write("as_dynamic", honour) })
        assertNamesBothTypes(assertFailsWith<MappingException> { db.write("as_composite", honour) })
    }

    @Test
    fun `a list of it is refused the same way`() {
        // The array's type is taken from its first element, which is the same undeclared parameter.
        val honours = listOf(Honour("Corona Civica", 47), Honour("Corona Muralis", 52))
        assertNamesBothTypes(assertFailsWith<MappingException> { db.write("as_composites", honours) })
    }

    @Test
    fun `naming the composite writes it as the composite`() {
        val honour = Honour("Corona Civica", 47)
        db.write("as_composite", honour.withPgType("honour", "public"))

        assertEquals(honour, db.read<Honour>("as_composite"))
    }

    @Test
    fun `naming dynamic_dto writes it as a dynamic_dto`() {
        val honour = Honour("Corona Civica", 47)
        db.write("as_dynamic", honour.withPgType("dynamic_dto", "public"))

        assertEquals("honour_dyn", db.read<DynamicDto>("as_dynamic").typeName)
        assertEquals(honour, db.read<Any>("as_dynamic"))
    }

    //---------------------------------------WHERE THE DESTINATION NAMES ITS TYPE---------------------------------------

    @Test
    fun `an attribute takes the type its composite declares`() {
        // The class registered both ways goes to `award` as a public.honour and the one registered only as a
        // dynamic type to `citation` as a dynamic_dto, since both attribute types come from the catalogue.
        val decoration = Decoration(Honour("Corona Civica", 47), Triumph("Scipio", "Carthage"))
        db.write("as_nested", decoration)

        assertEquals(decoration, db.read<Decoration>("as_nested"))
    }

    @Test
    fun `an array element takes the type the array declares`() {
        val honours = listOf(Honour("Corona Civica", 47), Honour("Corona Muralis", 52))
        db.write("as_composites", honours.withPgType("honour", "public", isArray = true))

        assertEquals(honours, db.read<List<Honour>>("as_composites"))
    }

    @Test
    fun `a class that does not belong in the named type is refused before the wire`() {
        val thrown = assertFailsWith<MappingException> {
            db.write("as_composite", Triumph("Scipio", "Carthage").withPgType("honour", "public"))
        }

        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, thrown.reason)
        assertTrue(
            thrown.details.contains("Triumph") && thrown.details.contains("honour"),
            "the message should name the class and the type it was sent to: ${thrown.details}"
        )
    }

    //---------------------------------------------------THE RAW FORM---------------------------------------------------

    @Test
    fun `a DynamicDto read earlier is written back as it is`() {
        val triumph = Triumph("Scipio", "Carthage")
        db.write("as_dynamic", triumph)
        val raw = db.read<DynamicDto>("as_dynamic")

        db.write("as_dynamic", raw)

        assertEquals(
            listOf(triumph, triumph),
            db.select("as_dynamic").from("dyn_write").orderBy("id").fetchFields<Any>()
        )
    }
}
