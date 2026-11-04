package io.github.octaviusframework.driver.message.backend

/** `CopyInResponse`: the server is ready for data. */
internal class CopyInResponseMessage(
    val format: Byte,
    val numColumns: Short,
    val columnFormats: List<Short>
) : BackendMessage {
    override fun toString(): String = "CopyInResponse(format=$format, numColumns=$numColumns)"
}

/** `CopyOutResponse`: the server is about to send data. */
internal class CopyOutResponseMessage(
    val format: Byte,
    val numColumns: Short,
    val columnFormats: List<Short>
) : BackendMessage {
    override fun toString(): String = "CopyOutResponse(format=$format, numColumns=$numColumns)"
}

/** `CopyBothResponse`. */
internal class CopyBothResponseMessage(
    val format: Byte,
    val numColumns: Short,
    val columnFormats: List<Short>
) : BackendMessage {
    override fun toString(): String = "CopyBothResponse(format=$format, numColumns=$numColumns)"
}

/** `CopyData` from the server. */
internal class BackendCopyDataMessage(val data: ByteArray) : BackendMessage {
    override fun toString(): String = "CopyData(size=${data.size})"
}

/** `CopyDone` from the server. */
internal object BackendCopyDoneMessage : BackendMessage {
    override fun toString(): String = "CopyDone"
}
