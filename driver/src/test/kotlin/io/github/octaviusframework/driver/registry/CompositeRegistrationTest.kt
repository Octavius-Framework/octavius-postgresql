package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.identifier.QualifiedName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A composite type name is registered for one class and a class under one type name, as a `dynamic_dto` name is:
 * a composite read as `Any` and a class written without its type stated each have one answer, which a second
 * registration could only replace.
 */
class CompositeRegistrationTest {

    data class Address(val city: String, val street: String)
    data class Residence(val city: String, val street: String)

    object Elsewhere {
        data class Address(val city: String, val street: String)
    }

    @Test
    fun `one type name for two classes is refused`() {
        val holder = CatalogHolder()
        val types = TypeManager(holder)
        types.registerAutoComposite<Address>("address", "public")
        val before = holder.catalog

        val ex = assertThrows<InvalidOperationException> {
            types.registerAutoComposite<Residence>("address", "public")
        }

        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, ex.reason)
        assertSame(before, holder.catalog)
        assertEquals(Address::class, holder.catalog.compositeClassByName[QualifiedName("public", "address")])
    }

    @Test
    fun `one class under two type names is refused`() {
        val holder = CatalogHolder()
        val types = TypeManager(holder)
        types.registerAutoComposite<Address>("address", "public")
        val before = holder.catalog

        val ex = assertThrows<InvalidOperationException> {
            types.registerAutoComposite<Address>("domicile", "public")
        }

        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, ex.reason)
        assertSame(before, holder.catalog)
        assertEquals(QualifiedName("public", "address"), holder.catalog.registeredComposites[Address::class])
    }

    @Test
    fun `a name without a schema and the same name with one are two names`() {
        val types = TypeManager(CatalogHolder())
        types.registerAutoComposite<Address>("address")

        assertThrows<InvalidOperationException> {
            types.registerAutoComposite<Address>("address", "public")
        }
    }

    @Test
    fun `the same class under the same name again changes nothing`() {
        val holder = CatalogHolder()
        val types = TypeManager(holder)
        types.registerAutoComposite<Address>("address", "public")
        val before = holder.catalog

        types.registerAutoComposite<Address>("address", "public")

        assertSame(before, holder.catalog)
    }

    @Test
    fun `two classes of one simple name are told apart in the message`() {
        val types = TypeManager(CatalogHolder())
        types.registerAutoComposite<Address>()

        val ex = assertThrows<InvalidOperationException> {
            types.registerAutoComposite<Elsewhere.Address>()
        }

        val details = ex.details!!
        assertTrue(details.contains(Address::class.qualifiedName!!), details)
        assertTrue(details.contains(Elsewhere.Address::class.qualifiedName!!), details)
    }
}
