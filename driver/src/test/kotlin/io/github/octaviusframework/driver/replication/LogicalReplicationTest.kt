package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Streams a logical slot through `test_decoding`, whose output is text one can read.
 *
 * Runs only against a server with `wal_level = logical` - `TEST_LOGICAL_REPLICATION=true`, with
 * `TEST_DATABASE_PORT` pointing at it. `scripts/test-server.ps1 start <name> ... -LogicalReplication` stands one up.
 */
@EnabledIfEnvironmentVariable(named = "TEST_LOGICAL_REPLICATION", matches = "true")
@Timeout(60)
class LogicalReplicationTest : AbstractIntegrationTest() {

    override val schema = "CREATE TABLE senator (id int PRIMARY KEY, cognomen text NOT NULL)"

    private val slot = "octavius_test_slot"

    /** Stable output: no transaction ids, and nothing for a transaction that changed no table. */
    private val plainOutput = mapOf("include-xids" to "0", "skip-empty-xacts" to "1")

    @BeforeEach
    fun createSlot() {
        // A run that was killed leaves its slot behind.
        dropSlot()
        openSession().use { session ->
            session.createNativeQuery("TRUNCATE senator").execute()
            session.createNativeQuery("SELECT 1 FROM pg_create_logical_replication_slot('$slot', 'test_decoding')")
                .fetchField<Int>()
        }
    }

    @AfterEach
    fun dropSlot() {
        // A slot is not a schema object, so the reset of the next class would leave it, holding WAL back for good.
        openSession().use { session ->
            session.createNativeQuery(
                "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = '$slot'"
            ).execute(ignoreRows = true)
        }
    }

    private fun start(
        slot: String = this.slot,
        statusInterval: Duration = 10.seconds,
        configure: OctaviusProperties.() -> Unit = {}
    ): LogicalReplicationStream =
        startLogical(TestDatabase.URL, TestDatabase.properties(configure), slot, plainOutput, statusInterval = statusInterval)

    private fun run(sql: String) = openSession().use { it.createNativeQuery(sql).execute() }

    private fun LogicalReplicationStream.nextText(): String =
        (poll(10.seconds) ?: error("Nothing arrived within 10 seconds")).payload.decodeToString()

    /** The messages up to the next `COMMIT`, and the position of that `COMMIT`. */
    private fun LogicalReplicationStream.nextTransaction(): Pair<List<String>, PgLsn> {
        val texts = mutableListOf<String>()
        while (true) {
            val data = poll(10.seconds) ?: error("No COMMIT within 10 seconds, after $texts")
            val text = data.payload.decodeToString()
            texts += text
            if (text == "COMMIT") return texts to data.lsn
        }
    }

    @Test
    fun `a stream delivers committed transactions in commit order, and no rolled-back one`() {
        run("INSERT INTO senator VALUES (1, 'Cicero')")
        run("BEGIN; INSERT INTO senator VALUES (99, 'Catilina'); ROLLBACK")
        run("BEGIN; INSERT INTO senator VALUES (2, 'Cato'); INSERT INTO senator VALUES (3, 'Brutus'); COMMIT")

        start().use { stream ->
            assertEquals(
                listOf("BEGIN", "table public.senator: INSERT: id[integer]:1 cognomen[text]:'Cicero'", "COMMIT"),
                stream.nextTransaction().first
            )
            assertEquals(
                listOf(
                    "BEGIN",
                    "table public.senator: INSERT: id[integer]:2 cognomen[text]:'Cato'",
                    "table public.senator: INSERT: id[integer]:3 cognomen[text]:'Brutus'",
                    "COMMIT"
                ),
                stream.nextTransaction().first
            )
            assertNull(stream.poll(500.milliseconds), "A rolled-back transaction must not be delivered")
            assertTrue(stream.isActive)
        }
    }

    @Test
    fun `an acknowledged transaction is not delivered again, and one not acknowledged is`() {
        run("INSERT INTO senator VALUES (1, 'Cicero')")
        run("INSERT INTO senator VALUES (2, 'Cato')")

        val firstCommit = start().use { stream ->
            val (first, commit) = stream.nextTransaction()
            assertEquals("table public.senator: INSERT: id[integer]:1 cognomen[text]:'Cicero'", first[1])
            stream.acknowledge(commit)
            // The second transaction is read, but not acknowledged.
            stream.nextTransaction()
            commit
        }

        val confirmed = openSession().use { session ->
            session.createNativeQuery(
                "SELECT confirmed_flush_lsn::text FROM pg_replication_slots WHERE slot_name = '$slot'"
            ).fetchField<String>()
        }
        assertEquals(firstCommit, PgLsn.parse(confirmed))

        start().use { stream ->
            val (texts, _) = stream.nextTransaction()
            assertEquals("table public.senator: INSERT: id[integer]:2 cognomen[text]:'Cato'", texts[1])
        }
    }

    @Test
    fun `a stream being read outlives a quiet spell longer than wal_sender_timeout`() {
        start(statusInterval = 200.milliseconds) { additionalProperties["options"] = "-c wal_sender_timeout=1s" }.use { stream ->
            assertNull(stream.poll(3.seconds))
            assertTrue(stream.isActive)

            run("INSERT INTO senator VALUES (1, 'Cicero')")
            assertEquals("BEGIN", stream.nextText())
        }
    }

    @Test
    fun `a stream nobody reads outlives wal_sender_timeout`() {
        start(statusInterval = 200.milliseconds) { additionalProperties["options"] = "-c wal_sender_timeout=1s" }.use { stream ->
            Thread.sleep(3000)
            assertTrue(stream.isActive)

            run("INSERT INTO senator VALUES (1, 'Cicero')")
            assertEquals("BEGIN", stream.nextText())
        }
    }

    @Test
    fun `close from another thread ends a read that waits for data, and gives the slot back`() {
        val stream = start()
        val reader = CompletableFuture.supplyAsync { stream.read() }

        Thread.sleep(300)
        stream.close()

        assertNull(reader.get(5, TimeUnit.SECONDS))
        assertFalse(stream.isActive)

        run("INSERT INTO senator VALUES (1, 'Cicero')")
        start().use { assertEquals("BEGIN", it.nextText()) }
    }

    @Test
    fun `a slot that does not exist raises the server's error`() {
        val e = assertFailsWith<StatementException> { start("no_such_slot") }
        assertEquals("42704", e.sqlState)
    }
}
