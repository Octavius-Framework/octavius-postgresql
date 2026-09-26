package io.github.octaviusframework.migrations

import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.migrations.execution.MigrationInfo
import io.github.octaviusframework.migrations.execution.MigrationReport
import io.github.octaviusframework.migrations.execution.MigrationStatus
import io.github.octaviusframework.migrations.history.MigrationState
import io.github.octaviusframework.migrations.history.MigrationType
import io.github.octaviusframework.testsupport.TestDatabase
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class OctaviusMigratorIntegrationTest {

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
        historySchema = MigrationTestDatabase.SCHEMA,
        historyTable = "history"
    )

    private fun <T> onSession(block: (OctaviusSession) -> T): T = MigrationTestDatabase.session().use(block)

    private fun migrate(config: MigratorConfig): MigrationReport =
        onSession { OctaviusMigrator.onSession(it, config).migrate() }

    private fun info(config: MigratorConfig): List<MigrationInfo> =
        onSession { OctaviusMigrator.onSession(it, config).info() }

    /** Whether a table exists in the test schema. */
    private fun tableExists(name: String): Boolean = onSession { session ->
        session.createNativeQuery("SELECT to_regclass($1) IS NOT NULL").fetchFieldStrict("$schema.$name")
    }

    private fun creates(table: String) = "CREATE TABLE $schema.$table (id int);"

    // ---------------------------------------------------------------- the ordinary run

    @Test
    fun `applies every migration in version order`(@TempDir dir: Path) {
        dir.resolve("V2__second.sql").writeText(creates("second"))
        dir.resolve("V1__first.sql").writeText(creates("first"))
        dir.resolve("V10__tenth.sql").writeText(creates("tenth"))

        val report = migrate(config(dir))

        assertEquals(listOf("1", "2", "10"), report.applied.map { it.version?.canonical })
        assertTrue(tableExists("first") && tableExists("second") && tableExists("tenth"))
    }

    @Test
    fun `a second run has nothing to do`(@TempDir dir: Path) {
        dir.resolve("V1__first.sql").writeText(creates("first"))
        migrate(config(dir))

        val second = migrate(config(dir))

        assertTrue(second.isEmpty(), "the second run should have found the database up to date")
    }

    @Test
    fun `the history records what ran`(@TempDir dir: Path) {
        dir.resolve("V1__create_first.sql").writeText(creates("first"))

        val applied = migrate(config(dir)).applied.single()

        assertEquals("1", applied.version?.canonical)
        assertEquals("create first", applied.description)
        assertEquals(MigrationType.SQL, applied.type)
        assertEquals("V1__create_first.sql", applied.script)
        assertEquals(MigrationState.SUCCESS, applied.state)
        assertEquals(TestDatabase.USER, applied.installedBy)
    }

    @Test
    fun `a migration written in Kotlin runs too`() {
        val report = migrate(
            MigratorConfig(
                sqlLocations = emptyList(),
                codePackages = listOf("io.github.octaviusframework.migrations.fixtures.runnable"),
                historySchema = schema,
                historyTable = "history"
            )
        )

        assertEquals(listOf("20", "21"), report.applied.map { it.version?.canonical })
        assertEquals(MigrationType.CODE, report.applied.first().type)
        assertTrue(tableExists("from_code"), "the code migration should have created its table")
        assertNull(report.applied.first().checksum, "a class records no checksum unless it declares one")
    }

    // ---------------------------------------------------------------- failure, in a transaction

    @Test
    fun `a failing transactional migration leaves nothing behind`(@TempDir dir: Path) {
        dir.resolve("V1__first.sql").writeText(creates("first"))
        dir.resolve("V2__broken.sql").writeText(
            creates("half_way") + "\nSELECT * FROM a_table_that_is_not_there;"
        )

        assertThrows<MigrationException> { migrate(config(dir)) }

        assertTrue(tableExists("first"), "the migration before the broken one stays applied")
        assertFalse(tableExists("half_way"), "the broken migration's own work must have rolled back")

        val rows = info(config(dir))
        assertEquals(MigrationStatus.APPLIED, rows.first { it.version?.canonical == "1" }.status)
        assertEquals(
            MigrationStatus.PENDING,
            rows.first { it.version?.canonical == "2" }.status,
            "a transactional failure leaves no row, so the migration is simply pending again"
        )
    }

    @Test
    fun `a transactional failure can be fixed and re-run`(@TempDir dir: Path) {
        val broken = dir.resolve("V1__first.sql")
        broken.writeText("SELECT * FROM a_table_that_is_not_there;")
        assertThrows<MigrationException> { migrate(config(dir)) }

        // Nothing to repair, and no checksum complaint either: the first attempt left no record of itself.
        broken.writeText(creates("first"))
        val report = migrate(config(dir))

        assertEquals(1, report.applied.size)
        assertTrue(tableExists("first"))
    }

    // ---------------------------------------------------------------- failure, outside a transaction

    @Test
    fun `a failing non-transactional migration keeps what it managed and says where it stopped`(
        @TempDir dir: Path
    ) {
        dir.resolve("V1__partly.sql").writeText(
            """
            -- octavius:no-transaction
            ${creates("made_it")}
            SELECT * FROM a_table_that_is_not_there;
            ${creates("never_reached")}
            """.trimIndent()
        )

        assertThrows<MigrationException> { migrate(config(dir)) }

        assertTrue(tableExists("made_it"), "statements before the failure stay applied")
        assertFalse(tableExists("never_reached"), "statements after it never ran")

        val row = info(config(dir)).single().applied!!
        assertEquals(MigrationState.FAILED, row.state)
        assertEquals(2, row.failedStatement, "the second statement is the one that failed")
    }

    @Test
    fun `a run refuses to go on while a migration is left half-applied`(@TempDir dir: Path) {
        dir.resolve("V1__partly.sql").writeText(
            "-- octavius:no-transaction\n${creates("made_it")}\nSELECT * FROM nope;"
        )
        assertThrows<MigrationException> { migrate(config(dir)) }

        val e = assertThrows<MigrationException> { migrate(config(dir)) }

        assertEquals(MigrationExceptionReason.HISTORY_INCOMPLETE, e.reason)
        assertTrue(e.details!!.contains("statement 2"), "should say where it stopped: ${e.details}")
    }

    // ---------------------------------------------------------------- validation

    @Test
    fun `a migration that changed after it ran is refused`(@TempDir dir: Path) {
        val file = dir.resolve("V1__first.sql")
        file.writeText(creates("first"))
        migrate(config(dir))

        file.writeText(creates("first") + "\n-- and one more thought")

        val e = assertThrows<MigrationException> { migrate(config(dir)) }
        assertEquals(MigrationExceptionReason.VALIDATION_FAILED, e.reason)
        assertTrue(e.details!!.contains("hashes to"), "should show both checksums: ${e.details}")
    }

    @Test
    fun `only the line endings changing is not a change`(@TempDir dir: Path) {
        val file = dir.resolve("V1__first.sql")
        file.writeText(creates("first") + "\n")
        migrate(config(dir))

        file.writeText(creates("first").replace("\n", "\r\n") + "\r\n\r\n")

        // The whole reason the checksum normalises: this is one checkout meeting another, not an edit.
        assertTrue(migrate(config(dir)).isEmpty())
    }

    @Test
    fun `a migration the database ran and the disk has lost is refused`(@TempDir dir: Path) {
        val file = dir.resolve("V1__first.sql")
        file.writeText(creates("first"))
        migrate(config(dir))

        java.nio.file.Files.delete(file)
        dir.resolve("V2__second.sql").writeText(creates("second"))

        val e = assertThrows<MigrationException> { migrate(config(dir)) }
        assertEquals(MigrationExceptionReason.VALIDATION_FAILED, e.reason)
        assertTrue(e.details!!.contains("1 first"), "should name the missing migration: ${e.details}")
    }

    @Test
    fun `a migration arriving below one already applied is refused`(@TempDir dir: Path) {
        dir.resolve("V2__second.sql").writeText(creates("second"))
        migrate(config(dir))

        dir.resolve("V1__late_arrival.sql").writeText(creates("late"))

        val e = assertThrows<MigrationException> { migrate(config(dir)) }
        assertEquals(MigrationExceptionReason.VALIDATION_FAILED, e.reason)
        assertTrue(e.details!!.contains("outOfOrder"), "should name the way out: ${e.details}")
    }

    @Test
    fun `out of order applies the late arrival when it is allowed`(@TempDir dir: Path) {
        dir.resolve("V2__second.sql").writeText(creates("second"))
        migrate(config(dir))

        dir.resolve("V1__late_arrival.sql").writeText(creates("late"))
        val report = migrate(config(dir).copy(outOfOrder = true))

        assertEquals(listOf("1"), report.applied.map { it.version?.canonical })
        assertTrue(tableExists("late"))
    }

    // ---------------------------------------------------------------- placeholders

    @Test
    fun `a placeholder is pasted into the sql the server runs`(@TempDir dir: Path) {
        dir.resolve("V1__create.sql").writeText($$"""CREATE TABLE $$schema.${tab} (id int);""")

        val report = migrate(config(dir).copy(placeholders = mapOf("tab" to "castra_roma")))

        assertEquals(1, report.applied.size)
        assertTrue(tableExists("castra_roma"), "the placeholder should have named the table")
    }

    @Test
    fun `changing a placeholder value is not a change to the migration`(@TempDir dir: Path) {
        // The checksum is the file's and not the filled-in text's, which is the whole reason one migration
        // can be deployed with a different value per environment: were it the other way round, the first run
        // anywhere would leave every other environment refusing the file as changed.
        dir.resolve("V1__create.sql").writeText($$"""CREATE TABLE $$schema.${tab} (id int);""")
        migrate(config(dir).copy(placeholders = mapOf("tab" to "castra_dev")))

        val second = migrate(config(dir).copy(placeholders = mapOf("tab" to "castra_prod")))

        assertTrue(second.isEmpty(), "the second run should have found the database up to date")
        assertTrue(tableExists("castra_dev"), "the first value is what ran")
        assertFalse(tableExists("castra_prod"), "and the second run should not have run it again")
    }

    @Test
    fun `a placeholder with no value stops the run before anything is applied`(@TempDir dir: Path) {
        dir.resolve("V1__first.sql").writeText(creates("first"))
        dir.resolve("V2__second.sql").writeText($$"""CREATE TABLE $$schema.${tab} (id int);""")

        val e = assertThrows<MigrationException> {
            migrate(config(dir).copy(placeholders = mapOf("elsewhere" to "roma")))
        }

        assertEquals(MigrationExceptionReason.INVALID_MIGRATION, e.reason)
        assertFalse(tableExists("first"), "the refusal is at the scan, so the one in front of it never ran")
    }

    // ---------------------------------------------------------------- repeatable

    @Test
    fun `a repeatable migration runs again once its content changes`(@TempDir dir: Path) {
        val file = dir.resolve("R__view.sql")
        file.writeText("CREATE OR REPLACE VIEW $schema.v AS SELECT 1 AS n;")
        assertEquals(1, migrate(config(dir)).applied.size)

        assertTrue(migrate(config(dir)).isEmpty(), "unchanged, so there was nothing to do")

        file.writeText("CREATE OR REPLACE VIEW $schema.v AS SELECT 2 AS n;")
        assertEquals(1, migrate(config(dir)).applied.size, "changed, so it should run again")

        val n = onSession { it.createNativeQuery("SELECT n FROM $schema.v").fetchFieldStrict<Int>() }
        assertEquals(2, n)
    }

    @Test
    fun `a repeatable migration runs after the versioned ones`(@TempDir dir: Path) {
        dir.resolve("R__view.sql").writeText("CREATE OR REPLACE VIEW $schema.v AS SELECT id FROM $schema.first;")
        dir.resolve("V1__first.sql").writeText(creates("first"))

        // The view names a table the versioned migration creates, so this only works in one order.
        val report = migrate(config(dir))

        assertEquals(listOf("1", null), report.applied.map { it.version?.canonical })
    }

    @Test
    fun `a repeatable migration keeps one row however often it runs`(@TempDir dir: Path) {
        val file = dir.resolve("R__view.sql")
        for (n in 1..3) {
            file.writeText("CREATE OR REPLACE VIEW $schema.v AS SELECT $n AS n;")
            migrate(config(dir))
        }

        assertEquals(1, info(config(dir)).count { it.version == null })
    }

    // ---------------------------------------------------------------- baseline and target

    @Test
    fun `a baseline adopts a database at a version and skips what is below it`(@TempDir dir: Path) {
        dir.resolve("V1__already_there.sql").writeText("SELECT * FROM a_table_that_is_not_there;")
        dir.resolve("V2__also_there.sql").writeText("SELECT * FROM nor_this_one;")
        dir.resolve("V3__the_new_one.sql").writeText(creates("the_new_one"))

        // The first two would fail if they ran. That they do not is the assertion.
        val report = migrate(config(dir).copy(baselineVersion = "2"))

        assertEquals(listOf("3"), report.applied.map { it.version?.canonical })
        assertTrue(tableExists("the_new_one"))
    }

    @Test
    fun `a baseline is only written for a database with no history at all`(@TempDir dir: Path) {
        dir.resolve("V1__first.sql").writeText(creates("first"))
        migrate(config(dir))

        dir.resolve("V2__second.sql").writeText(creates("second"))
        val report = migrate(config(dir).copy(baselineVersion = "5"))

        assertEquals(
            listOf("2"), report.applied.map { it.version?.canonical },
            "a database that already had a history table has already been adopted"
        )
    }

    @Test
    fun `target stops the run before a higher version`(@TempDir dir: Path) {
        dir.resolve("V1__first.sql").writeText(creates("first"))
        dir.resolve("V2__second.sql").writeText(creates("second"))
        dir.resolve("V3__third.sql").writeText(creates("third"))

        val report = migrate(config(dir).copy(target = "2"))

        assertEquals(listOf("1", "2"), report.applied.map { it.version?.canonical })
        assertFalse(tableExists("third"))
        assertEquals(
            MigrationStatus.ABOVE_TARGET,
            info(config(dir).copy(target = "2")).first { it.version?.canonical == "3" }.status
        )
    }

    // ---------------------------------------------------------------- info

    @Test
    fun `info creates nothing`(@TempDir dir: Path) {
        dir.resolve("V1__first.sql").writeText(creates("first"))

        val infos = info(config(dir))

        assertEquals(listOf(MigrationStatus.PENDING), infos.map { it.status })
        assertFalse(tableExists("history"), "info must not bring the history table into being")
    }

    @Test
    fun `info reports a drifted checksum instead of refusing`(@TempDir dir: Path) {
        val file = dir.resolve("V1__first.sql")
        file.writeText(creates("first"))
        migrate(config(dir))
        file.writeText(creates("first") + "\n-- changed")

        val info = info(config(dir)).single()

        assertEquals(MigrationStatus.CHANGED, info.status)
        assertTrue(info.checksum != info.applied?.checksum, "both checksums should be there to compare")
    }

    // ---------------------------------------------------------------- through a DataSource

    @Test
    fun `a migrator reached through a DataSource borrows a session and gives it back`(@TempDir dir: Path) {
        // The documented way in, and until now the untested one: every other test here hands it a session.
        dir.resolve("V1__first.sql").writeText(creates("first"))

        val report = OctaviusMigrator(MigrationTestDatabase.dataSource(), config(dir)).migrate()

        assertEquals(1, report.applied.size)
        assertTrue(tableExists("first"))
    }

    @Test
    fun `info through a DataSource answers without applying anything`(@TempDir dir: Path) {
        dir.resolve("V1__first.sql").writeText(creates("first"))

        val infos = OctaviusMigrator(MigrationTestDatabase.dataSource(), config(dir)).info()

        assertEquals(listOf(MigrationStatus.PENDING), infos.map { it.status })
        assertFalse(tableExists("first"))
    }

    // ---------------------------------------------------------------- a migration that throws

    @Test
    fun `a code migration that throws is reported with what it threw`() {
        val e = assertThrows<MigrationException> {
            migrate(
                MigratorConfig(
                    sqlLocations = emptyList(),
                    codePackages = listOf("io.github.octaviusframework.migrations.fixtures.throwing"),
                    historySchema = schema,
                    historyTable = "history"
                )
            )
        }

        assertEquals(MigrationExceptionReason.MIGRATION_FAILED, e.reason)
        assertTrue(e.details!!.contains("V30"), "should name the migration: ${e.details}")
        assertEquals("the aqueduct is dry", e.cause?.message, "the original failure should still be the cause")
    }

    // ---------------------------------------------------------------- the session it is given

    @Test
    fun `a session already inside a transaction is refused`(@TempDir dir: Path) {
        dir.resolve("V1__first.sql").writeText(creates("first"))

        val e = assertThrows<MigrationException> {
            MigrationTestDatabase.session().use { session ->
                session.autoCommit = false
                OctaviusMigrator.onSession(session, config(dir)).migrate()
            }
        }

        assertEquals(MigrationExceptionReason.CONFIGURATION, e.reason)
    }
}
