package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins down the two independent questions the field family answers: how many rows came back, which the
 * `Strict` suffix governs, and whether a value had to be there at all, which the nullability of `T` does.
 */
class FieldNullabilityIntegrationTest : AbstractIntegrationTest() {

    private val noRows = "SELECT 'x'::text WHERE false"
    private val oneNullRow = "SELECT NULL::text"

    // ---------------------------------- T states whether a value is required ----------------------------------

    @Test
    fun `fetchField with a non-nullable type should throw when no row matched`() {
        openSession().use { s ->
            val e = assertFailsWith<MappingException> {
                s.createNativeQuery(noRows).fetchField<String>()
            }
            assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, e.reason)
        }
    }

    @Test
    fun `fetchField with a non-nullable type should throw when the value is NULL`() {
        openSession().use { s ->
            val e = assertFailsWith<MappingException> {
                s.createNativeQuery(oneNullRow).fetchField<String>()
            }
            assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, e.reason)
        }
    }

    @Test
    fun `fetchField with a nullable type should return null for both kinds of absence`() {
        openSession().use { s ->
            assertNull(s.createNativeQuery(noRows).fetchField<String?>())
            assertNull(s.createNativeQuery(oneNullRow).fetchField<String?>())
        }
    }

    @Test
    fun `fetchField should still return a present value`() {
        openSession().use { s ->
            assertEquals("x", s.createNativeQuery("SELECT 'x'::text").fetchField<String>())
        }
    }

    /**
     * The guarantee is a type-level one, not only a runtime one: a non-nullable `T` comes back as a
     * non-nullable type, so none of these assignments need an unwrap. They are the assertion - if
     * `fetchField` went back to declaring `T?`, this stops compiling.
     */
    @Test
    fun `fetchField should return T as declared, without widening it to nullable`() {
        openSession().use { s ->
            val native: String = s.createNativeQuery("SELECT 'x'::text").fetchField<String>()
            val namedMap: String = s.createNamedQuery("SELECT @v::text").fetchField<String>(mapOf("v" to "x"))
            val namedPair: String = s.createNamedQuery("SELECT @v::text").fetchField<String>("v" to "x")
            assertEquals("x", native)
            assertEquals("x", namedMap)
            assertEquals("x", namedPair)

            val nullable: String? = s.createNativeQuery(oneNullRow).fetchField<String?>()
            assertNull(nullable)
        }
    }

    @Test
    fun `named fetchField should follow the same rule through the map form`() {
        openSession().use { s ->
            val e = assertFailsWith<MappingException> {
                s.createNamedQuery("SELECT 'x'::text WHERE @flag").fetchField<String>(mapOf("flag" to false))
            }
            assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, e.reason)

            assertNull(s.createNamedQuery("SELECT 'x'::text WHERE @flag").fetchField<String?>(mapOf("flag" to false)))
        }
    }

    /**
     * The `Pair` overloads delegate to the `Map` ones, and the delegation used to let inference widen
     * the type argument from `T` to `T?` - the expected return type `T?` admits both. That handed the
     * callee a nullable target type and silently disabled every nullability check behind it, so this
     * pins the two forms to the same behaviour rather than only the rule itself.
     */
    @Test
    fun `named fetchField should follow the same rule through the pair form`() {
        openSession().use { s ->
            val noRow = assertFailsWith<MappingException> {
                s.createNamedQuery("SELECT 'x'::text WHERE @flag").fetchField<String>("flag" to false)
            }
            assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, noRow.reason)

            val nullValue = assertFailsWith<MappingException> {
                s.createNamedQuery("SELECT NULL::text WHERE @flag").fetchField<String>("flag" to true)
            }
            assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, nullValue.reason)

            assertNull(s.createNamedQuery("SELECT 'x'::text WHERE @flag").fetchField<String?>("flag" to false))
            assertNull(s.createNamedQuery("SELECT NULL::text WHERE @flag").fetchField<String?>("flag" to true))
            assertEquals("x", s.createNamedQuery("SELECT 'x'::text WHERE @flag").fetchField<String>("flag" to true))
        }
    }

    @Test
    fun `named fetchFields should reject a NULL value under a non-nullable type in both forms`() {
        openSession().use { s ->
            assertFailsWith<MappingException> {
                s.createNamedQuery("SELECT NULL::text WHERE @flag").fetchFields<String>("flag" to true)
            }
            assertFailsWith<MappingException> {
                s.createNamedQuery("SELECT NULL::text WHERE @flag").fetchFields<String>(mapOf("flag" to true))
            }
            assertEquals(listOf(null), s.createNamedQuery("SELECT NULL::text WHERE @flag").fetchFields<String?>("flag" to true))
        }
    }

    // ---------------------------------- Strict counts rows, and only rows ----------------------------------

    @Test
    fun `fetchFieldStrict should report an empty result as a size problem even for a nullable type`() {
        openSession().use { s ->
            val e = assertFailsWith<InvalidOperationException> {
                s.createNativeQuery(noRows).fetchFieldStrict<String?>()
            }
            assertEquals(InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE, e.reason)
        }
    }

    @Test
    fun `fetchFieldStrict should still return null for a NULL value under a nullable type`() {
        openSession().use { s ->
            assertNull(s.createNativeQuery(oneNullRow).fetchFieldStrict<String?>())
        }
    }

    @Test
    fun `both variants should reject more than one row`() {
        openSession().use { s ->
            val plain = assertFailsWith<InvalidOperationException> {
                s.createNativeQuery("SELECT i FROM generate_series(1, 2) AS i").fetchField<Int>()
            }
            assertEquals(InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE, plain.reason)

            val strict = assertFailsWith<InvalidOperationException> {
                s.createNativeQuery("SELECT i FROM generate_series(1, 2) AS i").fetchFieldStrict<Int>()
            }
            assertEquals(InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE, strict.reason)
        }
    }

    // ---------------------------------- The list form is unaffected ----------------------------------

    @Test
    fun `fetchFields should return an empty list rather than throwing when no row matched`() {
        openSession().use { s ->
            assertTrue(s.createNativeQuery(noRows).fetchFields<String>().isEmpty())
        }
    }

    // ------------------------- The row and object families have no nullable T to read -------------------------

    @Test
    fun `fetchRow and fetchObject should still return null when no row matched`() {
        openSession().use { s ->
            assertNull(s.createNativeQuery("SELECT 1 AS a WHERE false").fetchRow())
            assertNull(s.createNativeQuery("SELECT 1 AS a WHERE false").fetchObject<Map<String, Any?>>())
        }
    }
}
