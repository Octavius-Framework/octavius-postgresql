package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A composite and an anonymous `ROW(...)` of keys and values read into the data class asked for with nothing
 * registered, as a row does: the class says what to build, and the names on this side of the wire are the
 * attributes' or the keys'.
 */
class ReadIntoClassIntegrationTest : AbstractIntegrationTest() {

    data class Tribute(val amount: Int, val currency: String)
    data class Assessment(val label: String, val payload: Tribute)

    data class LandGrant(val province: String, val iugera: Int, val grantedOn: LocalDate? = null)
    data class Settlement(val cognomen: String, val grants: List<LandGrant>, val tribute: Tribute)
    data class Allotment(val province: String, val iugera: Int = 0)

    override val schema = """
        CREATE TYPE tribute AS (amount int, currency text);
        CREATE TYPE assessment AS (label text, payload tribute);
    """.trimIndent()

    private inline fun <reified T> read(sql: String): T =
        openSession().use { it.createNativeQuery(sql).fetchFieldStrict<T>() }

    private inline fun <reified T> refusal(sql: String): MappingException =
        assertFailsWith<MappingException> { read<T>(sql) }

    // --- Composites -----------------------------------------------------------------------------------

    @Test
    fun `a composite reads into the class asked for with nothing registered`() {
        assertEquals(Tribute(40, "denarius"), read<Tribute>("SELECT ROW(40, 'denarius')::tribute"))
    }

    @Test
    fun `a nested composite and an array of them read through the classes of their properties`() {
        assertEquals(
            Assessment("census", Tribute(40, "denarius")),
            read<Assessment>("SELECT ROW('census', ROW(40, 'denarius')::tribute)::assessment")
        )
        assertEquals(
            listOf(Tribute(40, "denarius"), Tribute(12, "as")),
            read<List<Tribute>>("SELECT ARRAY[ROW(40, 'denarius')::tribute, ROW(12, 'as')::tribute]")
        )
    }

    // --- Records --------------------------------------------------------------------------------------

    @Test
    fun `a ROW of keys and values reads into the class asked for, each field keeping its type`() {
        assertEquals(
            LandGrant("Gallia Narbonensis", 120, LocalDate(43, 5, 1)),
            read<LandGrant>("SELECT ROW('province', 'Gallia Narbonensis', 'iugera', 120, 'granted_on', DATE '0043-05-01')")
        )
    }

    @Test
    fun `nested records, arrays of them and composites under them read through their properties' classes`() {
        val settlement = read<Settlement>(
            """
            SELECT ROW(
                'cognomen', 'Marcus',
                'grants', ARRAY(
                    SELECT ROW('province', p, 'iugera', i)
                    FROM (VALUES ('Gallia', 120), ('Hispania', 80)) v(p, i)
                    ORDER BY i DESC
                ),
                'tribute', ROW(40, 'denarius')::tribute
            )
            """.trimIndent()
        )

        assertEquals(
            Settlement("Marcus", listOf(LandGrant("Gallia", 120), LandGrant("Hispania", 80)), Tribute(40, "denarius")),
            settlement
        )
    }

    @Test
    fun `a key with no property is ignored, and a property with no key takes its default`() {
        assertEquals(Allotment("Gallia"), read<Allotment>("SELECT ROW('legio', 'X Equestris', 'province', 'Gallia')"))
    }

    @Test
    fun `a NULL under a key is a value, which a default does not replace`() {
        val e = refusal<Allotment>("SELECT ROW('province', 'Gallia', 'iugera', NULL)")

        assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, e.reason)
        assertEquals("iugera", e.path.first())
    }

    @Test
    fun `a record the map reading refuses is refused for a class too`() {
        assertEquals(
            MappingExceptionReason.CONVERSION_ERROR,
            refusal<LandGrant>("SELECT ROW('province', 'Gallia', 'iugera')").reason
        )
        assertEquals(
            MappingExceptionReason.CONVERSION_ERROR,
            refusal<LandGrant>("SELECT ROW(NULL::text, 'Gallia')").reason
        )

        val duplicate = refusal<LandGrant>("SELECT ROW('province', 'Gallia', 'province', 'Hispania', 'iugera', 120)")
        assertEquals(MappingExceptionReason.CONVERSION_ERROR, duplicate.reason)
        assertEquals("[2]", duplicate.path.first())
    }

    @Test
    fun `a key that is not text fails at its position`() {
        val e = refusal<LandGrant>("SELECT ROW(1, 'Gallia')")

        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, e.reason)
        assertEquals("[0]", e.path.first())
    }
}
