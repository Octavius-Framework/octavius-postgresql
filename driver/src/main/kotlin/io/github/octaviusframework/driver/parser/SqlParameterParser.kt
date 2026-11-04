package io.github.octaviusframework.driver.parser

import java.util.concurrent.ConcurrentHashMap

internal data class ParsedSql(val originalSql: String, val transformedSql: String, val paramNames: List<String>)

internal data class ParsedParameter(val name: String, val startIndex: Int, val endIndex: Int)

internal object SqlParameterParser {
    private val cache = ConcurrentHashMap<String, ParsedSql>()
    private const val MAX_CACHE_SIZE = 10_000

    private const val PARAMETER_SEPARATORS = "\"':&,;()|=+-*%/\\<>^[]@~!#`?"
    private val separatorIndex = BooleanArray(128).apply {
        PARAMETER_SEPARATORS.forEach { this[it.code] = true }
    }

    private fun isParameterSeparator(c: Char): Boolean {
        return (c.code < 128 && separatorIndex[c.code]) || c.isWhitespace()
    }

    /** [sql] in its `$n` form and the names in placeholder order, cached by text; the cache is cleared when full. */
    fun parse(sql: String): ParsedSql {
        return cache[sql] ?: run {
            if (cache.size >= MAX_CACHE_SIZE) {
                cache.clear()
            }
            val parsed = doParse(sql)
            cache.putIfAbsent(sql, parsed) ?: parsed
        }
    }

    private fun doParse(sql: String): ParsedSql {
        val foundParameters = mutableListOf<ParsedParameter>()
        var i = 0

        while (i < sql.length) {
            val skipIndex = SqlScanner.findConstructEnd(sql, i)
            if (skipIndex > i) {
                i = skipIndex + 1
                continue
            }

            if (sql[i] == '@') {
                i = processAt(sql, i, foundParameters)
            }
            i++
        }

        if (foundParameters.isEmpty()) {
            return ParsedSql(sql, sql, emptyList())
        }

        val uniqueParamNames = mutableListOf<String>()
        val paramIndices = mutableMapOf<String, Int>()

        val transformedSql = buildString(sql.length + 32) {
            var lastIndex = 0
            for (parsedParam in foundParameters) {
                val paramName = parsedParam.name
                val index = paramIndices.getOrPut(paramName) {
                    uniqueParamNames.add(paramName)
                    uniqueParamNames.size
                }

                append(sql.substring(lastIndex, parsedParam.startIndex))
                append("$").append(index)
                lastIndex = parsedParam.endIndex + 1
            }
            append(sql.substring(lastIndex, sql.length))
        }

        return ParsedSql(sql, transformedSql, uniqueParamNames)
    }

    private fun processAt(
        sql: String,
        index: Int,
        foundParameters: MutableList<ParsedParameter>
    ): Int {
        var j = index + 1
        while (j < sql.length && !isParameterSeparator(sql[j])) {
            j++
        }

        if (j - index > 1) {
            val paramName = sql.substring(index + 1, j)
            foundParameters.add(ParsedParameter(paramName, index, j - 1))
            return j - 1
        }

        return index
    }
}
