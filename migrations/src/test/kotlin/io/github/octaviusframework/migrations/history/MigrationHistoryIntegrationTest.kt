package io.github.octaviusframework.migrations.history

import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.migrations.MigrationTestDatabase
import io.github.octaviusframework.migrations.MigrationVersion
import io.github.octaviusframework.migrations.OctaviusMigration
import io.github.octaviusframework.migrations.discovery.DiscoveredMigration
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MigrationHistoryIntegrationTest {

    private val history = MigrationHistory(MigrationTestDatabase.SCHEMA, "test_history")

    @BeforeEach
    fun freshSchema() = MigrationTestDatabase.reset()

    companion object {
        @JvmStatic
        @AfterAll
        fun cleanUp() = MigrationTestDatabase.drop()
    }

    private fun <T> withHistory(block: (OctaviusSession) -> T): T =
        MigrationTestDatabase.session().use { session ->
            history.bootstrap(session)
            block(session)
        }

    private fun sqlMigration(
        version: String?,
        description: String = "a migration",
        script: String = "V${version}__a_migration.sql",
        checksum: Long = 123L
    ) = DiscoveredMigration.Sql(
        version = version?.let { MigrationVersion.parse(it) },
        description = description,
        script = script,
        origin = "test:$script",
        checksum = checksum,
        transactional = true,
        content = "SELECT 1;"
    )

    // ---------------------------------------------------------------- bootstrap

    @Test
    fun `bootstrap creates the table, and says so before and after`() {
        MigrationTestDatabase.session().use { session ->
            assertFalse(history.exists(session), "the table should not be there yet")
            history.bootstrap(session)
            assertTrue(history.exists(session), "the table should be there now")
        }
    }

    @Test
    fun `bootstrap runs twice without complaint`() {
        // Every run re-asserts the shape; that is the whole upgrade mechanism, so it has to be idempotent.
        MigrationTestDatabase.session().use { session ->
            history.bootstrap(session)
            history.bootstrap(session)
            assertTrue(history.exists(session))
        }
    }

    @Test
    fun `bootstrap creates a schema that is not there`() {
        MigrationTestDatabase.drop()
        MigrationTestDatabase.session().use { session ->
            MigrationHistory(MigrationTestDatabase.SCHEMA, "test_history").bootstrap(session)
            assertTrue(history.exists(session))
        }
    }

    // ---------------------------------------------------------------- writing and reading

    @Test
    fun `a recorded migration comes back as it went in`() {
        val applied = withHistory { session ->
            history.record(session, sqlMigration("1"), MigrationState.SUCCESS, executionTimeMs = 42)
            history.readAll(session).single()
        }

        assertEquals("1", applied.version?.canonical)
        assertEquals("a migration", applied.description)
        assertEquals(MigrationType.SQL, applied.type)
        assertEquals("V1__a_migration.sql", applied.script)
        assertEquals(123L, applied.checksum)
        assertEquals(MigrationState.SUCCESS, applied.state)
        assertEquals(42L, applied.executionTimeMs)
        assertNull(applied.failedStatement)
        assertEquals(TestDatabase.USER, applied.installedBy)
        assertNotNull(applied.installedOn)
    }

    @Test
    fun `a checksum above Int MAX survives the round trip`() {
        // The column is bigint for this reason: CRC32 is unsigned and half its range does not fit an int.
        val big = 4_294_967_290L
        val applied = withHistory { session ->
            history.record(session, sqlMigration("1", checksum = big), MigrationState.SUCCESS, 1)
            history.readAll(session).single()
        }
        assertEquals(big, applied.checksum)
    }

    @Test
    fun `rows come back oldest first`() {
        val versions = withHistory { session ->
            // Recorded out of order on purpose: the order that comes back is the order they were applied in,
            // which is not the same question as what their versions are.
            history.record(session, sqlMigration("3", script = "V3__c.sql"), MigrationState.SUCCESS, 1)
            history.record(session, sqlMigration("1", script = "V1__a.sql"), MigrationState.SUCCESS, 1)
            history.record(session, sqlMigration("2", script = "V2__b.sql"), MigrationState.SUCCESS, 1)
            history.readAll(session).map { it.version?.canonical }
        }
        assertEquals(listOf("3", "1", "2"), versions)
    }

    @Test
    fun `a code migration records its type and no checksum`() {
        val applied = withHistory { session ->
            val migration = DiscoveredMigration.Code(
                version = MigrationVersion.parse("4"),
                description = "backfill provinces",
                script = "com.roma.V4__Backfill_provinces",
                origin = "com.roma.V4__Backfill_provinces",
                // Not this migration's real class; nothing here builds it, only files it.
                migrationClass = OctaviusMigration::class.java
            )
            history.record(session, migration, MigrationState.SUCCESS, 7)
            history.readAll(session).single()
        }

        assertEquals(MigrationType.CODE, applied.type)
        assertNull(applied.checksum, "a class has no honest checksum unless it declared one")
    }

    // ---------------------------------------------------------------- repeatable

    @Test
    fun `a repeatable migration is updated in place, not added again`() {
        val (rows, sameRow) = withHistory { session ->
            val first = history.record(
                session, sqlMigration(null, "rebuild views", "R__rebuild_views.sql", checksum = 1),
                MigrationState.SUCCESS, 1
            )
            val second = history.record(
                session, sqlMigration(null, "rebuild views", "R__rebuild_views.sql", checksum = 2),
                MigrationState.SUCCESS, 5
            )
            history.readAll(session) to (first == second)
        }

        assertEquals(1, rows.size, "a repeatable migration keeps one row, not one per run")
        assertTrue(sameRow, "the same row should have been updated")
        assertEquals(2L, rows.single().checksum, "the new checksum should have replaced the old")
        assertEquals(5L, rows.single().executionTimeMs)
    }

    @Test
    fun `two different repeatable migrations keep separate rows`() {
        val rows = withHistory { session ->
            history.record(session, sqlMigration(null, "a", "R__a.sql"), MigrationState.SUCCESS, 1)
            history.record(session, sqlMigration(null, "b", "R__b.sql"), MigrationState.SUCCESS, 1)
            history.readAll(session)
        }
        assertEquals(2, rows.size)
    }

    // ---------------------------------------------------------------- the two-step path

    @Test
    fun `a running migration can be closed off as succeeded`() {
        val applied = withHistory { session ->
            val id = history.record(session, sqlMigration("1"), MigrationState.RUNNING, 0)
            history.complete(session, id, MigrationState.SUCCESS, executionTimeMs = 99)
            history.readAll(session).single()
        }

        assertEquals(MigrationState.SUCCESS, applied.state)
        assertEquals(99L, applied.executionTimeMs)
        assertNull(applied.failedStatement)
    }

    @Test
    fun `a failure records which statement it stopped on`() {
        val applied = withHistory { session ->
            val id = history.record(session, sqlMigration("1"), MigrationState.RUNNING, 0)
            history.complete(session, id, MigrationState.FAILED, executionTimeMs = 12, failedStatement = 3)
            history.readAll(session).single()
        }

        assertEquals(MigrationState.FAILED, applied.state)
        assertEquals(3, applied.failedStatement)
    }

    @Test
    fun `a migration left running is still running on the next read`() {
        // What the next run has to see after a process died halfway through a non-transactional migration.
        val applied = withHistory { session ->
            history.record(session, sqlMigration("1"), MigrationState.RUNNING, 0)
            history.readAll(session).single()
        }
        assertEquals(MigrationState.RUNNING, applied.state)
    }

    // ---------------------------------------------------------------- what the database refuses

    @Test
    fun `two rows cannot claim one version`() {
        // The last line of defence. Discovery refuses this first, but two migrators racing past each other
        // would get here, and the index is what makes that a failure rather than a mess.
        assertThrows<ConstraintViolationException> {
            withHistory { session ->
                history.record(session, sqlMigration("1", script = "V1__a.sql"), MigrationState.SUCCESS, 1)
                history.record(session, sqlMigration("1", script = "V1__b.sql"), MigrationState.SUCCESS, 1)
            }
        }
    }

    @Test
    fun `many repeatable rows do not collide on their null version`() {
        // The index on version is partial for this reason: NULL is not equal to NULL, but a plain unique
        // index would still be the wrong shape to reason about here.
        val rows = withHistory { session ->
            for (name in listOf("a", "b", "c")) {
                history.record(session, sqlMigration(null, name, "R__$name.sql"), MigrationState.SUCCESS, 1)
            }
            history.readAll(session)
        }
        assertEquals(3, rows.size)
    }
}
