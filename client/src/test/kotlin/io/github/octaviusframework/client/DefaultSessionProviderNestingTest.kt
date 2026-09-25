package io.github.octaviusframework.client

import io.github.octaviusframework.client.transaction.TransactionPropagation
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DefaultSessionProviderNestingTest : AbstractIntegrationTest() {

    override val schema = "CREATE TABLE probe_rows (tag TEXT PRIMARY KEY)"

    private fun pool(size: Int, connectionTimeoutMs: Long = 30_000) = TestDatabase.dataSource {
        maximumPoolSize = size
        connectionTimeout = connectionTimeoutMs
    }

    /** Six rows out of nothing, so a walk has something to walk and the test owns no table. */
    private val SIX_ROWS = "SELECT n FROM generate_series(1, 6) AS n"

    private fun OctaviusClient.pid(): Int = rawQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>()

    @Test
    fun `the outer session comes back after REQUIRES_NEW returns`() {
        pool(3).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                db.transaction {
                    val before = pid()
                    val inner = transaction(propagation = TransactionPropagation.REQUIRES_NEW) { pid() }
                    val after = pid()

                    assertNotEquals(before, inner, "REQUIRES_NEW must run on a session of its own")
                    assertEquals(before, after, "the outer session must be bound again after the inner one ends")
                }
            }
        }
    }

    @Test
    fun `two levels of REQUIRES_NEW unwind in order`() {
        pool(3).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                db.transaction {
                    val l0 = pid()
                    transaction(propagation = TransactionPropagation.REQUIRES_NEW) {
                        val l1 = pid()
                        transaction(propagation = TransactionPropagation.REQUIRES_NEW) {
                            val l2 = pid()
                            assertNotEquals(l1, l2)
                            assertNotEquals(l0, l2)
                        }
                        assertEquals(l1, pid(), "level 1 must be bound again")
                    }
                    assertEquals(l0, pid(), "level 0 must be bound again")
                }
            }
        }
    }

    @Test
    fun `work after a REQUIRES_NEW still rolls back with the outer transaction`() {
        pool(3).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                db.rawQuery("TRUNCATE probe_rows").execute()

                assertFailsWith<IllegalStateException> {
                    db.transaction {
                        transaction(propagation = TransactionPropagation.REQUIRES_NEW) {
                            rawQuery("INSERT INTO probe_rows VALUES ('inner')").update()
                        }
                        // If the binding were lost here this would auto-commit on a borrowed session.
                        rawQuery("INSERT INTO probe_rows VALUES ('after')").update()
                        error("take the outer transaction down")
                    }
                }

                val tags = db.rawQuery("SELECT tag FROM probe_rows ORDER BY tag").fetchFields<String>()
                assertEquals(listOf("inner"), tags, "'after' must have rolled back with the outer transaction")
            }
        }
    }

    // --- One session per thread, not one per level -------------------------------------------------
    //
    // `execute` binds the session it borrows, so nested client calls join it instead of borrowing again.
    // Documented in docs/client/transactions-failures.md#one-session-per-thread-not-one-per-level.

    @Test
    fun `nested client calls share one connection outside a transaction`() {
        pool(3).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                val active = db.execute { db.execute { p.hikariPoolMXBean.activeConnections } }

                assertEquals(1, active, "a bound session is joined rather than borrowed alongside")
            }
        }
    }

    @Test
    fun `nested client calls share one connection inside a transaction too`() {
        pool(3).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                val active = db.transaction { db.execute { db.execute { p.hikariPoolMXBean.activeConnections } } }

                assertEquals(1, active, "the transaction's session is the one every level lands on")
            }
        }
    }

    @Test
    fun `REQUIRES_NEW is the one nesting that does take a second connection`() {
        pool(3).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                val active = db.transaction {
                    db.transaction(propagation = TransactionPropagation.REQUIRES_NEW) {
                        p.hikariPoolMXBean.activeConnections
                    }
                }

                assertEquals(
                    2,
                    active,
                    "a transaction that must outlive the one around it cannot share its connection, so this " +
                        "is the design rather than the accident the binding removed"
                )
            }
        }
    }

    @Test
    fun `nesting runs on a pool of one`() {
        // The regression this binding exists for: each level borrowing its own connection deadlocked here,
        // the outer one holding the only connection while the inner waited for a second.
        pool(1, connectionTimeoutMs = 1_000).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                val one = db.execute { db.rawQuery("SELECT 1 AS one").fetchFieldStrict<Int>() }

                assertEquals(1, one, "one connection is all this needed")
            }
        }
    }

    @Test
    fun `REQUIRES_NEW inside execute still takes a session of its own`() {
        pool(3).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                db.execute {
                    val outer = db.pid()
                    val inner = db.transaction(propagation = TransactionPropagation.REQUIRES_NEW) { db.pid() }

                    assertNotEquals(outer, inner, "REQUIRES_NEW must not join the session execute bound")
                    assertEquals(outer, db.pid(), "the execute session must be bound again afterwards")
                }
            }
        }
    }

    // --- A transaction opened on a session `execute` already bound ----------------------------------

    @Test
    fun `a transaction opened inside execute commits on the bound session`() {
        withProbeTable { db ->
            db.execute {
                db.transaction { db.rawQuery("INSERT INTO probe_rows VALUES ('kept')").update() }
            }

            assertEquals(listOf("kept"), db.rawQuery("SELECT tag FROM probe_rows").fetchFields<String>())
        }
    }

    @Test
    fun `a transaction opened inside execute rolls back on the bound session`() {
        withProbeTable { db ->
            assertFailsWith<IllegalStateException> {
                db.execute {
                    db.transaction {
                        db.rawQuery("INSERT INTO probe_rows VALUES ('gone')").update()
                        error("take it down")
                    }
                }
            }

            assertEquals(
                emptyList(),
                db.rawQuery("SELECT tag FROM probe_rows").fetchFields<String>(),
                "a real transaction was open on the bound session, so the insert went with it"
            )
        }
    }

    @Test
    fun `NESTED inside execute opens a transaction rather than a savepoint`() {
        // There is nothing to take a savepoint in, so this scope is the one that opens the transaction.
        withProbeTable { db ->
            assertFailsWith<IllegalStateException> {
                db.execute {
                    db.transaction(propagation = TransactionPropagation.NESTED) {
                        db.rawQuery("INSERT INTO probe_rows VALUES ('gone')").update()
                        error("take it down")
                    }
                }
            }

            assertEquals(emptyList(), db.rawQuery("SELECT tag FROM probe_rows").fetchFields<String>())
        }
    }

    // --- Querying from inside a result -------------------------------------------------------------
    //
    // A client query made from inside a `forEach*` block runs on the session the walk is reading from,
    // which is busy - so it is refused, and refused the same way whether or not a transaction is open.
    // Documented in docs/client/transactions-failures.md#querying-from-inside-a-result.

    @Test
    fun `a nested query inside forEachRow collides inside a transaction`() {
        assertForEachCollides { db, body -> db.transaction { body() } }
    }

    @Test
    fun `a nested query inside forEachRow collides outside one as well`() {
        assertForEachCollides { _, body -> body() }
    }

    private fun assertForEachCollides(around: (OctaviusClient, () -> Unit) -> Unit) {
        pool(3).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                val seen = mutableListOf<Int>()

                val failure = assertFailsWith<InvalidOperationException> {
                    around(db) {
                        db.rawQuery(SIX_ROWS).forEachRow(fetchSize = 2) { row ->
                            seen += row.get<Int>("n")
                            db.rawQuery("SELECT 1 AS one").fetchFieldStrict<Int>()
                        }
                    }
                }

                assertEquals(
                    InvalidOperationExceptionReason.CONNECTION_BUSY,
                    failure.reason,
                    "the nested query lands on the bound session, which is still reading the result"
                )
                assertEquals(1, seen.size, "the walk stops at the row the nested query was made from")
                assertEquals(
                    0,
                    p.hikariPoolMXBean.activeConnections,
                    "nothing was left checked out by the attempt"
                )
            }
        }
    }

    private fun withProbeTable(body: (OctaviusClient) -> Unit) {
        pool(3).use { p ->
            OctaviusClient.fromDataSource(p).use { db ->
                db.rawQuery("TRUNCATE probe_rows").execute()
                body(db)
            }
        }
    }
}
