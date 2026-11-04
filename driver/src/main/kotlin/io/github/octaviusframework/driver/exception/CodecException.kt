package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.util.formatDiagnosticValue
import kotlin.reflect.KClass

/** Whether a codec was encoding or decoding. */
enum class CodecAction { ENCODING, DECODING }

/**
 * A codec failed to encode or decode a value; the codec's own failure is the `cause`.
 *
 * @property name The PostgreSQL type's name.
 */
class CodecException(
    val action: CodecAction,
    val value: Any?,
    val name: String,
    val schema: String = "",
    val oid: Int? = null,
    val kotlinClass: KClass<*>? = null,
    cause: Exception
) : OctaviusException("CODEC_EXCEPTION:${action.name}", cause = cause) {

    override fun getDetailedMessage(): String = buildString {
        val actionStr = if (action == CodecAction.ENCODING) "encode" else "decode"
        val typeInfo = name
        val classInfo = kotlinClass?.qualifiedName ?: "unknown"

        appendLine("message: Failed to $actionStr value [${formatDiagnosticValue(value)}] for PostgreSQL type '$typeInfo' and Kotlin class '$classInfo'")
        appendLine("Name: $name")
        if (schema.isNotEmpty()) appendLine("Schema: $schema")
        if (oid != null) appendLine("OID: $oid")
        if (kotlinClass != null) appendLine("Kotlin Class: ${kotlinClass.qualifiedName}")
    }

}
