package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
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
}
