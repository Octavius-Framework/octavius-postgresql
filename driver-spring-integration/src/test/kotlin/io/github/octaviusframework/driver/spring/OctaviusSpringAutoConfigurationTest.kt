package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource
import org.springframework.beans.factory.getBean

class OctaviusSpringAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OctaviusSpringAutoConfiguration::class.java, DataSourceAutoConfiguration::class.java))
        .withPropertyValues(
            "spring.datasource.url=${TestDatabase.URL}",
            "spring.datasource.username=${TestDatabase.USER}",
            "spring.datasource.password=${TestDatabase.PASSWORD}",
            "spring.datasource.driver-class-name=io.github.octaviusframework.driver.jdbc.OctaviusDriver"
        )

    @Test
    fun `should configure OctaviusTemplate and TransactionManager by default`() {
        contextRunner.run { context ->
            assertTrue(context.containsBean("octaviusTemplate"))
            assertTrue(context.containsBean("transactionManager"))
            
            val tm = context.getBean<PlatformTransactionManager>()
            // Still a JdbcTransactionManager, so everything keyed off that type keeps working; what
            // the subclass adds is an answer for a transaction whose connection has already left.
            assertInstanceOf(OctaviusJdbcTransactionManager::class.java, tm)
            assertInstanceOf(JdbcTransactionManager::class.java, tm)
        }
    }

    @Test
    fun `should register its TransactionManager ahead of Boot's own, whatever the class names sort to`() {
        // Boot sorts auto-configurations by name before anything else, and io.github already sorts ahead of
        // org.springframework. An auto-configuration ordered first that runs after TransactionAutoConfiguration
        // pulls Boot's transaction manager forward with it, so only a declared ordering keeps Octavius's first.
        contextRunner
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceTransactionManagerAutoConfiguration::class.java,
                    TransactionAutoConfiguration::class.java,
                    AfterTransactionAutoConfiguration::class.java
                )
            )
            .run { context ->
                assertInstanceOf(OctaviusJdbcTransactionManager::class.java, context.getBean<PlatformTransactionManager>())
                // TransactionAutoConfiguration saw the one transaction manager and built its template on it.
                assertNotNull(context.getBean<TransactionTemplate>())
            }
    }

    @Test
    fun `should back off if custom OctaviusTemplate is provided`() {
        contextRunner.withUserConfiguration(CustomTemplateConfiguration::class.java).run { context ->
            assertTrue(context.containsBean("customOctaviusTemplate"))
            assertFalse(context.containsBean("octaviusTemplate")) // The default one should not be created
            
            val template = context.getBean<OctaviusTemplate>()
            assertNotNull(template)
        }
    }

    @Test
    fun `should back off if custom PlatformTransactionManager is provided`() {
        contextRunner.withUserConfiguration(CustomTransactionManagerConfiguration::class.java).run { context ->
            assertTrue(context.containsBean("customTransactionManager"))
            assertFalse(context.containsBean("transactionManager")) // The default one should not be created
            
            val tm = context.getBean<PlatformTransactionManager>()
            assertNotNull(tm)
        }
    }

    @Test
    fun `should let a bean created before it inject its TransactionManager as OctaviusJdbcTransactionManager`() {
        contextRunner.withUserConfiguration(TransactionManagerConsumerConfiguration::class.java).run { context ->
            assertNull(context.startupFailure)
            assertSame(context.getBean<OctaviusJdbcTransactionManager>(), context.getBean<TransactionManagerConsumer>().manager)
        }
    }

    @Test
    fun `should back off if a TransactionManager that is not a PlatformTransactionManager is provided`() {
        contextRunner.withUserConfiguration(ReactiveStyleTransactionManagerConfiguration::class.java).run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.containsBean("transactionManager"))
            assertTrue(context.getBeansOfType(PlatformTransactionManager::class.java).isEmpty())
        }
    }

    @Test
    fun `should contribute nothing when several DataSources have no primary`() {
        contextRunner.withUserConfiguration(TwoDataSourcesConfiguration::class.java).run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.containsBean("octaviusTemplate"))
            assertFalse(context.containsBean("transactionManager"))
        }
    }

    @Test
    fun `should build on the primary DataSource when there are several`() {
        contextRunner.withUserConfiguration(TwoDataSourcesConfiguration::class.java, PrimaryDataSourceConfiguration::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.containsBean("octaviusTemplate"))
                val primary = context.getBean("primaryDataSource")
                assertSame(primary, context.getBean<OctaviusJdbcTransactionManager>().dataSource)
            }
    }

    @AutoConfiguration(after = [TransactionAutoConfiguration::class])
    @AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
    open class AfterTransactionAutoConfiguration

    @Configuration
    open class CustomTemplateConfiguration {
        @Bean
        open fun customOctaviusTemplate(dataSource: DataSource): OctaviusTemplate {
            return OctaviusTemplate(dataSource)
        }
    }

    @Configuration
    open class CustomTransactionManagerConfiguration {
        @Bean
        open fun customTransactionManager(dataSource: DataSource): PlatformTransactionManager {
            return DataSourceTransactionManager(dataSource)
        }
    }

    class TransactionManagerConsumer(val manager: OctaviusJdbcTransactionManager)

    // User configuration is instantiated before auto-configuration, so the type has to be known from the declaration.
    @Configuration
    open class TransactionManagerConsumerConfiguration {
        @Bean
        open fun transactionManagerConsumer(manager: OctaviusJdbcTransactionManager) = TransactionManagerConsumer(manager)
    }

    // Stands in for a reactive one, such as R2DBC's: a TransactionManager, but not a PlatformTransactionManager.
    @Configuration
    open class ReactiveStyleTransactionManagerConfiguration {
        @Bean
        open fun customTransactionManager(): TransactionManager = object : TransactionManager {}
    }

    @Configuration
    open class TwoDataSourcesConfiguration {
        @Bean
        open fun senatusDataSource(): DataSource = testDataSource()

        @Bean
        open fun curiaDataSource(): DataSource = testDataSource()
    }

    @Configuration
    open class PrimaryDataSourceConfiguration {
        @Bean
        @Primary
        open fun primaryDataSource(): DataSource = testDataSource()
    }

    companion object {
        private fun testDataSource() = DriverManagerDataSource(TestDatabase.URL, TestDatabase.USER, TestDatabase.PASSWORD)
    }
}
