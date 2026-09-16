package io.github.octaviusframework.driver.deserialization

import io.github.octaviusframework.driver.container.ArrayDimension
import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.converter.result.array.CollectionArrayConverter
import io.github.octaviusframework.driver.converter.result.composite.MapCompositeConverter
import io.github.octaviusframework.driver.converter.result.composite.ReflectionCompositeConverter
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.registry.TypeManager
import io.github.octaviusframework.driver.registry.CatalogHolder
import io.github.octaviusframework.driver.type.PgType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf


class DeserializationTest {

    private val dummyRegistry = CatalogHolder().apply {
        update { it.withTypes(mapOf(
            1 to PgType.Base(1, "dummy", "public"),
            2 to PgType.Array(2, "dummy_array", "public", 1)
        )) }
        update { it.withComposite(Address::class, QualifiedName("", "address")) }
        update { it.withComposite(Person::class, QualifiedName("", "person")) }
        update { it.withComposite(Company::class, QualifiedName("", "company")) }
        update { it.withComposite(OptionalFields::class, QualifiedName("", "optional_fields")) }
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
    data class OptionalFields(val id: Int, val name: String = "Unknown", val desc: String?)

    @Test
    fun `test simple composite reflection mapping`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(ReflectionCompositeConverter),
            TypeManager(dummyRegistry).lookup
        )

        val composite = createComposite(mapOf("street" to "Baker St", "city" to "London"))

        val address: Address? = deserializer.deserialize(composite, typeOf<Address>(), composite.type)
        assertNotNull(address)
        assertEquals("Baker St", address?.street)
        assertEquals("London", address?.city)
    }

    @Test
    fun `test nested composite reflection mapping`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(ReflectionCompositeConverter),
            TypeManager(dummyRegistry).lookup
        )

        val addressComposite = createComposite(mapOf("street" to "Wall St", "city" to "NY"))
        val personComposite = createComposite(mapOf("name" to "John", "age" to 30, "address" to addressComposite))

        val person: Person? = deserializer.deserialize(personComposite, typeOf<Person>(), personComposite.type)
        assertNotNull(person)
        assertEquals("John", person?.name)
        assertEquals(30, person?.age)
        assertEquals("Wall St", person?.address?.street)
        assertEquals("NY", person?.address?.city)
    }

    @Test
    fun `test array to list mapping with nested composites`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(ReflectionCompositeConverter, CollectionArrayConverter),
            TypeManager(dummyRegistry).lookup
        )

        val p1 = createComposite(
            mapOf(
                "name" to "A",
                "age" to 20,
                "address" to createComposite(mapOf("street" to "S1", "city" to "C1"))
            )
        )
        val p2 = createComposite(
            mapOf(
                "name" to "B",
                "age" to 25,
                "address" to createComposite(mapOf("street" to "S2", "city" to "C2"))
            )
        )

        val array = createArray(listOf(p1, p2))
        val companyComposite = createComposite(mapOf("name" to "Corp", "employees" to array))

        val company: Company? = deserializer.deserialize(companyComposite, typeOf<Company>(), companyComposite.type)
        assertNotNull(company)
        assertEquals("Corp", company?.name)
        assertEquals(2, company?.employees?.size)
        assertEquals("A", company?.employees?.get(0)?.name)
        assertEquals("S2", company?.employees?.get(1)?.address?.street)
    }

    @Test
    fun `test fallback to PgComposite for composite when Any is requested and explicit map conversion`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(ReflectionCompositeConverter, MapCompositeConverter),
            TypeManager(dummyRegistry).lookup
        )

        val composite = createComposite(mapOf("key1" to "value1", "key2" to 42))

        val resultAny: Any? = deserializer.deserialize(composite, typeOf<Any>(), composite.type)
        assertTrue(resultAny is PgComposite)

        val resultMap: Map<String, Any?>? = deserializer.deserialize(composite, typeOf<Map<String, Any?>>(), composite.type)
        assertNotNull(resultMap)
        assertEquals("value1", resultMap!!["key1"])
        assertEquals(42, resultMap["key2"])
    }

    @Test
    fun `test missing non-nullable field throws exception`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(ReflectionCompositeConverter),
            TypeManager(dummyRegistry).lookup
        )

        val composite = createComposite(mapOf("street" to "Baker St")) // Missing 'city'

        val ex = assertThrows(MappingException::class.java) {
            deserializer.deserialize<Address>(composite, typeOf<Address>(), composite.type)
        }
        assertTrue(ex.message!!.contains("REQUIRED_ATTRIBUTE_MISSING"))
    }

    @Test
    fun `test optional parameters and nulls are handled correctly`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(ReflectionCompositeConverter),
            TypeManager(dummyRegistry).lookup
        )

        val composite = createComposite(mapOf("id" to 10)) // missing name (has default), missing desc (nullable)

        val result: OptionalFields? = deserializer.deserialize(composite, typeOf<OptionalFields>(), composite.type)
        assertNotNull(result)
        assertEquals(10, result?.id)
        assertEquals("Unknown", result?.name)
        assertNull(result?.desc)
    }

    @Test
    fun `test local registry overrides global registry`() {
        val deserializer = ResultMapper(dummyRegistry.catalog, null, TypeManager(dummyRegistry).lookup)

        val composite = createComposite(mapOf("street" to "Global", "city" to "City"))

        // Define a custom converter for Address
        val localConverter = object : ResultConverter<Any, Address> {
            override val supportedSourceClass = Any::class
            override fun canConvert(sourceClass: kotlin.reflect.KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
                expectedType.classifier == Address::class

            override fun convert(
                source: Any,
                expectedType: KType,
                sourceType: PgType,
                context: DeserializationContext
            ): Address {
                return Address("LocalOverride", "LocalCity")
            }
        }

        val localDeserializer = ResultMapper(dummyRegistry.catalog, listOf(localConverter), TypeManager(dummyRegistry).lookup)

        // Using local registry
        val address: Address? = localDeserializer.deserialize(composite, typeOf<Address>(), composite.type)
        assertEquals("LocalOverride", address?.street)

        // Using global only
        val globalAddress: Address? = deserializer.deserialize(composite, typeOf<Address>(), composite.type)
        assertEquals("Global", globalAddress?.street)
    }
}

