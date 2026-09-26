package io.github.octaviusframework.client

import io.github.octaviusframework.client.transaction.TransactionPropagation
import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.RoutineAssertionException
import io.github.octaviusframework.driver.exception.RoutineAssertionExceptionReason
import io.github.octaviusframework.driver.exception.RoutineRaiseException
import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.StatementExceptionReason
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the client over a real PostgreSQL: the session seam, the transaction contract, and the line
 * between a failure [dbResult] catches and one it lets through.
 */
class ClientIntegrationTest : AbstractClientIntegrationTest() {

    data class Senator(val id: Int, val cognomen: String, val provinceId: Int)

    override val schema = """
        CREATE TABLE client_senators (
            id          SERIAL PRIMARY KEY,
            cognomen    TEXT NOT NULL UNIQUE,
            province_id INT  NOT NULL
        );
    """.trimIndent()

    @BeforeEach
    fun clearTable() {
        db.rawQuery("TRUNCATE client_senators RESTART IDENTITY").execute()
    }

    private fun countSenators(): Long =
        db.rawQuery("SELECT count(*) FROM client_senators").fetchFieldStrict<Long>()

    private fun cognomina(): List<String> =
        db.rawQuery("SELECT cognomen FROM client_senators ORDER BY cognomen").fetchFields<String>()

    /**
     * Stands in for a repository function: it says nothing about transactions and takes no session, which is
     * the shape the seam exists to make work.
     */
    private fun OctaviusClient.recordSenator(cognomen: String, province: Int): Long =
        rawQuery("INSERT INTO client_senators (cognomen, province_id) VALUES (@c, @p)")
            .update("c" to cognomen, "p" to province)

    // --- The basics -------------------------------------------------------------------------------

    @Test
    fun `a query outside a transaction is a single expression`() {
        val num = db.rawQuery("SELECT 1 AS num").fetchRowStrict().get<Int>("num")
        assertEquals(1, num)
    }

    @Test
    fun `named parameters bind and rows map onto a data class`() {
        db.recordSenator("Cato", 7)
        db.recordSenator("Cicero", 7)
        db.recordSenator("Brutus", 9)

        val senators = db
            .rawQuery("SELECT id, cognomen, province_id FROM client_senators WHERE province_id = @p ORDER BY cognomen")
            .fetchObjects<Senator>("p" to 7)

        assertEquals(listOf("Cato", "Cicero"), senators.map { it.cognomen })
        assertTrue(senators.all { it.provinceId == 7 })
    }

    @Test
    fun `execute is still there for the work that is not a query`() {
        // The session-level path: several statements that have to share one session, and the driver's own API
        // reachable unchanged.
        val path = db.execute {
            createNativeQuery("SET LOCAL TIME ZONE 'UTC'").execute()
            searchPath
        }
        assertTrue(path.isNotEmpty())
    }

    // --- The seam ---------------------------------------------------------------------------------

    @Test
    fun `a query inside a transaction joins it rather than taking a second connection`() {
        // A pool of exactly one. `recordSenator` knows nothing about the transaction around it; if its
        // terminal borrowed a second connection there would be none to borrow, and this would wait forever.
        // Reading its uncommitted row back proves the same thing from the other side.
        TestDatabase.dataSource { maximumPoolSize = 1 }.use { singleConnectionPool ->
            val single = OctaviusClient.fromDataSource(singleConnectionPool)

            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                val seen = single.transaction {
                    recordSenator("Sulla", 1)
                    rawQuery("SELECT count(*) FROM client_senators").fetchFieldStrict<Long>()
                }
                assertEquals(1L, seen)
            }
            single.close()
        }
    }

    @Test
    fun `a repository call rolls back with the transaction that surrounds it`() {
        assertFailsWith<IllegalStateException> {
            db.transaction {
                recordSenator("Clodius", 2)
                error("a bug after the write")
            }
        }

        assertEquals(0L, countSenators(), "the call must have joined the transaction, not committed on its own")
    }

    @Test
    fun `the same call outside a transaction commits on its own`() {
        // The other half of the previous test: identical code, no transaction around it, and it stands.
        db.recordSenator("Pompey", 3)
        assertEquals(listOf("Pompey"), cognomina())
    }

    // --- The transaction contract -----------------------------------------------------------------

    @Test
    fun `a transaction that returns normally commits`() {
        val affected = db.transaction { recordSenator("Crassus", 2) }

        assertEquals(1L, affected)
        assertEquals(1L, countSenators())
    }

    @Test
    fun `REQUIRES_NEW commits on its own and survives the outer rollback`() {
        assertFailsWith<ConstraintViolationException> {
            db.transaction {
                recordSenator("Outer", 4)

                transaction(propagation = TransactionPropagation.REQUIRES_NEW) {
                    recordSenator("Audit", 4)
                }

                // Rolls the outer transaction back: the unique index refuses a second "Outer".
                recordSenator("Outer", 4)
            }
        }

        assertEquals(listOf("Audit"), cognomina(), "the inner transaction committed independently")
    }

    // --- dbResult ---------------------------------------------------------------------------------

    @Test
    fun `dbResult turns a constraint violation into a value`() {
        db.recordSenator("Cato", 7)

        val result = dbResult { db.recordSenator("Cato", 8) }

        assertIs<DataResult.Failure>(result)
        assertIs<ConstraintViolationException>(result.error)
    }

    @Test
    fun `dbResult carries a success through unchanged`() {
        assertEquals(DataResult.Success(1L), dbResult { db.recordSenator("Cato", 7) })
    }

    @Test
    fun `dbResult does not catch SQL the server will not parse`() {
        val thrown = assertFailsWith<StatementException> {
            dbResult { db.rawQuery("SELECT FROM WHERE").fetchRows() }
        }
        assertEquals(StatementExceptionReason.SYNTAX_ERROR, thrown.reason)
    }

    @Test
    fun `dbResult turns a routine's RAISE EXCEPTION into a value`() {
        // The database declining on purpose is a business rule answering, so it comes back as a value.
        val result = dbResult {
            db.rawQuery(
                """
                DO $$
                BEGIN
                    RAISE EXCEPTION 'The augury forbids it';
                END;
                $$
                """.trimIndent()
            ).execute()
        }

        assertIs<DataResult.Failure>(result)
        assertIs<RoutineRaiseException>(result.error)
    }

    @Test
    fun `dbResult does not catch a routine's own failed assertion`() {
        // INTO STRICT is fetchRowStrict written in PL/pgSQL: the routine claimed exactly one row and the data
        // said otherwise, which is the routine being wrong rather than the call not working out.
        val thrown = assertFailsWith<RoutineAssertionException> {
            dbResult {
                db.rawQuery(
                    """
                    DO $$
                    DECLARE
                        found_id INT;
                    BEGIN
                        SELECT id INTO STRICT found_id FROM client_senators WHERE cognomen = 'nobody at all';
                    END;
                    $$
                    """.trimIndent()
                ).execute()
            }
        }
        assertEquals(RoutineAssertionExceptionReason.NO_DATA_FOUND, thrown.reason)
    }

    @Test
    fun `dbResult around a whole transaction reports the failure that rolled it back`() {
        db.recordSenator("Cato", 7)

        val result = dbResult {
            db.transaction {
                recordSenator("Cethegus", 2)
                recordSenator("Cato", 2) // refused by the unique index
            }
        }

        assertIs<DataResult.Failure>(result)
        assertIs<ConstraintViolationException>(result.error)
        assertEquals(listOf("Cato"), cognomina(), "the write before the failure was rolled back")
    }

    // --- asResult ---------------------------------------------------------------------------------

    @Test
    fun `asResult turns a violation into a value without a wrapping lambda`() {
        db.recordSenator("Cato", 7)

        val result = db.rawQuery("INSERT INTO client_senators (cognomen, province_id) VALUES (@c, 8)")
            .asResult()
            .update("c" to "Cato")

        assertIs<DataResult.Failure>(result)
        assertIs<ConstraintViolationException>(result.error)
    }

    @Test
    fun `asResult carries a success through`() {
        db.recordSenator("Cato", 7)

        val senators = db.rawQuery("SELECT id, cognomen, province_id FROM client_senators")
            .asResult()
            .fetchObjects<Senator>()

        assertEquals(listOf("Cato"), senators.getOrThrow().map { it.cognomen })
    }

    @Test
    fun `asResult applies the same boundary and lets a caller bug through`() {
        // Not a softer boundary, only a different shape: what dbResult would throw, this throws too.
        assertFailsWith<StatementException> {
            db.rawQuery("SELECT FROM WHERE").asResult().fetchRows()
        }
        assertFailsWith<InvalidOperationException> {
            db.rawQuery("SELECT id FROM client_senators WHERE cognomen = @c")
                .asResult()
                .fetchRowStrict("c" to "nobody at all")
        }
    }

    // --- transactionResult ------------------------------------------------------------------------

    @Test
    fun `transactionResult commits and returns what the block produced`() {
        val result = db.transactionResult {
            rawQuery("INSERT INTO client_senators (cognomen, province_id) VALUES (@c, 2)")
                .asResult()
                .update("c" to "Crassus")
        }

        assertEquals(DataResult.Success(1L), result)
        assertEquals(1L, countSenators())
    }

    @Test
    fun `transactionResult rolls back on a failure returned from the block`() {
        db.recordSenator("Cato", 7)

        // Captured outside, so the transaction below is in perfect health when the block hands it back.
        // Raising it inside would abort the transaction server-side and PostgreSQL would turn the COMMIT
        // into a ROLLBACK by itself - and this test would pass with the rollback removed.
        val unrelatedFailure = db.rawQuery("INSERT INTO client_senators (cognomen, province_id) VALUES (@c, 8)")
            .asResult()
            .update("c" to "Cato")
        assertIs<DataResult.Failure>(unrelatedFailure)

        val result = db.transactionResult {
            recordSenator("Cethegus", 2)
            unrelatedFailure
        }

        assertIs<DataResult.Failure>(result)
        assertEquals(listOf("Cato"), cognomina(), "the write before the returned failure was rolled back")
    }

    @Test
    fun `transactionResult reports a failure raised inside the block`() {
        db.recordSenator("Cato", 7)

        val result = db.transactionResult<Unit> {
            recordSenator("Cethegus", 2)
            recordSenator("Cato", 2) // refused by the unique index, and thrown rather than returned
            DataResult.Success(Unit)
        }

        assertIs<DataResult.Failure>(result)
        assertIs<ConstraintViolationException>(result.error)
        assertEquals(listOf("Cato"), cognomina())
    }

    @Test
    fun `transactionResult still lets a bug in the block through`() {
        assertFailsWith<IllegalStateException> {
            db.transactionResult<Unit> {
                recordSenator("Clodius", 2)
                error("a bug in the block, not a database failure")
            }
        }

        assertEquals(0L, countSenators())
    }

    // --- Strict is an assertion -------------------------------------------------------------------

    @Test
    fun `a strict fetch that finds no row is thrown, not caught`() {
        // Strict asserts exactly one row. Finding none falsifies that, and a falsified assertion is a
        // defect rather than an outcome - so not even dbResult turns it into a value.
        val thrown = assertFailsWith<InvalidOperationException> {
            dbResult {
                db.rawQuery("SELECT id FROM client_senators WHERE cognomen = @c")
                    .fetchRowStrict("c" to "nobody at all")
            }
        }
        assertEquals(InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE, thrown.reason)
    }

    @Test
    fun `the same lookup written to allow absence returns null rather than throwing`() {
        val row = db.rawQuery("SELECT id FROM client_senators WHERE cognomen = @c")
            .fetchRow("c" to "nobody at all")
        assertNull(row)
    }

    @Test
    fun `a non-nullable field over a missing row is thrown, and a nullable one is not`() {
        assertFailsWith<MappingException> {
            db.rawQuery("SELECT cognomen FROM client_senators WHERE cognomen = @c")
                .fetchField<String>("c" to "nobody at all")
        }

        val absent = db.rawQuery("SELECT cognomen FROM client_senators WHERE cognomen = @c")
            .fetchField<String?>("c" to "nobody at all")
        assertNull(absent)
    }

    @Test
    fun `a row that does not fit the class it was asked for is thrown`() {
        db.recordSenator("Cato", 7)

        assertFailsWith<MappingException> {
            db.rawQuery("SELECT cognomen FROM client_senators").fetchObjects<Senator>()
        }
    }

    // --- Streaming --------------------------------------------------------------------------------

    @Test
    fun `forEachRow walks the result with the session held open`() {
        repeat(5) { db.recordSenator("Senator $it", it) }

        val seen = mutableListOf<String>()
        db.rawQuery("SELECT cognomen FROM client_senators ORDER BY cognomen")
            .forEachRow(fetchSize = 2) { seen += it.get<String>("cognomen") }

        assertEquals(5, seen.size)
    }

    // --- toSql ------------------------------------------------------------------------------------

    @Test
    fun `toSql renders what would be sent`() {
        assertEquals("SELECT 1 AS num", db.rawQuery("SELECT 1 AS num").toSql())
    }

    @Test
    fun `toSql composes into a larger statement, parameters and all`() {
        // The reason toSql is not just a logging getter: a query is a value, and its SQL drops into a WITH,
        // a subquery, an arm of a UNION. Because no query carries its own parameters, the @name placeholders
        // survive the embedding untouched and are bound by whoever runs the outer statement.
        db.recordSenator("Cato", 7)
        db.recordSenator("Cicero", 7)
        db.recordSenator("Brutus", 9)

        val inProvince = db.rawQuery("SELECT cognomen FROM client_senators WHERE province_id = @p")

        val cognomina = db.rawQuery(
            "WITH in_province AS (${inProvince.toSql()}) SELECT cognomen FROM in_province ORDER BY cognomen"
        ).fetchFields<String>("p" to 7)

        assertEquals(listOf("Cato", "Cicero"), cognomina)
    }

    @Test
    fun `an embedded query can be reused under a different binding`() {
        db.recordSenator("Cato", 7)
        db.recordSenator("Brutus", 9)

        val inProvince = db.rawQuery("SELECT cognomen FROM client_senators WHERE province_id = @p")
        val counted = db.rawQuery("SELECT count(*) FROM (${inProvince.toSql()}) AS s")

        assertEquals(1L, counted.fetchFieldStrict<Long>("p" to 7))
        assertEquals(1L, counted.fetchFieldStrict<Long>("p" to 9))
        assertEquals(0L, counted.fetchFieldStrict<Long>("p" to 1))
    }
}
