package io.github.octaviusframework.client.session

import io.github.octaviusframework.client.transaction.TransactionDefinition
import io.github.octaviusframework.driver.session.OctaviusSessionOperations

/**
 * Answers the only question the client cannot answer for itself: which session does this operation run on.
 *
 * The client is a pooled facade over a driver that is session-per-connection, and this interface is where
 * those two meet. Everything above it - queries, builders, transaction plans - is written against
 * [execute] and never obtains a session of its own, which is what lets the same query code run standalone
 * on a connection pool and under a transaction manager that owns the connection itself.
 *
 * Implementations decide what "the current session" means. The default one binds it to the thread; one
 * backed by Spring finds it on whatever the surrounding `@Transactional` bound.
 */
interface SessionProvider : AutoCloseable {

    /**
     * Runs [action] on the session for the current context.
     *
     * Inside a [transaction] on this thread that is the transaction's session, and the work joins it.
     * Inside another [execute] it is that one's session, for a plainer reason: a connection carries one
     * exchange at a time, so borrowing a second would buy nothing and cost one connection per level of
     * nesting. Outside both, a session is obtained for this call and given back when it returns - so two
     * calls in a row are two transactions of the server's own making, exactly as two auto-commit statements
     * are.
     *
     * Sharing across levels is what an implementation is expected to do rather than something this interface
     * can enforce; one backed by a framework shares whatever that framework binds.
     *
     * @param action The work to run against the session.
     * @return Whatever [action] produced.
     */
    fun <T> execute(action: OctaviusSessionOperations.() -> T): T

    /**
     * Runs [block] with a transaction open, committing when it returns and rolling back when it throws.
     *
     * Every [execute] made from this thread while [block] runs lands on the transaction's session. That
     * binding is per-thread and does not follow work handed to another one: a coroutine launched on a
     * different dispatcher inside [block] gets a session of its own and a transaction of its own, and the
     * commit here says nothing about it.
     *
     * [block] returning normally is what commits, and only a throw rolls back. A failure already caught and
     * turned into a value - by [dbResult][io.github.octaviusframework.client.dbResult], say - is no longer a
     * throw, and a transaction that finishes over one commits.
     * [transactionResult][io.github.octaviusframework.client.OctaviusClient.transactionResult] is what closes that gap for
     * callers in the result style: it is built on this method and turns a returned failure back into the throw
     * this one needs.
     *
     * @param definition How the transaction should be run.
     * @param block The work to run inside it.
     * @return Whatever [block] produced.
     */
    fun <T> transaction(definition: TransactionDefinition, block: () -> T): T

    /**
     * Releases what this provider holds of its own. Whatever supplies its connections is not its to close.
     */
    override fun close() {}
}
