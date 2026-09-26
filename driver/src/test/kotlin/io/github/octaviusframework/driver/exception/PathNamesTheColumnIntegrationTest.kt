package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A failure inside a composite column has to read the same way whichever route reached it.
 *
 * `fetchObjects` maps the row through `ReflectionRowConverter`, which names each column as it descends;
 * `fetchField` and `Row.get` go through `Row.get`, which used to hand the value to the mapper with no segment
 * at all - so the same broken value arrived naming the attribute and never the column it was in.
 */
class PathNamesTheColumnIntegrationTest : AbstractIntegrationTest() {

    /** `city` is `text` in the database, so reading it as an `Int` fails inside the composite. */
    data class PathAddress(val street: String, val city: Int)

    private val sql = "SELECT ROW('Via Sacra', 'Roma')::path_addr AS residence"

    override val schema = "CREATE TYPE path_addr AS (street text, city text)"

    @Test
    fun `the column is on the path whichever route reached the failure`() {
        openSession().use { s ->
            s.reloadTypes()
            s.typeManager.registerAutoComposite<PathAddress>("path_addr")

            val throughFetchField = assertFailsWith<MappingException> {
                s.createNativeQuery(sql).fetchField<PathAddress>()
            }
            val throughRowGet = assertFailsWith<MappingException> {
                s.createNativeQuery(sql).fetchRowStrict().get<PathAddress>("residence")
            }

            assertEquals(listOf("residence", "city"), throughFetchField.path.asReversed())
            assertEquals(throughFetchField.path, throughRowGet.path, "the two routes should read alike")
        }
    }
}
