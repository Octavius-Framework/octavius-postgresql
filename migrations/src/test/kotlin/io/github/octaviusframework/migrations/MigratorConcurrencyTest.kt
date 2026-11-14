package io.github.octaviusframework.migrations

import io.github.octaviusframework.migrations.execution.MigrationReport
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.writeText
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Two applications coming up at the same time against one database.
 *
 * This is the case the advisory lock exists for, and the one that is invisible in a single-threaded suite: a
 * migrator that took no lock at all passes every other test in this module.
 */
class MigratorConcurrencyTest {

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

    private fun writeMigrations(dir: Path, count: Int) {
        for (n in 1..count) {
            dir.resolve("V${n}__table_$n.sql").writeText("CREATE TABLE $schema.table_$n (id int);")
        }
    }

    /** Runs [migrators] migrations concurrently, all released from the same starting line. */
    private fun raceOf(count: Int, dir: Path): List<Result<MigrationReport>> {
        val startingLine = CyclicBarrier(count)
        val results = arrayOfNulls<Result<MigrationReport>>(count)

        val threads = (0 until count).map { index ->
            thread {
                results[index] = runCatching {
                    startingLine.await(10, TimeUnit.SECONDS)
                    MigrationTestDatabase.session().use { session ->
                        OctaviusMigrator.onSession(session, config(dir)).migrate()
                    }
                }
            }
        }
        threads.forEach { it.join(30_000) }

        return results.map { it ?: Result.failure(AssertionError("a migrator never finished")) }
    }

    @Test
    fun `two migrators starting together apply every migration exactly once`(@TempDir dir: Path) {
        writeMigrations(dir, count = 3)

        val results = raceOf(count = 2, dir = dir)

        results.forEachIndexed { index, result ->
            assertTrue(result.isSuccess, "migrator $index failed: ${result.exceptionOrNull()}")
        }

        val applied = results.flatMap { it.getOrThrow().applied }
        assertEquals(
            3, applied.size,
            "between them the two migrators should have applied three migrations, not ${applied.size}"
        )
        assertEquals(
            listOf("1", "2", "3"), applied.mapNotNull { it.version?.canonical }.sorted(),
            "each migration should appear once across both runs"
        )
    }

    @Test
    fun `one of the two does the work and the other finds nothing to do`(@TempDir dir: Path) {
        writeMigrations(dir, count = 2)

        val reports = raceOf(count = 2, dir = dir).map { it.getOrThrow() }

        // Not "the first one": which of them wins the lock is the point of the lock, and asserting on which
        // would be asserting on the scheduler.
        assertEquals(
            1, reports.count { it.isEmpty() },
            "exactly one migrator should have found the database already up to date"
        )
        assertEquals(1, reports.count { it.applied.size == 2 })
    }

    @Test
    fun `four migrators leave one history row per migration`(@TempDir dir: Path) {
        writeMigrations(dir, count = 3)

        raceOf(count = 4, dir = dir).forEachIndexed { index, result ->
            assertTrue(result.isSuccess, "migrator $index failed: ${result.exceptionOrNull()}")
        }

        val rows = MigrationTestDatabase.session().use { session ->
            session.createNativeQuery("SELECT count(*) FROM $schema.history").fetchFieldStrict<Long>()
        }
        assertEquals(3L, rows, "three migrations, three rows, however many migrators there were")
    }

    @Test
    fun `the table each migration creates is created once`(@TempDir dir: Path) {
        // The other half of the same claim: a second migrator running the same CREATE TABLE would not
        // silently duplicate a row, it would fail outright. This says it never got that far.
        writeMigrations(dir, count = 2)

        raceOf(count = 3, dir = dir).forEachIndexed { index, result ->
            assertTrue(result.isSuccess, "migrator $index failed: ${result.exceptionOrNull()}")
        }

        val tables = MigrationTestDatabase.session().use { session ->
            session.createNativeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = $1 AND table_name LIKE 'table_%'"
            ).fetchFieldStrict<Long>(schema)
        }
        assertEquals(2L, tables)
    }
}
