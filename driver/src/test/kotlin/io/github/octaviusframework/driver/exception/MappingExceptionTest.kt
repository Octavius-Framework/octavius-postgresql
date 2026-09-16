package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.converter.result.array.CollectionArrayConverter
import io.github.octaviusframework.driver.converter.result.composite.ReflectionCompositeConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.registry.CatalogHolder
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.container.ArrayDimension
import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.registry.TypeManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MappingExceptionTest {
    companion object {
        val logger = KotlinLogging.logger {}
    }

    private val dummyRegistry = CatalogHolder().apply {
        update { it.withTypes(mapOf(
            1 to PgType.Base(1, "dummy", "public"),
            2 to PgType.Array(2, "dummy_array", "public", 1)
        )) }
        update { it.withComposite(Address::class, QualifiedName("", "address")) }
        update { it.withComposite(Person::class, QualifiedName("", "person")) }
        update { it.withComposite(Company::class, QualifiedName("", "company")) }
    }

    private fun createComposite(attributes: Map<String, Any?>): PgComposite {
        val type = PgType.Composite(1, "dummy", "public", LinkedHashMap(attributes.keys.associateWith { 1 }))
        val fields = attributes.values.toTypedArray()
        return PgComposite(type, fields)
    }

    private fun createArray(elements: List<Any?>): PgArray {
        return PgArray(
            arrayOid = 2,
            elementOid = 1,
            dimensions = listOf(ArrayDimension(elements.size, 1)),
            elements = elements.toMutableList()
        )
    }

    data class Address(val street: String, val city: String)
    data class Person(val name: String, val age: Int, val address: Address)
    data class Company(val name: String, val employees: List<Person>)

    @Test
    fun `test nested composite mapping exception path for missing attribute`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(ReflectionCompositeConverter, CollectionArrayConverter),
            TypeManager(dummyRegistry).lookup
        )

        // create valid person
        val p1 = createComposite(
            mapOf(
                "name" to "A",
                "age" to 20,
                "address" to createComposite(mapOf("street" to "S1", "city" to "C1"))
            )
        )
        // create invalid person (address is missing city)
        val p2 = createComposite(
            mapOf(
                "name" to "B",
                "age" to 25,
                "address" to createComposite(mapOf("street" to "S2")) // missing 'city'
            )
        )

        val array = createArray(listOf(p1, p2))
        val companyComposite = createComposite(mapOf("name" to "Corp", "employees" to array))

        val ex = assertFailsWith<MappingException> {
            deserializer.deserialize<Company>(companyComposite, typeOf<Company>(), companyComposite.type)
        }
        logger.error(ex) {}

        val details = ex.getDetailedMessage()
        // The path is rendered by OctaviusException, alongside the query context and for the same reason:
        // every layer writes to it, not only the mapper that raised this.
        val rendered = ex.toString()
        assertTrue(rendered.contains("PATH: employees -> [1] -> address -> city"), "Expected path missing, got: $rendered")
        assertTrue(details.contains("Missing non-nullable attribute 'city'"), "Expected missing attribute message, got: $details")
    }

    @Test
    fun `test nested composite mapping exception path for null in non-nullable property`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(ReflectionCompositeConverter, CollectionArrayConverter),
            TypeManager(dummyRegistry).lookup
        )

        // create invalid person (name is null but expected String)
        val p1 = createComposite(
            mapOf(
                "name" to null,
                "age" to 20,
                "address" to createComposite(mapOf("street" to "S1", "city" to "C1"))
            )
        )

        val array = createArray(listOf(p1))
        val companyComposite = createComposite(mapOf("name" to "Corp", "employees" to array))

        val ex = assertFailsWith<MappingException> {
            deserializer.deserialize<Company>(companyComposite, typeOf<Company>(), companyComposite.type)
        }
        logger.error(ex) {}
        val details = ex.getDetailedMessage()
        assertEquals(listOf("employees", "[0]", "name"), ex.path.asReversed(), "Expected path missing, got: ${ex.path}")
        assertTrue(details.contains("Null value for non-nullable attribute 'name'"), "Expected null property message, got: $details")
    }

    @Test
    fun `test nested array mapping exception path for null in non-nullable array element`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(ReflectionCompositeConverter, CollectionArrayConverter),
            TypeManager(dummyRegistry).lookup
        )

        // employees is List<Person> (non-nullable elements)
        // we put null as one of the elements
        val array = createArray(listOf(null))
        val companyComposite = createComposite(mapOf("name" to "Corp", "employees" to array))

        val ex = assertFailsWith<MappingException> {
            deserializer.deserialize<Company>(companyComposite, typeOf<Company>(), companyComposite.type)
        }
        logger.error(ex) {}
        val details = ex.getDetailedMessage()
        assertEquals(listOf("employees", "[0]"), ex.path.asReversed(), "Expected path missing, got: ${ex.path}")
        assertTrue(details.contains("Null array element for non-nullable type"), "Expected null element message, got: $details")
    }

    // --- Where a container's own accessor is the leaf ------------------------------------------------

    @Test
    fun `a composite accessor names the attribute it failed on`() {
        // Read out of a container by hand - which is what a hand-written converter does - and the path has to
        // end somewhere. Without this it ends at the attribute above, and says nothing about which attribute
        // of it was being read.
        val composite = createComposite(mapOf("street" to "Via Sacra", "city" to "Roma"))

        val ex = assertFailsWith<MappingException> { composite.get<Int>("city") }

        assertEquals(listOf("city"), ex.path)
        assertEquals(MappingExceptionReason.CONVERSION_ERROR, ex.reason)
    }

    @Test
    fun `an array accessor names the element position`() {
        val array = createArray(listOf("Roma", "Ostia"))

        val ex = assertFailsWith<MappingException> { array.get<Int>(1) }

        assertEquals(listOf("[1]"), ex.path)
    }

    @Test
    fun `a lookup that finds nothing gets no path`() {
        // The rule the two above are one half of: a path says where the value was, so there is none to give
        // for a position that holds nothing. The index is in the message, and saying it twice would read as
        // though something had been found there.
        val composite = createComposite(mapOf("street" to "Via Sacra"))

        val outOfBounds = assertFailsWith<MappingException> { composite.get<String>(9) }
        assertEquals(emptyList(), outOfBounds.path)
        assertEquals(MappingExceptionReason.COLUMN_NOT_FOUND, outOfBounds.reason)

        val noSuchName = assertFailsWith<MappingException> { composite.get<String>("forum") }
        assertEquals(emptyList(), noSuchName.path)
        assertEquals(MappingExceptionReason.COLUMN_NOT_FOUND, noSuchName.reason)
    }
}
