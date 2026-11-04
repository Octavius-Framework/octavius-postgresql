package io.github.octaviusframework.driver.spring

/**
 * What a Spring transaction timeout - `@Transactional(timeout = …)`, a `TransactionTemplate`'s, or
 * `spring.transaction.default-timeout` - is enforced on.
 *
 * Each scope is one PostgreSQL setting and promises what that setting does. Neither is what `JdbcTemplate`
 * does, which is to give each statement what is left of the timeout. [OctaviusJdbcTransactionManager.timeoutScope]
 * is where the choice is made.
 */
enum class TransactionTimeoutScope {

    /**
     * Each statement of the transaction, through `statement_timeout`: one that runs longer is cancelled and
     * comes back as `ExecutionAbortedException` on a connection that stays. Every statement gets the whole
     * timeout, so the transaction as a whole can run longer.
     */
    STATEMENT,

    /**
     * The transaction as a whole, through `transaction_timeout`, the time between statements included: at the
     * deadline the server ends the session - running statement or none - and whatever touches it next gets
     * `SessionTerminatedException(TRANSACTION_TIMEOUT)`. It costs a connection each time it fires.
     */
    TRANSACTION
}
