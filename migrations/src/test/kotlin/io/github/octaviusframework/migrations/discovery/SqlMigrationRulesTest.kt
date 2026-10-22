package io.github.octaviusframework.migrations.discovery

import io.github.octaviusframework.migrations.MigrationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SqlMigrationRulesTest {

    private fun transactionality(content: String) = SqlMigrationRules.readTransactionality(content, "V1__x.sql")
    private fun control(content: String) = SqlMigrationRules.refuseTransactionControl(content, "V1__x.sql")

    // ------------------------------------------------------------- directives

    @Test
    fun `a script with no directive wants a transaction`() {
        assertTrue(transactionality("CREATE TABLE castra (id int);"))
        assertTrue(transactionality("-- just an ordinary comment\nCREATE TABLE castra (id int);"))
    }

    @Test
    fun `the no-transaction directive is read from the header`() {
        assertFalse(transactionality("-- octavius:no-transaction\nCREATE INDEX CONCURRENTLY i ON t (c);"))
    }

    @Test
    fun `spacing and case around the directive do not matter`() {
        assertFalse(transactionality("--octavius:no-transaction\nSELECT 1;"))
        assertFalse(transactionality("--   Octavius:  No-Transaction  \nSELECT 1;"))
        assertFalse(transactionality("\n\n-- a note first\n-- octavius:no-transaction\nSELECT 1;"))
    }

    @Test
    fun `a directive nobody knows is refused rather than ignored`() {
        // The failure this exists for: 'no-transactions' passing silently, the migration running inside a
        // transaction after all, and CREATE INDEX CONCURRENTLY being refused by the server instead.
        val e = assertThrows<MigrationException> { transactionality("-- octavius:no-transactions\nSELECT 1;") }
        assertTrue(e.details!!.contains("no-transaction"), "the refusal should name the one that exists: ${e.details}")
    }

    @Test
    fun `a directive below the header is refused`() {
        val e = assertThrows<MigrationException> {
            transactionality("CREATE TABLE castra (id int);\n-- octavius:no-transaction\nSELECT 1;")
        }
        assertTrue(e.details!!.contains("header"), "the refusal should say where it belongs: ${e.details}")
    }

    // ------------------------------------------------------- transaction control

    @Test
    fun `an ordinary script passes`() {
        control("CREATE TABLE castra (id int);\nINSERT INTO castra VALUES (1);")
    }

    @Test
    fun `a script that opens or closes a transaction is refused`() {
        for (statement in listOf("BEGIN", "START TRANSACTION", "COMMIT", "END", "ROLLBACK", "ABORT")) {
            assertThrows<MigrationException>("$statement should be refused") {
                control("CREATE TABLE castra (id int);\n$statement;\nSELECT 1;")
            }
        }
    }

    @Test
    fun `the END of a plpgsql body is not transaction control`() {
        // It lives inside a dollar-quoted body, so the splitter never surfaces it as a statement.
        control(
            $$$"""
            CREATE FUNCTION f() RETURNS int AS $$
            BEGIN
                RETURN 1;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
    }

    @Test
    fun `the END of a BEGIN ATOMIC body is not transaction control`() {
        // No dollar quotes here: the body is SQL the server parses, and its END stands at the top level.
        control(
            """
            CREATE FUNCTION enroll(recruit text) RETURNS void LANGUAGE sql
            BEGIN ATOMIC
                INSERT INTO legionnaires VALUES (recruit);
            END;
            """.trimIndent()
        )
    }

    @Test
    fun `a COMMIT hidden behind comments is still found`() {
        assertThrows<MigrationException> { control("-- why we do this\nCOMMIT;") }
        assertThrows<MigrationException> { control("/* a reason */ COMMIT;") }
        assertThrows<MigrationException> { control("/* outer /* inner */ still outer */ COMMIT;") }
    }

    @Test
    fun `a longer word beginning with a control keyword is not one`() {
        // Guards against matching on a prefix: the first word is taken whole and compared whole.
        control("ENDORSE the treaty;")
        control("BEGINNING_OF_TIME();")
        control("SELECT commits FROM ledger;")
    }
}
