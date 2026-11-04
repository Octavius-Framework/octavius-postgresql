package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/** What was wrong with the value. */
enum class DataExceptionReason {
    /** String, interval, or datetime truncation/overflow (22001, 22008, 22015). */
    DATA_TRUNCATION,
    /** Numeric value out of bounds (22003, 22022). */
    NUMERIC_OUT_OF_RANGE,
    /** Division by zero (22012). */
    DIVISION_BY_ZERO,
    /** Invalid data format or representation (22007, 22P02, 22P03, 22018). */
    INVALID_FORMAT,
    /** Array subscript out of bounds (2202E). */
    ARRAY_SUBSCRIPT_ERROR,
    /** Null value not allowed by constraint (22004, 22002). */
    NULL_VALUE_NOT_ALLOWED,
    /** Error parsing or operating on JSON data (2203X). */
    JSON_ERROR,
    /** Error parsing or operating on XML data (2200L - 2200T). */
    XML_ERROR,
    /** Invalid escape character sequence (22019, 2200D, 22025, 22P06, 2200C, 2200B). */
    ESCAPE_CHARACTER_ERROR,
    /** Invalid regular expression (2201B). */
    REGEX_ERROR,
    /** A generic or unknown data error. */
    UNKNOWN
}

/**
 * A value the server could not take: too long, out of range, a division by zero, a malformed literal.
 *
 * @property dbMessage The server's message.
 * @property details The server's detail.
 * @property where The call stack, for an error raised inside a trigger or function.
 */
class DataException(
    val reason: DataExceptionReason,
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException("DATA_EXCEPTION:${reason.name}", sqlState, serverErrorMessage) {

    val dbMessage: String get() = serverErrorMessage!!.message
    val details: String? get() = serverErrorMessage!!.detail
    val where: String? get() = serverErrorMessage!!.where

    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        appendLine("Database Message: $dbMessage")
        if (details != null) appendLine("Details: $details")
        if (where != null) appendLine("Context: $where")
    }
}

private fun generateDeveloperMessage(reason: DataExceptionReason): String =
    when (reason) {
        DataExceptionReason.DATA_TRUNCATION -> "A string, interval or datetime value was truncated or overflowed."
        DataExceptionReason.NUMERIC_OUT_OF_RANGE -> "A numeric value is out of bounds for its target data type."
        DataExceptionReason.DIVISION_BY_ZERO -> "Attempted to divide by zero."
        DataExceptionReason.INVALID_FORMAT -> "The provided data has an invalid format or text/binary representation for the target type."
        DataExceptionReason.ARRAY_SUBSCRIPT_ERROR -> "Array subscript out of bounds or invalid array dimensions."
        DataExceptionReason.NULL_VALUE_NOT_ALLOWED -> "A null value was provided where it is not allowed by a data constraint."
        DataExceptionReason.JSON_ERROR -> "An error occurred while parsing or operating on JSON data."
        DataExceptionReason.XML_ERROR -> "An error occurred while parsing or operating on XML data."
        DataExceptionReason.ESCAPE_CHARACTER_ERROR -> "Invalid escape character or escape sequence."
        DataExceptionReason.REGEX_ERROR -> "Invalid regular expression."
        DataExceptionReason.UNKNOWN -> "A generic data error occurred."
    }
