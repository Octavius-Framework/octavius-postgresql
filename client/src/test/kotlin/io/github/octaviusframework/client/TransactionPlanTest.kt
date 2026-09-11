package io.github.octaviusframework.client

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.transaction.TransactionPlan
import io.github.octaviusframework.client.transaction.map
import io.github.octaviusframework.client.transaction.spread
import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers [TransactionPlan] against a real PostgreSQL: that a later step sees what an earlier one produced,
 * that the whole thing is one transaction, and that a handle used against the wrong shape says so.
 */
class TransactionPlanTest {

    data class Item(val province: String, val amount: Int)

    /** Wider than the projection the mapping test selects, which is what makes that step fail. */
    data class Edict(val id: Int, val title: String, val tribute: Int)

    companion object {
        private lateinit var dataSource: HikariDataSource
        private lateinit var db: OctaviusClient

        @BeforeAll
        @JvmStatic
        fun setUp() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:octavius://localhost:5432/octavius_test"
                username = "postgres"
                password = "1234"
                maximumPoolSize = 2
            })
            db = OctaviusClient.fromDataSource(dataSource)
            db.rawQuery(
                """
                CREATE TABLE IF NOT EXISTS plan_edicts (
                    id      SERIAL PRIMARY KEY,
                    title   TEXT NOT NULL UNIQUE,
                    tribute INT  NOT NULL
                );
                CREATE TABLE IF NOT EXISTS plan_items (
                    id       SERIAL PRIMARY KEY,
                    edict_id INT  NOT NULL REFERENCES plan_edicts(id),
                    province TEXT NOT NULL,
                    amount   INT  NOT NULL
                )
                """.trimIndent()
            ).execute()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            db.rawQuery("DROP TABLE IF EXISTS plan_items; DROP TABLE IF EXISTS plan_edicts").execute()
            db.close()
            dataSource.close()
        }
    }

    @BeforeEach
    fun clearTables() {
        db.rawQuery("TRUNCATE plan_items, plan_edicts RESTART IDENTITY CASCADE").execute()
    }

    private fun edictCount(): Long = db.rawQuery("SELECT count(*) FROM plan_edicts").fetchFieldStrict<Long>()
    private fun itemCount(): Long = db.rawQuery("SELECT count(*) FROM plan_items").fetchFieldStrict<Long>()

    // --- The point of the thing -------------------------------------------------------------------

    @Test
    fun `a later step uses the id an earlier step generated`() {
        val levy = listOf(Item("Gallia", 300), Item("Hispania", 250), Item("Britannia", 100))
        val plan = TransactionPlan()

        val edictId = plan.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute")).returning("id")
                .asStep().fetchFieldStrict<Int>("title" to "De Tributis", "tribute" to 650)
        )

        for (item in levy) {
            plan.add(
                db.insertInto("plan_items").values(listOf("edict_id", "province", "amount"))
                    .asStep().update(
                        "edict_id" to edictId.value(),
                        "province" to item.province,
                        "amount" to item.amount
                    )
            )
        }

        val results = db.executeTransactionPlan(plan)

        val id = results[edictId]
        assertTrue(id > 0)
        assertEquals(4, results.size)
        assertEquals(
            listOf("Britannia", "Gallia", "Hispania"),
            db.select("province").from("plan_items").where("edict_id = @id").orderBy("province")
                .fetchFields<String>("id" to id)
        )
    }

    @Test
    fun `executing a plan does not consume it`() {
        // What makes a retry loop a plain `for`: a plan that failed on a serialization failure or a deadlock is
        // re-runnable as it stands, because execution copies the steps out and keeps its results in a map of
        // its own rather than writing anything back.
        val edict = db.insertInto("plan_edicts").values(listOf("title", "tribute")).returning("id")
            .fetchFieldStrict<Int>("title" to "De Repetundis", "tribute" to 40)

        val plan = TransactionPlan()
        val itemId = plan.add(
            db.insertInto("plan_items").values(listOf("edict_id", "province", "amount")).returning("id")
                .asStep().fetchFieldStrict<Int>("edict_id" to edict, "province" to "Asia", "amount" to 40)
        )
        // Records the id the step above produced, so each run leaves proof of what it resolved.
        plan.add(
            db.insertInto("plan_items").values(listOf("edict_id", "province", "amount"))
                .asStep().update("edict_id" to edict, "province" to "Cilicia", "amount" to itemId.value())
        )

        val first = db.executeTransactionPlan(plan)
        val second = db.executeTransactionPlan(plan)

        assertEquals(2, plan.size, "the plan should still hold both steps")
        assertEquals(first.size, second.size)
        assertEquals(4L, itemCount(), "two runs of a two-step plan")
        assertTrue(second[itemId] > first[itemId])
        // Each run resolved the handle against its own results, not against the run before it.
        assertEquals(
            listOf(first[itemId], second[itemId]),
            db.select("amount").from("plan_items").where("province = 'Cilicia'").orderBy("id")
                .fetchFields<Int>()
        )
    }

    // --- Merging ----------------------------------------------------------------------------------

    @Test
    fun `a merged plan runs as one transaction and keeps both plans' handles`() {
        val head = TransactionPlan()
        val edictId = head.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute")).returning("id")
                .asStep().fetchFieldStrict<Int>("title" to "De Provinciis", "tribute" to 90)
        )

        // Built by somebody who knows nothing about the plan above, against its own handle.
        val tail = TransactionPlan()
        val itemId = tail.add(
            db.insertInto("plan_items").values(listOf("edict_id", "province", "amount")).returning("id")
                .asStep().fetchFieldStrict<Int>("edict_id" to edictId.value(), "province" to "Asia", "amount" to 90)
        )
        tail.add(
            db.update("plan_items").setValues(listOf("amount")).where("id = @id")
                .asStep().update("id" to itemId.value(), "amount" to 91)
        )

        head.addPlan(tail)
        assertEquals(3, head.size)

        val results = db.executeTransactionPlan(head)

        // Both plans' handles resolve against the merged run, though tail's were numbered against tail.
        assertTrue(results[edictId] > 0)
        assertTrue(results[itemId] > 0)
        assertEquals(3, results.size)
        assertEquals(91, db.select("amount").from("plan_items").fetchFieldStrict<Int>())
    }

    @Test
    fun `a failure in a merged step takes the whole merged plan down`() {
        val head = TransactionPlan()
        head.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute"))
                .asStep().update("title" to "De Annona", "tribute" to 10)
        )

        val tail = TransactionPlan()
        tail.add(
            db.insertInto("plan_items").values(listOf("edict_id", "province", "amount"))
                .asStep().update("edict_id" to 9999, "province" to "Nusquam", "amount" to 1)
        )

        head.addPlan(tail)

        assertFailsWith<ConstraintViolationException> { db.executeTransactionPlan(head) }
        assertEquals(0L, edictCount(), "the step from the first plan must have gone back too")
    }

    @Test
    fun `merging leaves the plan that was merged in runnable on its own`() {
        val tail = TransactionPlan()
        tail.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute"))
                .asStep().update("title" to "De Vectigalibus", "tribute" to 5)
        )

        val head = TransactionPlan()
        head.addPlan(tail)

        assertEquals(1, tail.size, "merging must not consume it")
        db.executeTransactionPlan(head)
        assertEquals(1L, edictCount())
    }

    @Test
    fun `a plan cannot be merged into itself`() {
        val plan = TransactionPlan()
        plan.add(db.insertInto("plan_edicts").values(listOf("title", "tribute")).asStep().update())

        val thrown = assertFailsWith<InvalidOperationException> { plan.addPlan(plan) }
        assertTrue(thrown.details!!.contains("into itself"), thrown.details!!)
    }

    @Test
    fun `a plan cannot be merged in twice`() {
        // The second merge would run every one of its steps again under the handle that already named the
        // first run, leaving that first result unreachable.
        val tail = TransactionPlan()
        tail.add(db.insertInto("plan_edicts").values(listOf("title", "tribute")).asStep().update())

        val head = TransactionPlan()
        head.addPlan(tail)

        val thrown = assertFailsWith<InvalidOperationException> { head.addPlan(tail) }
        assertTrue(thrown.details!!.contains("already a step of this plan"), thrown.details!!)
    }

    @Test
    fun `merging an empty plan changes nothing`() {
        val plan = TransactionPlan()
        plan.add(db.insertInto("plan_edicts").values(listOf("title", "tribute")).asStep().update())
        plan.addPlan(TransactionPlan())
        assertEquals(1, plan.size)
    }

    @Test
    fun `a step that fails takes every step before it down with it`() {
        val plan = TransactionPlan()

        val edictId = plan.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute")).returning("id")
                .asStep().fetchFieldStrict<Int>("title" to "De Tributis", "tribute" to 650)
        )
        plan.add(
            db.insertInto("plan_items").values(listOf("edict_id", "province", "amount"))
                .asStep().update("edict_id" to edictId.value(), "province" to "Gallia", "amount" to 300)
        )
        // The foreign key holds, but the title does not: a second edict under a name already taken.
        plan.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute"))
                .asStep().update("title" to "De Tributis", "tribute" to 1)
        )

        assertFailsWith<ConstraintViolationException> { db.executeTransactionPlan(plan) }

        assertEquals(0L, edictCount(), "the edict inserted by step one must have been rolled back")
        assertEquals(0L, itemCount())
    }

    @Test
    fun `a step whose statement fails names itself on the path`() {
        // The query context carries the SQL and the values, and in a plan built in a loop that is the same
        // statement in every step. Which of them it was is the executor's to say, and the path is where it
        // says it - the one thing that can be added without replacing the exception a retry matches on.
        val plan = TransactionPlan()
        for (tribute in listOf(650, 700, 750)) {
            plan.add(
                db.insertInto("plan_edicts").values(listOf("title", "tribute"))
                    .asStep().update("title" to "De Tributis", "tribute" to tribute)
            )
        }

        val thrown = assertFailsWith<ConstraintViolationException> { db.executeTransactionPlan(plan) }

        assertEquals(listOf("step 1"), thrown.path)
        assertTrue(thrown.toString().contains("PATH: step 1"), thrown.toString())
    }

    @Test
    fun `a step whose result cannot be mapped keeps the attribute and gains the step`() {
        db.insertInto("plan_edicts").values(listOf("title", "tribute"))
            .update("title" to "De Tributis", "tribute" to 650)

        val plan = TransactionPlan()
        plan.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute"))
                .asStep().update("title" to "De Bello Gallico", "tribute" to 1)
        )
        // The projection has no `tribute` to give the class, which the mapper reports against the attribute.
        plan.add(db.select("id", "title").from("plan_edicts").asStep().fetchObjects<Edict>())

        val thrown = assertFailsWith<MappingException> { db.executeTransactionPlan(plan) }

        assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, thrown.reason)
        assertEquals(listOf("step 1", "tribute"), thrown.path.asReversed(), "the mapper's own path, under the step")
        assertEquals(1L, edictCount(), "only the seeded edict is left: the insert in step 0 rolled back")
    }

    @Test
    fun `a plan reaches the result style by being wrapped, like anything else`() {
        db.insertInto("plan_edicts").values(listOf("title", "tribute"))
            .update("title" to "De Tributis", "tribute" to 1)

        val plan = TransactionPlan()
        plan.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute"))
                .asStep().update("title" to "De Tributis", "tribute" to 2)
        )

        val result = dbResult { db.executeTransactionPlan(plan) }

        assertIs<DataResult.Failure>(result)
        assertIs<ConstraintViolationException>(result.error)
    }

    // --- Reading a handle -------------------------------------------------------------------------

    @Test
    fun `map takes one column of a row-shaped result`() {
        val plan = TransactionPlan()

        val edict = plan.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute")).returning("id", "tribute")
                .asStep().fetchRowStrict("title" to "De Tributis", "tribute" to 650)
        )
        plan.add(
            db.insertInto("plan_items").values(listOf("edict_id", "province", "amount"))
                .asStep().update(
                    "edict_id" to edict.value().map { it.get<Int>("id") },
                    "province" to "Gallia",
                    "amount" to edict.value().map { it.get<Int>("tribute") }
                )
        )

        db.executeTransactionPlan(plan)

        assertEquals(650, db.rawQuery("SELECT amount FROM plan_items").fetchFieldStrict<Int>())
    }

    @Test
    fun `map gathers one column of every row into something bindable`() {
        val plan = TransactionPlan()

        val ids = plan.add(
            db.insertInto("plan_edicts")
                .fromSelect("SELECT 'Edict ' || i, i * 100 FROM generate_series(1, 3) AS i")
                .columns("title", "tribute")
                .returning("id")
                .asStep().fetchRows()
        )

        val total = plan.add(
            db.select("sum(tribute)").from("plan_edicts").where("id = ANY(@ids)")
                .asStep().fetchFieldStrict<Long>(
                    // The row asked for the column at Int rather than at Any?, so what arrives is a List<Int>
                    // the driver sends as an array - no filtering a list of Any? into one it will accept.
                    "ids" to ids.value().map { rows -> rows.map { row -> row.get<Int>("id") } }
                )
        )

        val results = db.executeTransactionPlan(plan)

        assertEquals(3, results[ids].size)
        assertEquals(600L, results[total])
    }

    @Test
    fun `a map result is spread into parameters under its own keys`() {
        db.insertInto("plan_edicts").values(listOf("title", "tribute"))
            .update("title" to "De Tributis", "tribute" to 650)

        val plan = TransactionPlan()

        val source = plan.add(
            db.select("tribute").from("plan_edicts").where("title = @title")
                .asStep().fetchObjectStrict<Map<String, Any?>>("title" to "De Tributis")
        )
        // "row" is not a parameter the statement names - the map's keys are, and they arrive under those
        // names because a spread drops the key it was filed under.
        plan.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute"))
                .asStep().update("title" to "De Tributis II", "row" to source.value().spread())
        )

        db.executeTransactionPlan(plan)

        assertEquals(
            650,
            db.rawQuery("SELECT tribute FROM plan_edicts WHERE title = @t").fetchFieldStrict<Int>("t" to "De Tributis II")
        )
    }

    // --- What it refuses --------------------------------------------------------------------------

    @Test
    fun `a fetchObject that matched nothing spreads whatever map takes its place`() {
        // fetchObject returns Map<String, Any?>?, which does not spread. What an absent row contributes is
        // said in a map() before the spread.
        val plan = TransactionPlan()

        val missing = plan.add(
            db.select("title", "tribute").from("plan_edicts").where("false")
                .asStep().fetchObject<Map<String, Any?>>()
        )
        plan.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute")).asStep().update(
                "row" to missing.value().map { it ?: mapOf("title" to "Nihil", "tribute" to 0) }.spread()
            )
        )

        db.executeTransactionPlan(plan)

        assertEquals(
            0,
            db.rawQuery("SELECT tribute FROM plan_edicts WHERE title = @t").fetchFieldStrict<Int>("t" to "Nihil")
        )
    }

    @Test
    fun `a handle from another plan is refused rather than silently missing`() {
        val other = TransactionPlan()
        val strayHandle = other.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute"))
                .asStep().update("title" to "Elsewhere", "tribute" to 1)
        )

        val plan = TransactionPlan()
        plan.add(
            db.insertInto("plan_items").values(listOf("edict_id", "province", "amount"))
                .asStep().update("edict_id" to strayHandle.value(), "province" to "Gallia", "amount" to 1)
        )

        val thrown = assertFailsWith<InvalidOperationException> { db.executeTransactionPlan(plan) }
        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, thrown.reason)
    }

    @Test
    fun `a plan merged in ahead of the one it takes values from is refused before anything runs`() {
        // The one order a handle cannot enforce by itself: tail is built against head's handle, and merging
        // the two the wrong way round puts the step that needs the id ahead of the step that generates it.
        val head = TransactionPlan()
        val edictId = head.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute")).returning("id")
                .asStep().fetchFieldStrict<Int>("title" to "De Provinciis", "tribute" to 90)
        )

        val tail = TransactionPlan()
        tail.add(
            db.insertInto("plan_items").values(listOf("edict_id", "province", "amount"))
                .asStep().update("edict_id" to edictId.value(), "province" to "Asia", "amount" to 90)
        )
        tail.addPlan(head)

        val thrown = assertFailsWith<InvalidOperationException> { db.executeTransactionPlan(tail) }

        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, thrown.reason)
        assertTrue(thrown.details!!.contains("step 1, which runs after it"), thrown.details!!)
        assertTrue(thrown.path.isEmpty(), "refused by validation, not by the executor partway through")
    }

    @Test
    fun `an empty plan runs and produces nothing`() {
        val results = db.executeTransactionPlan(TransactionPlan())
        assertEquals(0, results.size)
        assertEquals(0L, edictCount())
    }

    @Test
    fun `a plan reports how many steps it holds before it runs`() {
        val plan = TransactionPlan()
        assertTrue(plan.isEmpty())
        plan.add(
            db.insertInto("plan_edicts").values(listOf("title", "tribute"))
                .asStep().update("title" to "De Tributis", "tribute" to 1)
        )
        assertEquals(1, plan.size)
    }
}
