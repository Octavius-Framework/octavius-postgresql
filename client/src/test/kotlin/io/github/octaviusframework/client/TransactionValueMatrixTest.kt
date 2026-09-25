package io.github.octaviusframework.client

import io.github.octaviusframework.client.transaction.StepHandle
import io.github.octaviusframework.client.transaction.TransactionPlan
import io.github.octaviusframework.client.transaction.TransactionValue
import io.github.octaviusframework.client.transaction.map
import io.github.octaviusframework.client.transaction.spread
import io.github.octaviusframework.client.transaction.toTransactionValue
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.row.Row
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every kind of [TransactionValue] against every shape of result a step terminal can produce.
 *
 * A handle reaches one thing, `value()`, typed as its terminal declared it, and everything read out of a
 * result is read in `map { }` over that type. What is pinned here is what runs: that each shape resolves, that
 * the driver can then send it, and what happens where it cannot. Reaching for a column of something that has
 * none does not compile, so it is not among these.
 */
class TransactionValueMatrixTest : AbstractClientIntegrationTest() {

    data class Probe(val id: Int, val name: String, val amount: Int)

    /** Registered against `public.tv_rank`, which is what makes a converter claim that column. */
    enum class Rank { Legatus, Tribunus }

    override val schema = """
        CREATE TABLE tv_probe (
            id     SERIAL PRIMARY KEY,
            name   TEXT NOT NULL,
            amount INT  NOT NULL
        );
        CREATE TABLE tv_sink (
            id     SERIAL PRIMARY KEY,
            name   TEXT,
            amount INT
        );
        CREATE TYPE public.tv_probe_t AS (id int, name text, amount int);
        CREATE TYPE public.tv_rank AS ENUM ('LEGATUS', 'TRIBUNUS');
        CREATE TABLE tv_shapes (id SERIAL PRIMARY KEY, rank public.tv_rank, doc jsonb);
    """.trimIndent()

    @BeforeAll
    fun registerRank() {
        db.execute { typeManager.registerEnum<Rank>("tv_rank", "public") }
    }

    @BeforeEach
    fun seed() {
        db.rawQuery("TRUNCATE tv_sink, tv_probe, tv_shapes RESTART IDENTITY").execute()
        db.insertInto("tv_probe").values(listOf("name", "amount"))
            .update("name" to "Gallia", "amount" to 300)
        db.insertInto("tv_probe").values(listOf("name", "amount"))
            .update("name" to "Hispania", "amount" to 250)
        db.insertInto("tv_probe").values(listOf("name", "amount"))
            .update("name" to "Britannia", "amount" to 100)
    }

    /** A step over all three probe rows, for the terminals that return many. */
    private fun probeAll() = db.select("id", "name", "amount").from("tv_probe").orderBy("id").asStep()

    /** A step over one probe row, for the terminals that insist on exactly one. */
    private fun probeOne() = db.select("id", "name", "amount").from("tv_probe").where("id = 1").asStep()

    /**
     * Adds a producing step, then a step that binds [use] of it and hands the bound value straight back.
     *
     * Passing the value through the server and reading it again is what says it really resolved *and* that
     * the driver could send it - a resolution nothing can bind is not a usable one.
     *
     * [H] is what the producing step's terminal declared, so [use] gets a handle carrying that type and can
     * reach into the result as ordinary Kotlin.
     */
    private inline fun <reified R, H> throughParameter(
        producer: (TransactionPlan) -> StepHandle<H>,
        sql: String = "SELECT @v AS v",
        use: (StepHandle<H>) -> Any?
    ): R {
        val plan = TransactionPlan()
        val source = producer(plan)
        val sink = plan.add(db.rawQuery(sql).asStep().fetchFieldStrict<R>("v" to use(source)))
        return db.executeTransactionPlan(plan)[sink]
    }

    // --- value(), by what the terminal produced ----------------------------------------------------

    @Test
    fun `value carries a scalar from fetchFieldStrict`() {
        val out: Int = throughParameter(
            producer = { it.add(db.select("amount").from("tv_probe").where("id = 1").asStep().fetchFieldStrict<Int>()) },
            use = { it.value() }
        )
        assertEquals(300, out)
    }

    @Test
    fun `value carries the count from update`() {
        val out: Long = throughParameter(
            producer = {
                it.add(
                    db.update("tv_probe").setValues(listOf("amount")).where("true")
                        .asStep().update("amount" to 1)
                )
            },
            use = { it.value() }
        )
        assertEquals(3L, out)
    }

    @Test
    fun `value carries a list from fetchFields, which binds as an array`() {
        val out: Long = throughParameter(
            producer = { it.add(db.select("amount").from("tv_probe").orderBy("id").asStep().fetchFields<Int>()) },
            sql = "SELECT count(*) FROM unnest(@v) AS x",
            use = { it.value() }
        )
        assertEquals(3L, out)
    }

    @Test
    fun `value on a row-shaped step resolves to a Row, which nothing can bind`() {
        val thrown = assertFailsWith<OctaviusException> {
            throughParameter<Any?, Row>(
                producer = { it.add(probeOne().fetchRowStrict()) },
                use = { it.value() }
            )
        }
        assertTrue(
            thrown.getDetailedMessage()!!.contains("Row"),
            "the failure should name what it could not send: ${thrown.message}"
        )
    }

    @Test
    fun `value on a map step is not something to send either, which is what the spread is for`() {
        val thrown = assertFailsWith<OctaviusException> {
            throughParameter<Any?, Map<String, Any?>>(
                producer = { it.add(probeOne().fetchObjectStrict<Map<String, Any?>>()) },
                use = { it.value() }
            )
        }
        assertTrue(
            thrown.getDetailedMessage()!!.contains("Map"),
            "the failure should name what it could not send: ${thrown.getDetailedMessage()}"
        )
    }

    @Test
    fun `value on an object step needs the class to be a type the registry knows`() {
        val thrown = assertFailsWith<OctaviusException> {
            throughParameter<Any?, Probe>(
                producer = { it.add(probeOne().fetchObjectStrict<Probe>()) },
                use = { it.value() }
            )
        }
        assertTrue(thrown.getDetailedMessage()!!.contains("Probe"), thrown.getDetailedMessage()!!)
    }

    @Test
    fun `value on an object step binds once that class is a registered composite`() {
        // The other half of the test above, and the reason its name says "the registry knows" rather than
        // "nothing can bind": what stops an object being sent is having no composite to be sent as, not being
        // an object. Register one and value() carries the whole thing to the next step.
        db.execute { typeManager.registerAutoComposite<Probe>("tv_probe_t", "public") }

        val out: String = throughParameter(
            producer = { it.add(probeOne().fetchObjectStrict<Probe>()) },
            sql = "SELECT (@v).name AS v",
            use = { it.value() }
        )
        assertEquals("Gallia", out)
    }

    @Test
    fun `map is how an object result becomes something bindable`() {
        // Which is what Transformed is for: value() reaches the object, map() takes the part that can be sent.
        // The handle carries Probe, so the property is read without a cast anywhere.
        val out: String = throughParameter(
            producer = { it.add(probeOne().fetchObjectStrict<Probe>()) },
            use = { handle -> handle.value().map { probe -> probe.name } }
        )
        assertEquals("Gallia", out)
    }

    // --- map(), which is how a row-shaped result is reached into -----------------------------------

    @Test
    fun `map takes a column of a fetchRowStrict result`() {
        val out: String = throughParameter(
            producer = { it.add(probeOne().fetchRowStrict()) },
            use = { handle -> handle.value().map { row -> row.get<String>("name") } }
        )
        assertEquals("Gallia", out)
    }

    @Test
    fun `map takes a column of a fetchRow result, which says in its type that there may be none`() {
        val out: String = throughParameter(
            producer = { it.add(probeOne().fetchRow()) },
            use = { handle -> handle.value().map { row -> row!!.get<String>("name") } }
        )
        assertEquals("Gallia", out)
    }

    @Test
    fun `map indexes into a fetchRows result`() {
        val out: String = throughParameter(
            producer = { it.add(probeAll().fetchRows()) },
            use = { handle -> handle.value().map { rows -> rows[2].get<String>("name") } }
        )
        assertEquals("Britannia", out)
    }

    @Test
    fun `map gathers one column of every row, and the list binds as an array`() {
        // The column is asked for at Int, so what comes out is a List<Int>, which the driver sends as an
        // int array.
        val out: Long = throughParameter(
            producer = { it.add(probeAll().fetchRows()) },
            sql = "SELECT count(*) FROM tv_probe WHERE id = ANY(@v)",
            use = { handle -> handle.value().map { rows -> rows.map { row -> row.get<Int>("id") } } }
        )
        assertEquals(3L, out)
    }

    @Test
    fun `map can collapse a result to a scalar`() {
        val out: Int = throughParameter(
            producer = { it.add(probeAll().fetchRows()) },
            use = { handle -> handle.value().map { rows -> rows.size } }
        )
        assertEquals(3, out)
    }

    @Test
    fun `map nests`() {
        val out: Int = throughParameter(
            producer = { it.add(probeAll().fetchRows()) },
            use = { handle -> handle.value().map { rows -> rows.size }.map { size -> size * 10 } }
        )
        assertEquals(30, out)
    }

    // --- spread(), which fills a slot with parameters rather than with one -------------------------

    @Test
    fun `spread puts a map's entries into parameters of their own`() {
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchObjectStrict<Map<String, Any?>>())
        plan.add(
            db.insertInto("tv_sink").values(listOf("name", "amount"))
                .asStep().update("ignored" to source.value().spread())
        )
        db.executeTransactionPlan(plan)

        assertEquals(
            Probe(1, "Gallia", 300),
            db.select("id", "name", "amount").from("tv_sink").fetchObjectStrict<Probe>()
        )
    }

    @Test
    fun `spread comes after map, which is how an entry is dropped or overwritten on the way`() {
        // The mark is on the parameter slot rather than on the value in it, so everything ordinary Kotlin
        // does to a map can be done first.
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchObjectStrict<Map<String, Any?>>())
        plan.add(
            db.insertInto("tv_sink").values(listOf("name", "amount")).asStep().update(
                "ignored" to source.value().map { row -> row - "id" + ("amount" to 7) }.spread()
            )
        )
        db.executeTransactionPlan(plan)

        assertEquals(
            Probe(1, "Gallia", 7),
            db.select("id", "name", "amount").from("tv_sink").fetchObjectStrict<Probe>()
        )
    }

    @Test
    fun `spread comes after map, which is also how a row other than the first is spread`() {
        val plan = TransactionPlan()
        val source = plan.add(probeAll().fetchObjects<Map<String, Any?>>())
        plan.add(
            db.insertInto("tv_sink").values(listOf("name", "amount"))
                .asStep().update("ignored" to source.value().map { rows -> rows[2] }.spread())
        )
        db.executeTransactionPlan(plan)

        assertEquals("Britannia", db.select("name").from("tv_sink").fetchFieldStrict<String>())
    }

    @Test
    fun `the map's value type is the terminal's to choose, and the spread carries what it made`() {
        // The terminal decides what the columns are converted to. At Any? this jsonb column is a JsonElement,
        // which goes back out as jsonb and a text column refuses; asked for as String no converter claims it
        // and the codec's own text arrives, which the text column takes.
        db.rawQuery("INSERT INTO tv_shapes (rank, doc) VALUES ('LEGATUS', '{\"a\": 1}')").execute()

        val plan = TransactionPlan()
        val source = plan.add(
            db.rawQuery("SELECT doc AS name FROM tv_shapes").asStep().fetchObjectStrict<Map<String, String>>()
        )
        plan.add(
            db.insertInto("tv_sink").values(listOf("name"))
                .asStep().update("ignored" to source.value().spread())
        )
        db.executeTransactionPlan(plan)

        assertTrue(
            db.select("name").from("tv_sink").fetchFieldStrict<String>().contains("\"a\""),
            "the text the codec produced, not a JsonElement the converters made"
        )
    }

    // --- Value and Transformed ---------------------------------------------------------------------

    @Test
    fun `a plain value passes through untouched`() {
        val plan = TransactionPlan()
        val sink = plan.add(db.rawQuery("SELECT @v AS v").asStep().fetchFieldStrict<Int>("v" to 42))
        assertEquals(42, db.executeTransactionPlan(plan)[sink])
    }

    @Test
    fun `a wrapped known value resolves to itself`() {
        val plan = TransactionPlan()
        val sink = plan.add(
            db.rawQuery("SELECT @v AS v").asStep().fetchFieldStrict<Int>("v" to 42.toTransactionValue())
        )
        assertEquals(42, db.executeTransactionPlan(plan)[sink])
    }

    // --- What a value looks like when it is carried between steps -----------------------------------

    @Test
    fun `an enum and a jsonb column carry between steps, type and all`() {
        // These are the two that would not, were the codec's own output carried: an enum's label and a jsonb
        // document are both Strings, and a parameter's type is declared from its Kotlin class, so both would
        // go back out as `text` and be refused by the column they came from. Map<String, Any?> asks the
        // result converters what each column is instead, and what they produce - the enum, a JsonElement -
        // has a parameter converter that names its own PostgreSQL type.
        db.rawQuery("INSERT INTO tv_shapes (rank, doc) VALUES ('LEGATUS', '{\"a\": 1}')").execute()

        val plan = TransactionPlan()
        val source = plan.add(
            db.select("rank", "doc").from("tv_shapes").asStep().fetchObjectStrict<Map<String, Any?>>()
        )
        plan.add(
            db.insertInto("tv_shapes").values(listOf("rank", "doc"))
                .asStep().update("ignored" to source.value().spread())
        )
        db.executeTransactionPlan(plan)

        assertEquals(
            2L,
            db.rawQuery("SELECT count(*) FROM tv_shapes WHERE rank = 'LEGATUS' AND doc = '{\"a\": 1}'")
                .fetchFieldStrict<Long>()
        )
        assertEquals(
            listOf(Rank.Legatus, Rank.Legatus),
            db.select("rank").from("tv_shapes").orderBy("id").fetchFields<Rank>()
        )
    }

    @Test
    fun `a jsonb column arrives as a JsonElement rather than as its text`() {
        db.rawQuery("INSERT INTO tv_shapes (rank, doc) VALUES ('LEGATUS', '{\"a\": 1}')").execute()

        val plan = TransactionPlan()
        val source = plan.add(db.select("doc").from("tv_shapes").asStep().fetchRowStrict())
        val kind = plan.add(
            db.rawQuery("SELECT @v AS v").asStep()
                .fetchFieldStrict<String>("v" to source.value().map { it.get<Any?>("doc")!!::class.simpleName })
        )

        assertTrue(
            db.executeTransactionPlan(plan)[kind].contains("Json"),
            "a converter ran, so this is a JsonElement and not a String"
        )
    }

    @Test
    fun `an int column carries between steps, its codec class being its own type`() {
        // The other side of the limitation above: where the codec's class maps back to the same PostgreSQL
        // type, which is most of them, carrying a value between steps round-trips as it should. Asked for at
        // Any?, so it is the converters' output being sent and not a type the call site imposed.
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        val sink = plan.add(
            db.rawQuery("SELECT @v + 1 AS v").asStep()
                .fetchFieldStrict<Int>("v" to source.value().map { it.get<Any?>("amount") })
        )
        assertEquals(301, db.executeTransactionPlan(plan)[sink])
    }

    // --- What the caller's own code does ------------------------------------------------------------

    @Test
    fun `a transformation that throws arrives as a MappingException naming the parameter`() {
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        plan.add(
            db.insertInto("tv_sink").values(listOf("amount"))
                .asStep().update("amount" to source.value().map { it.get<String>("name").toInt() })
        )

        val thrown = assertFailsWith<MappingException> { db.executeTransactionPlan(plan) }

        assertTrue(thrown.details.contains("'amount'"), thrown.details)
        assertIs<NumberFormatException>(thrown.cause, "what was actually thrown has to survive as the cause")
        assertEquals(0L, db.rawQuery("SELECT count(*) FROM tv_sink").fetchFieldStrict<Long>())
    }

    @Test
    fun `a transformation that throws is a failure the result style can see`() {
        // The reason wrapping is worth doing at all: dbResult catches OctaviusException and nothing else, so
        // a bare NumberFormatException would travel straight through the boundary instead of being classified.
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        plan.add(
            db.insertInto("tv_sink").values(listOf("amount"))
                .asStep().update("amount" to source.value().map { it.get<String>("name").toInt() })
        )

        // MappingException is a caller bug, so the boundary rethrows it rather than returning it - which is
        // the point: it reaches the classification at all, instead of going round it.
        assertFailsWith<MappingException> { dbResult { db.executeTransactionPlan(plan) } }
    }

    @Test
    fun `a transformation throwing an OctaviusException is passed through as it is`() {
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        plan.add(
            db.insertInto("tv_sink").values(listOf("amount")).asStep().update(
                "amount" to source.value().map<Row, Any?> {
                    throw InvalidOperationException(
                        InvalidOperationExceptionReason.INVALID_ARGUMENT,
                        details = "raised by the caller's own code"
                    )
                }
            )
        )

        val thrown = assertFailsWith<InvalidOperationException> { db.executeTransactionPlan(plan) }
        assertEquals("raised by the caller's own code", thrown.details)
        // Passed through as it was thrown, and told where it was thrown from: the path is on the whole
        // hierarchy, so this says as much about where it happened as a MappingException does.
        assertEquals(listOf("step 1", "parameter 'amount'", "map #1"), thrown.path.asReversed())
    }

    @Test
    fun `a column the row has not got is the driver's failure, and arrives as the driver raised it`() {
        // Row.get raises this one, and it is already an OctaviusException, so it is not wrapped.
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        plan.add(
            db.insertInto("tv_sink").values(listOf("name"))
                .asStep().update("name" to source.value().map { it.get<String>("tribute") })
        )

        val thrown = assertFailsWith<MappingException> { db.executeTransactionPlan(plan) }
        assertEquals(MappingExceptionReason.COLUMN_NOT_FOUND, thrown.reason)
        assertTrue(thrown.getDetailedMessage()!!.contains("tribute"), thrown.getDetailedMessage()!!)
    }

    @Test
    fun `the parameter is named however deep the transformation that threw`() {
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        plan.add(
            db.insertInto("tv_sink").values(listOf("amount")).asStep().update(
                "amount" to source.value().map { it.get<Int>("amount") }.map { listOf<Int>()[it] }
            )
        )

        val thrown = assertFailsWith<MappingException> { db.executeTransactionPlan(plan) }
        assertTrue(thrown.details.contains("'amount'"), thrown.details)
        assertIs<IndexOutOfBoundsException>(thrown.cause)
    }

    // --- Handles across plans ----------------------------------------------------------------------

    @Test
    fun `a handle from another plan is refused before the transaction is opened`() {
        val other = TransactionPlan()
        val stranger = other.add(probeOne().fetchRowStrict())

        val plan = TransactionPlan()
        plan.add(
            db.insertInto("tv_sink").values(listOf("name"))
                .asStep().update("name" to stranger.value().map { it.get<String>("name") })
        )

        val thrown = assertFailsWith<OctaviusException> { db.executeTransactionPlan(plan) }
        assertTrue(thrown.getDetailedMessage()!!.contains("not a step of"), thrown.getDetailedMessage()!!)
        // Nothing ran: the row this step would have written is not there, and it is not there because the
        // plan was refused rather than because a transaction rolled back.
        assertEquals(0L, db.rawQuery("SELECT count(*) FROM tv_sink").fetchFieldStrict<Long>())
    }

    @Test
    fun `a foreign handle is found however many transformations wrap it`() {
        val other = TransactionPlan()
        val stranger = other.add(probeOne().fetchRowStrict())

        val plan = TransactionPlan()
        plan.add(
            db.insertInto("tv_sink").values(listOf("name")).asStep().update(
                "name" to stranger.value().map { it.get<String>("name") }.map { it.uppercase() }
            )
        )

        val thrown = assertFailsWith<OctaviusException> { db.executeTransactionPlan(plan) }
        assertTrue(thrown.getDetailedMessage()!!.contains("not a step of"), thrown.getDetailedMessage()!!)
    }

    @Test
    fun `a foreign handle is found under a spread as well as under a value`() {
        // A spread is not a TransactionValue, so validation walks it through a branch of its own - and a
        // handle the plan never held is caught there too, not only where a plain value would have caught it.
        val other = TransactionPlan()
        val stranger = other.add(probeOne().fetchObjectStrict<Map<String, Any?>>())

        val plan = TransactionPlan()
        plan.add(
            db.insertInto("tv_sink").values(listOf("name", "amount"))
                .asStep().update("ignored" to stranger.value().spread())
        )

        val thrown = assertFailsWith<OctaviusException> { db.executeTransactionPlan(plan) }
        assertTrue(thrown.getDetailedMessage()!!.contains("not a step of"), thrown.getDetailedMessage()!!)
    }

    @Test
    fun `a step whose query cannot render is refused before anything runs, and says which step`() {
        val plan = TransactionPlan()
        plan.add(db.insertInto("tv_sink").values(listOf("name")).asStep().update("name" to "Gallia"))
        // An UPDATE with no WHERE never renders, and until now that surfaced only once step 1 had inserted.
        plan.add(db.update("tv_sink").setValues(listOf("name")).asStep().update("name" to "Roma"))

        val thrown = assertFailsWith<OctaviusException> { db.executeTransactionPlan(plan) }
        assertTrue(thrown.getDetailedMessage()!!.contains("Step 1"), thrown.getDetailedMessage()!!)
        assertEquals(0L, db.rawQuery("SELECT count(*) FROM tv_sink").fetchFieldStrict<Long>())
    }

    @Test
    fun `an empty plan produces an empty result rather than a failure`() {
        // The short-circuit that skips the transaction is not observable from here - what this pins is the
        // contract either way round: no steps, no result, no exception.
        val results = db.executeTransactionPlan(TransactionPlan())
        assertEquals(0, results.size)
    }

    // --- Saying which step, and which map ----------------------------------------------------------

    @Test
    fun `a failing transformation names the step, the parameter and which map of the chain it was`() {
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        plan.add(
            db.insertInto("tv_sink").values(listOf("amount"))
                .asStep().update("amount" to source.value().map { it.get<String>("name").toInt() })
        )

        val thrown = assertFailsWith<MappingException> { db.executeTransactionPlan(plan) }

        assertTrue(thrown.details.contains("Step 1 of the plan"), thrown.details)
        assertTrue(thrown.details.contains("map #1 over step 0"), thrown.details)
    }

    @Test
    fun `two maps on one parameter are told apart by their number`() {
        // A lambda has no name to report and the parameter is the same in both, so the number is the whole of
        // what separates them. Take it out of the message and these two failures read identically.
        fun detailsOf(chain: (StepHandle<Row>) -> TransactionValue<Int>): String {
            val plan = TransactionPlan()
            val source = plan.add(probeOne().fetchRowStrict())
            plan.add(
                db.insertInto("tv_sink").values(listOf("amount"))
                    .asStep().update("amount" to chain(source))
            )
            return assertFailsWith<MappingException> { db.executeTransactionPlan(plan) }.details
        }

        val firstThrows = detailsOf { it.value().map { row -> row.get<String>("name").toInt() }.map { n -> n + 1 } }
        val secondThrows = detailsOf { it.value().map { row -> row.get<Int>("amount") }.map { n -> listOf<Int>()[n] } }

        assertTrue(firstThrows.contains("map #1"), firstThrows)
        assertTrue(secondThrows.contains("map #2"), secondThrows)
    }

    @Test
    fun `a driver failure inside a transformation picks up the step on its path`() {
        // It is passed through as it was thrown, so the path the driver's own layers write to as they unwind
        // is the only place the step can be recorded without replacing the exception.
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        plan.add(
            db.insertInto("tv_sink").values(listOf("name"))
                .asStep().update("name" to source.value().map { it.get<String>("tribute") })
        )

        val thrown = assertFailsWith<MappingException> { db.executeTransactionPlan(plan) }
        assertEquals(MappingExceptionReason.COLUMN_NOT_FOUND, thrown.reason)
        assertEquals(listOf("step 1", "parameter 'name'", "map #1"), thrown.path.asReversed().take(3))
    }

    /** A merged plan whose last step maps a result produced by a step of the plan that was merged in. */
    private fun mergedPlan(): TransactionPlan {
        val head = TransactionPlan()
        head.add(db.insertInto("tv_sink").values(listOf("name")).asStep().update("name" to "Roma"))
        head.add(db.insertInto("tv_sink").values(listOf("name")).asStep().update("name" to "Ostia"))

        val tail = TransactionPlan()
        val source = tail.add(probeOne().fetchRowStrict())
        tail.add(
            db.insertInto("tv_sink").values(listOf("amount"))
                .asStep().update("amount" to source.value().map { it.get<String>("name").toInt() })
        )

        // The handle keeps the index it was created at, which is 0 - and after this merge its step is third.
        assertTrue(source.toString().contains("step 0"), source.toString())
        head.addPlan(tail)
        return head
    }

    @Test
    fun `a failure names where a step sits in the merged plan, not where its handle was created`() {
        val thrown = assertFailsWith<MappingException> { db.executeTransactionPlan(mergedPlan()) }

        assertTrue(thrown.details.contains("Step 3 of the plan"), thrown.details)
        assertTrue(thrown.details.contains("over step 2"), thrown.details)
    }

    // --- describe() ---------------------------------------------------------------------------------

    @Test
    fun `describe gives every step its index, its SQL and where each parameter comes from`() {
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        plan.add(
            db.insertInto("tv_sink").values(listOf("name", "amount")).asStep().update(
                "name" to "Roma",
                "amount" to source.value().map { it.get<Int>("amount") }.map { it * 2 }
            )
        )

        val described = plan.describe()

        assertTrue(described.startsWith("TransactionPlan, 2 steps"), described)
        assertTrue(described.contains("step 0"), described)
        assertTrue(described.contains("tv_probe"), described)
        assertTrue(described.contains("@amount <- step 0.map(#1).map(#2)"), described)
        // Padded to the longest name, so the sources read as a column.
        assertTrue(described.contains("@name   <- literal"), described)
    }

    @Test
    fun `describe names a spread and says the name it was filed under is dropped`() {
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchObjectStrict<Map<String, Any?>>())
        plan.add(
            db.insertInto("tv_sink").values(listOf("name", "amount"))
                .asStep().update("anything" to source.value().map { it - "id" }.spread())
        )

        val described = plan.describe()

        assertTrue(described.contains("@anything <- spread of step 0.map(#1), this name dropped"), described)
    }

    @Test
    fun `describe reports where a step sits in the merged plan, not where its handle was created`() {
        val described = mergedPlan().describe()

        assertTrue(described.startsWith("TransactionPlan, 4 steps"), described)
        assertTrue(described.contains("@amount <- step 2.map(#1)"), described)
    }

    @Test
    fun `describe says so where a step's query cannot render, and describes the rest anyway`() {
        // A plan holding one is among the things worth describing, so this is the one place the rendering
        // failure is reported instead of thrown.
        val plan = TransactionPlan()
        plan.add(db.insertInto("tv_sink").values(listOf("name")).asStep().update("name" to "Roma"))
        plan.add(db.update("tv_sink").setValues(listOf("name")).asStep().update("name" to "Ostia"))

        val described = plan.describe()

        assertTrue(described.contains("step 0"), described)
        assertTrue(described.contains("tv_sink"), described)
        assertTrue(described.contains("cannot be rendered"), described)
    }

    @Test
    fun `an empty plan describes as one, and a plan says its size where a line has room for no more`() {
        assertEquals("TransactionPlan, no steps", TransactionPlan().describe())
        assertEquals("TransactionPlan(0 steps)", TransactionPlan().toString())
    }

    @Test
    fun `a row-shaped step still answers value when nothing needs to bind it`() {
        // value() on a fetchRowStrict is only unusable as a *parameter*; as the plan's own result it is fine.
        val plan = TransactionPlan()
        val source = plan.add(probeOne().fetchRowStrict())
        val results = db.executeTransactionPlan(plan)

        val row: Row = results[source]
        assertEquals("Gallia", row.get<String>("name"))
    }
}
