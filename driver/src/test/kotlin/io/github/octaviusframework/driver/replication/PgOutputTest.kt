package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class PgOutputSenator(val id: Int, val cognomen: String, val toga: String?, val status: String?)

/** A senator without the toga, a column it has no property for. */
data class SenatorName(val id: Int, val cognomen: String)

class Cognomen(val value: String)

/** Claims only [Cognomen], which nothing else produces. */
object CognomenConverter : ResultConverter<String, Cognomen> {
    override val supportedSourceClass = String::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
        expectedType.classifier == Cognomen::class

    override fun convert(source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
        Cognomen(source)
}

class Titulus(val value: String)

/** Claims only [Titulus], and is registered on a stream alone, never on the catalog. */
object TitulusConverter : ResultConverter<String, Titulus> {
    override val supportedSourceClass = String::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
        expectedType.classifier == Titulus::class

    override fun convert(source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
        Titulus(source)
}

/** A `forEach` run on a thread of its own, and what it threw. */
private class Consuming(run: () -> Unit) {
    private val failure = AtomicReference<Throwable?>()
    private val thread = thread { try { run() } catch (e: Throwable) { failure.set(e) } }

    /** Waits for the run to end, and returns what it threw. */
    fun end(): Throwable? {
        thread.join(10_000)
        check(!thread.isAlive) { "The forEach did not return within 10 seconds" }
        return failure.get()
    }
}

/**
 * Streams a logical slot through pgoutput, decoded into transactions.
 *
 * Runs only against a server with `wal_level = logical` - `TEST_LOGICAL_REPLICATION=true`, with
 * `TEST_DATABASE_PORT` pointing at it. `scripts/test-server.ps1 start <name> ... -LogicalReplication` stands one up.
 */
@EnabledIfEnvironmentVariable(named = "TEST_LOGICAL_REPLICATION", matches = "true")
@Timeout(60)
class PgOutputTest : AbstractIntegrationTest() {

    // A publication belongs to the database, not to a schema, so the reset that drops the tables leaves it.
    override val schema = """
        DROP PUBLICATION IF EXISTS senatus;
        CREATE TYPE legio_status AS ENUM ('ON_CAMPAIGN', 'IN_GARRISON');
        CREATE DOMAIN cognomen AS text CHECK (VALUE <> '');
        CREATE TABLE senator (id int PRIMARY KEY, cognomen cognomen NOT NULL, toga text, status legio_status);
        ALTER TABLE senator ALTER COLUMN toga SET STORAGE EXTERNAL;
        CREATE TABLE legion (id int PRIMARY KEY, name text, motto text);
        ALTER TABLE legion REPLICA IDENTITY FULL;
        ALTER TABLE legion ALTER COLUMN motto SET STORAGE EXTERNAL;
        CREATE TABLE tribute (id serial PRIMARY KEY, amount int);
        CREATE TABLE edictum (id int PRIMARY KEY);
        CREATE PUBLICATION senatus FOR TABLE senator, legion, edictum;
    """.trimIndent()

    private val slot = "octavius_test_pgoutput"

    /** One a test has [startPgOutput] create. */
    private val madeSlot = "octavius_test_pgoutput_made"

    private val publications = listOf("senatus")

    /** The test database under another name, which the catalog is not kept under. */
    private val otherUrl = "jdbc:octavius://127.0.0.1:${TestDatabase.PORT}/${TestDatabase.DATABASE}"

    /** Another database of the same server. */
    private val otherDatabaseUrl = "jdbc:octavius://${TestDatabase.HOST}:${TestDatabase.PORT}/postgres"

    @BeforeEach
    fun createSlot() {
        dropSlot()
        run("TRUNCATE senator, legion, tribute, edictum")
        openSession().use { session ->
            session.createNativeQuery("SELECT 1 FROM pg_create_logical_replication_slot('$slot', 'pgoutput')")
                .fetchField<Int>()
        }
    }

    @AfterEach
    fun dropSlot() {
        // A temporary slot goes with its stream.
        openSession().use { session ->
            session.createNativeQuery(
                "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots " +
                    "WHERE slot_name IN ('$slot', '$madeSlot') AND NOT temporary"
            ).execute(ignoreRows = true)
        }
        GlobalCatalogStore.removeCatalog(otherUrl)
        GlobalCatalogStore.removeCatalog(otherDatabaseUrl)
    }

    private fun run(sql: String) = openSession().use { it.createNativeQuery(sql).execute(ignoreRows = true) }

    /** A stream of the class's slot. */
    private fun start(
        url: String = TestDatabase.URL,
        statusInterval: Duration = 10.seconds,
        typeSource: DataSource? = null,
        configure: OctaviusProperties.() -> Unit = {}
    ): ChangeStream = startPgOutput(
        url, TestDatabase.properties(configure), slot, publications,
        statusInterval = statusInterval, typeSource = typeSource
    )

    /** Whether the slot [name] exists, and is temporary; `null` where it does not exist. */
    private fun temporary(name: String): Boolean? = openSession().use { session ->
        session.createNativeQuery("SELECT temporary FROM pg_replication_slots WHERE slot_name = $1")
            .fetchFields<Boolean>(name).firstOrNull()
    }

    private fun confirmedFlush(): PgLsn = openSession().use { session ->
        PgLsn.parse(
            session.createNativeQuery("SELECT confirmed_flush_lsn::text FROM pg_replication_slots WHERE slot_name = $1")
                .fetchField<String>(slot)
        )
    }

    private fun currentLsn(): PgLsn = openSession().use { session ->
        PgLsn.parse(session.createNativeQuery("SELECT pg_current_wal_lsn()::text").fetchField<String>())
    }

    /** Whether [condition] comes to hold within [time]. */
    private fun holdsWithin(time: Duration, condition: () -> Boolean): Boolean {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < time) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    /** The changes of the next [count] transactions, after which the stream is closed. */
    private fun ChangeStream.collect(count: Int): List<List<Change>> {
        val seen = mutableListOf<List<Change>>()
        val watchdog = thread {
            try {
                Thread.sleep(10_000)
                close()
            } catch (_: InterruptedException) {
            }
        }
        try {
            forEachTransaction { transaction ->
                seen += transaction.changes.toList()
                if (seen.size == count) close()
            }
        } finally {
            watchdog.interrupt()
        }
        check(seen.size == count) { "${seen.size} of $count transactions arrived within 10 seconds" }
        return seen
    }

    /** The changes of the next transaction, from a stream of its own. */
    private fun nextTransaction(url: String = TestDatabase.URL, typeSource: DataSource? = null): List<Change> =
        start(url, typeSource = typeSource).use { it.collect(1).single() }

    @Test
    fun `inserts, updates and deletes arrive as rows of their relation`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', 'praetexta', 'IN_GARRISON')")
        run("UPDATE senator SET status = 'ON_CAMPAIGN' WHERE id = 1")
        run("DELETE FROM senator WHERE id = 1")

        val (inserted, updated, deleted) = start().use { it.collect(3) }

        val insert = assertIs<Change.Insert>(inserted.single())
        assertEquals("public", insert.relation.schema)
        assertEquals("senator", insert.relation.name)
        assertEquals(ReplicaIdentity.DEFAULT, insert.relation.replicaIdentity)
        assertEquals(listOf("id"), insert.relation.keyColumns)
        assertEquals(PgOutputSenator(1, "Cicero", "praetexta", "IN_GARRISON"), insert.new.toObject<PgOutputSenator>())
        val queried = openSession().use { it.createNativeQuery("SELECT 'Cicero'::cognomen").fetchRowStrict() }
        assertEquals(queried.getOid(0), insert.new.getOid(1), "A domain column is described as a query describes it")

        val update = assertIs<Change.Update>(updated.single())
        assertNull(update.old, "An update that keeps the key carries no old row")
        assertEquals("ON_CAMPAIGN", update.new.get<String>("status"))
        assertEquals(emptySet(), update.unchanged)
        assertEquals(PgOutputSenator(1, "Cicero", "praetexta", "ON_CAMPAIGN"), update.new.toObject<PgOutputSenator>())

        val delete = assertIs<Change.Delete>(deleted.single())
        assertEquals(listOf("id"), delete.old.columnNames)
        assertEquals(1, delete.old.get<Int>("id"))
    }

    @Test
    fun `an update's new row lacks the TOASTed values it did not change, and names them`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', repeat('x', 10000), NULL)")
        run("UPDATE senator SET cognomen = 'Cato' WHERE id = 1")

        val (_, updated) = start().use { it.collect(2) }

        val update = assertIs<Change.Update>(updated.single())
        val new = update.new
        assertEquals(setOf("toga"), update.unchanged)
        assertEquals(listOf("id", "cognomen", "status"), new.columnNames)
        assertTrue(new.hasColumn("cognomen"))
        assertFalse(new.hasColumn("toga"), "An unchanged column is not one the row carries")
        assertEquals("Cato", new.get<String>("cognomen"))
        val e = assertFailsWith<MappingException> { new.get<String?>("toga") }
        assertEquals(MappingExceptionReason.COLUMN_NOT_FOUND, e.reason)

        assertEquals(mapOf("id" to 1, "cognomen" to "Cato", "status" to null), new.toObject<Map<String, Any?>>())
        assertEquals(SenatorName(1, "Cato"), new.toObject<SenatorName>())
        assertEquals(
            PgOutputSenator(1, "Cato", null, null),
            new.toObject<PgOutputSenator>(),
            "As a query's row without the column, a nullable property for it is null"
        )
    }

    @Test
    fun `under replica identity full the old row comes whole and gives what the new one came without`() {
        run("INSERT INTO legion VALUES (10, 'Legio X Equestris', repeat('y', 10000))")
        run("UPDATE legion SET name = 'Legio X Gemina' WHERE id = 10")

        val (_, updated) = start().use { it.collect(2) }

        val update = assertIs<Change.Update>(updated.single())
        assertEquals(ReplicaIdentity.FULL, update.relation.replicaIdentity)
        assertEquals(listOf("id", "name", "motto"), update.relation.keyColumns)
        val old = assertNotNull(update.old)
        assertEquals("Legio X Equestris", old.get<String>("name"))
        assertEquals("Legio X Gemina", update.new.get<String>("name"))
        assertEquals(emptySet(), update.unchanged)
        assertEquals("y".repeat(10000), update.new.get<String>("motto"))
    }

    @Test
    fun `an update of the key carries the old key alone`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', 'praetexta', NULL)")
        run("UPDATE senator SET id = 2 WHERE id = 1")

        val (_, updated) = start().use { it.collect(2) }

        val update = assertIs<Change.Update>(updated.single())
        val old = assertNotNull(update.old)
        assertEquals(listOf("id"), old.columnNames)
        assertEquals(1, old.get<Int>("id"))
        assertEquals(2, update.new.get<Int>("id"))
    }

    @Test
    fun `a truncate names its relations`() {
        run("TRUNCATE senator, legion RESTART IDENTITY")

        val truncates = nextTransaction().map { assertIs<Change.Truncate>(it) }
        assertEquals(setOf("senator", "legion"), truncates.flatMap { it.relations }.map { it.name }.toSet())
        assertTrue(truncates.all { it.restartIdentity && !it.cascade })
    }

    @Test
    fun `an aborted transaction the server dropped unsent leaves the stream reading`() {
        // With a subtransaction, and past logical_decoding_work_mem, it is dropped as aborted while decoded, and a
        // server before 18.7 then sends a stream abort for it.
        run(
            "BEGIN; SAVEPOINT s; INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL); RELEASE SAVEPOINT s; " +
                "INSERT INTO senator SELECT g, 'Cato', NULL, NULL FROM generate_series(2, 5000) g; ROLLBACK"
        )
        run("INSERT INTO senator VALUES (1, 'Brutus', NULL, NULL)")

        val changes = start { additionalProperties["options"] = "-c logical_decoding_work_mem=64kB" }
            .use { it.collect(1).single() }
        assertEquals("Brutus", assertIs<Change.Insert>(changes.single()).new.get<String>("cognomen"))
    }

    @Test
    fun `a type created after the catalog was read ends the stream until reloadTypes reads it`() {
        // The catalog was read before the type exists. On a table of its own, which no other test reads: the column
        // stays for the rest of the class.
        run("CREATE TYPE provincia AS ENUM ('ASIA', 'AFRICA'); ALTER TABLE edictum ADD COLUMN provincia provincia")
        run("INSERT INTO edictum VALUES (1, 'AFRICA')")

        val ended = assertFailsWith<TypeException> { start().use { it.collect(1) } }
        assertEquals(TypeExceptionReason.TYPE_NOT_FOUND, ended.reason)

        openSession().use { it.reloadTypes() }
        val inserted = start().use { it.collect(1) }.single()
        assertEquals(
            "AFRICA",
            assertIs<Change.Insert>(inserted.single()).new.get<String>("provincia"),
            "The transaction the stream ended in comes again"
        )
    }

    @Test
    fun `a transaction is acknowledged once its block returns, and holds the slot until then`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val commit = AtomicReference<PgLsn>()

        val stream = start(statusInterval = 100.milliseconds)
        val consuming = Consuming {
            stream.forEachTransaction { transaction ->
                transaction.changes.toList()
                commit.set(transaction.commitLsn)
                entered.countDown()
                release.await()
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        repeat(20) { run("INSERT INTO tribute (amount) VALUES ($it)") }
        assertFalse(
            holdsWithin(2.seconds) { confirmedFlush() > commit.get() },
            "The slot moved past a transaction whose block had not returned"
        )

        release.countDown()
        repeat(20) { run("INSERT INTO tribute (amount) VALUES ($it)") }
        assertTrue(
            holdsWithin(10.seconds) { confirmedFlush() > commit.get() },
            "The slot stayed at ${confirmedFlush()}, at the transaction at ${commit.get()}"
        )
        stream.close()
        assertNull(consuming.end())
    }

    @Test
    fun `between transactions changes outside the publications move the slot on`() {
        val stream = start(statusInterval = 100.milliseconds)
        val consuming = Consuming { stream.forEachTransaction { error("Nothing published was written") } }
        val before = currentLsn()
        repeat(20) { run("INSERT INTO tribute (amount) VALUES ($it)") }
        assertTrue(
            holdsWithin(10.seconds) { confirmedFlush() > before },
            "The slot stayed at ${confirmedFlush()}, behind $before"
        )
        stream.close()
        assertNull(consuming.end())
    }

    @Test
    fun `a block that takes longer than wal_sender_timeout keeps the stream`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
        run("INSERT INTO senator VALUES (2, 'Cato', NULL, NULL)")
        val handed = AtomicInteger()

        start(statusInterval = 200.milliseconds) { additionalProperties["options"] = "-c wal_sender_timeout=1s" }.use { stream ->
            stream.forEachTransaction { transaction ->
                transaction.changes.toList()
                if (handed.incrementAndGet() == 1) Thread.sleep(3000) else stream.close()
            }
        }
        assertEquals(2, handed.get(), "The second transaction came after a block three times wal_sender_timeout long")
    }

    @Test
    fun `a block that throws stops the stream, and its transaction comes again`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
        val failure = IllegalStateException("The senate is not in session")

        start().use { stream ->
            val thrown = assertFailsWith<IllegalStateException> { stream.forEachTransaction { throw failure } }
            assertSame(failure, thrown)
            assertFalse(stream.isActive)
        }
        assertIs<Change.Insert>(nextTransaction().single())
    }

    @Test
    fun `a stream stopped inside a transaction ends the block's read, and the transaction comes again`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
        val seen = AtomicReference<Throwable>()

        start().use { stream ->
            stream.forEachTransaction { transaction ->
                stream.close()
                try {
                    transaction.changes.toList()
                } catch (e: InvalidOperationException) {
                    seen.set(e)
                    throw e
                }
            }
        }
        assertEquals(InvalidOperationExceptionReason.RESOURCE_CLOSED, assertIs<InvalidOperationException>(seen.get()).reason)
        assertIs<Change.Insert>(nextTransaction().single())
    }

    @Test
    fun `a transaction's changes are read only inside its block`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
        run("INSERT INTO senator VALUES (2, 'Cato', NULL, NULL)")
        val kept = AtomicReference<ReplicatedTransaction>()
        val seen = AtomicReference<Throwable>()

        start().use { stream ->
            stream.forEachTransaction { transaction ->
                if (kept.get() == null) {
                    kept.set(transaction)
                } else {
                    // Read now, the first transaction's changes would be this one's.
                    seen.set(runCatching { kept.get().changes.toList() }.exceptionOrNull())
                    stream.close()
                }
            }
        }
        assertEquals(InvalidOperationExceptionReason.RESOURCE_CLOSED, assertIs<InvalidOperationException>(seen.get()).reason)
    }

    @Test
    fun `asynchronous stages are acknowledged in commit order, whatever order they complete in`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
        run("INSERT INTO senator VALUES (2, 'Cato', NULL, NULL)")
        val stages = CopyOnWriteArrayList<Pair<PgLsn, CompletableFuture<Unit>>>()

        val stream = start(statusInterval = 100.milliseconds)
        val consuming = Consuming {
            stream.forEachTransactionAsync { transaction ->
                transaction.changes.toList()
                CompletableFuture<Unit>().also { stages += transaction.commitLsn to it }
            }
        }
        assertTrue(holdsWithin(10.seconds) { stages.size == 2 })
        val (first, second) = stages

        second.second.complete(Unit)
        assertFalse(
            holdsWithin(1.seconds) { confirmedFlush() > first.first },
            "A later transaction's stage acknowledged one whose stage had not completed"
        )
        first.second.complete(Unit)
        assertTrue(
            holdsWithin(10.seconds) { confirmedFlush() > second.first },
            "The slot stayed at ${confirmedFlush()}, behind ${second.first}"
        )
        stream.close()
        assertNull(consuming.end())
    }

    @Test
    fun `a failed stage stops the stream with its failure, and its transaction comes again`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
        val failure = IllegalStateException("The courier was lost")

        start().use { stream ->
            val thrown = assertFailsWith<IllegalStateException> {
                stream.forEachTransactionAsync { transaction ->
                    transaction.changes.toList()
                    CompletableFuture.supplyAsync { throw failure }
                }
            }
            assertSame(failure, thrown)
        }
        assertIs<Change.Insert>(nextTransaction().single())
    }

    @Test
    fun `no more transactions wait for their stages than maxInFlight allows`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
        run("INSERT INTO senator VALUES (2, 'Cato', NULL, NULL)")
        val handed = AtomicInteger()
        val first = CompletableFuture<Unit>()

        val stream = start()
        val consuming = Consuming {
            stream.forEachTransactionAsync(maxInFlight = 1) { transaction ->
                transaction.changes.toList()
                if (handed.incrementAndGet() == 1) first else CompletableFuture.completedFuture(Unit)
            }
        }
        assertFalse(holdsWithin(1.seconds) { handed.get() > 1 }, "A second transaction was handed out past maxInFlight")
        first.complete(Unit)
        assertTrue(holdsWithin(10.seconds) { handed.get() == 2 })
        stream.close()
        assertNull(consuming.end())
    }

    @Test
    fun `a converter registered on the stream reads its rows, and a query on the database does not see it`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")

        val inserted = start().registerResultConverter(TitulusConverter)
            .use { it.collect(1).single() }
        assertEquals("Cicero", assertIs<Change.Insert>(inserted.single()).new.get<Titulus>("cognomen").value)

        val e = assertFailsWith<MappingException> {
            openSession().use { it.createNativeQuery("SELECT 'Cato'::text").fetchRowStrict().get<Titulus>(0) }
        }
        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, e.reason)
    }

    @Test
    fun `a type source lends its catalog, with what is registered on it`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")

        TestDatabase.dataSource().use { pool ->
            pool.getOctaviusSession().use { it.typeManager.registerResultConverter(CognomenConverter) }

            // Under another name the database has a catalog of its own, without the converter.
            val withoutSource = assertIs<Change.Insert>(nextTransaction(otherUrl).single())
            val e = assertFailsWith<MappingException> { withoutSource.new.get<Cognomen>("cognomen") }
            assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, e.reason)

            val withSource = assertIs<Change.Insert>(nextTransaction(otherUrl, typeSource = pool).single())
            assertEquals("Cicero", withSource.new.get<Cognomen>("cognomen").value)
        }
    }

    @Test
    fun `a type source of another database is refused`() {
        TestDatabase.dataSource { jdbcUrl = otherDatabaseUrl }.use { other ->
            val e = assertFailsWith<InvalidOperationException> { start(typeSource = other) }
            assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, e.reason)
        }
    }

    @Test
    fun `a new slot's copy reads the database as of its start through the type source, and the stream carries on after it`() {
        run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
        val copied = mutableListOf<String>()

        val inserted = TestDatabase.dataSource().use { pool ->
            startPgOutput(
                TestDatabase.URL, TestDatabase.properties(), madeSlot, publications,
                newSlot = NewSlot(copy = {
                    // Committed once the slot was made: the stream sends it, and the copy does not see it.
                    run("INSERT INTO senator VALUES (2, 'Cato', NULL, NULL)")
                    copied += createNativeQuery("SELECT cognomen FROM senator ORDER BY id").fetchFields<String>()
                }),
                typeSource = pool
            ).use { it.collect(1).single() }
        }
        assertEquals(listOf("Cicero"), copied)
        assertEquals("Cato", assertIs<Change.Insert>(inserted.single()).new.get<String>("cognomen"))
        assertEquals(false, temporary(madeSlot), "A persistent slot outlives its stream")
    }

    @Test
    fun `a temporary new slot goes when its stream closes`() {
        startPgOutput(TestDatabase.URL, TestDatabase.properties(), madeSlot, publications, newSlot = NewSlot(temporary = true))
            .use { stream ->
                assertEquals(true, temporary(madeSlot))
                run("INSERT INTO senator VALUES (1, 'Cicero', NULL, NULL)")
                stream.collect(1)
            }

        // The server drops it once the backend has seen the connection close.
        assertTrue(holdsWithin(5.seconds) { temporary(madeSlot) == null }, "The temporary slot outlived its stream")
    }

    @Test
    fun `a persistent new slot whose copy fails is dropped again, and the failure comes out as it was`() {
        val failure = IllegalStateException("The census was interrupted")

        val thrown = assertFailsWith<IllegalStateException> {
            startPgOutput(TestDatabase.URL, TestDatabase.properties(), madeSlot, publications, newSlot = NewSlot(copy = { throw failure }))
        }
        assertSame(failure, thrown)
        assertNull(temporary(madeSlot), "The slot whose copy failed is gone")
    }

    @Test
    fun `a new slot of a name another slot has is refused, and that slot is left`() {
        val e = assertFailsWith<StatementException> {
            startPgOutput(TestDatabase.URL, TestDatabase.properties(), slot, publications, newSlot = NewSlot())
        }
        assertEquals("42710", e.sqlState)
        assertEquals(false, temporary(slot), "The slot that had the name is still there")
    }

    @Test
    fun `a new slot with a start position is refused before anything is made`() {
        val e = assertFailsWith<InvalidOperationException> {
            startPgOutput(
                TestDatabase.URL, TestDatabase.properties(), madeSlot, publications,
                newSlot = NewSlot(), startLsn = PgLsn.parse("0/1")
            )
        }
        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, e.reason)
        assertNull(temporary(madeSlot))
    }
}
