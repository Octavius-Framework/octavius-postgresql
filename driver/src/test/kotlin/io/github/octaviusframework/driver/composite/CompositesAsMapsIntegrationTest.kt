package io.github.octaviusframework.driver.composite

import io.github.octaviusframework.driver.converter.result.composite.compositesAsMaps
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The end of [compositesAsMaps] the unit test cannot reach: that registering one on a query builder puts it
 * where the mapper will find it, against types the catalogue loaded rather than ones written by hand.
 */
class CompositesAsMapsIntegrationTest : AbstractIntegrationTest() {

    data class CamTribute(val amount: Int, val currency: String)
    data class CamAssessment(val label: String, val payload: CamTribute)

    override val schema = """
        CREATE TYPE cam_tribute AS (amount int, currency text);
        CREATE TYPE cam_assessment AS (label text, payload cam_tribute);
    """.trimIndent()

    private val sql =
        "SELECT ROW('census', ROW(40, 'denarius')::cam_tribute)::cam_assessment AS assessment"

    @Test
    fun `the query it is registered on reads maps, and the next query does not`() {
        openSession().use { session ->
            session.reloadTypes()
            session.typeManager.registerAutoComposite<CamTribute>("cam_tribute")
            session.typeManager.registerAutoComposite<CamAssessment>("cam_assessment")

            val asMaps: Map<String, Any?> = session.createNativeQuery(sql)
                .registerResultConverter(compositesAsMaps())
                .fetchFieldStrict()

            assertEquals("census", asMaps["label"])
            assertEquals(mapOf("amount" to 40, "currency" to "denarius"), asMaps["payload"])

            // The registration went with the query, so the registration on the session is what the next one
            // sees - which is the whole point of asking for this per query rather than switching it on.
            val asClasses: CamAssessment = session.createNativeQuery(sql).fetchFieldStrict()

            assertEquals(CamAssessment("census", CamTribute(40, "denarius")), asClasses)
        }
    }
}
