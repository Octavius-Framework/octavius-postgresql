package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.session.OctaviusSession
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
 * Contributes an [OctaviusTemplate] and an [OctaviusJdbcTransactionManager], each only where the context declares
 * none of its own - any `TransactionManager` counting for the second. It runs after Boot's
 * `TransactionManagerCustomizationAutoConfiguration`, whose customizers it applies, and before the transaction
 * manager Boot would make. Like Boot's own JDBC auto-configuration, it contributes nothing with several
 * `DataSource` beans and none of them primary.
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, TransactionManagerCustomizationAutoConfiguration::class],
    before = [DataSourceTransactionManagerAutoConfiguration::class, TransactionAutoConfiguration::class]
)
@ConditionalOnClass(OctaviusSession::class, DataSource::class)
@ConditionalOnSingleCandidate(DataSource::class)
open class OctaviusSpringAutoConfiguration {

    /** An [OctaviusTemplate], unless the context has one. */
    @Bean
    @ConditionalOnMissingBean
    open fun octaviusTemplate(dataSource: DataSource): OctaviusTemplate {
        return OctaviusTemplate(dataSource)
    }

    /**
     * An [OctaviusJdbcTransactionManager], unless the context declares a `TransactionManager` of its own - JDBC,
     * JPA or reactive alike, as for Boot's. Declared as the class it is, so a bean created before it can inject it
     * by that type.
     *
     * Boot's transaction manager customizers are applied to it: `spring.transaction.default-timeout` reaches it,
     * and so does a `TransactionManagerCustomizer<OctaviusJdbcTransactionManager>` bean - the way to change its
     * [OctaviusJdbcTransactionManager.timeoutScope] without declaring the manager yourself.
     * `spring.transaction.rollback-on-commit-failure` reaches it too and changes nothing, as the class says.
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
