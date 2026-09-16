package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterMapper
import io.github.octaviusframework.driver.registry.CatalogHolder
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.registry.TypeManager
import org.junit.jupiter.api.Test
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.execution.ParameterSerializer
import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.type.PgStandardType
import io.github.octaviusframework.driver.type.withPgType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParameterSerializerTest {

    private fun serializeValueForTest(serializer: ParameterSerializer, value: Any?): ByteArray? {
        val writer = PgByteWriter()
        serializer.serializeAll(arrayOf<Any?>(value), writer)
        val bytes = writer.toByteArray()
        if (bytes.size < 4) return null
        val length = (bytes[0].toInt() and 0xFF shl 24) or
                (bytes[1].toInt() and 0xFF shl 16) or
                (bytes[2].toInt() and 0xFF shl 8) or
                (bytes[3].toInt() and 0xFF)
        if (length == -1) return null
        return bytes.copyOfRange(4, 4 + length)
    }

    @Test
    fun testBasicRoundTrip() {
        val registry = CatalogHolder()
        val typeManager = TypeManager(registry)
        val parameterMapper = ParameterMapper(registry.catalog, null, typeManager.lookup)
        val serializer = ParameterSerializer(typeManager.lookup, parameterMapper)

        // Test for Null
        val nullBytes = serializeValueForTest(serializer, null)
        assertNull(nullBytes, "Serialization of null should return null")

        // Test for Integer
        val intVal = 12345
        val intBytes = serializeValueForTest(serializer, intVal)
        assertNotNull(intBytes)
        val intHandler = registry.catalog.codecs.getCodecByClass(Int::class)!!
        val parsedInt = intHandler.fromBinary(intBytes, 0, intBytes.size)
        assertEquals(intVal, parsedInt, "Integer roundtrip should match original value")

        // Test for String
        val stringVal = "test_string_123"
        val stringBytes = serializeValueForTest(serializer, stringVal)
        assertNotNull(stringBytes)
        val stringHandler = registry.catalog.codecs.getCodecByClass(String::class)!!
        val parsedString = stringHandler.fromBinary(stringBytes, 0, stringBytes.size)
        assertEquals(stringVal, parsedString, "String roundtrip should match original value")

        // Test for Boolean
        val boolVal = true
        val boolBytes = serializeValueForTest(serializer, boolVal)
        assertNotNull(boolBytes)
        val boolHandler = registry.catalog.codecs.getCodecByClass(Boolean::class)!!
        val parsedBool = boolHandler.fromBinary(boolBytes, 0, boolBytes.size)
        assertEquals(boolVal, parsedBool, "Boolean roundtrip should match original value")

        // Test for Double
        val doubleVal = 3.14159
        val doubleBytes = serializeValueForTest(serializer, doubleVal)
        assertNotNull(doubleBytes)
        val doubleHandler = registry.catalog.codecs.getCodecByClass(Double::class)!!
        val parsedDouble = doubleHandler.fromBinary(doubleBytes, 0, doubleBytes.size)
        assertEquals(doubleVal, parsedDouble, "Double roundtrip should match original value")
    }

    @Test
    fun testByteArrayRoundTrip() {
        val registry = CatalogHolder()
        val typeManager = TypeManager(registry)
        val parameterMapper = ParameterMapper(registry.catalog, null, typeManager.lookup)
        val serializer = ParameterSerializer(typeManager.lookup, parameterMapper)

        val byteArrayVal = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
        val bytes = serializeValueForTest(serializer, byteArrayVal)
        assertNotNull(bytes)
        val handler = registry.catalog.codecs.getCodecByClass(ByteArray::class)!!
        val parsedByteArray = handler.fromBinary(bytes, 0, bytes.size)
        
        assertEquals(byteArrayVal.toList(), parsedByteArray.toList(), "ByteArray roundtrip should match original value")
    }

    @Test
    fun testSerializeAllMultipleParameters() {
        val registry = CatalogHolder()
        val typeManager = TypeManager(registry)
        val parameterMapper = ParameterMapper(registry.catalog, null, typeManager.lookup)
        val serializer = ParameterSerializer(typeManager.lookup, parameterMapper)

        val parameters = arrayOf<Any?>(123, "test", null, true)
        val writer = PgByteWriter()
        val oids = serializer.serializeAll(parameters, writer)
        val bytes = writer.toByteArray()

        assertEquals(4, oids.size)
        // verify oids logic: Int, String, Null, Boolean
        assertTrue(oids[0] != 0, "Int OID should be resolved")
        assertTrue(oids[1] != 0, "String OID should be resolved")
        assertEquals(0, oids[2], "Null OID should be 0 by default")
        assertTrue(oids[3] != 0, "Boolean OID should be resolved")
        
        assertTrue(bytes.size > 16, "Bytes should contain lengths and payload")
    }

    @Test
    fun testPgTypedSerialization() {
        val registry = CatalogHolder()
        registry.update { it.withTypes(mapOf(PgStandardType.INT8.oid to PgType.Base(PgStandardType.INT8.oid, PgStandardType.INT8.typeName, "pg_catalog"))) }
        
        val typeManager = TypeManager(registry)
        val parameterMapper = ParameterMapper(registry.catalog, null, typeManager.lookup)
        val serializer = ParameterSerializer(typeManager.lookup, parameterMapper)

        // Int value explicitly typed as INT8
        val typedValue = 123L.withPgType(PgStandardType.INT8)
        val writer = PgByteWriter()
        val oids = serializer.serializeAll(arrayOf<Any?>(typedValue), writer)

        assertEquals(1, oids.size)
        assertEquals(PgStandardType.INT8.oid, oids[0], "OID should match explicitly provided INT8 type")
    }

    @Test
    fun testUnsupportedTypeThrowsException() {
        val registry = CatalogHolder()
        val typeManager = TypeManager(registry)
        val parameterMapper = ParameterMapper(registry.catalog, null, typeManager.lookup)
        val serializer = ParameterSerializer(typeManager.lookup, parameterMapper)

        class CustomUnsupportedClass(val data: String)
        val unsupported = CustomUnsupportedClass("test")
        val writer = PgByteWriter()

        assertFailsWith<TypeException>("Should fail when codec is missing") {
            serializer.serializeAll(arrayOf<Any?>(unsupported), writer)
        }
    }
}
