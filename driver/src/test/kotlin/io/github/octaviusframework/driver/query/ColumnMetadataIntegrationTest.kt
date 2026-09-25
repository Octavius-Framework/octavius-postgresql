package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins down what a result says about its own columns: the type it was read as, and - for a column the server
 * tracked back to a relation - the relation and column it came from, named out of the type catalog.
 */
class ColumnMetadataIntegrationTest : AbstractIntegrationTest() {

    override val schema = """
        CREATE SCHEMA column_metadata_test;
        CREATE DOMAIN column_metadata_test.tribute_amount AS numeric(10,2);
        CREATE TABLE column_metadata_test.senators (
            id int, cognomen text, province text, tribute numeric(10,2), levy column_metadata_test.tribute_amount
        );
        -- Takes attribute number 3 out of the sequence for good, so everything declared after it sits one place
        -- behind its own number.
        ALTER TABLE column_metadata_test.senators DROP COLUMN province;
        CREATE VIEW column_metadata_test.consuls AS SELECT id, cognomen FROM column_metadata_test.senators;
        INSERT INTO column_metadata_test.senators VALUES (1, 'Cato', 12.50, 3.00);
    """.trimIndent()

    // ---------------------------------- Where a column came from ----------------------------------

    @Test
    fun `an aliased column reports the alias as its name and the column it was read from as its origin`() {
        openSession().use { s ->
            val row = s.createNativeQuery("SELECT cognomen AS name FROM column_metadata_test.senators").fetchRowStrict()
            val column = row.metadata.getColumn(0)

            assertEquals("name", column.name)
            val origin = column.origin!!
            assertEquals("cognomen", origin.columnName)
            assertEquals("senators", origin.relationName)
            assertEquals("column_metadata_test", origin.schema)
            assertNotEquals(0, origin.relationOid)
        }
    }

    @Test
    fun `a column declared after a dropped one is named by its attribute number and not by its position`() {
        openSession().use { s ->
            val row = s.createNativeQuery("SELECT tribute FROM column_metadata_test.senators").fetchRowStrict()
            val origin = row.metadata.getColumn(0).origin!!

            // Third of the three surviving attributes, fourth by number.
            assertEquals(4, origin.attributeNumber)
            assertEquals("tribute", origin.columnName)
        }
    }

    @Test
    fun `a view is a relation like any other`() {
        openSession().use { s ->
            val row = s.createNativeQuery("SELECT cognomen FROM column_metadata_test.consuls").fetchRowStrict()
            val origin = row.metadata.getColumn(0).origin!!

            assertEquals("consuls", origin.relationName)
            assertEquals("column_metadata_test", origin.schema)
            assertEquals("cognomen", origin.columnName)
        }
    }

    @Test
    fun `anything that is not a column reference has no origin at all`() {
        openSession().use { s ->
            val row = s.createNativeQuery("SELECT 1 + 1, upper(cognomen), now() FROM column_metadata_test.senators").fetchRowStrict()

            assertNull(row.metadata.getColumn(0).origin)
            assertNull(row.metadata.getColumn(1).origin)
            assertNull(row.metadata.getColumn(2).origin)
        }
    }

    @Test
    fun `a column of a relation the catalog does not describe keeps the OIDs and loses the names`() {
        openSession().use { s ->
            // Composites in pg_catalog are not loaded, so nothing here can put a name to the relation - while
            // the server still says the value came from one.
            val row = s.createNativeQuery("SELECT relname FROM pg_class LIMIT 1").fetchRowStrict()
            val origin = row.metadata.getColumn(0).origin!!

            assertNotEquals(0, origin.relationOid)
            assertTrue(origin.attributeNumber > 0)
            assertNull(origin.relationName)
            assertNull(origin.schema)
            assertNull(origin.columnName)
        }
    }

    // ---------------------------------- What the column is ----------------------------------

    @Test
    fun `the type is resolved out of the catalog, structure and all`() {
        openSession().use { s ->
            val row = s.createNativeQuery("SELECT tribute, ARRAY['a', 'b'] FROM column_metadata_test.senators").fetchRowStrict()

            val tribute = row.metadata.getColumn(0).type
            assertTrue(tribute is PgType.Base)
            assertEquals("numeric", tribute.name)
            assertEquals("pg_catalog", tribute.schema)

            val names = row.metadata.getColumn(1).type
            assertTrue(names is PgType.Array)
            assertEquals("_text", names.name)
            assertEquals(25, names.elementOid)
        }
    }

    @Test
    fun `a domain column is described by the type underneath it`() {
        openSession().use { s ->
            val row = s.createNativeQuery("SELECT levy, 1::column_metadata_test.tribute_amount FROM column_metadata_test.senators").fetchRowStrict()

            // The server resolves a domain to its base type before describing a column - for a plain reference
            // and for an explicit cast alike - so a result column never arrives as one, however it was written.
            val levy = row.metadata.getColumn(0).type
            assertTrue(levy is PgType.Base)
            assertEquals("numeric", levy.name)
            assertEquals(levy.oid, row.metadata.getColumn(1).type.oid)

            // What the column was read from is unaffected: that is the catalog's answer, not the server's.
            assertEquals("levy", row.metadata.getColumn(0).origin!!.columnName)
        }
    }

    @Test
    fun `the type modifier carries the precision the type alone does not`() {
        openSession().use { s ->
            val row = s.createNativeQuery("SELECT tribute, cognomen FROM column_metadata_test.senators").fetchRowStrict()

            assertEquals(((10 shl 16) or 2) + 4, row.metadata.getColumn(0).typeModifier)
            assertEquals(-1, row.metadata.getColumn(1).typeModifier)
        }
    }

    @Test
    fun `the column reports the OID of its own type`() {
        openSession().use { s ->
            val row = s.createNativeQuery("SELECT id FROM column_metadata_test.senators").fetchRowStrict()
            val column = row.metadata.getColumn(0)

            assertEquals(23, column.oid)
            assertEquals(column.type.oid, column.oid)
            assertEquals(column.oid, row.getOid(0))
        }
    }

    // ------------------- A type the catalog does not describe, on both result paths -------------------

    @Test
    fun `a column of an undescribed type fails on the description and leaves the connection usable`() {
        openSession().use { s ->
            // An information_schema view's own row type is a composite the type load skips, and every column of
            // this one is a domain over a type the server can send in binary. A pg_class row cannot be relied on
            // for that: relacl's element type has no binary send function, so a row carrying an ACL fails on the
            // server while it is being produced, and that error arrives in place of the driver's own.
            assertFailsWith<TypeException> {
                s.createNativeQuery("SELECT t FROM information_schema.tables t LIMIT 1").fetchRows()
            }

            assertEquals(42, s.createNativeQuery("SELECT 42").fetchFieldStrict<Int>())
        }
    }

    @Test
    fun `the same holds while streaming`() {
        openSession().use { s ->
            assertFailsWith<TypeException> {
                s.createNativeQuery("SELECT t FROM information_schema.tables t").forEachRow(fetchSize = 2) { }
            }

            assertEquals(42, s.createNativeQuery("SELECT 42").fetchFieldStrict<Int>())
        }
    }

    @Test
    fun `it is the description that fails, not a value on its way through a codec`() {
        openSession().use { s ->
            // The column's type is the undescribed composite and its value is null in the one row that comes
            // back, so nothing is ever handed to a codec: only a check made when the result is described can
            // fail here. The same shape over a type the catalog does describe returns its null row.
            assertFailsWith<TypeException> {
                s.createNativeQuery("SELECT (SELECT t FROM information_schema.tables t WHERE false) AS absent").fetchRows()
            }

            assertNull(s.createNativeQuery("SELECT (SELECT 1 WHERE false) AS absent").fetchRowStrict().getRaw(0))
        }
    }
}
