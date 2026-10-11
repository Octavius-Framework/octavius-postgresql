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
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class TransactionTest : AbstractIntegrationTest() {

    private lateinit var session: OctaviusSession

    @BeforeEach
    fun setup() {
        session = openSession()

        session.createNativeQuery("CREATE TEMP TABLE IF NOT EXISTS tributes (id INT, province TEXT)").execute()
        session.createNativeQuery("TRUNCATE TABLE tributes").execute()
    }

    @AfterEach
    fun teardown() {
        try {
            session.close()
        } catch (e: Exception) {}
    }

    private fun countRows(): Long {
        val rows = session.createNativeQuery("SELECT COUNT(*) FROM tributes").fetchRows()
        return rows[0].get<Long>(0)
    }

    @Test
    fun `test autoCommit false requires explicit commit`() {
        session.autoCommit = false
        assertFalse(session.autoCommit)

        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
        assertEquals(1L, countRows())

        session.commit() // This should send COMMIT

        // Verify data is still there outside transaction
        assertEquals(1L, countRows())
    }

    @Test
    fun `test rollback`() {
        session.autoCommit = false

        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
        assertEquals(1L, countRows())

        session.rollback()

        // Verify data is not there
        assertEquals(0L, countRows())
    }

    @Test
    fun `test savepoints`() {
        session.autoCommit = false

        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()

        val sp1 = session.setSavepoint("rubicon")

        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (2, 'Hispania')").execute()

        assertEquals(2L, countRows())

        session.rollback(sp1)

        assertEquals(1L, countRows())

        session.commit()

        assertEquals(1L, countRows())
    }

    @Test
    fun `test release savepoint`() {
        session.autoCommit = false

        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()

        val sp1 = session.setSavepoint()

        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (2, 'Hispania')").execute()

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
            session.createNativeQuery("INSERT INTO tributes (id, province) VALUES ('XLII', 'Gallia')").execute()
        } catch (e: OctaviusException) {
            // Expected syntax error
        }

        assertEquals(TransactionState.FAILED, session.transactionState)

        session.rollback()
        assertEquals(TransactionState.IN_TRANSACTION, session.transactionState)
    }

    // ------------------------------------------- the BEGIN goes out with the first statement

    private fun backendPid(): Int = session.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict()

    /** What `pg_stat_activity` says the backend [pid] is doing, and the last statement it ran - asked from elsewhere. */
    private fun backend(pid: Int): Pair<String, String> = openSession().use { observer ->
        observer.createNativeQuery("SELECT state, query FROM pg_stat_activity WHERE pid = $1")
            .fetchRows(pid).single().let { it.get<String>(0) to it.get<String>(1) }
    }

    @Test
    fun `leaving auto-commit sends nothing until the first statement`() {
        val pid = backendPid()

        session.autoCommit = false

        assertEquals(TransactionState.IN_TRANSACTION, session.transactionState, "open from the caller's side")
        assertEquals("idle" to "SELECT pg_backend_pid()", backend(pid), "and not yet on the server's")

        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
        assertEquals("idle in transaction", backend(pid).first)

        session.rollback()
        assertEquals(0L, countRows())
    }

    @Test
    fun `a transaction that runs no statement is never sent`() {
        val pid = backendPid()

        session.autoCommit = false
        session.commit()
        session.rollback()
        session.autoCommit = true
        session.transaction.required(readOnly = true, statementTimeout = 5.seconds) { }
        session.transaction.nested(isolation = TransactionIsolationLevel.SERIALIZABLE) { }

        assertEquals("idle" to "SELECT pg_backend_pid()", backend(pid))
    }

    @Test
    fun `commit and rollback leave nothing open on the server`() {
        val pid = backendPid()
        session.autoCommit = false

        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
        session.commit()
        assertEquals("idle" to "COMMIT", backend(pid))
        assertEquals(TransactionState.IN_TRANSACTION, session.transactionState)

        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (2, 'Hispania')").execute()
        session.rollback()
        assertEquals("idle" to "ROLLBACK", backend(pid))

        session.autoCommit = true
        assertEquals("idle" to "ROLLBACK", backend(pid), "nothing was open, so nothing to commit")
        assertEquals(1L, countRows())
    }

    @Test
    fun `now() after a commit is the time of the next transaction, not of the commit`() {
        session.autoCommit = false
        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
        session.commit()

        // Taken on the server's own clock, after the commit and before anything else reaches this session.
        val afterCommit = openSession().use { it.createNativeQuery("SELECT clock_timestamp()::text").fetchFieldStrict<String>() }

        val nowIsLater = session.createNativeQuery("SELECT now() >= $1::text::timestamptz").fetchFieldStrict<Boolean>(afterCommit)
        assertTrue(nowIsLater, "now() belongs to a transaction the commit had already opened")
        session.rollback()
    }

    @Test
    fun `a savepoint can be what begins the transaction`() {
        session.autoCommit = false
        val savepoint = session.setSavepoint()
        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
        session.releaseSavepoint(savepoint)

        session.rollback()
        assertEquals(0L, countRows())
    }

    @Test
    fun `an isolation level set while the BEGIN waits reaches the transaction`() {
        session.autoCommit = false
        session.transactionIsolationLevel = TransactionIsolationLevel.SERIALIZABLE

        assertEquals("serializable", setting("transaction_isolation"))
        session.rollback()
    }

    @Test
    fun `a validation probe does not begin the waiting transaction`() {
        val pid = backendPid()
        session.autoCommit = false

        assertTrue(session.isValid(1))

        assertEquals("idle", backend(pid).first)
    }

    @Test
    fun `a term the server refuses fails the first statement and leaves auto-commit on`() {
        // Past the int milliseconds statement_timeout is kept in, so the SET LOCAL after the BEGIN is refused.
        assertThrows<OctaviusException> {
            session.transaction.required(statementTimeout = 30.days) {
                createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
            }
        }

        assertTrue(session.autoCommit)
        assertEquals(TransactionState.IDLE, session.transactionState)
        assertEquals(0L, countRows())
    }

    @Test
    fun `a term refused on a transaction a hand-written BEGIN opened leaves auto-commit on as well`() {
        // No BEGIN of the driver's is coming here, so the terms go out as the scope is entered rather than with
        // the first statement - and the refusal has to be rolled back all the same.
        session.createNativeQuery("BEGIN").execute()

        assertThrows<OctaviusException> {
            session.transaction.required(statementTimeout = 30.days) {
                createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
            }
        }

        assertTrue(session.autoCommit)
        assertEquals(TransactionState.IDLE, session.transactionState)
        assertEquals(0L, countRows())
    }

    @Test
    fun `whichever terminal begins the transaction reads its own answer`() {
        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia'), (2, 'Hispania')").execute()
        session.autoCommit = false

        val streamed = mutableListOf<Int>()
        session.createNativeQuery("SELECT id FROM tributes ORDER BY id").forEachField<Int>(fetchSize = 1) { streamed += it }
        session.rollback()
        val fetched = session.createNativeQuery("SELECT id FROM tributes ORDER BY id").fetchFields<Int>()
        session.rollback()
        val updated = session.createNativeQuery("UPDATE tributes SET province = upper(province)").update()
        session.rollback()
        session.createNativeQuery("DELETE FROM tributes").execute()
        session.rollback()
        session.autoCommit = true

        assertEquals(listOf(1, 2), streamed)
        assertEquals(listOf(1, 2), fetched)
        assertEquals(2L, updated)
        assertEquals(2L, countRows())
    }

    @Test
    fun `a refused term is what the first statement throws, whichever terminal it is`() {
        val terminals = listOf<OctaviusSessionOperations.() -> Unit>(
            { createNativeQuery("SELECT 1").forEachRow(fetchSize = 1) { } },
            { createNativeQuery("SELECT 1").fetchRows() },
            { createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").update() },
            { createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute() }
        )

        for (terminal in terminals) {
            val thrown = assertThrows<OctaviusException> {
                session.transaction.required(statementTimeout = 30.days) { terminal(this) }
            }

            // The term's own refusal, not the 25P02 the statement drew from the transaction it failed
            assertEquals("22023", thrown.sqlState)
            assertTrue(session.autoCommit)
        }
        assertEquals(0L, countRows())
    }

    @Test
    fun `a term the server cannot parse leaves the first statement refused rather than run alone`() {
        val connection = (session as OctaviusSessionImpl).octaviusConnection
        val statements = listOf<OctaviusSessionOperations.() -> Unit>(
            { createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").update() },
            { createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute() }
        )

        for (statement in statements) {
            session.autoCommit = false
            connection.deferTransactionTerms("SET LOCAL statement_timeout =")

            val thrown = assertThrows<OctaviusException> { statement(session) }

            assertEquals("42601", thrown.sqlState)
            session.rollback()
            session.autoCommit = true
        }
        assertEquals(0L, countRows())
    }

    @Test
    fun `commit refuses a transaction an earlier error aborted`() {
        session.autoCommit = false
        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
        assertThrows<OctaviusException> {
            session.createNativeQuery("INSERT INTO tributes (id, province) VALUES ('XLII', 'Hispania')").execute()
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
        session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
        assertThrows<OctaviusException> {
            session.createNativeQuery("INSERT INTO tributes (id, province) VALUES ('XLII', 'Hispania')").execute()
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
                createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
                try {
                    createNativeQuery("INSERT INTO tributes (id, province) VALUES ('XLII', 'Hispania')").execute()
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
            createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
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
                createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()
                throw RuntimeException("The legion mutinied")
            }
        } catch (e: RuntimeException) {
            assertEquals("The legion mutinied", e.message)
        }

        // Verify data was rolled back
        assertEquals(0L, countRows())
        // Verify autoCommit was restored to true
        assertEquals(true, session.autoCommit)
    }

    @Test
    fun `test transaction manager nested successful block`() {
        session.transaction.required {
            session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()

            session.transaction.nested {
                session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (2, 'Hispania')").execute()
            }
        }

        // Verify both were committed
        assertEquals(2L, countRows())
    }

    @Test
    fun `test transaction manager nested failing block rolls back to savepoint`() {
        session.transaction.required {
            session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (1, 'Gallia')").execute()

            try {
                session.transaction.nested {
                    session.createNativeQuery("INSERT INTO tributes (id, province) VALUES (2, 'Hispania')").execute()
                    throw RuntimeException("The cohort broke ranks")
                }
            } catch (e: RuntimeException) {
                assertEquals("The cohort broke ranks", e.message)
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
