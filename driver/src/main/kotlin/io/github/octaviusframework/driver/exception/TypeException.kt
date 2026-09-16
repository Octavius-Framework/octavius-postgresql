package io.github.octaviusframework.driver.exception

/**
 * Represents the reason for a type resolution or validation failure.
 */
enum class TypeExceptionReason {
    /** The specified type was not found in the type catalog. */
    TYPE_NOT_FOUND,
    /** The type with the specified OID is not a valid container type (Composite, Array, Enum, etc.). */
    NOT_A_CONTAINER,
    /** Missing codec for the specific OID when parsing or serializing. */
    MISSING_CODEC
}

/**
 * Exception thrown when type resolution or validation fails within the driver's type registry.
 *
 * Includes scenarios where a type cannot be found, an incorrect container type is used,
 * or an expected codec is missing.
 *
 * @property reason The specific reason for the type error.
 * @property oid The OID of the type that caused the error, if applicable.
 * @property typeName The name of the type that caused the error, if applicable.
 * @property details Additional human-readable details about the failure.
 */
class TypeException(
    val reason: TypeExceptionReason,
    val oid: Int? = null,
    val typeName: String? = null,
    val details: String? = null
) : OctaviusException("TYPE_EXCEPTION:${reason.name}") {
    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        if (oid != null) appendLine("OID: $oid")
        if (typeName != null) appendLine("Type Name: $typeName")
        if (details != null) appendLine("Details: $details")
    }
}

private fun generateDeveloperMessage(reason: TypeExceptionReason): String =
    when (reason) {
        TypeExceptionReason.TYPE_NOT_FOUND -> "The specified type was not found in the type catalog."
        TypeExceptionReason.NOT_A_CONTAINER -> "The type with the specified OID is not a valid container type (Composite, Array, etc.)."
        TypeExceptionReason.MISSING_CODEC -> "Missing codec for the specific OID when parsing or serializing."
    }
