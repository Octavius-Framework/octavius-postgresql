package io.github.octaviusframework.driver.message.frontend

import io.github.octaviusframework.driver.io.PgOutputStream
import java.nio.charset.StandardCharsets

private val NO_BYTES = ByteArray(0)

/** [s] in UTF-8, with the empty name every query uses held rather than encoded each time. */
private fun utf8(s: String): ByteArray = if (s.isEmpty()) NO_BYTES else s.toByteArray(StandardCharsets.UTF_8)

/** `Parse`: [query] as the statement [statementName], its parameters declared as [parameterTypes]. */
internal class ParseMessage(
    private val statementName: String,
    private val query: String,
    private val parameterTypes: IntArray = IntArray(0) // List of parameter OIDs (can be empty to be inferred by Postgres)
) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        val nameBytes = utf8(statementName)
        val queryBytes = utf8(query)
        val length = 4 + nameBytes.size + 1 + queryBytes.size + 1 + 2 + (parameterTypes.size * 4)

        out.writeByte('P'.code.toByte())
        out.writeInt(length)
        out.writeCString(nameBytes)
        out.writeCString(queryBytes)
        out.writeShort(parameterTypes.size)
        parameterTypes.forEach { out.writeInt(it) }
    }
}

/**
 * `Bind`: the statement [statementName] with its parameters, as the portal [portalName].
 *
 * @property parameterValues The serialized values, each behind its length.
 */
internal class BindMessage(
    private val portalName: String,
    private val statementName: String,
    private val parameterCount: Int,
    private val parameterValues: ByteArray, // Pre-serialized values including lengths
    private val parameterFormats: List<Int>, // 0 for text, 1 for binary (for each parameter, or one for all)
    private val resultFormats: List<Int> = listOf(1), // default to binary for all
    private val parameterValuesLength: Int = parameterValues.size
) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        val portalBytes = utf8(portalName)
        val statementBytes = utf8(statementName)

        val length = 4 + portalBytes.size + 1 + statementBytes.size + 1 +
                2 + (parameterFormats.size * 2) +
                2 + parameterValuesLength +
                2 + (resultFormats.size * 2)

        out.writeByte('B'.code.toByte())
        out.writeInt(length)
        out.writeCString(portalBytes)
        out.writeCString(statementBytes)

        // Parameter formats
        out.writeShort(parameterFormats.size)
        parameterFormats.forEach { out.writeShort(it) }

        // Parameter values
        out.writeShort(parameterCount)
        if (parameterValuesLength > 0) {
            out.writeBytes(parameterValues, 0, parameterValuesLength)
        }

        // Result formats
        out.writeShort(resultFormats.size)
        resultFormats.forEach { out.writeShort(it) }
    }
}

/** `Describe` of the statement (`S`) or portal (`P`) [name]. */
internal class DescribeMessage(private val targetType: Char, private val name: String) :
    FrontendMessage {
    override fun encode(out: PgOutputStream) {
        val nameBytes = utf8(name)
        val length = 4 + 1 + nameBytes.size + 1

        out.writeByte('D'.code.toByte())
        out.writeInt(length)
        out.writeByte(targetType.code.toByte())
        out.writeCString(nameBytes)
    }
}

/** `Execute` of [portalName], returning at most [maxRows] rows, `0` for all. */
internal class ExecuteMessage(private val portalName: String, private val maxRows: Int = 0) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        val portalBytes = utf8(portalName)
        val length = 4 + portalBytes.size + 1 + 4

        out.writeByte('E'.code.toByte())
        out.writeInt(length)
        out.writeCString(portalBytes)
        out.writeInt(maxRows)
    }
}

/** `Sync`, ending an extended-query exchange. */
internal class SyncMessage : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        out.writeByte('S'.code.toByte())
        out.writeInt(4)
    }
}

/** `Flush`, asking the server to send what it has. */
internal class FlushMessage : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        out.writeByte('H'.code.toByte())
        out.writeInt(4)
    }
}

/** `Close` of the statement (`S`) or portal (`P`) [name]. */
internal class CloseMessage(private val targetType: Char, private val name: String) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        val nameBytes = utf8(name)
        val length = 4 + 1 + nameBytes.size + 1
        out.writeByte('C'.code.toByte())
        out.writeInt(length)
        out.writeByte(targetType.code.toByte())
        out.writeCString(nameBytes)
    }
}


