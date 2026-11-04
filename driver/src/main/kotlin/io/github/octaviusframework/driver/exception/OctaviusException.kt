package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage
import io.github.octaviusframework.driver.query.QueryContext

/**
 * The base of every exception the driver throws.
 *
 * @param message `EXCEPTION_NAME[:REASON]`, for filtering.
 * @property sqlState The server's SQLSTATE, where the server reported the failure.
 * @property serverErrorMessage The server's error report, where there was one.
 * @property path Where in a nested value or a plan this happened, innermost first, each layer appending its
 * segment as the exception unwinds. Rendered outermost first: `PATH: step 1 -> parameter 'name' -> map #1`.
 */
abstract class OctaviusException(
    message: String,
    val sqlState: String? = null,
    val serverErrorMessage: ServerErrorMessage? = null,
    cause: Throwable? = null,
    val path: MutableList<String> = mutableListOf()
) : RuntimeException(message, cause) {

    /**
     * The SQL and parameters of the query this happened in, set by the innermost query layer. Settable, so a
     * caller can put a redacted one in its place before logging.
     */
    var queryContext: QueryContext? = null

    /** A human-readable description of the failure, or `null`. [toString] shows it under `EXCEPTION DETAILS`. */
    open fun getDetailedMessage(): String? = null

    override fun toString(): String = buildString {
        appendLine()
        appendLine(line)
        appendLine("MESSAGE: $message")

        if (sqlState != null) {
            appendLine("SQLSTATE: $sqlState")
        }


        val detailedMsg = getDetailedMessage()
        if (detailedMsg != null) {
            appendLine("EXCEPTION DETAILS: \n$detailedMsg")
        }

        if (path.isNotEmpty()) {
            appendLine("PATH: ${path.asReversed().joinToString(" -> ")}")
        }

        if (queryContext != null) {
            appendLine(queryContext.toString())
        }
        
        append(line)
        
        if (cause != null) {
            appendLine()
            appendLine("CAUSE:")
            appendLine(line)
            appendLine(cause.toString())
            append(line)
        }
    }
}

private const val line = "--------------------------------------------------------------------------------"

/** How far down a cause chain [findOctaviusCause] looks, so a looping chain ends. */
private const val MAX_CAUSE_DEPTH = 16

/**
 * The driver's exception behind this throwable - itself, or one down its cause chain, unwrapped from a
 * [SQLExceptionWrapper] - or `null`. For a failure a pool or a framework has restated as its own.
 */
fun Throwable.findOctaviusCause(): OctaviusException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is SQLExceptionWrapper -> return current.wrappedException
            is OctaviusException -> return current
        }
        current = current.cause
        depth++
    }
    return null
}
