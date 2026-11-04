package io.github.octaviusframework.driver.parser

import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.StatementExceptionReason

/**
 * Finds where quotes, dollar quotes and comments end, for [SqlParameterParser] and [SqlScript], to whom nothing
 * inside them counts.
 */
internal object SqlScanner {

    /**
     * The index of the last character of the construct starting at [i], or [i] itself if none starts there.
     *
     * @throws io.github.octaviusframework.driver.exception.StatementException `UNCLOSED_TOKEN` where a quote,
     * dollar quote or block comment never closes.
     */
    fun findConstructEnd(sql: String, i: Int): Int {
        return when (sql[i]) {
            '\'' -> processSingleQuote(sql, i)
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

    /** Whether a comment starts at [i]. */
    fun isCommentStart(sql: String, i: Int): Boolean {
        if (i + 1 >= sql.length) return false
        return (sql[i] == '-' && sql[i + 1] == '-') || (sql[i] == '/' && sql[i + 1] == '*')
    }

    private fun processSingleQuote(sql: String, index: Int): Int {
        return if (index > 0 && (sql[index - 1] == 'E' || sql[index - 1] == 'e')) {
            skipBackslashEscapedLiteral(sql, index)
        } else {
            skipUntil(sql, index, '\'', throwOnEof = true, exceptionMessage = StatementExceptionReason.UNCLOSED_TOKEN)
        }
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
        return when {
            char.isLetter() || char == '_' -> true
            !isFirstChar && char in '0'..'9' -> true
            else -> false
        }
    }

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