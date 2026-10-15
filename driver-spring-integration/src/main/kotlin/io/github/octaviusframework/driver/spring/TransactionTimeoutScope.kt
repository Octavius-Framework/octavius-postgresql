package io.github.octaviusframework.driver.spring

/**
 * What a Spring transaction timeout - `@Transactional(timeout = …)`, a `TransactionTemplate`'s, or
 * `spring.transaction.default-timeout` - is enforced on.
 *
 * Each scope is one PostgreSQL setting, and promises what that setting does. PostgreSQL has a timeout for each
 * statement and one for the whole transaction, and none for both: bounding the whole transaction is
 * `transaction_timeout`, and when that runs out the server ends the session rather than the transaction, so the
 * connection goes with it. Neither is what `JdbcTemplate` does with the same timeout, which is to give each
 * statement what is left of it. [OctaviusJdbcTransactionManager.timeoutScope] is where the choice is made.
 */
enum class TransactionTimeoutScope {

    /**
     * Each statement of the transaction, through `statement_timeout`: one that runs longer is cancelled, and it
     * comes back as `ExecutionAbortedException` on a connection that stays.
     *
     * Each statement, not their sum: every one gets the whole timeout, so a transaction of several statements can
     * run longer than the timeout as a whole.
     */
    STATEMENT,

    /**
     * The transaction as a whole, through `transaction_timeout`, the time between statements included: at the
     * deadline the server ends the session - running statement or none - and whatever touches it next gets
     * `SessionTerminatedException(TRANSACTION_TIMEOUT)`. It holds locks to the timeout even while the
     * application is busy elsewhere, and it costs a connection each time it fires.
     */
    TRANSACTION
}
