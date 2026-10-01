package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.datetime.toKotlinLocalDate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A value that cannot be sent names the parameter it was bound to.
 *
 * The query context lists every value, and two of the same type are indistinguishable in it - so an attribute on
 * the path says what failed and not which of them it failed in.
 */
class PathNamesTheParameterIntegrationTest : AbstractIntegrationTest() {

    /** `tribute` is `int4` in the database, so a `String` in it has nothing to go out as, and a `null` goes as one. */
    data class PathLevy(val tribute: String?)

    override val schema = "CREATE TYPE path_levy AS (tribute int4)"

    @Test
    fun `a mapping failure keeps its attribute under the parameter`() {
        openSession().use { s ->
            s.reloadTypes()
            s.typeManager.registerAutoComposite<PathLevy>("path_levy")

            val positional = assertFailsWith<MappingException> {
                s.createNativeQuery("SELECT $1::path_levy, $2::path_levy").fetchRowStrict(PathLevy(null), PathLevy("XL"))
            }
            val named = assertFailsWith<MappingException> {
                s.createNamedQuery("SELECT @gallia::path_levy, @hispania::path_levy")
                    .fetchRowStrict("gallia" to PathLevy(null), "hispania" to PathLevy("XL"))
            }

            assertEquals(listOf("\$2", "tribute"), positional.path.asReversed())
            assertEquals(listOf("\$2", "tribute"), named.path.asReversed(), "the position in the statement sent")
        }
    }

    @Test
    fun `a failure that is not a mapping one is named the same way`() {
        // A date whose day count is the one PostgreSQL reserves for infinity, refused by the codec.
        val reserved = java.time.LocalDate.ofEpochDay(Int.MAX_VALUE.toLong() + 10957L).toKotlinLocalDate()
        val ordinary = java.time.LocalDate.of(9, 9, 9).toKotlinLocalDate()

        openSession().use { s ->
            val thrown = assertFailsWith<CodecException> {
                s.createNativeQuery("SELECT $1::date, $2::date").fetchRowStrict(ordinary, reserved)
            }

            assertEquals(listOf("\$2"), thrown.path)
        }
    }
}
