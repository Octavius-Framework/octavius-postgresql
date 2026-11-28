package io.github.octaviusframework.driver.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SqlParameterParserTest {

    @Test
    fun `named parameters become positional ones in the order they first appear`() {
        val parsed = SqlParameterParser.parse("SELECT * FROM users WHERE id = @id AND name = @name")
        assertEquals("SELECT * FROM users WHERE id = $1 AND name = $2", parsed.transformedSql)
        assertEquals(listOf("id", "name"), parsed.paramNames)
    }

    @Test
    fun `an at sign inside a string literal or a comment is not a parameter`() {
        val sql = """
            SELECT * -- this is a @comment
            FROM users /* block @comment */
            WHERE string_val = '@notParam' AND real_val = @realParam
        """.trimIndent()

        val parsed = SqlParameterParser.parse(sql)
        val expected = """
            SELECT * -- this is a @comment
            FROM users /* block @comment */
            WHERE string_val = '@notParam' AND real_val = $1
        """.trimIndent()

        assertEquals(expected, parsed.transformedSql)
        assertEquals(listOf("realParam"), parsed.paramNames)
    }

    @Test
    fun `an at sign inside a dollar-quoted string is not a parameter`() {
        val sql = $$$"SELECT $$@notParam$$, $tag$@notParam2$tag$ WHERE id = @id"
        val parsed = SqlParameterParser.parse(sql)
        val expected = $$$"SELECT $$@notParam$$, $tag$@notParam2$tag$ WHERE id = $1"

        assertEquals(expected, parsed.transformedSql)
        assertEquals(listOf("id"), parsed.paramNames)
    }

    @Test
    fun `a parameter used twice takes one position`() {
        val parsed = SqlParameterParser.parse("SELECT @val, @val::uuid, @other")
        assertEquals("SELECT $1, $1::uuid, $2", parsed.transformedSql)
        assertEquals(listOf("val", "other"), parsed.paramNames)
    }

    @Test
    fun `a cast after a parameter is left in place`() {
        val parsed = SqlParameterParser.parse("SELECT @val::uuid, @other")
        assertEquals("SELECT $1::uuid, $2", parsed.transformedSql)
        assertEquals(listOf("val", "other"), parsed.paramNames)
    }

    @Test
    fun `an at sign inside an escaped string literal is not a parameter`() {
        val parsed = SqlParameterParser.parse("SELECT E'a''b \\' @notParam', @param")
        assertEquals("SELECT E'a''b \\' @notParam', $1", parsed.transformedSql)
        assertEquals(listOf("param"), parsed.paramNames)
    }

    @Test
    fun `a dollar-quote tag may hold any character outside ASCII`() {
        // A symbol, an emoji, a letter outside the BMP and a digit that is not 0-9: PostgreSQL takes them all.
        val sql = $$$"""SELECT $€$@a$€$, $😀$@b$😀$, $𝐀$@c$𝐀$, $٣$@d$٣$, @param"""
        val parsed = SqlParameterParser.parse(sql)
        assertEquals($$$"""SELECT $€$@a$€$, $😀$@b$😀$, $𝐀$@c$𝐀$, $٣$@d$٣$, $1""", parsed.transformedSql)
        assertEquals(listOf("param"), parsed.paramNames)
    }

    @Test
    fun `a dollar sign inside a name does not open a dollar quote`() {
        val parsed = SqlParameterParser.parse($$$"""SELECT 1 AS a$b$, @param""")
        assertEquals($$$"""SELECT 1 AS a$b$, $1""", parsed.transformedSql)
        assertEquals(listOf("param"), parsed.paramNames)
    }

    @Test
    fun `an E at the end of a name does not make the literal after it an escaped one`() {
        // `name'C:\'` is a literal of the name type closed by the quote after the backslash. An E standing on
        // its own after a parenthesis still opens an escaped literal.
        val sql = """SELECT name'C:\' AS path, (E'\' @notParam') WHERE path LIKE'C:\' AND id = @id"""
        val parsed = SqlParameterParser.parse(sql)
        assertEquals("""SELECT name'C:\' AS path, (E'\' @notParam') WHERE path LIKE'C:\' AND id = $1""", parsed.transformedSql)
        assertEquals(listOf("id"), parsed.paramNames)
    }

    @Test
    fun `an escaped literal continued on the next line is escaped in every part`() {
        val sql = """
            SELECT E'Ave'
            '\' @notParam' -- a comment may stand between the parts
            '\' @notParam2', @param
        """.trimIndent()
        val parsed = SqlParameterParser.parse(sql)
        assertEquals(sql.replace("@param", "$1"), parsed.transformedSql)
        assertEquals(listOf("param"), parsed.paramNames)
    }
}
