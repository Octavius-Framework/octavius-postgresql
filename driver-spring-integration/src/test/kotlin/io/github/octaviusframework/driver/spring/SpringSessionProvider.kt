package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.client.session.SessionProvider
import io.github.octaviusframework.client.transaction.TransactionDefinition
import io.github.octaviusframework.client.transaction.TransactionPropagation
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.TransactionDefinition as SpringDefinition

/**
 * The provider `docs/client/transactions-failures.md` offers for running the client under Spring, copied as it
 * stands there. Nothing compiles that page, so this is what does, and [OctaviusSpringClientTest] holds it to
 * what the page says of it.
 */
class SpringSessionProvider(
    private val template: OctaviusTemplate,
    private val transactionManager: PlatformTransactionManager
) : SessionProvider {

    override fun <T> execute(action: OctaviusSessionOperations.() -> T): T = template.execute(action)

    override fun <T> transaction(definition: TransactionDefinition, block: () -> T): T {
        val spring = DefaultTransactionDefinition().apply {
            propagationBehavior = when (definition.propagation) {
                TransactionPropagation.REQUIRED -> SpringDefinition.PROPAGATION_REQUIRED
                TransactionPropagation.REQUIRES_NEW -> SpringDefinition.PROPAGATION_REQUIRES_NEW
                TransactionPropagation.NESTED -> SpringDefinition.PROPAGATION_NESTED
            }
            definition.isolation?.let { isolationLevel = it.jdbcValue }
            isReadOnly = definition.readOnly
        }
        val timeouts = listOfNotNull(
            definition.statementTimeout?.let { "SET LOCAL statement_timeout = ${it.inWholeMilliseconds}" },
            definition.transactionTimeout?.let {
                "SET LOCAL transaction_timeout = 0; SET LOCAL transaction_timeout = ${it.inWholeMilliseconds}"
            }
        )

        return TransactionTemplate(transactionManager, spring).execute { status ->
            if (status.isNewTransaction && timeouts.isNotEmpty()) {
                template.execute { createNativeQuery(timeouts.joinToString("; ")).execute() }
            }
            block()
        }
    }
}
