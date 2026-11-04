package io.github.octaviusframework.driver.exception

/** Why a type could not be used. */
enum class TypeExceptionReason {
    /** The type catalog has no such type. */
    TYPE_NOT_FOUND,
    /** The type is not the kind of container - composite, array, range, multirange - asked for. */
    NOT_A_CONTAINER,
    /** No codec for the type. */
    MISSING_CODEC
}

/** A type the driver's catalog cannot resolve or has no codec for. */
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
