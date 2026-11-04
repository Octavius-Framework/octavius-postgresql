package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import kotlin.time.Duration

/**
 * How a transaction should be run, as a [SessionProvider][io.github.octaviusframework.client.session.SessionProvider]
 * is handed it.
 *
 * @property isolation `null` for the server's level. Applied only where this starts a transaction, as is
 * [readOnly].
 * @property readOnly Whether the transaction refuses writes.
 * @property statementTimeout Aborts any single statement running longer than this, as `SET LOCAL
 * statement_timeout`. `null` leaves the server's setting.
 * @property transactionTimeout Aborts the transaction once it has been open longer than this, as `SET LOCAL
 * transaction_timeout`. `null` leaves the server's setting.
 */
data class TransactionDefinition(
    val propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
    val isolation: TransactionIsolationLevel? = null,
    val readOnly: Boolean = false,
    val statementTimeout: Duration? = null,
    val transactionTimeout: Duration? = null
)
