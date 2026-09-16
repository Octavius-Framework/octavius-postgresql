package io.github.octaviusframework.client.scanner

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.scanner.fixtures.good.ScanAppointment
import io.github.octaviusframework.client.scanner.fixtures.good.ScanGrant
import io.github.octaviusframework.client.scanner.fixtures.good.ScanProvince
import io.github.octaviusframework.client.scanner.fixtures.good.ScanOffice
import io.github.octaviusframework.client.scanner.fixtures.good.ScanRank
import io.github.octaviusframework.client.scanner.fixtures.wrongname.Misnamed
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the scan against a real PostgreSQL: that the three annotations are found and registered, that what
 * they registered actually round-trips, and that a scan which found nothing says so rather than passing.
 */
class TypeScannerTest {

    data class Senator(val id: Int, val rank: ScanRank, val home: ScanProvince)

    companion object {
        private const val GOOD = "io.github.octaviusframework.client.scanner.fixtures.good"
        private const val BAD = "io.github.octaviusframework.client.scanner.fixtures.bad"
        private const val QUIET = "io.github.octaviusframework.client.scanner.fixtures.quiet"
        private const val WRONG = "io.github.octaviusframework.client.scanner.fixtures.wrongname"

        private lateinit var dataSource: HikariDataSource
        private lateinit var db: OctaviusClient
        private lateinit var report: ScanReport

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

            // Before the table, which has a column of that type.
            db.dynamicTypes.install()

            db.rawQuery(
                """
                DROP TABLE IF EXISTS scan_senators;
                DROP TYPE IF EXISTS scan_rank;
                DROP TYPE IF EXISTS scan_province;
                DROP TYPE IF EXISTS scan_office;
                CREATE TYPE scan_rank AS ENUM ('QUAESTOR', 'PRAETOR', 'CONSUL');
                CREATE TYPE scan_province AS (name text, capital text);
                CREATE TYPE scan_office AS ENUM ('quaestor', 'praetor');
                CREATE TABLE scan_senators (
                    id      SERIAL PRIMARY KEY,
                    rank    scan_rank NOT NULL,
                    home    scan_province NOT NULL,
                    benefit public.dynamic_dto
                )
                """.trimIndent()
            ).execute()

            // The types were created after the pool was built, so the catalogue this connection read at
            // connect time does not have them yet.
            db.execute { reloadTypes() }

            // Nothing was registered by hand: the scan is what teaches the driver all three.
            report = db.registerAnnotatedTypes(GOOD)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            db.rawQuery(
                "DROP TABLE IF EXISTS scan_senators; DROP TYPE IF EXISTS scan_rank; " +
                    "DROP TYPE IF EXISTS scan_province; DROP TYPE IF EXISTS scan_office"
            ).execute()
            db.close()
            dataSource.close()
        }
    }

    @BeforeEach
    fun clearTable() {
        db.rawQuery("TRUNCATE scan_senators RESTART IDENTITY").execute()
    }

    // --- What the scan found ----------------------------------------------------------------------

    @Test
    fun `the scan finds one of each kind and names it`() {
        assertEquals(5, report.total)
        assertEquals(listOf("scan_office", "scan_rank"), report.enums.map { it.name }.sorted())
        assertEquals(listOf("scan_province"), report.composites.map { it.name })
        assertEquals(listOf("scan_appointment", "scan_grant"), report.dynamicTypes.map { it.name }.sorted())
    }

    @Test
    fun `the report names the classes it registered`() {
        assertTrue(report.enums.any { it.kClass == ScanRank::class })
        assertEquals(ScanProvince::class, report.composites.single().kClass)
        assertTrue(report.dynamicTypes.any { it.kClass == ScanGrant::class })
    }

    // --- That the registration actually took ------------------------------------------------------

    @Test
    fun `an enum and a composite registered by the scan round-trip`() {
        // The proof that matters: nothing named these types in Kotlin, and the columns still map.
        db.insertInto("scan_senators").values(listOf("rank", "home"))
            .update("rank" to ScanRank.Consul, "home" to ScanProvince("Gallia", "Lugdunum"))

        val senator = db.select("id", "rank", "home").from("scan_senators").fetchObjectStrict<Senator>()

        assertEquals(ScanRank.Consul, senator.rank)
        assertEquals(ScanProvince("Gallia", "Lugdunum"), senator.home)
    }

    @Test
    fun `a dynamic type registered by the scan round-trips`() {
        db.insertInto("scan_senators").values(listOf("rank", "home", "benefit"))
            .update(
                "rank" to ScanRank.Praetor,
                "home" to ScanProvince("Hispania", "Tarraco"),
                "benefit" to db.dynamicTypes.toDynamicDto(ScanGrant(120))
            )

        assertEquals(
            ScanGrant(120),
            db.select("benefit").from("scan_senators").fetchFieldStrict<ScanGrant>()
        )
    }

    @Test
    fun `a scanned enum inside a payload carries the label the scan registered it under`() {
        // `@PgEnumType(pgConvention = SNAKE_CASE_LOWER)` on ScanOffice is the only place those labels are
        // stated. Nothing wrote a serializer, and the enum is not `@Serializable`: the scan is what makes the
        // payload say `praetor` rather than `Praetor`.
        db.insertInto("scan_senators").values(listOf("rank", "home", "benefit"))
            .update(
                "rank" to ScanRank.Praetor,
                "home" to ScanProvince("Hispania", "Tarraco"),
                "benefit" to ScanAppointment(ScanOffice.Praetor)
            )

        assertEquals(
            "praetor",
            db.select("(benefit).data_payload ->> 'office'").from("scan_senators").fetchFieldStrict<String>()
        )
        assertEquals(
            true,
            db.select("(benefit).data_payload ->> 'office' = @o::text")
                .from("scan_senators").fetchFieldStrict<Boolean>("o" to ScanOffice.Praetor)
        )
    }

    @Test
    fun `the derived enum name matches what the driver actually registered`() {
        // The report says scan_rank; if the driver had derived anything else, this query would not resolve.
        val roundTripped = db.rawQuery("SELECT @r").fetchFieldStrict<ScanRank>("r" to ScanRank.Quaestor)
        assertEquals(ScanRank.Quaestor, roundTripped)
    }

    // --- What it refuses, and what it warns about -------------------------------------------------

    @Test
    fun `a scan that matches nothing reports empty rather than passing quietly`() {
        val quiet = db.registerAnnotatedTypes(QUIET)

        assertTrue(quiet.isEmpty())
        assertEquals(0, quiet.total)
    }

    @Test
    fun `the enum annotation on something that is not an enum is refused, by name`() {
        val thrown = assertFailsWith<InvalidOperationException> { db.registerAnnotatedTypes(BAD) }

        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, thrown.reason)
        assertTrue(thrown.details!!.contains("NotAnEnum"), "the message should name the offending class")
    }

    @Test
    fun `a name the database has no type for is registered anyway, and reported`() {
        // Not refused: registering ahead of a type that does not exist yet works, because a reload afterwards
        // picks it up and the converters survive it. So the scan says it out loud instead of deciding.
        val report = db.registerAnnotatedTypes(WRONG)

        assertEquals(1, report.enums.size, "it is registered like any other")
        assertEquals(listOf("no_such_enum_type"), report.unresolved.map { it.name })
        assertEquals("Misnamed", report.unresolved.single().kClass.simpleName)
    }

    @Test
    fun `a type registered before it existed works once it does and the catalogue is reloaded`() {
        // The reason unresolved is a report and not a refusal. If this did not work, refusing would be right.
        db.registerAnnotatedTypes(WRONG)

        db.rawQuery("CREATE TYPE no_such_enum_type AS ENUM ('A', 'B')").execute()
        try {
            db.execute { reloadTypes() }

            val roundTripped = db.rawQuery("SELECT @v")
                .fetchFieldStrict<Misnamed>("v" to Misnamed.B)

            assertEquals(Misnamed.B, roundTripped, "the converter registered earlier survived the reload")
        } finally {
            db.rawQuery("DROP TYPE IF EXISTS no_such_enum_type").execute()
            db.execute { reloadTypes() }
        }
    }

    @Test
    fun `a case convention stated on the annotation reaches the driver`() {
        // The labels are lowercase in the database and PascalCase in Kotlin. Without the convention travelling
        // from the annotation through the scan, the default would look for QUAESTOR and find nothing.
        val roundTripped = db.rawQuery("SELECT @v")
            .fetchFieldStrict<ScanOffice>("v" to ScanOffice.Praetor)

        assertEquals(ScanOffice.Praetor, roundTripped)
        assertEquals("praetor", db.rawQuery("SELECT @v::text").fetchFieldStrict<String>("v" to ScanOffice.Praetor))
    }

    @Test
    fun `a scan whose types all exist reports nothing unresolved`() {
        assertTrue(report.unresolved.isEmpty())
    }

    @Test
    fun `scanning no packages at all is refused`() {
        val thrown = assertFailsWith<InvalidOperationException> { db.registerAnnotatedTypes() }
        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, thrown.reason)
    }

    @Test
    fun `a package holds only its own classes`() {
        // The good fixtures live next door to the bad one; scanning the quiet package must not reach either.
        assertTrue(db.registerAnnotatedTypes(QUIET).isEmpty())
    }
}
