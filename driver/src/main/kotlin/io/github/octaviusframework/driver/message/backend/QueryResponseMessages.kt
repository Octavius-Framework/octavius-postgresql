package io.github.octaviusframework.driver.message.backend

/** `ParseComplete`. */
internal object ParseCompleteMessage : BackendMessage {
    override fun toString(): String = "ParseComplete"
}

/** `EmptyQueryResponse`, the answer to an empty statement. */
internal object EmptyQueryResponseMessage : BackendMessage {
    override fun toString(): String = "EmptyQueryResponse"
}

/** `BindComplete`. */
internal object BindCompleteMessage : BackendMessage {
    override fun toString(): String = "BindComplete"
}

/** `NoData`: the statement described returns no rows. */
internal object NoDataMessage : BackendMessage {
    override fun toString(): String = "NoData"
}

/** `CommandComplete`, with the command [tag] such as `UPDATE 5`. */
internal class CommandCompleteMessage(val tag: String) : BackendMessage {
    override fun toString(): String = "CommandComplete(tag=$tag)"
}

/** `RowDescription`: the columns of the rows that follow. */
internal class RowDescriptionMessage(val fields: List<FieldDescription>) : BackendMessage {
    override fun toString(): String = "RowDescription(fields=${fields.size})"
}

/** `DataRow`. Its arrays may be buffers the next row overwrites, so they are read before that one is received. */
internal class DataRowMessage(
    val rawData: ByteArray,
    val columnOffsets: IntArray,
    val columnLengths: IntArray
) : BackendMessage {
    override fun toString(): String = "DataRow(columns=${columnOffsets.size})"
}

/** `PortalSuspended`: an `Execute` reached its row limit. */
internal object PortalSuspendedMessage : BackendMessage {
    override fun toString(): String = "PortalSuspended"
}
