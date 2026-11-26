package io.github.octaviusframework.driver.replication

/**
 * A message a transaction wrote with `pg_logical_emit_message(true, prefix, content)`, read through
 * [ReplicatedTransaction.messages] where `startPgOutput` was asked for messages.
 */
class LogicalMessage internal constructor(val prefix: String, val content: ByteArray) : TransactionEntry {
    override fun toString(): String = "LogicalMessage(prefix=$prefix, ${content.size} bytes)"
}
