package io.github.octaviusframework.driver.transaction

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.TransactionState
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.exception.SQLExceptionWrapper
import io.github.octaviusframework.driver.session.OctaviusSessionImpl
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class TransactionTest : AbstractIntegrationTest() {

    private lateinit var session: OctaviusSession

    @BeforeEach
    fun setup() {
        session = openSession()

        session.createNativeQuery("CREATE TEMP TABLE IF NOT EXISTS test_trx (id INT, value TEXT)").execute()
        session.createNativeQuery("TRUNCATE TABLE test_trx").execute()
    }

    @AfterEach
    fun teardown() {
        try {
            session.close()
        } catch (e: Exception) {}
    }

    private fun countRows(): Long {
        val rows = session.createNativeQuery("SELECT COUNT(*) FROM test_trx").fetchRows()
        return rows[0].get<Long>(0)
    }

    @Test
    fun `test autoCommit false requires explicit commit`() {
        session.autoCommit = false
        assertFalse(session.autoCommit)

        session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()
        assertEquals(1L, countRows())

        session.commit() // This should send COMMIT

        // Verify data is still there outside transaction
        assertEquals(1L, countRows())
    }

    @Test
    fun `test rollback`() {
        session.autoCommit = false

        session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()
        assertEquals(1L, countRows())

        session.rollback()

        // Verify data is not there
        assertEquals(0L, countRows())
    }

    @Test
    fun `test savepoints`() {
        session.autoCommit = false

        session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()

        val sp1 = session.setSavepoint("sp1")

        session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (2, 'B')").execute()

        assertEquals(2L, countRows())

        session.rollback(sp1)

        assertEquals(1L, countRows())

        session.commit()

        assertEquals(1L, countRows())
    }

    @Test
    fun `test release savepoint`() {
        session.autoCommit = false

        session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()

        val sp1 = session.setSavepoint()

        session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (2, 'B')").execute()

        session.releaseSavepoint(sp1)

        session.commit()

        assertEquals(2L, countRows())
    }

    @Test
    fun `test transaction state`() {
        assertEquals(TransactionState.IDLE, session.transactionState)

        session.autoCommit = false

        assertEquals(TransactionState.IN_TRANSACTION, session.transactionState)

        try {
            session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES ('INVALID_INT', 'A')").execute()
        } catch (e: OctaviusException) {
            // Expected syntax error
        }

        assertEquals(TransactionState.FAILED, session.transactionState)

        session.rollback()
        assertEquals(TransactionState.IN_TRANSACTION, session.transactionState)
    }

    @Test
    fun `commit refuses a transaction an earlier error aborted`() {
        session.autoCommit = false
        session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()
        assertThrows<OctaviusException> {
            session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES ('INVALID_INT', 'B')").execute()
        }

        // Sent, the COMMIT would come back as a ROLLBACK and nothing else, with row 1 gone and nobody told.
        val thrown = assertThrows<InvalidOperationException> { session.commit() }

        assertEquals(InvalidOperationExceptionReason.COMMIT_OF_FAILED_TRANSACTION, thrown.reason)
        assertEquals(TransactionState.FAILED, session.transactionState, "nothing was sent; the rollback is still owed")
        session.rollback()
        assertEquals(0L, countRows())
    }

    @Test
    fun `switching auto-commit back on refuses it on the same terms`() {
        session.autoCommit = false
        session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()
        assertThrows<OctaviusException> {
            session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES ('INVALID_INT', 'B')").execute()
        }

        val thrown = assertThrows<InvalidOperationException> { session.autoCommit = true }

        assertEquals(InvalidOperationExceptionReason.COMMIT_OF_FAILED_TRANSACTION, thrown.reason)
        assertFalse(session.autoCommit, "still inside the transaction it could not commit")
        session.rollback()
        session.autoCommit = true
        assertEquals(0L, countRows())
    }

    @Test
    fun `a required block that swallowed a server error fails at its commit`() {
        val thrown = assertThrows<InvalidOperationException> {
            session.transaction.required {
                createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()
                try {
                    createNativeQuery("INSERT INTO test_trx (id, value) VALUES ('INVALID_INT', 'B')").execute()
                } catch (e: OctaviusException) {
                    // Caught and carried on from, so the block returns normally over an aborted transaction.
                }
            }
        }

        assertEquals(InvalidOperationExceptionReason.COMMIT_OF_FAILED_TRANSACTION, thrown.reason)
        assertEquals(0L, countRows())
        assertEquals(true, session.autoCommit)
    }

    @Test
    fun `test transaction manager successful block`() {
        session.transaction.required {
            createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()
        }

        // Verify data was committed
        assertEquals(1L, countRows())
        // Verify autoCommit was restored to true
        assertEquals(true, session.autoCommit)
    }

    @Test
    fun `test transaction manager failing block rolls back`() {
        try {
            session.transaction.required {
                createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()
                throw RuntimeException("Simulated error")
            }
        } catch (e: RuntimeException) {
            assertEquals("Simulated error", e.message)
        }

        // Verify data was rolled back
        assertEquals(0L, countRows())
        // Verify autoCommit was restored to true
        assertEquals(true, session.autoCommit)
    }

    @Test
    fun `test transaction manager nested successful block`() {
        session.transaction.required {
            session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()

            session.transaction.nested {
                session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (2, 'B')").execute()
            }
        }

        // Verify both were committed
        assertEquals(2L, countRows())
    }

    @Test
    fun `test transaction manager nested failing block rolls back to savepoint`() {
        session.transaction.required {
            session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (1, 'A')").execute()

            try {
                session.transaction.nested {
                    session.createNativeQuery("INSERT INTO test_trx (id, value) VALUES (2, 'B')").execute()
                    throw RuntimeException("Simulated error in savepoint")
                }
            } catch (e: RuntimeException) {
                assertEquals("Simulated error in savepoint", e.message)
            }
            
            // Should still be 1 row within transaction after savepoint rollback
            assertEquals(1L, countRows())
        }

        // Verify only the first was committed
        assertEquals(1L, countRows())
    }

    @Test
    fun `test transaction isolation level configurations`() {
        // Test autoCommit = true
        session.autoCommit = true
        session.transactionIsolationLevel = TransactionIsolationLevel.SERIALIZABLE
        assertEquals(TransactionIsolationLevel.SERIALIZABLE, session.transactionIsolationLevel)
        
        session.createNativeQuery("SELECT 1").fetchField<Any?>()
        
        // Allowed after query when autoCommit is true
        session.transactionIsolationLevel = TransactionIsolationLevel.READ_COMMITTED
        assertEquals(TransactionIsolationLevel.READ_COMMITTED, session.transactionIsolationLevel)
        
        // Test autoCommit = false
        session.autoCommit = false
        // Allowed before query
        session.transactionIsolationLevel = TransactionIsolationLevel.REPEATABLE_READ
        assertEquals(TransactionIsolationLevel.REPEATABLE_READ, session.transactionIsolationLevel)
        
        session.createNativeQuery("SELECT 1").fetchField<Any?>()
        
        // Changing after query in a transaction block should throw OctaviusException from PostgreSQL
        assertThrows<OctaviusException> {
            session.transactionIsolationLevel = TransactionIsolationLevel.SERIALIZABLE
        }
    }

    @Test
    fun `test unsupported transaction isolation level`() {
        val internalSession = session as OctaviusSessionImpl
        val wrapper = assertThrows<SQLExceptionWrapper> {
            internalSession.octaviusConnection.transactionIsolation = java.sql.Connection.TRANSACTION_NONE
        }
        val innerEx = wrapper.wrappedException as InvalidOperationException
        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, innerEx.reason)
    }

    // ------------------------------------------- terms scoped to one transaction

    private fun setting(name: String): String =
        session.createNativeQuery("SELECT current_setting($1)").fetchFieldStrict<String>(name)

    @Test
    fun `required applies the isolation level to its own transaction only`() {
        val before = session.transactionIsolationLevel

        val inside = session.transaction.required(isolation = TransactionIsolationLevel.SERIALIZABLE) {
            setting("transaction_isolation")
        }

        assertEquals("serializable", inside)
        // Scoped to the transaction: the session is where it was, with nothing to undo.
        assertEquals(before, session.transactionIsolationLevel)
        assertEquals("read committed", setting("transaction_isolation"))
    }

    @Test
    fun `required applies read-only to its own transaction only`() {
        val inside = session.transaction.required(readOnly = true) {
            setting("transaction_read_only")
        }

        assertEquals("on", inside)
        assertFalse(session.readOnly)
        assertEquals("off", setting("transaction_read_only"))
    }

    @Test
    fun `required applies both timeouts and lets them revert with the transaction`() {
        val inside = session.transaction.required(
            statementTimeout = 7.seconds,
            transactionTimeout = 30.seconds
        ) {
            setting("statement_timeout") to setting("transaction_timeout")
        }

        assertEquals("7s" to "30s", inside)
        assertEquals("0", setting("statement_timeout"))
        assertEquals("0", setting("transaction_timeout"))
    }

    @Test
    fun `all four travel together`() {
        val inside = session.transaction.required(
            isolation = TransactionIsolationLevel.REPEATABLE_READ,
            readOnly = true,
            statementTimeout = 7.seconds,
            transactionTimeout = 30.seconds
        ) {
            listOf(
                setting("transaction_isolation"),
                setting("transaction_read_only"),
                setting("statement_timeout"),
                setting("transaction_timeout")
            )
        }

        assertEquals(listOf("repeatable read", "on", "7s", "30s"), inside)
    }

    @Test
    fun `asking for nothing sends nothing`() {
        val inside = session.transaction.required { setting("transaction_isolation") }

        assertEquals("read committed", inside)
    }

    @Test
    fun `a joined transaction keeps the terms it began at`() {
        val inside = session.transaction.required(isolation = TransactionIsolationLevel.SERIALIZABLE) {
            // Joining, so this asks for terms it is in no position to set: the outer ones stand.
            session.transaction.required(isolation = TransactionIsolationLevel.READ_COMMITTED) {
                setting("transaction_isolation")
            }
        }

        assertEquals("serializable", inside)
    }

    @Test
    fun `a savepoint keeps the terms of the transaction around it`() {
        val inside = session.transaction.required(readOnly = true) {
            session.transaction.nested(readOnly = false) {
                setting("transaction_read_only")
            }
        }

        assertEquals("on", inside)
    }
}
