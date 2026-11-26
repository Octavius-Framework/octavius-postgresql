package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

//--------------------------------------------------CONSUMER 1: MIRROR--------------------------------------------------

/**
 * The senators of the table, in memory, copied once and kept current from the slot. The table is
 * `REPLICA IDENTITY FULL`, so an update's new row carries every column.
 */
class SenatorMirror {
    val senators = ConcurrentHashMap<Int, PgOutputSenator>()

    /** Copies the table as it was when the slot was made, which the stream then carries on from. */
    fun copy(session: OctaviusSessionOperations) =
        session.createNativeQuery("SELECT * FROM senator").fetchObjects<PgOutputSenator>().forEach { senators[it.id] = it }

    /** Applies each transaction until the stream stops; the stream acknowledges what was applied. */
    fun follow(stream: ChangeStream) = stream.forEachTransaction { transaction ->
        for (change in transaction.changes) when (change) {
            is Change.Insert -> senators[change.new["id"]] = change.new.toObject()
            is Change.Update -> {
                val id: Int = change.new["id"]
                senators[id] = change.new.toObject()
                // An update of the key leaves the senator under the old one as well.
                val oldId = change.old?.get<Int>("id")
                if (oldId != null && oldId != id) senators.remove(oldId)
            }
            is Change.Delete -> senators.remove(change.old.get<Int>("id"))
            is Change.Truncate -> senators.clear()
        }
    }
}

//------------------------------------------------CONSUMER 2: FORWARDER-------------------------------------------------

/** A change as a sink is sent it: what happened, to which table, and the columns that came. */
data class ChangeEvent(val kind: String, val table: String, val values: Map<String, Any?>)

/** Sends each committed transaction on to [sink], which answers later, as a broker does. */
class Forwarder(private val sink: (List<ChangeEvent>) -> CompletionStage<*>) {

    /** Sends transactions until the stream stops; the stream acknowledges, in commit order, what the sink has. */
    fun follow(stream: ChangeStream) = stream.forEachTransactionAsync { transaction ->
        sink(transaction.changes.flatMap { events(it) }.toList())
    }

    private fun events(change: Change): List<ChangeEvent> = when (change) {
        is Change.Insert -> listOf(ChangeEvent("insert", change.relation.name, change.new.toObject()))
        is Change.Update -> listOf(ChangeEvent("update", change.relation.name, change.new.toObject()))
        is Change.Delete -> listOf(ChangeEvent("delete", change.relation.name, change.old.toObject()))
        is Change.Truncate -> change.relations.map { ChangeEvent("truncate", it.name, emptyMap()) }
    }
}

//-------------------------------------------------------THE RUNS-------------------------------------------------------

/**
 * Two consumers written against the pgoutput API as an application would write them, to see how it reads.
 *
 * Runs only against a server with `wal_level = logical` - `TEST_LOGICAL_REPLICATION=true`, with
 * `TEST_DATABASE_PORT` pointing at it.
 */
@EnabledIfEnvironmentVariable(named = "TEST_LOGICAL_REPLICATION", matches = "true")
@Timeout(60)
class PgOutputConsumerExampleTest : AbstractIntegrationTest() {

    // The mirror has a publication of its own table; the forwarder sends on two.
    override val schema = """
        DROP PUBLICATION IF EXISTS curia_mirror;
        DROP PUBLICATION IF EXISTS senatus;
        CREATE TYPE legio_status AS ENUM ('ON_CAMPAIGN', 'IN_GARRISON');
        CREATE TABLE senator (id int PRIMARY KEY, cognomen text NOT NULL, toga text, status legio_status);
        ALTER TABLE senator ALTER COLUMN toga SET STORAGE EXTERNAL;
        CREATE TABLE legion (id int PRIMARY KEY, name text);
        CREATE PUBLICATION curia_mirror FOR TABLE senator;
        CREATE PUBLICATION senatus FOR TABLE senator, legion;
    """.trimIndent()

    private val slot = "octavius_test_consumer"

    @BeforeEach
    @AfterEach
    fun dropSlot() {
        openSession().use { session ->
            session.createNativeQuery(
                "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots " +
                    "WHERE slot_name = '$slot' AND NOT temporary"
            ).execute(ignoreRows = true)
        }
    }

    private fun run(sql: String) = openSession().use { it.createNativeQuery(sql).execute(ignoreRows = true) }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (start.elapsedNow() > 10.seconds) error("Not within 10 seconds: $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun `a mirror copies the table through the slot's snapshot and follows its changes`() {
        run("TRUNCATE senator, legion")
        run("INSERT INTO senator VALUES (1, 'Cicero', repeat('x', 5000), 'IN_GARRISON'), (2, 'Cato', NULL, NULL)")
        // For the mirror alone: the forwarder reads the same table as it is.
        run("ALTER TABLE senator REPLICA IDENTITY FULL")
        try {
            val mirror = SenatorMirror()

            val stream = startPgOutput(
                TestDatabase.URL, TestDatabase.properties(),
                slot = slot,
                publications = listOf("curia_mirror"),
                newSlot = NewSlot(temporary = true, copy = { mirror.copy(this) })
            )
            val following = thread { mirror.follow(stream) }

            run("UPDATE senator SET cognomen = 'Marcus Tullius' WHERE id = 1")
            run("UPDATE senator SET id = 3 WHERE id = 2")
            run("INSERT INTO senator VALUES (4, 'Brutus', NULL, 'ON_CAMPAIGN')")
            run("DELETE FROM senator WHERE id = 4")
            run("INSERT INTO legion VALUES (10, 'Legio X')")
            awaitUntil("the mirror caught up") {
                mirror.senators.keys == setOf(1, 3) && mirror.senators[1]?.cognomen == "Marcus Tullius"
            }

            stream.close()
            following.join()

            assertEquals(
                mapOf(
                    1 to PgOutputSenator(1, "Marcus Tullius", "x".repeat(5000), "IN_GARRISON"),
                    3 to PgOutputSenator(3, "Cato", null, null)
                ),
                mirror.senators.toMap()
            )
        } finally {
            run("ALTER TABLE senator REPLICA IDENTITY DEFAULT")
        }
    }

    @Test
    fun `a forwarder has each transaction acknowledged once its sink has it, in commit order`() {
        run("TRUNCATE senator, legion")
        openSession().use { session ->
            session.createNativeQuery("SELECT 1 FROM pg_create_logical_replication_slot('$slot', 'pgoutput')").fetchField<Int>()
        }
        val sent = CopyOnWriteArrayList<List<ChangeEvent>>()
        val broker = Executors.newSingleThreadScheduledExecutor()
        val forwarder = Forwarder { events ->
            CompletableFuture<Unit>().also { done ->
                broker.schedule({ sent += events; done.complete(Unit) }, 200, TimeUnit.MILLISECONDS)
            }
        }

        try {
            val stream = startPgOutput(
                TestDatabase.URL, TestDatabase.properties(),
                slot = slot,
                publications = listOf("senatus"),
                statusInterval = 100.milliseconds
            )
            val following = thread { forwarder.follow(stream) }

            run("INSERT INTO senator VALUES (1, 'Cicero', repeat('x', 5000), NULL)")
            run("UPDATE senator SET cognomen = 'Cato' WHERE id = 1")
            run("BEGIN; DELETE FROM senator WHERE id = 1; INSERT INTO legion VALUES (10, 'Legio X'); COMMIT")
            val after = openSession().use {
                PgLsn.parse(it.createNativeQuery("SELECT pg_current_wal_lsn()::text").fetchField<String>())
            }
            awaitUntil("everything sent and acknowledged") { sent.size == 3 && stream.lastAcknowledgedLsn >= after }

            stream.close()
            following.join()
        } finally {
            broker.shutdownNow()
        }

        assertEquals(
            listOf(listOf("insert senator"), listOf("update senator"), listOf("delete senator", "insert legion")),
            sent.map { batch -> batch.map { "${it.kind} ${it.table}" } }
        )
        assertFalse("toga" in sent[1].single().values, "The toga the update left as it was did not come")
        assertEquals("Cato", sent[1].single().values["cognomen"])
    }
}
