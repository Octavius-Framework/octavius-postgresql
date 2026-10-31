package io.github.octaviusframework.client.session

import io.github.octaviusframework.client.transaction.TransactionDefinition
import io.github.octaviusframework.client.transaction.TransactionPropagation
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import javax.sql.DataSource

/**
 * The [SessionProvider] for an application that runs its own transactions: the outermost operation on a thread
 * borrows a session from [dataSource], binds it to the thread, and gives it back when it returns.
 *
 * Operations made from inside one another share that session, in a transaction or not, so nesting holds one
 * connection. A level that begins while the one above is still reading a result - in a `forEach*` block or a
 * `ResultConverter` - is refused by the driver with `CONNECTION_BUSY`; a second connection there is taken from
 * the `DataSource` by hand.
 *
 * Where a framework owns the transactions, use a provider that finds its session instead.
 *
 * @param dataSource Where connections come from. Not closed by [close].
 */
class DefaultSessionProvider(private val dataSource: DataSource) : SessionProvider {

    /** The session bound to this thread, and whether a transaction is open on it: [execute] binds without one. */
    private class Binding(val session: OctaviusSession, val inTransaction: Boolean)

    private val bound = ThreadLocal<Binding?>()

    override fun <T> execute(action: OctaviusSessionOperations.() -> T): T {
        val active = bound.get()
        if (active != null) return active.session.action()
        return inNewSession(inTransaction = false) { it.action() }
    }

    override fun <T> transaction(definition: TransactionDefinition, block: () -> T): T {
        // A session of its own; inNewSession puts the outer binding back afterwards.
        if (definition.propagation == TransactionPropagation.REQUIRES_NEW) {
            return newTransaction(definition, block)
        }

        val active = bound.get() ?: return newTransaction(definition, block)

        // Bound by execute, with no transaction to join, so this scope opens one, as both remaining
        // propagations do where none is running.
        if (!active.inTransaction) {
            return withBinding(Binding(active.session, inTransaction = true)) {
                open(active.session, definition, block)
            }
        }

        return if (definition.propagation == TransactionPropagation.REQUIRED) {
            // Joining, so this does not commit. The terms go to the driver anyway, which decides what a joined
            // transaction can honour and warns about the rest.
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
     * Opens a transaction on [session] and runs [block] inside it. Every term goes out with the `BEGIN` and ends
     * with the transaction, so none follows the connection back to the pool.
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

    /** Runs [body] with [binding], then puts the previous one back, so `REQUIRES_NEW` returns to the outer session. */
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
