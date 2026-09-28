package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.spring.exception.OctaviusExceptionTranslator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * Spring Boot auto-configuration for the Octavius driver.
 *
 * Runs after Spring's own `DataSourceAutoConfiguration` and contributes an [OctaviusTemplate] and a
 * [PlatformTransactionManager], each only when the application context does not already declare one
 * of its own. Declaring either bean yourself takes precedence over everything here.
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class])
@ConditionalOnClass(OctaviusSession::class, DataSource::class)
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
     * Creates and registers a [PlatformTransactionManager] bean backed by an
     * [OctaviusJdbcTransactionManager] if one is not already present in the application context.
     *
     * It carries an [OctaviusExceptionTranslator], so a failure raised while the manager itself is
     * committing or rolling back arrives in Spring's `DataAccessException` hierarchy rather than as a
     * raw `SQLException`, and it has nested transactions enabled, which is what makes
     * `@Transactional(propagation = NESTED)` resolve to a savepoint instead of being rejected. What it
     * adds over `JdbcTransactionManager` is the driver's exception for a transaction that never got a
     * connection, and an answer for one whose connection has already left - see the class for which
     * way each of commit and rollback goes, and why they differ.
     *
     * @param dataSource the underlying data source to use
     * @return a new instance of [PlatformTransactionManager]
     */
    @Bean
    @ConditionalOnMissingBean
    open fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        OctaviusJdbcTransactionManager(dataSource)
}
