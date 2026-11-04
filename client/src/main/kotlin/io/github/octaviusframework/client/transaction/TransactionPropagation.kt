package io.github.octaviusframework.client.transaction

/**
 * What a transaction does when it is started inside one that is already running.
 */
enum class TransactionPropagation {

    /**
     * Join the running transaction, or start one. A joined block does not commit, and a failure in it takes the
     * whole transaction down.
     */
    REQUIRED,

    /**
     * Run in a transaction of its own, on a session of its own, while the surrounding one is suspended: neither
     * can roll the other back, as an audit record needs. It holds a second connection and does not see the outer
     * transaction's uncommitted rows.
     */
    REQUIRES_NEW,

    /**
     * Run in a savepoint of the running transaction, or start one. A failure rolls back to the savepoint and
     * leaves the surrounding transaction usable; the outer one failing later still discards this work.
     */
    NESTED
}
