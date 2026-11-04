package io.github.octaviusframework.driver.message.backend

/** `ParameterStatus`: a reported setting and its value. */
internal class ParameterStatusMessage(val name: String, val value: String) : BackendMessage {
    override fun toString(): String = "ParameterStatus($name=$value)"
}

/** `BackendKeyData`: what a cancel request names. */
internal class BackendKeyDataMessage(val processId: Int, val secretKey: ByteArray) : BackendMessage {
    override fun toString(): String = "BackendKeyData(pid=$processId, keySize=${secretKey.size})"
}

/** `ReadyForQuery`, with the transaction status. */
internal class ReadyForQueryMessage(val transactionStatus: Char) : BackendMessage {
    override fun toString(): String = "ReadyForQuery(status=$transactionStatus)"
}


