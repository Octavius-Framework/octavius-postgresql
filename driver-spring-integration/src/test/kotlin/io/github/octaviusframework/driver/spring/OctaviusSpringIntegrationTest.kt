package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.TransactionStateException
import io.github.octaviusframework.driver.exception.TransactionStateExceptionReason
import io.github.octaviusframework.driver.spring.exception.OctaviusDataAccessException
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation

@SpringBootTest(classes = [TestApplication::class, OctaviusSpringAutoConfiguration::class, DataSourceAutoConfiguration::class], properties = [
    "spring.datasource.url=${TestDatabase.URL}",
    "spring.datasource.username=${TestDatabase.USER}",
    "spring.datasource.password=${TestDatabase.PASSWORD}",
    "spring.datasource.driver-class-name=io.github.octaviusframework.driver.jdbc.OctaviusDriver"
])
class OctaviusSpringIntegrationTest {

    @Autowired
    lateinit var octaviusTemplate: OctaviusTemplate

    @Autowired
    lateinit var testService: TestService

    @Test
    fun `should autoconfigure OctaviusTemplate`() {
        assertNotNull(octaviusTemplate)
        val row = octaviusTemplate.execute { createNativeQuery("SELECT 1 as num").fetchRowStrict() }
        assertEquals(1, row.get<Int>("num"))
    }

    @Test
    fun `should work within transactions`() {
        testService.createTable()
        
        try {
            testService.insertWithRollback()
        } catch (e: RuntimeException) {
            // expected
        }

        val count = octaviusTemplate.execute { createNativeQuery("SELECT count(*) as c FROM test_spring").fetchRowStrict().get<Long>("c") }
        assertEquals(0L, count)
        
        testService.insertWithCommit()
        val countAfterCommit = octaviusTemplate.execute { createNativeQuery("SELECT count(*) as c FROM test_spring").fetchRowStrict().get<Long>("c") }
        assertEquals(1L, countAfterCommit)
    }
    
    @Test
    fun `should work with nested transactions`() {
        testService.createTable()
        
        try {
            testService.insertWithNestedRollback()
        } catch (e: RuntimeException) {
            // expected
        }
        
        val count = octaviusTemplate.execute { createNativeQuery("SELECT count(*) as c FROM test_spring").fetchRowStrict().get<Long>("c") }
        assertEquals(1L, count) // Outer insert should be there, nested should be rolled back
    }

    @Test
    fun `should translate octavius exceptions to OctaviusDataAccessException`() {
        val ex = assertThrows(OctaviusDataAccessException::class.java) {
            octaviusTemplate.execute {
                createNativeQuery("SELECT * FROM non_existent_table_12345").execute() 
            }
        }
        
        assertNotNull(ex.octaviusException)
        assertTrue(ex.octaviusException is StatementException)
        assertEquals("42P01", (ex.octaviusException as StatementException).sqlState) // undefined_table
    }

    @Test
    fun `should handle deferred constraint violation on commit`() {
        testService.createTableWithDeferredConstraint()

        val ex = assertThrows(OctaviusDataAccessException::class.java) {
            testService.insertWithDeferredConstraintViolation()
        }
        
        assertNotNull(ex.octaviusException)
        assertTrue(ex.octaviusException is ConstraintViolationException)
        assertEquals("23505", (ex.octaviusException as ConstraintViolationException).sqlState) // unique_violation
    }

    @Test
    fun `should support read only transactions`() {
        testService.createTable()
        
        try {
            testService.insertReadOnly()
        } catch (e: OctaviusDataAccessException) {
            val root = assertInstanceOf<TransactionStateException>(e.octaviusException)
            assertEquals(TransactionStateExceptionReason.READ_ONLY_TRANSACTION, root.reason)
        }
        
        val count = octaviusTemplate.execute { createNativeQuery("SELECT count(*) as c FROM test_spring").fetchRowStrict().get<Long>("c") }
        assertEquals(0L, count) 
    }

    @Test
    fun `should support serializable isolation level`() {
        testService.createTable()
        
        testService.insertSerializable()
        
        val count = octaviusTemplate.execute { createNativeQuery("SELECT count(*) as c FROM test_spring").fetchRowStrict().get<Long>("c") }
        assertEquals(1L, count)
    }
}

@TestConfiguration
@EnableTransactionManagement
open class TestApplication {
    
    @Bean
    open fun testService(octaviusTemplate: OctaviusTemplate): TestService {
        return TestService(octaviusTemplate)
    }
}

open class TestService(private val octaviusTemplate: OctaviusTemplate) {

    open fun createTable() {
        octaviusTemplate.execute { createNativeQuery("CREATE TABLE IF NOT EXISTS test_spring (id SERIAL PRIMARY KEY, val TEXT)").execute() }
        octaviusTemplate.execute { createNativeQuery("TRUNCATE test_spring").execute() }
    }

    open fun createTableWithDeferredConstraint() {
        octaviusTemplate.execute {
            createNativeQuery("CREATE TABLE IF NOT EXISTS test_deferred (id INT, CONSTRAINT unique_id UNIQUE (id) DEFERRABLE INITIALLY DEFERRED)").execute()
            createNativeQuery("TRUNCATE test_deferred").execute()
        }
    }

    @Transactional
    open fun insertWithRollback() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('test')").execute() }
        throw RuntimeException("Rollback")
    }

    @Transactional
    open fun insertWithCommit() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('test')").execute() }
    }
    
    @Transactional
    open fun insertWithDeferredConstraintViolation() {
        octaviusTemplate.execute {
            createNativeQuery("INSERT INTO test_deferred (id) VALUES (1)").execute()
            createNativeQuery("INSERT INTO test_deferred (id) VALUES (1)").execute()
        }
    }

    @Lazy
    @Autowired
    lateinit var self: TestService

    @Transactional
    open fun insertWithNestedRollback() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('outer')").execute() }
        try {
            self.nestedRollback()
        } catch (e: RuntimeException) {
            // caught
        }
    }

    @Transactional(propagation = Propagation.NESTED)
    open fun nestedRollback() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('nested')").execute() }
        throw RuntimeException("Nested rollback")
    }

    @Transactional(readOnly = true)
    open fun insertReadOnly() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('readonly')").execute() }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    open fun insertSerializable() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('serializable')").execute() }
    }
}
