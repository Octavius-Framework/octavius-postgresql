package io.github.octaviusframework.client.session

import io.github.octaviusframework.client.transaction.TransactionDefinition
import io.github.octaviusframework.client.transaction.TransactionPropagation
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import javax.sql.DataSource

/**
 * The [SessionProvider] for an application that runs its own transactions, binding the session to the thread
 * for as long as the outermost operation on it lasts.
 *
 * A top-level operation borrows a session from [dataSource] and gives it back when it returns. That is as
 * cheap as it sounds: the session is a handful of allocations over the connection, and giving it back costs
 * no round trip unless it registered a `LISTEN` or left a transaction open.
 *
 * **Operations made from inside one another share that session**, transaction or no transaction. Composing
 * functions that each do a query is the ordinary way to use this client, and a provider that borrowed again
 * per level would hold one connection per level of nesting - two connections for work that needs one, and a
 * pool of `N` serving `N / D` callers at depth `D`. Sharing costs nothing, because a connection can only
 * carry one exchange at a time anyway: the levels are sequential whether they share or not.
 *
 * The one thing sharing does not survive is a level that begins **while the one above it is still reading a
 * result** - a `forEach*` block, or a `ResultConverter`. That connection is busy, and the driver says so with
 * `InvalidOperationException(CONNECTION_BUSY)` rather than quietly finding a second one. Code that genuinely
 * needs a second connection in that position should take it deliberately, out of the `DataSource`.
 *
 * Where something else owns the transaction - Spring, most obviously - this is the wrong provider; that one
 * finds the session where the framework put it rather than on a `ThreadLocal` of its own.
 *
 * @param dataSource Where connections come from. Not closed by [close]; whoever built the pool owns it.
 */
class DefaultSessionProvider(private val dataSource: DataSource) : SessionProvider {

    /**
     * The session this thread is working on, and whether a transaction has been opened on it.
     *
     * Both halves are needed because [execute] binds too: a session can be bound without being in a
     * transaction, and [transaction] has to know which, since that decides whether a scope joins what is
     * already running or is the one that opens it.
     */
    private class Binding(val session: OctaviusSession, val inTransaction: Boolean)

    private val bound = ThreadLocal<Binding?>()

    override fun <T> execute(action: OctaviusSessionOperations.() -> T): T {
        val active = bound.get()
        if (active != null) return active.session.action()
        return inNewSession(inTransaction = false) { it.action() }
    }

    override fun <T> transaction(definition: TransactionDefinition, block: () -> T): T {
        // A session of its own, whether or not one was bound. `inNewSession` rebinds for the duration and
        // puts the outer one back afterwards, so the suspension needs nothing else.
        if (definition.propagation == TransactionPropagation.REQUIRES_NEW) {
            return newTransaction(definition, block)
        }

        val active = bound.get() ?: return newTransaction(definition, block)

        // Bound by `execute` rather than by a transaction, so there is nothing here to join yet and this
        // scope is the one that opens it - which is what both remaining propagations mean where there is no
        // surrounding transaction. `NESTED` in particular cannot take a savepoint without one.
        if (!active.inTransaction) {
            return withBinding(Binding(active.session, inTransaction = true)) {
                open(active.session, definition, block)
            }
        }

        return if (definition.propagation == TransactionPropagation.REQUIRED) {
            // Joining, so this does not commit: `required` runs the block as it stands while the session is
            // already in a transaction, and the outermost scope is what decides. The definition is handed
            // over all the same, so that the driver stays the one place that decides which of these terms a
            // joined transaction can honour, and warns about the rest.
            active.session.transaction.required(
                definition.isolation,
                definition.readOnly,
                definition.statementTimeout,
                definition.transactionTimeout
            ) { block() }
        } else {
            active.session.transaction.nested(
                definition.isolation,
                definition.readOnly,
                definition.statementTimeout,
                definition.transactionTimeout
            ) { block() }
        }
    }

    /** Borrows a session, opens a transaction on it and runs [block] inside. */
    private fun <T> newTransaction(definition: TransactionDefinition, block: () -> T): T =
        inNewSession(inTransaction = true) { session -> open(session, definition, block) }

    /**
     * Opens a transaction on [session] and runs [block] inside it.
     *
     * Every term of the definition goes to `required`, which sends the whole lot as one statement after the
     * `BEGIN` - `SET TRANSACTION` for the isolation level and the read-only flag, `SET LOCAL` for the
     * timeouts. All four end with the transaction, so a pooled connection goes back carrying none of them
     * and there is nothing here to undo.
     */
    private fun <T> open(session: OctaviusSession, definition: TransactionDefinition, block: () -> T): T =
        session.transaction.required(
            definition.isolation,
            definition.readOnly,
            definition.statementTimeout,
            definition.transactionTimeout
        ) { block() }

    /** Borrows a session, binds it for the duration of [body], and gives it back afterwards. */
    private fun <T> inNewSession(inTransaction: Boolean, body: (OctaviusSession) -> T): T {
        val session = dataSource.getOctaviusSession()
        try {
            return withBinding(Binding(session, inTransaction)) { body(session) }
        } finally {
            session.close()
        }
    }

    /**
     * Runs [body] with [binding] in force.
     *
     * The previous binding is restored rather than cleared, which is what makes `REQUIRES_NEW` inside an
     * existing transaction come back to the outer session instead of to no session at all.
     */
    private fun <T> withBinding(binding: Binding, body: () -> T): T {
        val previous = bound.get()
        bound.set(binding)
        try {
            return body()
        } finally {
            if (previous != null) bound.set(previous) else bound.remove()
        }
    }
}
