package io.github.octaviusframework.migrations

import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.TransactionState
import io.github.octaviusframework.migrations.execution.MigrationReport
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The SQL that made the design what it is, run against a real server.
 *
 * Everything here is a case the migrator was shaped around rather than an invented one: a statement the
 * server refuses inside a transaction, a function body full of semicolons, a `SELECT` in the middle of a
 * script the way `pg_dump` writes them.
 */
class MigratorRealSqlIntegrationTest {

    private val schema = MigrationTestDatabase.SCHEMA

    @BeforeEach
    fun freshSchema() = MigrationTestDatabase.reset()

    companion object {
        @JvmStatic
        @AfterAll
        fun cleanUp() = MigrationTestDatabase.drop()
    }

    private fun config(dir: Path) = MigratorConfig(
        sqlLocations = listOf("filesystem:$dir"),
        historySchema = schema,
        historyTable = "history"
    )

    private fun migrate(dir: Path): MigrationReport =
        MigrationTestDatabase.session().use { OctaviusMigrator.onSession(it, config(dir)).migrate() }

    private fun <T> onSession(read: (OctaviusSession) -> T): T = MigrationTestDatabase.session().use(read)

    private fun indexExists(name: String): Boolean = MigrationTestDatabase.session().use { session ->
        session.createNativeQuery("SELECT to_regclass($1) IS NOT NULL").fetchFieldStrict("$schema.$name")
    }

    // ------------------------------------------------------ the statement the whole path exists for

    @Test
    fun `CREATE INDEX CONCURRENTLY runs when the file asks for no transaction`(@TempDir dir: Path) {
        dir.resolve("V1__table.sql").writeText("CREATE TABLE $schema.castra (id int);")
        dir.resolve("V2__index.sql").writeText(
            "-- octavius:no-transaction\nCREATE INDEX CONCURRENTLY idx_castra ON $schema.castra (id);"
        )

        val report = migrate(dir)

        assertEquals(2, report.applied.size)
        assertTrue(indexExists("idx_castra"), "the index should be there")
    }

    @Test
    fun `the same statement without the directive is refused by the server`(@TempDir dir: Path) {
        // The other half of the pair above. Without it, the first test would pass just as happily against a
        // migrator that ignored the directive entirely and never opened a transaction at all.
        dir.resolve("V1__table.sql").writeText("CREATE TABLE $schema.castra (id int);")
        dir.resolve("V2__index.sql").writeText("CREATE INDEX CONCURRENTLY idx_castra ON $schema.castra (id);")

        val e = assertThrows<MigrationException> { migrate(dir) }

        assertEquals(MigrationExceptionReason.MIGRATION_FAILED, e.reason)
        assertFalse(indexExists("idx_castra"))
    }

    // ------------------------------------------------------ semicolons that are not separators

    @Test
    fun `a function body full of semicolons survives being split`(@TempDir dir: Path) {
        // Non-transactional on purpose: that is the path that actually cuts the file up, and a body cut in
        // the middle would arrive as a syntax error rather than a function.
        dir.resolve("V1__function.sql").writeText(
            $$$"""
            -- octavius:no-transaction
            CREATE FUNCTION $$$schema.legions() RETURNS int AS $body$
            DECLARE total int;
            BEGIN
                total := 3;
                RETURN total;
            END;
            $body$ LANGUAGE plpgsql;
            SELECT $$$schema.legions();
            """.trimIndent()
        )

        migrate(dir)

        val answer = onSession { it.createNativeQuery("SELECT $schema.legions()").fetchFieldStrict<Int>() }
        assertEquals(3, answer)
    }

    @Test
    fun `BEGIN and END inside a function body are not transaction control`(@TempDir dir: Path) {
        // They are the plpgsql block, not the transaction. The check has to tell those apart, and this is a
        // file that would be refused outright if it could not.
        dir.resolve("V1__function.sql").writeText(
            $$$"""
            CREATE FUNCTION $$$schema.one() RETURNS int AS $body$
            BEGIN
                RETURN 1;
            END;
            $body$ LANGUAGE plpgsql;
            """.trimIndent()
        )

        assertEquals(1, migrate(dir).applied.size)
    }

    @Test
    fun `a BEGIN ATOMIC body is not transaction control`(@TempDir dir: Path) {
        // A standard-SQL body is not quoted, so its END stands where a COMMIT could. Transactional on purpose:
        // the file goes to the server whole, and only the scan before it could refuse it.
        dir.resolve("V1__function.sql").writeText(
            """
            CREATE FUNCTION $schema.one() RETURNS int LANGUAGE sql
            BEGIN ATOMIC
                SELECT 1;
            END;
            """.trimIndent()
        )

        assertEquals(1, migrate(dir).applied.size)
        assertEquals(1, onSession { it.createNativeQuery("SELECT $schema.one()").fetchFieldStrict<Int>() })
    }

    @Test
    fun `a BEGIN ATOMIC body full of semicolons survives being split`(@TempDir dir: Path) {
        dir.resolve("V1__function.sql").writeText(
            """
            -- octavius:no-transaction
            CREATE TABLE $schema.castra (id int);
            CREATE FUNCTION $schema.fortify(n int) RETURNS int LANGUAGE sql
            BEGIN ATOMIC
                INSERT INTO $schema.castra VALUES (n);
                SELECT CASE WHEN count(*) > 1 THEN 2 ELSE 1 END FROM $schema.castra;
            END;
            SELECT $schema.fortify(7);
            """.trimIndent()
        )

        migrate(dir)

        val answer = onSession { it.createNativeQuery("SELECT $schema.fortify(8)").fetchFieldStrict<Int>() }
        assertEquals(2, answer, "the call in the script and this one should both have inserted")
    }

    // ------------------------------------------------------ a SELECT in the middle of a script

    @Test
    fun `a script may set a sequence the way pg_dump writes it`(@TempDir dir: Path) {
        dir.resolve("V1__sequence.sql").writeText(
            """
            CREATE SEQUENCE $schema.legion_id_seq;
            SELECT pg_catalog.setval('$schema.legion_id_seq', 42, true);
            """.trimIndent()
        )

        migrate(dir)

        val next = onSession { it.createNativeQuery("SELECT nextval('$schema.legion_id_seq')").fetchFieldStrict<Long>() }
        assertEquals(43L, next, "the setval in the middle of the script should have taken effect")
    }

    // ------------------------------------------------------ what a migration may not do

    @Test
    fun `a file that commits its own transaction is refused before anything runs`(@TempDir dir: Path) {
        dir.resolve("V1__first.sql").writeText("CREATE TABLE $schema.first (id int);")
        dir.resolve("V2__commits.sql").writeText(
            "CREATE TABLE $schema.second (id int);\nCOMMIT;\nCREATE TABLE $schema.third (id int);"
        )

        val e = assertThrows<MigrationException> { migrate(dir) }

        assertEquals(MigrationExceptionReason.INVALID_MIGRATION, e.reason)
        assertTrue(e.details!!.contains("COMMIT"), "should name the statement: ${e.details}")

        // Refused at the scan, so even the migration in front of the bad one never ran.
        val firstExists = MigrationTestDatabase.session().use { session ->
            session.createNativeQuery("SELECT to_regclass($1) IS NOT NULL").fetchFieldStrict<Boolean>("$schema.first")
        }
        assertFalse(firstExists, "a bad file stops the run before the database is touched at all")
    }

    // ------------------------------------------------------ the session afterwards

    @Test
    fun `a failed migration leaves the session usable`(@TempDir dir: Path) {
        dir.resolve("V1__broken.sql").writeText("SELECT * FROM a_table_that_is_not_there;")

        MigrationTestDatabase.session().use { session ->
            assertThrows<MigrationException> { OctaviusMigrator.onSession(session, config(dir)).migrate() }

            assertEquals(
                TransactionState.IDLE, session.transactionState,
                "the run must not leave a transaction open behind it"
            )
            assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
        }
    }
}
