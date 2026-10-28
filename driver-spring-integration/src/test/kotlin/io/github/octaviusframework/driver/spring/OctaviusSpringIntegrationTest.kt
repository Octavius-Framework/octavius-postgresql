package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.TransactionStateException
import io.github.octaviusframework.driver.exception.TransactionStateExceptionReason
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.UnexpectedRollbackException

@ImportAutoConfiguration(OctaviusSpringAutoConfiguration::class, DataSourceAutoConfiguration::class)
@SpringBootTest(classes = [TestApplication::class], properties = [
    TEST_DATASOURCE_URL,
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
    fun `should roll a failed nested transaction back to its savepoint`() {
        testService.createTable()

        testService.insertWithNestedRollback()

        assertEquals(listOf("outer"), committedValues())
    }

    @Test
    fun `should commit a nested transaction with the one around it`() {
        testService.createTable()

        testService.insertWithNestedCommit()

        assertEquals(listOf("nested", "outer"), committedValues())
    }

    @Test
    fun `should roll back a transaction a failed participant marked rollback-only`() {
        testService.createTable()

        assertThrows(UnexpectedRollbackException::class.java) { testService.insertThenSwallowAFailedParticipant() }

        assertEquals(emptyList<String>(), committedValues())
    }

    @Test
    fun `should report a savepoint that could not be taken as the driver's exception`() {
        testService.createTable()

        val ex = assertThrows(TransactionStateException::class.java) { testService.nestedAfterAFailedStatement() }

        assertEquals(TransactionStateExceptionReason.IN_FAILED_TRANSACTION, ex.reason)
    }

    @Test
    fun `should commit a REQUIRES_NEW transaction on its own when the one around it rolls back`() {
        testService.createTable()

        assertThrows(RuntimeException::class.java) { testService.insertAroundANewTransactionThenFail() }

        assertEquals(listOf("inner"), committedValues())
    }

    @Test
    fun `should give a transaction its own session back once a REQUIRES_NEW one inside it ends`() {
        val (outer, inner, resumed) = testService.sessionsAroundANewTransaction()

        assertNotSame(outer, inner)
        assertSame(outer, resumed)
    }

    private fun committedValues(): List<String> =
        octaviusTemplate.execute { createNativeQuery("SELECT val FROM test_spring ORDER BY val").fetchFields<String>() }

    @Test
    fun `should leave octavius exceptions as they are`() {
        val ex = assertThrows(StatementException::class.java) {
            octaviusTemplate.execute {
                createNativeQuery("SELECT * FROM non_existent_table_12345").execute() 
            }
        }
        
        assertEquals("42P01", ex.sqlState) // undefined_table
    }

    @Test
    fun `should handle deferred constraint violation on commit`() {
        testService.createTableWithDeferredConstraint()

        val ex = assertThrows(ConstraintViolationException::class.java) {
            testService.insertWithDeferredConstraintViolation()
        }
        
        assertEquals("23505", ex.sqlState) // unique_violation
    }

    @Test
    fun `should support read only transactions`() {
        testService.createTable()
        
        try {
            testService.insertReadOnly()
        } catch (e: TransactionStateException) {
            assertEquals(TransactionStateExceptionReason.READ_ONLY_TRANSACTION, e.reason)
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

@Configuration
@EnableTransactionManagement
open class TestApplication {
    
    @Bean
    open fun testService(octaviusTemplate: OctaviusTemplate): TestService {
        return TestService(octaviusTemplate)
    }
}

class NestedRollbackException : RuntimeException("Nested rollback")

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
        } catch (_: NestedRollbackException) {
            // Only the nested method's own failure. One from taking the savepoint leaves this method too.
        }
    }

    @Transactional(propagation = Propagation.NESTED)
    open fun nestedRollback() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('nested')").execute() }
        throw NestedRollbackException()
    }

    @Transactional
    open fun insertWithNestedCommit() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('outer')").execute() }
        self.nestedInsert()
    }

    @Transactional(propagation = Propagation.NESTED)
    open fun nestedInsert() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('nested')").execute() }
    }

    @Transactional
    open fun insertThenSwallowAFailedParticipant() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('outer')").execute() }
        try {
            self.insertWithRollback()
        } catch (_: RuntimeException) {
            // Caught, but the transaction it joined is marked rollback-only all the same
        }
    }

    @Transactional
    open fun insertAroundANewTransactionThenFail() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('outer')").execute() }
        self.insertInANewTransaction()
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('resumed')").execute() }
        throw RuntimeException("Rollback")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun insertInANewTransaction() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO test_spring (val) VALUES ('inner')").execute() }
    }

    @Transactional
    open fun sessionsAroundANewTransaction(): Triple<OctaviusSessionOperations, OctaviusSessionOperations, OctaviusSessionOperations> {
        val outer = octaviusTemplate.execute { this }
        val inner = self.sessionInANewTransaction()
        return Triple(outer, inner, octaviusTemplate.execute { this })
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun sessionInANewTransaction(): OctaviusSessionOperations = octaviusTemplate.execute { this }

    @Transactional
    open fun nestedAfterAFailedStatement() {
        try {
            octaviusTemplate.execute { createNativeQuery("INSERT INTO no_such_table (val) VALUES ('outer')").execute() }
        } catch (_: StatementException) {
            // Caught, so the transaction carries on - failed on the server, where a SAVEPOINT is refused
        }
        self.nestedInsert()
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
