package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/** What is wrong with the statement. */
enum class StatementExceptionReason {
    /** The statement contains a syntax error. */
    SYNTAX_ERROR,
    /**
     * A quote, dollar-quoted body or comment the driver's parser found unclosed; `position` is where it began.
     */
    UNCLOSED_TOKEN,
    /** An object's definition or current state does not permit what was asked of it. */
    INVALID_DEFINITION,
    /** The statement references a table, column, function or type that does not exist. */
    UNDEFINED_OBJECT,
    /** The statement creates an object under a name that is already taken. */
    DUPLICATE_OBJECT,
    /** A name in the statement matches more than one candidate; qualify it. */
    AMBIGUOUS_OBJECT,
    /** A value's type does not fit where it was used, and no implicit cast applies. */
    DATA_TYPE_ERROR
}

/**
 * The SQL itself is wrong: syntax, a missing or duplicate object, an ambiguous name, a type mismatch. The message
 * points at [position] in the SQL where there is one.
 *
 * @property position The 1-based character position of the fault.
 */
class StatementException(
    val reason: StatementExceptionReason,
    val details: String? = null,
    val position: Int? = null,
    sqlState: String? = null,
    serverErrorMessage: ServerErrorMessage? = null
) : OctaviusException("STATEMENT_EXCEPTION:${reason.name}", sqlState, serverErrorMessage) {
    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        if (details != null) appendLine("Details: $details")
        
        val sqlContext = queryContext?.dbSql ?: queryContext?.sql
        if (sqlContext != null && position != null && position > 0 && position <= sqlContext.length + 1) {
            appendLine("Error at position $position:")
            val beforeError = sqlContext.substring(0, position - 1)
            val lastNewlineIndex = beforeError.lastIndexOf('\n')
            val lineStart = if (lastNewlineIndex == -1) 0 else lastNewlineIndex + 1
            
            val afterError = sqlContext.substring(position - 1)
            val nextNewlineIndex = afterError.indexOf('\n')
            val lineEnd = if (nextNewlineIndex == -1) sqlContext.length else (position - 1) + nextNewlineIndex
            
            val errorLine = sqlContext.substring(lineStart, lineEnd).replace("\r", "")
            val column = (position - 1) - lineStart
            
            appendLine(errorLine)
            appendLine(" ".repeat(maxOf(0, column)) + "^")
        }
    }
}

private fun generateDeveloperMessage(reason: StatementExceptionReason): String =
    when (reason) {
        StatementExceptionReason.SYNTAX_ERROR -> "The SQL statement contains a syntax error."
        StatementExceptionReason.UNCLOSED_TOKEN -> "The SQL statement leaves a quote, a dollar-quoted body or a comment unclosed. See the details for which."
        StatementExceptionReason.INVALID_DEFINITION -> "Invalid definition or object state."
        StatementExceptionReason.UNDEFINED_OBJECT -> "The referenced object is undefined."
        StatementExceptionReason.DUPLICATE_OBJECT -> "The referenced object already exists."
        StatementExceptionReason.AMBIGUOUS_OBJECT -> "The referenced object is ambiguous."
        StatementExceptionReason.DATA_TYPE_ERROR -> "Data type error in statement."
    }
