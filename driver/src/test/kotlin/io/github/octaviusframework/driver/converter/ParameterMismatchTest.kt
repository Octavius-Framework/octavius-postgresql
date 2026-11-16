package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.type.PgStandardType
import io.github.octaviusframework.driver.type.withPgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A value that reaches the end of the parameter converter chain unclaimed is passed through, which is right for a
 * scalar its codec accepts as it stands and for a raw container, which names its own type. Where the target OID is
 * known and its codec cannot take that class, or no OID is known and no codec takes the class at all, the driver
 * reports it as `NO_CONVERTER_FOUND` with the attribute in the path — the same shape the read direction uses for the
 * same mistake — instead of letting it fail one layer down with no path at all. What a converter returns is held to
 * the same codec, and refused as `CONVERSION_ERROR` naming the converter.
 */
class ParameterMismatchTest : AbstractIntegrationTest() {

    data class Tribute(val amount: Int, val currency: String)
    data class Assessment(val label: String, val payload: Tribute)

    data class NarrowInt(val big: Int)      // against an int8 attribute
    data class MatchingLong(val big: Long)  // against an int8 attribute too, correctly

    class Cognomen(val value: String)

    /** Returns a `StringBuilder`, which no codec encodes. */
    object CognomenConverter : ParameterConverter<Cognomen> {
        override val supportedClass = Cognomen::class
        override fun convert(source: Cognomen, expectedOid: Int, context: SerializationContext): Any =
            StringBuilder(source.value)
    }

    private lateinit var session: OctaviusSession

    override val schema = """
        CREATE SCHEMA parammm;
        CREATE TYPE parammm.tribute AS (amount int, currency text);
        CREATE TYPE parammm.assessment AS (label text, payload parammm.tribute);
        CREATE TYPE parammm.holder AS (big int8);
        CREATE TYPE parammm.long_holder AS (big int8);
    """.trimIndent()

    @BeforeAll
    fun setup() {
        session = openSession()
        session.typeManager.registerAutoComposite<Assessment>("assessment", schema = "parammm")
        session.typeManager.registerAutoComposite<NarrowInt>("holder", schema = "parammm")
        session.typeManager.registerAutoComposite<MatchingLong>("long_holder", schema = "parammm")
        // Tribute deliberately left unregistered
        session.typeManager.registerParameterConverter(CognomenConverter)
    }

    @AfterAll
    fun teardown() {
        session.close()
    }

    private fun assertNoConverter(expectedPathSegment: String, block: () -> Any?) {
        val ex = assertThrows<MappingException> { block() }
        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, ex.reason)
        assertTrue(
            ex.path.contains(expectedPathSegment),
            "expected '$expectedPathSegment' in path, got ${ex.path}"
        )
    }

    @Test
    fun `unregistered nested data class is reported with the attribute in the path`() {
        assertNoConverter("payload") {
            session.createNativeQuery("SELECT $1 AS a")
                .fetchRowStrict(Assessment("census", Tribute(10, "denarii")))
        }
    }

    @Test
    fun `an unregistered data class passed on its own is reported as no converter, naming the class`() {
        val ex = assertThrows<MappingException> {
            session.createNativeQuery("SELECT $1 AS t").fetchRowStrict(Tribute(10, "denarii"))
        }
        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, ex.reason)
        assertEquals(listOf("\$1"), ex.path)
        assertTrue(ex.details.contains("Tribute") && ex.details.contains("registerAutoComposite"), ex.details)
    }

    @Test
    fun `a list of an unregistered data class is reported as no converter`() {
        assertNoConverter("\$1") {
            session.createNativeQuery("SELECT $1 AS ts").fetchRowStrict(listOf(Tribute(10, "denarii")))
        }
    }

    @Test
    fun `a class the attribute's codec cannot encode is reported with the attribute in the path`() {
        assertNoConverter("big") {
            session.createNativeQuery("SELECT $1 AS h").fetchRowStrict(NarrowInt(42))
        }
    }

    @Test
    fun `the matching class still goes through`() {
        val back = session.createNativeQuery("SELECT $1 AS h")
            .fetchRowStrict(MatchingLong(42L)).get<MatchingLong>("h")
        assertEquals(42L, back.big)
    }

    @Test
    fun `a list of raw composites goes out as an array of their type`() {
        val raw = session.createNativeQuery("SELECT ROW(10, 'denarii')::parammm.tribute AS t")
            .fetchRowStrict().get<PgComposite>("t")

        val back = session.createNativeQuery("SELECT $1 AS ts")
            .fetchRowStrict(listOf(raw)).get<List<Tribute>>("ts")

        assertEquals(listOf(Tribute(10, "denarii")), back)
    }

    @Test
    fun `an ordinary scalar parameter is unaffected`() {
        val back = session.createNativeQuery("SELECT $1 AS s").fetchRowStrict("Cicero").get<String>("s")
        assertEquals("Cicero", back)
    }

    private fun assertConverterRefused(block: () -> Any?) {
        val ex = assertThrows<MappingException> { block() }
        assertEquals(MappingExceptionReason.CONVERSION_ERROR, ex.reason)
        assertTrue(ex.details.contains("CognomenConverter") && ex.details.contains("StringBuilder"), ex.details)
    }

    @Test
    fun `a converter's result no codec encodes is refused naming the converter`() {
        assertConverterRefused { session.createNativeQuery("SELECT $1 AS c").fetchRowStrict(Cognomen("Cato")) }
    }

    @Test
    fun `a converter's result the declared type's codec cannot take is refused naming the converter`() {
        assertConverterRefused {
            session.createNativeQuery("SELECT $1 AS c").fetchRowStrict(Cognomen("Cato").withPgType(PgStandardType.TEXT))
        }
    }

    @Test
    fun `a converter's result no codec encodes is refused in a list too`() {
        assertConverterRefused {
            session.createNativeQuery("SELECT $1 AS cs").fetchRowStrict(listOf(Cognomen("Cato")))
        }
    }

    @Test
    fun `an element the array's codec cannot encode is reported with the index in the path`() {
        // The collection converter claims the list, but hands each element back to the chain with the element
        // OID resolved - so an Int bound for int8[] falls through there and is caught with its index.
        val ex = assertThrows<MappingException> {
            session.createNativeQuery("SELECT $1 AS xs")
                .fetchRowStrict(listOf(1, 2, 3).withPgType(PgStandardType.INT8_ARRAY))
        }
        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, ex.reason)
        assertTrue(ex.path.any { it.contains("0") }, "expected the element index in path, got ${ex.path}")
        println("[array element mismatch] ${ex.details} | path=${ex.path.asReversed()}")
    }
}
