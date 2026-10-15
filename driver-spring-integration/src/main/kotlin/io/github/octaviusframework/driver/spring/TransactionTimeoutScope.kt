package io.github.octaviusframework.driver.spring

/**
 * What a Spring transaction timeout - `@Transactional(timeout = …)`, a `TransactionTemplate`'s, or
 * `spring.transaction.default-timeout` - is enforced on.
 *
 * PostgreSQL has a timeout for each and none for both: bounding the whole transaction is `transaction_timeout`,
 * and when that runs out the server ends the session rather than the transaction, so the connection goes with
 * it. That is the choice this makes, and [OctaviusJdbcTransactionManager.timeoutScope] is where it is made.
 */
enum class TransactionTimeoutScope(internal val setting: String) {

    /**
     * Each statement of the transaction, through `statement_timeout`: one that runs longer is cancelled, and it
     * comes back as `ExecutionAbortedException` on a connection that stays. The transaction as a
     * whole is held to the timeout by Spring's own deadline, which [OctaviusTemplate] checks before every block
     * - what `JdbcTemplate` does before every statement - and a block started past it throws Spring's
     * `TransactionTimedOutException`.
     *
     * Each statement gets the whole timeout rather than what is left of it, since telling the server how much
     * is left would cost a round trip per block.
     */
    STATEMENT("statement_timeout"),

    /**
     * The transaction as a whole, through `transaction_timeout`, the time between statements included: at the
     * deadline the server ends the session - running statement or none - and whatever touches it next gets
     * `SessionTerminatedException(TRANSACTION_TIMEOUT)`. It holds locks to the timeout even while the
     * application is busy elsewhere, and it costs a connection each time it fires.
     */
    TRANSACTION("transaction_timeout")
}
