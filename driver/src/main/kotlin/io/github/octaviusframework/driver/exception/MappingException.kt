package io.github.octaviusframework.driver.exception

/** Why a value could not be mapped. */
enum class MappingExceptionReason {
    /** No converter produces the type asked for. */
    NO_CONVERTER_FOUND,
    /** No column, attribute, field or element by that name or at that position. */
    COLUMN_NOT_FOUND,
    /** A converter failed, or a value is not of the type asked for. */
    CONVERSION_ERROR,
    /** A `NULL` where a non-nullable type was asked for. */
    REQUIRED_ATTRIBUTE_MISSING,
    /** The block handed to a streaming terminal threw; what it threw is the `cause`. */
    BLOCK_FAILED
}

/**
 * A value could not be mapped between its PostgreSQL and its Kotlin form.
 *
 * @param path The innermost segments of [OctaviusException.path], for a mapper that knows them already.
 */
class MappingException(
    val reason: MappingExceptionReason,
    val details: String,
    cause: Throwable? = null,
    path: MutableList<String> = mutableListOf()
) : OctaviusException("MAPPING_EXCEPTION:${reason.name}", cause = cause, path = path) {
    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        appendLine("Details: $details")
    }
}

private fun generateDeveloperMessage(reason: MappingExceptionReason): String =
    when (reason) {
        MappingExceptionReason.NO_CONVERTER_FOUND -> "No converter was found for the specified types."
        MappingExceptionReason.COLUMN_NOT_FOUND -> "The requested column, attribute, field or element was not found under that name or position."
        MappingExceptionReason.CONVERSION_ERROR -> "An error occurred during type conversion or casting."
        MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING -> "A required non-nullable attribute is missing or null."
        MappingExceptionReason.BLOCK_FAILED -> "The block handed to a streaming terminal threw; see the cause."
    }

