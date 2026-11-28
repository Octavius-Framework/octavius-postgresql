package io.github.octaviusframework.driver.parser

import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.StatementExceptionReason

/**
 * Finds where names, quotes, dollar quotes and comments end, for [SqlParameterParser] and [SqlScript], to whom
 * nothing inside them counts.
 */
internal object SqlScanner {

    /**
     * The index of the last character of the construct starting at [i], or [i] itself if none starts there.
     *
     * [i] has to start a token: asked inside a name, the `e` ending `name'x'` would open an escaped literal. Callers
     * ask here first and then step over a name whole with [nameEnd].
     *
     * @throws io.github.octaviusframework.driver.exception.StatementException `UNCLOSED_TOKEN` where a quote,
     * dollar quote or block comment never closes.
     */
    fun findConstructEnd(sql: String, i: Int): Int {
        return when (sql[i]) {
            '\'' -> skipUntil(sql, i, '\'', throwOnEof = true, exceptionMessage = StatementExceptionReason.UNCLOSED_TOKEN)
            'E', 'e' -> if (i + 1 < sql.length && sql[i + 1] == '\'') skipEscapedLiteral(sql, i + 1) else i
            '"' -> skipUntil(sql, i, '"', throwOnEof = true, exceptionMessage = StatementExceptionReason.UNCLOSED_TOKEN)
            '-' -> if (i + 1 < sql.length && sql[i + 1] == '-') skipUntil(sql, i, '\n', throwOnEof = false) else i
            '/' -> if (i + 1 < sql.length && sql[i + 1] == '*') skipComment(sql, i) else i
            '$' -> {
                val end = findDollarQuoteEnd(sql, i)
                if (end != -1) end else i
            }
            else -> i
        }
    }

    /**
     * The index just past the name starting at [i], or [i] where none starts there. A name runs from an ASCII letter,
     * `_` or any non-ASCII character through the digits and `$` after it, so `a$b$` is one.
     */
    fun nameEnd(sql: String, i: Int): Int {
        if (!isIdentifierStart(sql[i])) return i
        var end = i + 1
        while (end < sql.length && isIdentifierPart(sql[end])) end++
        return end
    }

    /** Whether a comment starts at [i]. */
    fun isCommentStart(sql: String, i: Int): Boolean {
        if (i + 1 >= sql.length) return false
        return (sql[i] == '-' && sql[i + 1] == '-') || (sql[i] == '/' && sql[i + 1] == '*')
    }

    /** The end of an `E'...'` literal whose quote is at [quote], with every part continued after it. */
    private fun skipEscapedLiteral(sql: String, quote: Int): Int {
        var end = skipBackslashEscapedLiteral(sql, quote)
        while (true) {
            val next = findContinuation(sql, end + 1)
            if (next == -1) return end
            end = skipBackslashEscapedLiteral(sql, next)
        }
    }

    /**
     * The quote that continues a literal closed just before [from], or -1. As in PostgreSQL's lexer, only whitespace
     * holding a newline, and `--` comments, may stand between the two parts.
     */
    private fun findContinuation(sql: String, from: Int): Int {
        var j = from
        var sawNewline = false
        while (j < sql.length) {
            val c = sql[j]
            when {
                c == '\n' || c == '\r' -> sawNewline = true
                c == ' ' || c == '\t' || c == '\u000C' || c == '\u000B' -> {}
                c == '-' && j + 1 < sql.length && sql[j + 1] == '-' -> {
                    while (j + 1 < sql.length && sql[j + 1] != '\n' && sql[j + 1] != '\r') j++
                }
                else -> break
            }
            j++
        }
        return if (sawNewline && j < sql.length && sql[j] == '\'') j else -1
    }

    private fun findDollarQuoteEnd(sql: String, start: Int): Int {
        if (start + 1 >= sql.length) return -1

        var tagEnd = start
        while (tagEnd + 1 < sql.length && sql[tagEnd + 1] != '$') {
            val char = sql[tagEnd + 1]
            if (!isValidTagCharacter(char, isFirstChar = tagEnd == start)) {
                return -1
            }
            tagEnd++
        }

        if (tagEnd + 1 >= sql.length || sql[tagEnd + 1] != '$') {
            return -1
        }

        val tagLength = (tagEnd + 1) - start + 1

        var searchPos = tagEnd + 2
        while (searchPos + tagLength <= sql.length) {
            if (sql.regionMatches(searchPos, sql, start, tagLength)) {
                return searchPos + tagLength - 1
            }
            searchPos++
        }

        throw StatementException(
            StatementExceptionReason.UNCLOSED_TOKEN,
            "Unclosed dollar-quoted string",
            position = start + 1
        )
    }

    private fun isValidTagCharacter(char: Char, isFirstChar: Boolean): Boolean {
        return isIdentifierStart(char) || (!isFirstChar && char in '0'..'9')
    }

    /** What PostgreSQL's lexer starts a name or a dollar-quote tag with: an ASCII letter, `_`, any non-ASCII character. */
    private fun isIdentifierStart(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c.code >= 0x80

    /** What continues a name: a digit and `$` as well. */
    private fun isIdentifierPart(c: Char): Boolean = isIdentifierStart(c) || c in '0'..'9' || c == '$'

    private fun skipBackslashEscapedLiteral(sql: String, start: Int): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == '\\') {
                i++
            } else if (sql[i] == '\'') {
                if (i + 1 < sql.length && sql[i + 1] == '\'') {
                    i++
                } else {
                    return i
                }
            }
            i++
        }
        throw StatementException(
            StatementExceptionReason.UNCLOSED_TOKEN,
            "Unclosed backslash-escaped literal",
            position = start + 1
        )
    }

    private fun skipUntil(sql: String, start: Int, endChar: Char, throwOnEof: Boolean = false, exceptionMessage: StatementExceptionReason? = null): Int {
        val index = sql.indexOf(endChar, start + 1)
        if (index == -1) {
            if (throwOnEof) {
                throw StatementException(
                    exceptionMessage ?: StatementExceptionReason.UNCLOSED_TOKEN,
                    "Unclosed token - [${endChar}]",
                    position = start + 1
                )
            }
            return sql.length
        }
        return index
    }

    private fun skipComment(sql: String, start: Int): Int {
        var i = start + 2
        var depth = 1
        while (i < sql.length && depth > 0) {
            if (i + 1 < sql.length) {
                if (sql[i] == '/' && sql[i + 1] == '*') {
                    depth++
                    i++
                } else if (sql[i] == '*' && sql[i + 1] == '/') {
                    depth--
                    i++
                }
            }
            i++
        }
        if (depth > 0) {
            throw StatementException(
                StatementExceptionReason.UNCLOSED_TOKEN,
                "Unclosed multi-line comment",
                position = start + 1
            )
        }
        return i - 1
    }
}