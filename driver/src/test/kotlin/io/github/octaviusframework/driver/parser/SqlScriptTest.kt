package io.github.octaviusframework.driver.parser

import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.StatementExceptionReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SqlScriptTest {

    /** Every statement has to be findable in the script at the offset it reports. */
    private fun assertOffsetsPointBack(script: String, statements: List<SqlStatement>) {
        for (statement in statements) {
            assertEquals(
                statement.sql,
                script.substring(statement.offset, statement.offset + statement.sql.length),
                "offset ${statement.offset} does not point at the statement it belongs to"
            )
        }
    }

    private fun sqlOf(script: String): List<String> = SqlScript.split(script).map { it.sql }

    // ------------------------------------------------------------------ basics

    @Test
    fun `splits on top-level semicolons`() {
        val script = "CREATE TABLE a (id int); CREATE TABLE b (id int)"
        assertEquals(listOf("CREATE TABLE a (id int)", "CREATE TABLE b (id int)"), sqlOf(script))
        assertOffsetsPointBack(script, SqlScript.split(script))
    }

    @Test
    fun `a trailing semicolon does not add an empty statement`() {
        assertEquals(listOf("SELECT 1"), sqlOf("SELECT 1;"))
    }

    @Test
    fun `runs of semicolons and blank lines produce nothing extra`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), sqlOf("SELECT 1;;\n\n  ;SELECT 2;;"))
    }

    @Test
    fun `an empty script has no statements`() {
        assertEquals(emptyList<String>(), sqlOf(""))
        assertEquals(emptyList<String>(), sqlOf("   \n\t "))
    }

    @Test
    fun `a script of comments alone has no statements`() {
        assertEquals(emptyList<String>(), sqlOf("-- nothing here;\n/* nor here; */\n"))
    }

    @Test
    fun `a leading comment stays with the statement it introduces`() {
        val statements = SqlScript.split("-- octavius no-transaction\nCREATE INDEX CONCURRENTLY i ON t (c)")
        assertEquals(1, statements.size)
        assertTrue(statements[0].sql.startsWith("-- octavius no-transaction"))
    }

    // ------------------------------------------------------ semicolons that are not separators

    @Test
    fun `a semicolon inside a string literal is not a separator`() {
        assertEquals(listOf("INSERT INTO t VALUES ('a;b')"), sqlOf("INSERT INTO t VALUES ('a;b')"))
    }

    @Test
    fun `a semicolon inside an escaped string literal is not a separator`() {
        assertEquals(listOf("""SELECT E'a\';b'"""), sqlOf("""SELECT E'a\';b'"""))
    }

    @Test
    fun `a semicolon inside a quoted identifier is not a separator`() {
        assertEquals(listOf("""SELECT "we;ird" FROM t"""), sqlOf("""SELECT "we;ird" FROM t"""))
    }

    @Test
    fun `a semicolon inside a line comment is not a separator`() {
        assertEquals(listOf("SELECT 1 -- and; not; these"), sqlOf("SELECT 1 -- and; not; these"))
    }

    @Test
    fun `a semicolon inside a nested block comment is not a separator`() {
        assertEquals(listOf("SELECT /* a; /* b; */ c; */ 1"), sqlOf("SELECT /* a; /* b; */ c; */ 1"))
    }

    @Test
    fun `semicolons inside a dollar-quoted body are not separators`() {
        val script = $$$"""
            CREATE FUNCTION f() RETURNS int AS $$
            DECLARE x int;
            BEGIN
                x := 1;
                RETURN x;
            END;
            $$ LANGUAGE plpgsql;
            SELECT f()
        """.trimIndent()

        val statements = SqlScript.split(script)
        assertEquals(2, statements.size)
        assertTrue(statements[0].sql.startsWith("CREATE FUNCTION"))
        assertTrue(statements[0].sql.endsWith("LANGUAGE plpgsql"))
        assertEquals("SELECT f()", statements[1].sql)
        assertOffsetsPointBack(script, statements)
    }

    @Test
    fun `semicolons inside a tagged dollar quote are not separators`() {
        val script = $$$"""DO $body$ BEGIN PERFORM 1; PERFORM 2; END $body$; SELECT 1"""
        assertEquals(
            listOf($$$"""DO $body$ BEGIN PERFORM 1; PERFORM 2; END $body$""", "SELECT 1"),
            sqlOf(script)
        )
    }

    @Test
    fun `a positional parameter is not the start of a dollar quote`() {
        assertEquals(listOf("SELECT $1", "SELECT $2"), sqlOf("SELECT $1; SELECT $2"))
    }

    @Test
    fun `a semicolon inside parentheses is not a separator`() {
        val script = "CREATE RULE r AS ON INSERT TO t DO INSTEAD (INSERT INTO a VALUES (1); INSERT INTO b VALUES (2)); SELECT 1"
        val statements = sqlOf(script)
        assertEquals(2, statements.size)
        assertTrue(statements[0].startsWith("CREATE RULE"))
        assertTrue(statements[0].endsWith("VALUES (2))"))
        assertEquals("SELECT 1", statements[1])
    }

    @Test
    fun `an unbalanced closing paren does not swallow the separators after it`() {
        assertEquals(listOf("SELECT 1)", "SELECT 2"), sqlOf("SELECT 1); SELECT 2"))
    }

    // ------------------------------------------------------ standard-SQL function bodies

    @Test
    fun `semicolons inside a BEGIN ATOMIC body are not separators`() {
        val script = """
            CREATE FUNCTION enroll(recruit text) RETURNS void LANGUAGE sql
            BEGIN ATOMIC
                INSERT INTO legionnaires VALUES (recruit);
                DELETE FROM recruits WHERE name = recruit;
            END;
            SELECT 1
        """.trimIndent()

        val statements = SqlScript.split(script)
        assertEquals(2, statements.size)
        assertTrue(statements[0].sql.startsWith("CREATE FUNCTION"))
        assertTrue(statements[0].sql.endsWith("END"))
        assertEquals("SELECT 1", statements[1].sql)
        assertOffsetsPointBack(script, statements)
    }

    @Test
    fun `the END of a CASE does not close a BEGIN ATOMIC body`() {
        val script = """
            CREATE FUNCTION rank_of(years int) RETURNS text LANGUAGE sql
            BEGIN ATOMIC
                SELECT CASE WHEN years > 20 THEN 'veteranus' ELSE 'tiro' END;
            END;
            SELECT 1
        """.trimIndent()

        val statements = sqlOf(script)
        assertEquals(2, statements.size)
        assertTrue(statements[0].endsWith("END;\nEND"))
        assertEquals("SELECT 1", statements[1])
    }

    @Test
    fun `a procedure under CREATE OR REPLACE takes a BEGIN ATOMIC body too`() {
        val script = """
            create or replace procedure muster() language sql
            begin atomic
                update legions set mustered = true;
                insert into musters default values;
            end;
            CALL muster()
        """.trimIndent()

        val statements = sqlOf(script)
        assertEquals(2, statements.size)
        assertTrue(statements[0].startsWith("create or replace procedure"))
        assertEquals("CALL muster()", statements[1])
    }

    @Test
    fun `BEGIN and END that are not keywords do not count inside the body`() {
        // In a literal, a comment, a quoted identifier, a longer word, after a dot and after AS, each of
        // them is a name or text, and counting any one would close the body early or keep it open forever.
        val script = """
            CREATE FUNCTION calendar() RETURNS text LANGUAGE sql
            BEGIN ATOMIC
                -- the END of nothing;
                SELECT 'END;' || "end" || legend || end_date::text || f.end || f.case AS end
                FROM fasti f /* END; */ WHERE f.begin IS NULL;
            END;
            SELECT 2
        """.trimIndent()

        val statements = sqlOf(script)
        assertEquals(2, statements.size)
        assertTrue(statements[0].endsWith("IS NULL;\nEND"))
        assertEquals("SELECT 2", statements[1])
    }

    @Test
    fun `a function returning a single expression ends at its semicolon`() {
        // The other standard-SQL form, RETURN, has no block to hold the separator - not even with a CASE in it.
        assertEquals(
            listOf("CREATE FUNCTION f(a int) RETURNS int LANGUAGE sql RETURN CASE WHEN a > 0 THEN a ELSE 0 END", "SELECT f(1)"),
            sqlOf("CREATE FUNCTION f(a int) RETURNS int LANGUAGE sql RETURN CASE WHEN a > 0 THEN a ELSE 0 END; SELECT f(1)")
        )
    }

    @Test
    fun `BEGIN, CASE and END outside a routine definition separate as before`() {
        // Only CREATE FUNCTION and CREATE PROCEDURE open a block that holds semicolons. Anywhere else BEGIN
        // is a transaction and CASE an expression.
        assertEquals(
            listOf("BEGIN", "SELECT CASE WHEN true THEN 1 END", "END"),
            sqlOf("BEGIN; SELECT CASE WHEN true THEN 1 END; END;")
        )
    }

    // ------------------------------------------------------------------ offsets

    @Test
    fun `offsets survive Windows line endings`() {
        val script = "CREATE TABLE a (id int);\r\nCREATE TABLE b (id int);\r\n"
        val statements = SqlScript.split(script)
        assertEquals(listOf("CREATE TABLE a (id int)", "CREATE TABLE b (id int)"), statements.map { it.sql })
        assertOffsetsPointBack(script, statements)
    }

    @Test
    fun `offsets skip the whitespace in front of a statement`() {
        val script = "SELECT 1;\n\n    SELECT 2"
        val statements = SqlScript.split(script)
        assertEquals(0, statements[0].offset)
        assertEquals(script.indexOf("SELECT 2"), statements[1].offset)
    }

    // ------------------------------------------------------------------ the first word

    @Test
    fun `each statement carries its first word, upper-cased`() {
        assertEquals(listOf("SELECT", "CREATE"), SqlScript.split("select 1; Create TABLE t (id int)").map { it.keyword })
    }

    @Test
    fun `the first word is found past the comments in front of it`() {
        // The reason this is answered here at all: whoever needs it would otherwise walk the comments again,
        // and a second walk is a second thing to keep right.
        assertEquals("COMMIT", SqlScript.split("-- why we do this\nCOMMIT").single().keyword)
        assertEquals("COMMIT", SqlScript.split("/* a reason */ COMMIT").single().keyword)
        assertEquals("COMMIT", SqlScript.split("/* outer /* inner */ still outer */ COMMIT").single().keyword)
        assertEquals("COMMIT", SqlScript.split("\n\n  -- one\n  -- two\n  COMMIT").single().keyword)
    }

    @Test
    fun `the first word stops at the first character that is not a letter`() {
        // Guards against a caller matching on a prefix: ENDORSE is not END.
        assertEquals("ENDORSE", SqlScript.split("ENDORSE the treaty").single().keyword)
        assertEquals("BEGINNING", SqlScript.split("BEGINNING_OF_TIME()").single().keyword)
    }

    @Test
    fun `a statement that does not open with a word has no keyword`() {
        assertNull(SqlScript.split("'just a string'").single().keyword)
        assertNull(SqlScript.split("(SELECT 1)").single().keyword)
        assertNull(SqlScript.split($$$"""$body$ x $body$""").single().keyword)
    }

    @Test
    fun `the keyword belongs to its own statement`() {
        val statements = SqlScript.split("CREATE TABLE t (id int); -- and now\nINSERT INTO t VALUES (1)")
        assertEquals(listOf("CREATE", "INSERT"), statements.map { it.keyword })
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `an unclosed dollar quote is refused`() {
        val e = assertThrows<StatementException> { SqlScript.split($$$"""SELECT $$ never closed; SELECT 1""") }
        assertEquals(StatementExceptionReason.UNCLOSED_TOKEN, e.reason)
    }

    @Test
    fun `an unclosed string literal is refused`() {
        val e = assertThrows<StatementException> { SqlScript.split("SELECT 'never closed; SELECT 1") }
        assertEquals(StatementExceptionReason.UNCLOSED_TOKEN, e.reason)
    }

    @Test
    fun `an unclosed block comment is refused`() {
        val e = assertThrows<StatementException> { SqlScript.split("SELECT 1 /* never closed; SELECT 2") }
        assertEquals(StatementExceptionReason.UNCLOSED_TOKEN, e.reason)
    }

    @Test
    fun `a line comment running to the end of the script is not a refusal`() {
        assertEquals(listOf("SELECT 1", "SELECT 2 -- to the end"), sqlOf("SELECT 1; SELECT 2 -- to the end"))
    }
}
