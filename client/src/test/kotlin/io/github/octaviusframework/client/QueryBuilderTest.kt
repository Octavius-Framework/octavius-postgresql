package io.github.octaviusframework.client

import io.github.octaviusframework.client.query.LockWaitMode
import io.github.octaviusframework.client.query.QueryFragment
import io.github.octaviusframework.client.query.join
import io.github.octaviusframework.client.query.withParam
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the four builders in both of the ways they can be wrong: the SQL they render, asserted directly, and
 * whether PostgreSQL accepts what they rendered, asserted by running it.
 */
class QueryBuilderTest : AbstractClientIntegrationTest() {

    data class Legion(val id: Int, val name: String, val strength: Int)

    override val schema = """
        CREATE TABLE builder_legions (
            id       SERIAL PRIMARY KEY,
            name     TEXT NOT NULL UNIQUE,
            strength INT  NOT NULL DEFAULT 0,
            province TEXT
        );
    """.trimIndent()

    @BeforeEach
    fun clearTable() {
        db.rawQuery("TRUNCATE builder_legions RESTART IDENTITY").execute()
    }

    // --- SELECT: what it renders ------------------------------------------------------------------

    @Test
    fun `a minimal select renders only what it was given`() {
        assertEquals("SELECT id, name\nFROM legions", db.select("id", "name").from("legions").toSql())
    }

    @Test
    fun `clauses with nothing to say leave themselves out`() {
        // The whole reason for a builder over a string: a filter that is absent today produces no WHERE,
        // rather than a WHERE with a dangling condition or a hand-assembled string.
        val sql = db.select("*")
            .from("legions")
            .where(null)
            .groupBy("")
            .orderBy(null)
            .toSql()

        assertEquals("SELECT *\nFROM legions", sql)
    }

    @Test
    fun `a full select renders every clause in order`() {
        val sql = db.select("province", "count(*) AS total")
            .with("active", "SELECT id FROM legions WHERE disbanded_at IS NULL")
            .from("legions l JOIN active a ON a.id = l.id")
            .where("l.strength > @min")
            .groupBy("province")
            .having("count(*) > 1")
            .orderBy("total DESC")
            .limit(10)
            .offset(20)
            .forUpdate(of = "l", mode = LockWaitMode.SKIP_LOCKED)
            .toSql()

        assertEquals(
            """
            WITH active AS (SELECT id FROM legions WHERE disbanded_at IS NULL)
            SELECT province, count(*) AS total
            FROM legions l JOIN active a ON a.id = l.id
            WHERE l.strength > @min
            GROUP BY province
            HAVING count(*) > 1
            ORDER BY total DESC
            LIMIT 10
            OFFSET 20
            FOR UPDATE OF l SKIP LOCKED
            """.trimIndent(),
            sql
        )
    }

    @Test
    fun `page is counted from zero and sets both limit and offset`() {
        assertTrue(db.select("*").from("t").page(0, 20).toSql().endsWith("LIMIT 20"))
        assertTrue(db.select("*").from("t").page(2, 20).toSql().endsWith("LIMIT 20\nOFFSET 40"))
    }

    @Test
    fun `fromSubquery parenthesises and aliases`() {
        assertEquals(
            "SELECT *\nFROM (SELECT id FROM legions) AS l",
            db.select("*").fromSubquery("SELECT id FROM legions", "l").toSql()
        )
    }

    @Test
    fun `copy leaves the base untouched`() {
        val base = db.select("*").from("legions").orderBy("name")
        val strong = base.copy().where("strength > 1000")
        val weak = base.copy().where("strength <= 1000")

        assertEquals("SELECT *\nFROM legions\nORDER BY name", base.toSql())
        assertTrue(strong.toSql().contains("WHERE strength > 1000"))
        assertTrue(weak.toSql().contains("WHERE strength <= 1000"))
    }

    // --- INSERT / UPDATE / DELETE: what they render -----------------------------------------------

    @Test
    fun `insert pairs each declared column with its own placeholder`() {
        assertEquals(
            "INSERT INTO legions (name, strength)\nVALUES (@name, @strength)",
            db.insertInto("legions").values(listOf("name", "strength")).toSql()
        )
    }

    @Test
    fun `insert from a map reads its keys and not its values`() {
        val row = mapOf<String, Any?>("name" to "IX Hispana", "strength" to 4800)

        assertEquals(
            "INSERT INTO legions (name, strength)\nVALUES (@name, @strength)",
            db.insertInto("legions").values(row).toSql()
        )
    }

    @Test
    fun `insert mixes placeholders with expressions, and renders ON CONFLICT and RETURNING`() {
        val sql = db.insertInto("legions")
            .value("name")
            .valueExpression("enrolled_at", "now()")
            .onConflict {
                onColumns("name")
                doUpdate("strength = excluded.strength", whereCondition = "legions.strength < excluded.strength")
            }
            .returning("id")
            .toSql()

        assertEquals(
            """
            INSERT INTO legions (name, enrolled_at)
            VALUES (@name, now())
            ON CONFLICT (name) DO UPDATE SET strength = excluded.strength WHERE legions.strength < excluded.strength
            RETURNING id
            """.trimIndent(),
            sql
        )
    }

    @Test
    fun `insert from a select names its target columns instead of declaring values`() {
        assertEquals(
            "INSERT INTO legions (name, strength)\nSELECT name, strength FROM legion_drafts",
            db.insertInto("legions")
                .columns("name", "strength")
                .fromSelect("SELECT name, strength FROM legion_drafts")
                .toSql()
        )
    }

    @Test
    fun `update renders assignments, from, where and returning`() {
        val sql = db.update("legion_supplies")
            .setExpression("quantity", "quantity - @taken")
            .setValue("last_drawn_at")
            .from("legions l")
            .where("legion_supplies.legion_id = l.id AND l.id = @id")
            .returning("quantity")
            .toSql()

        assertEquals(
            """
            UPDATE legion_supplies
            SET quantity = quantity - @taken, last_drawn_at = @last_drawn_at
            FROM legions l
            WHERE legion_supplies.legion_id = l.id AND l.id = @id
            RETURNING quantity
            """.trimIndent(),
            sql
        )
    }

    @Test
    fun `delete renders using, where and returning`() {
        assertEquals(
            "DELETE FROM mandates\nUSING legions l\nWHERE mandates.legion_id = l.id AND l.disbanded\nRETURNING mandates.id",
            db.deleteFrom("mandates")
                .using("legions l")
                .where("mandates.legion_id = l.id AND l.disbanded")
                .returning("mandates.id")
                .toSql()
        )
    }

    // --- What a builder refuses to render ---------------------------------------------------------

    private fun assertRefuses(message: String, block: () -> Unit) {
        val thrown = assertFailsWith<InvalidOperationException>(message, block)
        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, thrown.reason)
    }

    @Test
    fun `an update without a where is refused`() {
        // Not a limitation - a decision. rawQuery() is where a whole-table update is written, and there it
        // is visible to whoever reads the diff.
        assertRefuses("update without where") { db.update("legions").setValue("strength").toSql() }
    }

    @Test
    fun `a delete without a where is refused`() {
        assertRefuses("delete without where") { db.deleteFrom("legions").toSql() }
    }

    @Test
    fun `a select whose clauses have nothing to apply to is refused`() {
        assertRefuses("where without from") { db.select("*").where("id = 1").toSql() }
        assertRefuses("having without group by") { db.select("*").from("t").having("count(*) > 1").toSql() }
    }

    @Test
    fun `an insert with neither values nor a select is refused`() {
        assertRefuses("insert with nothing") { db.insertInto("legions").toSql() }
    }

    @Test
    fun `an insert cannot take its rows from both values and a select`() {
        assertRefuses("values then select") {
            db.insertInto("legions").value("name").fromSelect("SELECT name FROM drafts").toSql()
        }
        assertRefuses("select then values") {
            db.insertInto("legions").fromSelect("SELECT name FROM drafts").value("name").toSql()
        }
    }

    @Test
    fun `an ON CONFLICT with no action is refused`() {
        assertRefuses("no action") {
            db.insertInto("legions").value("name").onConflict { onColumns("name") }.toSql()
        }
    }

    // --- QueryFragment ----------------------------------------------------------------------------

    @Test
    fun `join drops the filters that were not there and merges the parameters of those that were`() {
        fun filters(name: String?, minStrength: Int?) = listOfNotNull(
            name?.let { "name ILIKE @name" withParam ("name" to "%$it%") },
            minStrength?.let { "strength >= @minStrength" withParam ("minStrength" to it) }
        ).join(" AND ")

        val both = filters("hisp", 1000)
        assertEquals("(name ILIKE @name) AND (strength >= @minStrength)", both.sql)
        assertEquals(mapOf<String, Any?>("name" to "%hisp%", "minStrength" to 1000), both.params)

        val one = filters("hisp", null)
        assertEquals("(name ILIKE @name)", one.sql)
        assertEquals(mapOf<String, Any?>("name" to "%hisp%"), one.params)

        val none = filters(null, null)
        assertTrue(none.isEmpty)
        assertEquals(emptyMap<String, Any?>(), none.params)
    }

    @Test
    fun `join refuses two fragments that name one parameter differently`() {
        // Silently keeping one of the two values would make the result depend on the order the filters were
        // listed in, which is exactly the bug a fragment exists to prevent.
        assertRefuses("colliding names") {
            listOf(
                "a = @v" withParam ("v" to 1),
                "b = @v" withParam ("v" to 2)
            ).join(" AND ")
        }
    }

    @Test
    fun `join parenthesises each fragment so a joined OR keeps its precedence`() {
        // Without the parentheses this reads as `a = 1 OR (b = 2 AND c = 3)`, because AND binds tighter -
        // a different set of rows, and nothing in the code would say so.
        val joined = listOf(
            QueryFragment("a = 1 OR b = 2"),
            QueryFragment("c = 3")
        ).join(" AND ")

        assertEquals("(a = 1 OR b = 2) AND (c = 3)", joined.sql)
    }

    @Test
    fun `join can put the keyword in front, and drops it along with an empty filter`() {
        val present = listOf(QueryFragment("a = 1")).join(" AND ", prefix = "WHERE ")
        assertEquals("WHERE (a = 1)", present.sql)

        val absent = emptyList<QueryFragment>().join(" AND ", prefix = "WHERE ")
        assertEquals("", absent.sql)
    }

    @Test
    fun `an empty join leaves the WHERE out altogether`() {
        val filter = emptyList<QueryFragment>().join(" AND ")
        assertEquals("SELECT *\nFROM legions", db.select("*").from("legions").where(filter.sql).toSql())
    }

    // --- Against the server -----------------------------------------------------------------------

    @Test
    fun `what the builders render, PostgreSQL runs`() {
        val id = db.insertInto("builder_legions")
            .values(listOf("name", "strength"))
            .valueExpression("province", "'Hispania'")
            .returning("id")
            .fetchFieldStrict<Int>("name" to "IX Hispana", "strength" to 4800)

        db.insertInto("builder_legions")
            .values(listOf("name", "strength"))
            .update("name" to "X Fretensis", "strength" to 5200)

        val strong = db.select("id", "name", "strength")
            .from("builder_legions")
            .where("strength > @min")
            .orderBy("name")
            .fetchObjects<Legion>("min" to 5000)

        assertEquals(listOf("X Fretensis"), strong.map { it.name })

        val newStrength = db.update("builder_legions")
            .setExpression("strength", "strength - @lost")
            .where("id = @id")
            .returning("strength")
            .fetchFieldStrict<Int>("lost" to 800, "id" to id)

        assertEquals(4000, newStrength)

        val removed = db.deleteFrom("builder_legions")
            .where("strength < @floor")
            .returning("name")
            .fetchFields<String>("floor" to 4500)

        assertEquals(listOf("IX Hispana"), removed)
    }

    @Test
    fun `ON CONFLICT DO UPDATE upserts against the server`() {
        db.insertInto("builder_legions")
            .values(listOf("name", "strength"))
            .update("name" to "IX Hispana", "strength" to 4800)

        db.insertInto("builder_legions")
            .values(listOf("name", "strength"))
            .onConflict {
                onColumns("name")
                doUpdate("strength = excluded.strength")
            }
            .update("name" to "IX Hispana", "strength" to 5000)

        val rows = db.select("name", "strength").from("builder_legions").fetchRows()
        assertEquals(1, rows.size)
        assertEquals(5000, rows.single().get<Int>("strength"))
    }

    @Test
    fun `a filter assembled at runtime binds only the parameters that survived`() {
        db.insertInto("builder_legions").values(listOf("name", "strength"))
            .update("name" to "IX Hispana", "strength" to 4800)
        db.insertInto("builder_legions").values(listOf("name", "strength"))
            .update("name" to "X Fretensis", "strength" to 5200)

        fun search(name: String?, minStrength: Int?): List<String> {
            val filter = listOfNotNull(
                name?.let { "name ILIKE @name" withParam ("name" to "%$it%") },
                minStrength?.let { "strength >= @minStrength" withParam ("minStrength" to it) }
            ).join(" AND ")

            return db.select("name")
                .from("builder_legions")
                .where(filter.sql)
                .orderBy("name")
                .fetchFields<String>(filter.params)
        }

        assertEquals(listOf("IX Hispana", "X Fretensis"), search(null, null))
        assertEquals(listOf("X Fretensis"), search("fret", null))
        assertEquals(listOf("X Fretensis"), search(null, 5000))
        assertEquals(emptyList(), search("hisp", 5000))
    }

    @Test
    fun `a builder composes into another statement through toSql`() {
        db.insertInto("builder_legions").values(listOf("name", "strength"))
            .update("name" to "IX Hispana", "strength" to 4800)

        val strong = db.select("name").from("builder_legions").where("strength >= @min")
        val counted = db.rawQuery("SELECT count(*) FROM (${strong.toSql()}) AS s")

        assertEquals(1L, counted.fetchFieldStrict<Long>("min" to 4000))
        assertEquals(0L, counted.fetchFieldStrict<Long>("min" to 9000))
    }

    @Test
    fun `a builder reaches the result style like any other query`() {
        db.insertInto("builder_legions").values(listOf("name", "strength"))
            .update("name" to "IX Hispana", "strength" to 4800)

        val clash = db.insertInto("builder_legions")
            .values(listOf("name", "strength"))
            .asResult()
            .update("name" to "IX Hispana", "strength" to 1)

        assertTrue(clash is DataResult.Failure)
        assertNull(clash.getOrNull())
    }
}
