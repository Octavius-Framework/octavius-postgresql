package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.spring.exception.OctaviusExceptionTranslator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers
import org.springframework.context.annotation.Bean
import org.springframework.transaction.TransactionManager
import javax.sql.DataSource

/**
 * Spring Boot auto-configuration for the Octavius driver.
 *
 * Runs after Spring's own `DataSourceAutoConfiguration`, and after Boot's
 * `TransactionManagerCustomizationAutoConfiguration`, whose customizers its transaction manager takes; and before
 * Boot's `DataSourceTransactionManagerAutoConfiguration` and `TransactionAutoConfiguration`. Contributes an
 * [OctaviusTemplate] and an [OctaviusJdbcTransactionManager], each only when the application context does not
 * already declare one of its own - any `TransactionManager` counting for the second. Declaring either bean yourself
 * takes precedence over everything here.
 *
 * It stands back where Boot's own JDBC auto-configuration does: with several `DataSource` beans and none of them
 * primary, there is no single one to build on, and it contributes nothing.
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, TransactionManagerCustomizationAutoConfiguration::class],
    before = [DataSourceTransactionManagerAutoConfiguration::class, TransactionAutoConfiguration::class]
)
@ConditionalOnClass(OctaviusSession::class, DataSource::class)
@ConditionalOnSingleCandidate(DataSource::class)
open class OctaviusSpringAutoConfiguration {

    /**
     * Creates and registers an [OctaviusTemplate] bean if one is not already present in the application context.
     *
     * @param dataSource the underlying data source to use
     * @return a new instance of [OctaviusTemplate]
     */
    @Bean
    @ConditionalOnMissingBean
    open fun octaviusTemplate(dataSource: DataSource): OctaviusTemplate {
        return OctaviusTemplate(dataSource)
    }

    /**
     * Creates and registers an [OctaviusJdbcTransactionManager] if the application context declares no
     * `TransactionManager` of its own - JDBC, JPA or reactive alike, as for Boot's own. Declared as the class it
     * is, so a bean created before it can inject it by that type.
     *
     * It carries an [OctaviusExceptionTranslator], so a failure raised while the manager itself is
     * committing or rolling back arrives in Spring's `DataAccessException` hierarchy rather than as a
     * raw `SQLException`, and it has nested transactions enabled, which is what makes
     * `@Transactional(propagation = NESTED)` resolve to a savepoint instead of being rejected. What it
     * adds over `JdbcTransactionManager` is the driver's exception for a transaction that never got a
     * connection, an answer for one whose connection has already left - see the class for which
     * way each of commit and rollback goes, and why they differ - and a transaction timeout that is
     * enforced.
     *
     * Boot's transaction manager customizers are applied to it, as they are to the one Boot would have
     * made: `spring.transaction.default-timeout` and `spring.transaction.rollback-on-commit-failure` reach
     * it, and so does a `TransactionManagerCustomizer<OctaviusJdbcTransactionManager>` bean - the way to
     * change its [OctaviusJdbcTransactionManager.timeoutScope] without declaring the manager yourself.
     *
     * @param dataSource the underlying data source to use
     * @param customizers Boot's transaction manager customizers, where Boot's transaction auto-configuration
     *   provides them
     * @return a new instance of [OctaviusJdbcTransactionManager]
     */
    @Bean
    @ConditionalOnMissingBean(TransactionManager::class)
    open fun transactionManager(
        dataSource: DataSource,
        customizers: ObjectProvider<TransactionManagerCustomizers>
    ): OctaviusJdbcTransactionManager =
        OctaviusJdbcTransactionManager(dataSource).also { manager ->
            customizers.ifAvailable { it.customize(manager) }
        }
}
