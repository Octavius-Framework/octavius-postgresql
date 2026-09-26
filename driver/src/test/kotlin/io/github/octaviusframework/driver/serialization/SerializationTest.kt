package io.github.octaviusframework.driver.serialization

import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.container.ArrayDimension
import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.codec.encodeSafely
import io.github.octaviusframework.driver.container.PgContainer
import io.github.octaviusframework.driver.registry.CatalogHolder
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class SerializationTest : AbstractIntegrationTest() {

    override val schema = "CREATE TYPE legion_standard AS (id int, name text)"

    /**
     * Encodes a container the way the driver does: through the codec the catalog binds to its OID, which is
     * what carries the dictionaries the nested fields are resolved through.
     */
    private fun CatalogHolder.encode(container: PgContainer, writer: PgByteWriter) {
        val codec = catalog.codecs.getCodecByOid<PgContainer>(container.containerOid)
        assertNotNull(codec, "no codec for OID ${container.containerOid}")
        codec.encodeSafely(container, writer)
    }

    @Test
    fun testFactoryAndSerializationRoundtrip() {
        val session = openSession()

        val catalogHolder = GlobalCatalogStore.holderFor(DatabaseKey.from(OctaviusProperties.parse(TestDatabase.URL)))

        // 1. Build the composite from scratch through the factory
        val composite = session.typeManager.containers.createComposite("legion_standard")
        composite["id"] = 777
        composite["name"] = "Legio X Equestris"

        val writer1 = PgByteWriter()
        catalogHolder.encode(composite, writer1)
        val builtCompositeBytes = writer1.toByteArray()

        // Compare against the database
        val expectedCompositeRow =
            session.createNativeQuery("SELECT ROW(777, 'Legio X Equestris')::legion_standard as standard").fetchRowStrict()
        val expectedComposite = expectedCompositeRow.get<PgComposite>(0)
        val writerComp = PgByteWriter()
        catalogHolder.encode(expectedComposite, writerComp)

        assertContentEquals(
            writerComp.toByteArray(),
            builtCompositeBytes,
            "The composite built here must match PostgreSQL's own"
        )

        // 2. Build the array by hand from scratch
        val array = PgArray(
            arrayOid = 1007,
            elementOid = 23,
            dimensions = listOf(ArrayDimension(3, 1)),
            elements = mutableListOf(10, 20, 30)
        )

        val writer2 = PgByteWriter()
        catalogHolder.encode(array, writer2)
        val builtArrayBytes = writer2.toByteArray()

        val expectedArrayRow = session.createNativeQuery("SELECT ARRAY[10, 20, 30]::int[]").fetchRowStrict()
        val expectedArray = expectedArrayRow.get<PgArray>(0)
        val writerArr = PgByteWriter()
        catalogHolder.encode(expectedArray, writerArr)

        assertContentEquals(
            writerArr.toByteArray(),
            builtArrayBytes,
            "The array built here must match PostgreSQL's own"
        )
        session.close()
    }

    @Test
    fun testQueryWithParameters() {
        val session = openSession()

        val array = listOf(10, 20, 30)

        val rows = session.createNativeQuery("SELECT $1::int[] as cohorts").fetchRows(array)

        val returnedArray = rows.first().get<PgArray>("cohorts")
        assertNotNull(returnedArray)
        assertEquals(10, returnedArray.get<Int>(0))
        assertEquals(20, returnedArray.get<Int>(1))
        assertEquals(30, returnedArray.get<Int>(2))
        session.close()
    }

    @Test
    fun testMultidimensionalArray() {
        val session = openSession()

        val catalogHolder = GlobalCatalogStore.holderFor(DatabaseKey.from(OctaviusProperties.parse(TestDatabase.URL)))

        // A 2x3 array (2 rows, 3 columns)
        val multiArray = PgArray(
            arrayOid = 1007,
            elementOid = 23,
            dimensions = listOf(
                ArrayDimension(2, 1),
                ArrayDimension(3, 1)
            ),
            elements = mutableListOf(1, 2, 3, 4, 5, 6)
        )

        val writer = PgByteWriter()
        catalogHolder.encode(multiArray, writer)
        val serializedArray = writer.toByteArray()

        val rows = session.createNativeQuery(
            "SELECT ARRAY[[1, 2, 3], [4, 5, 6]]::int[] as cohorts"
        ).fetchRows()

        val expectedArray = rows.first().get<PgArray>(0)
        val writerArr = PgByteWriter()
        catalogHolder.encode(expectedArray, writerArr)

        assertContentEquals(
            writerArr.toByteArray(),
            serializedArray,
            "The multidimensional array built here must match PostgreSQL's own"
        )
        session.close()
    }

    @Test
    fun testParameterSerializerDatabaseRoundTrip() {
        val session = openSession()

        // 1. Integer Round Trip
        val intVal = 424242
        val rowsInt = session.createNativeQuery("SELECT $1 as res").fetchRows(intVal)
        assertEquals(intVal, rowsInt.first().get<Int>("res"))

        // 2. String Round Trip
        val strVal = "Gallia est omnis dīvīsa in partēs trēs"
        val rowsStr = session.createNativeQuery("SELECT $1 as res").fetchRows(strVal)
        assertEquals(strVal, rowsStr.first().get<String>("res"))

        // 3. Boolean Round Trip
        val boolVal = true
        val rowsBool = session.createNativeQuery("SELECT $1 as res").fetchRows(boolVal)
        assertEquals(boolVal, rowsBool.first().get<Boolean>("res"))

        // 4. Double Round Trip
        val doubleVal = 3.14159
        val rowsDouble = session.createNativeQuery("SELECT $1 as res").fetchRows(doubleVal)
        assertEquals(doubleVal, rowsDouble.first().get<Double>("res"))

        val arrayVal = listOf(10, 20, 30)

        val rowsArray = session.createNativeQuery("SELECT $1 as res").fetchRows(arrayVal)
        val returnedArray = rowsArray.first().get<PgArray>("res")
        assertNotNull(returnedArray)
        assertEquals(10, returnedArray.get<Int>(0))
        assertEquals(20, returnedArray.get<Int>(1))
        assertEquals(30, returnedArray.get<Int>(2))
        session.close()
    }
    @Test
    fun testRecordMapSerialization() {
        val session = openSession()

        // 6. Record Map Serialization
        val recordMap = mapOf(
            "cognomen" to "Caesar",
            "legiones" to 10
        )

        val exception = assertThrows<TypeException> {
            session.createNativeQuery("SELECT $1 as res").fetchRows(recordMap)
        }
        
        assertEquals(TypeExceptionReason.MISSING_CODEC, exception.reason)
        session.close()
    }


    @Test
    fun testUnknownTypeSerialization() {
        val session = openSession()

        val stringVal = "alea iacta est"
        val res = session.createNativeQuery("SELECT '$stringVal' as res").fetchField<String>()

        assertEquals(stringVal, res)
        session.close()
    }
}

