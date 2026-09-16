package io.github.octaviusframework.driver.converter.range

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.parameter.range.MultiRangeParameterConverter
import io.github.octaviusframework.driver.converter.parameter.range.RangeParameterConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.converter.result.range.MultiRangeResultConverter
import io.github.octaviusframework.driver.converter.result.range.RangeResultConverter
import io.github.octaviusframework.driver.registry.CatalogHolder
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.registry.TypeManager
import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.container.PgMultirange
import io.github.octaviusframework.driver.container.PgRange
import io.github.octaviusframework.driver.type.range.Range
import io.github.octaviusframework.driver.type.range.rangeOf
import io.github.octaviusframework.driver.type.range.multiRangeOf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.typeOf

class RangeConverterTest {

    private val baseOid = 23 // int4
    private val rangeOid = 3904 // int4range
    private val multiRangeOid = 4451 // int4multirange

    private val dummyRegistry = CatalogHolder().apply {
        update { it.withTypes(mapOf(
            baseOid to PgType.Base(baseOid, "int4", "pg_catalog"),
            rangeOid to PgType.Range(rangeOid, "int4range", "pg_catalog", baseOid),
            multiRangeOid to PgType.Multirange(multiRangeOid, "int4multirange", "pg_catalog", rangeOid)
        )) }
    }

    private val typeManager = TypeManager(dummyRegistry)
    private val pgRangeType = dummyRegistry.catalog.dictionary.getPgType(rangeOid) as PgType.Range
    private val pgMultiRangeType = dummyRegistry.catalog.dictionary.getPgType(multiRangeOid) as PgType.Multirange

    @Test
    fun `test RangeResultConverter deserialization`() {
        val deserializer = ResultMapper(dummyRegistry.catalog, listOf(RangeResultConverter), typeManager.lookup)

        val pgRange = PgRange.create(
            rangeOid = rangeOid,
            elementOid = baseOid,
            lowerBound = 10,
            upperBound = 20,
            isLowerInclusive = true,
            isUpperInclusive = false,
            isLowerInfinite = false,
            isUpperInfinite = false,
            isLowerNull = false,
            isUpperNull = false
        )

        val expectedType = typeOf<Range<Int>>()
        val result = deserializer.deserialize<Range<Int>>(pgRange, expectedType, pgRangeType)
        
        assertNotNull(result)
        assertEquals(10, result.lowerBound)
        assertEquals(20, result.upperBound)
        assertTrue(result.isLowerInclusive)
        assertFalse(result.isUpperInclusive)
    }

    @Test
    fun `test MultiRangeResultConverter deserialization`() {
        val deserializer = ResultMapper(
            dummyRegistry.catalog,
            listOf(RangeResultConverter, MultiRangeResultConverter),
            typeManager.lookup
        )

        val pgRange1 = PgRange.create(
            rangeOid = rangeOid,
            elementOid = baseOid,
            lowerBound = 10,
            upperBound = 20,
            isLowerInclusive = true,
            isUpperInclusive = false
        )
        
        val pgRange2 = PgRange.create(
            rangeOid = rangeOid,
            elementOid = baseOid,
            lowerBound = 30,
            upperBound = null,
            isLowerInclusive = true,
            isUpperInclusive = false,
            isLowerInfinite = false,
            isUpperInfinite = true
        )

        val pgMultiRange = PgMultirange.create(multiRangeOid, rangeOid, listOf(pgRange1, pgRange2))

        val expectedType = typeOf<MultiRange<Int>>()
        val result = deserializer.deserialize<MultiRange<Int>>(pgMultiRange, expectedType, pgMultiRangeType)
        
        assertNotNull(result)
        assertEquals(2, result.ranges.size)
        assertEquals(10, result.ranges[0].lowerBound)
        assertEquals(20, result.ranges[0].upperBound)
        assertEquals(30, result.ranges[1].lowerBound)
        assertNull(result.ranges[1].upperBound)
        assertTrue(result.ranges[1].isUpperInfinite)
    }

    @Test
    fun `test RangeParameterConverter serialization`() {
        val converter = RangeParameterConverter
        
        val context = object : SerializationContext {
            override val types = this@RangeConverterTest.typeManager.lookup
            override fun convert(source: Any, expectedOid: Int, pathSegment: String?): Any = source
            override fun findConverter(source: Any, expectedOid: Int): ParameterConverter<Any>? = null
            override fun findConverterByClass(
                sourceClass: KClass<*>,
                expectedOid: Int
            ): ParameterConverter<Any>? = null
        }

        val range = rangeOf(
            lowerBound = 5,
            upperBound = 15,
            isLowerInclusive = true,
            isUpperInclusive = true
        )

        assertTrue(converter.canConvert(range::class, rangeOid, context))

        val serialized = converter.convert(range, rangeOid, context) as PgRange
        assertNotNull(serialized)
        assertEquals(rangeOid, serialized.rangeOid)
        assertEquals(baseOid, serialized.elementOid)
        assertEquals(5, serialized.lowerBound)
        assertEquals(15, serialized.upperBound)
        assertTrue(serialized.isLowerInclusive)
        assertTrue(serialized.isUpperInclusive)
    }

    @Test
    fun `test MultiRangeParameterConverter serialization`() {
        val converter = MultiRangeParameterConverter

        val context = object : SerializationContext {
            override val types = this@RangeConverterTest.typeManager.lookup
            override fun convert(source: Any, expectedOid: Int, pathSegment: String?): Any = source
            override fun findConverter(source: Any, expectedOid: Int): ParameterConverter<Any>? = null
            override fun findConverterByClass(
                sourceClass: KClass<*>,
                expectedOid: Int
            ): ParameterConverter<Any>? = null
        }

        val multiRange = multiRangeOf(
            listOf(
                rangeOf(lowerBound = 1, upperBound = 5, isLowerInclusive = true, isUpperInclusive = false),
                rangeOf(lowerBound = 10, upperBound = null, isLowerInclusive = false, isUpperInfinite = true)
            )
        )

        assertTrue(converter.canConvert(multiRange::class, multiRangeOid, context))

        val serialized = converter.convert(multiRange, multiRangeOid, context) as PgMultirange
        assertNotNull(serialized)
        assertEquals(multiRangeOid, serialized.multirangeOid)
        assertEquals(rangeOid, serialized.rangeOid)
        assertEquals(2, serialized.ranges.size)

        val pgRange1 = serialized.ranges[0]
        assertEquals(1, pgRange1.lowerBound)
        assertEquals(5, pgRange1.upperBound)
        assertTrue(pgRange1.isLowerInclusive)
        assertFalse(pgRange1.isUpperInclusive)

        val pgRange2 = serialized.ranges[1]
        assertEquals(10, pgRange2.lowerBound)
        assertNull(pgRange2.upperBound)
        assertFalse(pgRange2.isLowerInclusive)
        assertTrue(pgRange2.isUpperInfinite)
    }
}
