package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.identifier.CaseConvention
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * An enum type name is registered for one Kotlin enum and an enum under one type name and one pair of
 * conventions, as a composite is: a value read as `Any` and a constant written without its type stated each have
 * one answer, which a second registration could only replace.
 */
class EnumRegistrationTest {

    enum class Rank { Legatus, Tribunus }
    enum class Grade { Legatus, Tribunus }

    object Elsewhere {
        enum class Rank { Legatus, Tribunus }
    }

    @Test
    fun `one type name for two enums is refused`() {
        val holder = CatalogHolder()
        val types = TypeManager(holder)
        types.registerEnum<Rank>("rank", "public")
        val before = holder.catalog

        val ex = assertThrows<InvalidOperationException> {
            types.registerEnum<Grade>("rank", "public")
        }

        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, ex.reason)
        assertSame(before, holder.catalog)
    }

    @Test
    fun `one enum under two type names is refused`() {
        val holder = CatalogHolder()
        val types = TypeManager(holder)
        types.registerEnum<Rank>("rank", "public")
        val before = holder.catalog

        val ex = assertThrows<InvalidOperationException> {
            types.registerEnum<Rank>("grade", "public")
        }

        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, ex.reason)
        assertSame(before, holder.catalog)
        assertEquals(QualifiedName("public", "rank"), holder.catalog.registeredEnums[Rank::class]!!.qualifiedName)
    }

    @Test
    fun `one enum under one name with other conventions is refused`() {
        val holder = CatalogHolder()
        val types = TypeManager(holder)
        types.registerEnum<Rank>("rank", "public")
        val before = holder.catalog

        val ex = assertThrows<InvalidOperationException> {
            types.registerEnum<Rank>("rank", "public", pgConvention = CaseConvention.SNAKE_CASE_LOWER)
        }

        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, ex.reason)
        val details = ex.details!!
        assertTrue(details.contains("SNAKE_CASE_UPPER") && details.contains("SNAKE_CASE_LOWER"), details)
        assertSame(before, holder.catalog)
    }

    @Test
    fun `the same enum under the same name and conventions again changes nothing`() {
        // Converters included: the catalog is the one there was, so no second pair went ahead of the first.
        val holder = CatalogHolder()
        val types = TypeManager(holder)
        types.registerEnum<Rank>("rank", "public")
        val before = holder.catalog

        types.registerEnum<Rank>("rank", "public")

        assertSame(before, holder.catalog)
    }

    @Test
    fun `two enums of one simple name are told apart in the message`() {
        val types = TypeManager(CatalogHolder())
        types.registerEnum<Rank>()

        val ex = assertThrows<InvalidOperationException> {
            types.registerEnum<Elsewhere.Rank>()
        }

        val details = ex.details!!
        assertTrue(details.contains(Rank::class.qualifiedName!!), details)
        assertTrue(details.contains(Elsewhere.Rank::class.qualifiedName!!), details)
    }
}
