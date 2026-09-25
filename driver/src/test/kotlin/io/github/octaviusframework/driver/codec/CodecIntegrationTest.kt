package io.github.octaviusframework.driver.codec

import io.github.octaviusframework.driver.exception.CodecException
import io.github.octaviusframework.driver.type.PgStandardType
import io.github.octaviusframework.driver.type.geometric.*
import io.github.octaviusframework.driver.type.withPgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.*
import kotlin.uuid.Uuid

class CodecIntegrationTest : AbstractIntegrationTest() {

    @Test
    fun testGeometricTypes() {
        val session = openSession()
        
        // Point
        val point = PgPoint(1.5, 2.5)
        val resPoint = session.createNativeQuery("SELECT $1 as res").fetchField<PgPoint>(point)
        assertEquals(point, resPoint)

        // Line
        val line = PgLine(1.0, 2.0, 3.0)
        val resLine = session.createNativeQuery("SELECT $1 as res").fetchField<PgLine>(line)
        assertEquals(line, resLine)

        // Lseg
        val lseg = PgLseg(PgPoint(0.0, 0.0), PgPoint(1.0, 1.0))
        val resLseg = session.createNativeQuery("SELECT $1 as res").fetchField<PgLseg>(lseg)
        assertEquals(lseg, resLseg)

        // Box
        val box = PgBox(PgPoint(2.0, 2.0), PgPoint(0.0, 0.0))
        val resBox = session.createNativeQuery("SELECT $1 as res").fetchField<PgBox>(box)
        assertEquals(box, resBox)

        // Path
        val path = PgPath(true, listOf(PgPoint(0.0, 0.0), PgPoint(1.0, 0.0), PgPoint(0.0, 1.0)))
        val resPath = session.createNativeQuery("SELECT $1 as res").fetchField<PgPath>(path)
        assertEquals(path, resPath)

        // Polygon
        val polygon = PgPolygon(listOf(PgPoint(0.0, 0.0), PgPoint(1.0, 0.0), PgPoint(0.0, 1.0)))
        val resPolygon = session.createNativeQuery("SELECT $1 as res").fetchField<PgPolygon>(polygon)
        assertEquals(polygon, resPolygon)

        // Circle
        val circle = PgCircle(PgPoint(0.0, 0.0), 5.5)
        val resCircle = session.createNativeQuery("SELECT $1 as res").fetchField<PgCircle>(circle)
        assertEquals(circle, resCircle)

        session.close()
    }

    @Test
    fun testBitStringTypes() {
        val session = openSession()
        
        val bitSet = BitSet()
        bitSet.set(0)
        bitSet.set(2)
        // bitSet in Kotlin/Java ignores exact length unless specified, but our codec handles it.
        // We can test varbit mapping
        val resVarbit = session.createNativeQuery("SELECT $1::varbit as res").fetchField<BitSet>(bitSet)
        assertEquals(bitSet, resVarbit)
        
        session.close()
    }

    @Test
    fun testNetworkTypes() {
        val session = openSession()

        // Inet
        val inetVal = "192.168.1.5/24"
        val resInet = session.createNativeQuery("SELECT $1 as res").fetchField<String>(inetVal.withPgType("inet"))
        assertEquals(inetVal, resInet)

        // Inet IPv6
        val inet6Val = "2001:db8:0:0:0:0:0:1"
        val resInet6 = session.createNativeQuery("SELECT $1 as res").fetchField<String>("2001:db8::1".withPgType("inet"))
        assertEquals(inet6Val, resInet6)

        // Cidr
        val cidrVal = "192.168.1.0/24"
        val resCidr = session.createNativeQuery("SELECT $1 as res").fetchField<String>(cidrVal.withPgType("cidr"))
        assertEquals(cidrVal, resCidr)

        // MacAddr
        val macVal = "08:00:2b:01:02:03"
        val resMac = session.createNativeQuery("SELECT $1 as res").fetchField<String>(macVal.withPgType("macaddr"))
        assertEquals(macVal, resMac)

        // MacAddr8
        val mac8Val = "08:00:2b:01:02:03:04:05"
        val resMac8 = session.createNativeQuery("SELECT $1 as res").fetchField<String>(mac8Val.withPgType("macaddr8"))
        assertEquals(mac8Val, resMac8)

        session.close()
    }

    @Test
    fun testXmlType() {
        val session = openSession()

        val xmlVal = "<book><title>Effective Kotlin</title></book>"
        val resXml = session.createNativeQuery("SELECT $1 as res").fetchField<String>(xmlVal.withPgType(PgStandardType.XML))
        assertEquals(xmlVal, resXml)

        session.close()
    }

    @Test
    fun testStandardTypes() {
        val session = openSession()

        // Boolean
        val boolVal = true
        val resBool = session.createNativeQuery("SELECT $1 as res").fetchField<Boolean>(boolVal)
        assertEquals(boolVal, resBool)

        // Bytea (ByteArray)
        val byteaVal = byteArrayOf(0x01, 0x02, 0x03, -0x01, -0x02)
        val resBytea = session.createNativeQuery("SELECT $1 as res").fetchField<ByteArray>(byteaVal)
        kotlin.test.assertContentEquals(byteaVal, resBytea)

        // UUID
        val uuidVal = Uuid.random()
        val resUuid = session.createNativeQuery("SELECT $1 as res").fetchField<Uuid>(uuidVal)
        assertEquals(uuidVal, resUuid)

        // Int, Long, Short
        val intVal = 42
        assertEquals(intVal, session.createNativeQuery("SELECT $1 as res").fetchField<Int>(intVal))
        
        val longVal = 9999999999L
        assertEquals(longVal, session.createNativeQuery("SELECT $1 as res").fetchField<Long>(longVal))

        val shortVal: Short = 32000
        assertEquals(shortVal, session.createNativeQuery("SELECT $1 as res").fetchField<Short>(shortVal))

        // Float, Double
        val floatVal = 3.14f
        assertEquals(floatVal, session.createNativeQuery("SELECT $1 as res").fetchField<Float>(floatVal))

        val doubleVal = 2.718281828459
        assertEquals(doubleVal, session.createNativeQuery("SELECT $1 as res").fetchField<Double>(doubleVal))

        // JSON / JSONB
        val jsonVal = """{"key": "value", "list": [1, 2, 3]}"""
        val resJsonb = session.createNativeQuery("SELECT $1::jsonb as res").fetchField<String>(jsonVal.withPgType("jsonb"))
        assertEquals(jsonVal.replace(" ", ""), resJsonb.replace(" ", ""))

        // Numeric (BigDecimal)
        val numericVal = BigDecimal("123456789.987654321")
        val resNumeric = session.createNativeQuery("SELECT $1 as res").fetchField<BigDecimal>(numericVal)
        assertEquals(numericVal, resNumeric)

        // Numeric NaN and Infinity checks
        assertThrows<CodecException> {
            session.createNativeQuery("SELECT 'NaN'::numeric").fetchFieldStrict<BigDecimal>()
        }
        assertThrows<CodecException> {
            session.createNativeQuery("SELECT 'Infinity'::numeric").fetchFieldStrict<BigDecimal>()
        }
        assertThrows<CodecException> {
            session.createNativeQuery("SELECT '-Infinity'::numeric").fetchFieldStrict<BigDecimal>()
        }

        session.close()
    }
}
