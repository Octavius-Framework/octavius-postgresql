package com.roma.census

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Runs the installed application as a deployment runs it: a separate JVM over the jars in the distribution's
 * `lib`, which is the classpath its start script builds.
 *
 * That is the point of running it rather than calling [main]. The migrations and the annotated classes are
 * found by walking the classpath, and here they are inside `census-*.jar`, where in the repository's own
 * suites they are always in build directories.
 *
 * Each test gets an empty database of its own, created on the server `CENSUS_DATABASE_URL` points at and
 * dropped afterwards. Its first command applies the migrations, and every later one finds them applied.
 */
class CensusTest {

    private val home = File(System.getProperty("census.home"))
    private val server = System.getenv("CENSUS_DATABASE_URL") ?: "jdbc:octavius://localhost:5432/postgres"
    private val user = System.getenv("CENSUS_DATABASE_USER") ?: "postgres"
    private val password = System.getenv("CENSUS_DATABASE_PASSWORD") ?: "1234"

    private val database = "census_smoke_" + UUID.randomUUID().toString().replace("-", "").take(12)

    @BeforeTest
    fun createDatabase() = onServer("CREATE DATABASE $database")

    @AfterTest
    fun dropDatabase() = onServer("DROP DATABASE IF EXISTS $database WITH (FORCE)")

    @Test
    fun `a father and his sons go in as one plan, the sons under the id the father was given`() {
        val enrolled = census(
            "enroll", "--nomen", "Marcus Tullius Cicero", "--tribe", "Cornelia", "--ordo", "Senatorius",
            "--city", "Roma", "--district", "Carinae", "--assessment", "1000000",
            "--son", "Marcus Tullius Cicero Minor", "--son", "Quintus Tullius Cicero"
        )
        assertEquals(
            listOf(
                "Enrolled #1 Marcus Tullius Cicero",
                "  son #2 Marcus Tullius Cicero Minor",
                "  son #3 Quintus Tullius Cicero"
            ),
            enrolled
        )

        assertEquals(
            listOf(
                "#1 Marcus Tullius Cicero: tribe Cornelia, Senatorius, Roma (Carinae), 1000000 HS",
                "#2 Marcus Tullius Cicero Minor, son of #1: tribe Cornelia, Senatorius, Roma (Carinae), 0 HS",
                "#3 Quintus Tullius Cicero, son of #1: tribe Cornelia, Senatorius, Roma (Carinae), 0 HS",
                "3 of 3 citizens"
            ),
            census("list")
        )
    }

    @Test
    fun `a filter left out adds no condition, and the count ignores the limit`() {
        enroll("Marcus Tullius Cicero", "Cornelia", "Senatorius", "Roma")
        enroll("Titus Pomponius Atticus", "Sergia", "Equester", "Roma")
        enroll("Gaius Verres", "Romilia", "Senatorius", "Massilia")

        assertEquals("3 of 3 citizens", census("list").last())
        assertEquals(
            listOf(
                "#1 Marcus Tullius Cicero: tribe Cornelia, Senatorius, Roma (Forum), 100000 HS",
                "1 of 2 citizens"
            ),
            census("list", "--ordo", "Senatorius", "--limit", "1")
        )
        assertEquals(
            listOf("#3 Gaius Verres: tribe Romilia, Senatorius, Massilia (Forum), 100000 HS", "1 of 1 citizens"),
            census("list", "--ordo", "Senatorius", "--city", "Massilia")
        )
    }

    @Test
    fun `notae of different shapes share a column and each reads back as its own class`() {
        enroll("Gaius Verres", "Romilia", "Senatorius", "Roma")

        assertEquals(
            listOf("#1 moved from the tribe Romilia to Cornelia"),
            census("transfer", "1", "--tribe", "Cornelia")
        )
        assertEquals(
            listOf("#1 struck from Senatorius to Plebeius: extortion in Sicily"),
            census("demote", "1", "--ordo", "Plebeius", "--reason", "extortion in Sicily")
        )

        assertEquals(
            listOf(
                "#1 Gaius Verres: tribe Cornelia, Plebeius, Roma (Forum), 100000 HS",
                "  nota: moved from the tribe Romilia to Cornelia",
                "  nota: struck from Senatorius to Plebeius: extortion in Sicily"
            ),
            census("show", "1")
        )
    }

    private fun enroll(nomen: String, tribe: String, ordo: String, city: String) {
        census(
            "enroll", "--nomen", nomen, "--tribe", tribe, "--ordo", ordo,
            "--city", city, "--district", "Forum", "--assessment", "100000"
        )
    }

    /** Runs `census` with [args] against this test's database and returns what it printed to stdout. */
    private fun census(vararg args: String): List<String> {
        val jars = File(home, "lib").listFiles { file -> file.extension == "jar" }!!
        val java = File(System.getProperty("java.home"), "bin/java").path

        val process = ProcessBuilder(
            listOf(java, "-cp", jars.joinToString(File.pathSeparator), "com.roma.census.MainKt") + args
        ).apply {
            environment()["CENSUS_DATABASE_URL"] = server.substringBeforeLast('/') + "/" + database
            environment()["CENSUS_DATABASE_USER"] = user
            environment()["CENSUS_DATABASE_PASSWORD"] = password
        }.start()

        // Both streams at once, so neither fills its pipe while the other is being read
        val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        val stdout = process.inputStream.bufferedReader().readLines()

        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("census ${args.joinToString(" ")} did not finish")
        }
        if (process.exitValue() != 0) {
            fail("census ${args.joinToString(" ")} exited with ${process.exitValue()}:\n${stderr.join()}")
        }
        return stdout
    }

    private fun onServer(sql: String) {
        getOctaviusSession(server, user, password).use { it.createNativeQuery(sql).execute() }
    }
}
