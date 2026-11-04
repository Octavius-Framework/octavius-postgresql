package io.github.octaviusframework.client.session

import io.github.octaviusframework.client.transaction.TransactionDefinition
import io.github.octaviusframework.driver.session.OctaviusSessionOperations

/**
 * Decides which session an operation runs on. Queries, builders and plans run through [execute] and never obtain
 * a session themselves, so the same code runs on a pool or under a transaction manager that owns the connection.
 * [DefaultSessionProvider] binds the session to the thread.
 */
interface SessionProvider : AutoCloseable {

    /**
     * Runs [action] on the session for the current context: the transaction's inside a [transaction], the
     * enclosing call's inside another [execute], and otherwise one obtained for this call alone, whose statements
     * commit as they go. An implementation is expected to share the session across nested calls.
     */
    fun <T> execute(action: OctaviusSessionOperations.() -> T): T

    /**
     * Runs [block] with a transaction open, committing when it returns and rolling back when it throws. Every
     * [execute] from this thread while [block] runs lands on the transaction's session; work handed to another
     * thread does not.
     *
     * A failure turned into a value inside [block] is not a throw, so the transaction commits over it;
     * [transactionResult][io.github.octaviusframework.client.OctaviusClient.transactionResult] turns a returned
     * failure back into one.
     */
    fun <T> transaction(definition: TransactionDefinition, block: () -> T): T

    /**
     * Releases what this provider holds of its own. Whatever supplies its connections is not its to close.
     */
    override fun close() {}
}
