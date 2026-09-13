package io.github.octaviusframework.driver.benchmarks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.query.RawQuery
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/**
 * The writing direction of [CompositeVsDynamicDtoBenchmark]: 10 000 structures into one column, as a composite
 * built reflectively, as a composite built by hand, as plain `jsonb`, and as a `dynamic_dto`.
 *
 * The four paths, the families of classes and the reason they are separate are all described there, as is the
 * `nested`/`flat` shape this also runs in both of. This is a class of its own only because its table has to be
 * truncated after every invocation, and a `@TearDown(Level.Invocation)` runs for every method sharing a state
 * - which would add a `TRUNCATE` round trip to each of the read and filter timings there.
 *
 * All four go through `UNNEST` in a single statement, which is the bulk form the driver is built around, and
 * all four send one array: an array of composites, or of `jsonb`, or of `dynamic_dto`. So the statement shape
 * is held still and what varies is what each element had to be turned into on the way out.
 *
 * Each rung names the unnested element rather than selecting `*` from it. `UNNEST` of a composite array
 * expands the composite into its attributes, and `dynamic_dto` is a composite too - `SELECT *` would offer
 * several columns to an `INSERT` naming one.
 *
 * The insert is deliberately not wrapped in a transaction. One statement is atomic on its own, and a `BEGIN`
 * and `COMMIT` around it would add two round trips to every rung equally - noise with nothing to say.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Threads(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
open class CompositeVsDynamicDtoWriteBenchmark {

    @Param(SHAPE_NESTED, SHAPE_FLAT)
    lateinit var shape: String

    private lateinit var pool: HikariDataSource
    private lateinit var client: OctaviusClient

    private lateinit var writeAuto: RawQuery
    private lateinit var writeManual: RawQuery
    private lateinit var writeJsonbQuery: RawQuery
    private lateinit var writeDynamic: RawQuery

    private var autoRows: List<Any> = emptyList()
    private var manualRows: List<Any> = emptyList()
    private var jsonRows: List<Any> = emptyList()
    private var dynamicRows: List<Any> = emptyList()

    private val rowCount = 10000

    private val nested: Boolean get() = shape == SHAPE_NESTED

    @Setup(Level.Trial)
    fun setup() {
        Class.forName("io.github.octaviusframework.driver.jdbc.OctaviusDriver")

        val config = HikariConfig()
        config.jdbcUrl = "jdbc:octavius://localhost:5432/octavius_test"
        config.username = "postgres"
        config.password = "1234"
        config.maximumPoolSize = 4
        config.minimumIdle = 4
        config.idleTimeout = 0
        config.maxLifetime = 0

        pool = HikariDataSource(config)
        client = OctaviusClient.fromDataSource(pool)

        client.dynamicTypes.install()
        createSchema()

        client.execute {
            reloadTypes()
            if (nested) {
                typeManager.registerAutoComposite<CharacterAuto>(NESTED_CHARACTER_TYPE)
                typeManager.registerAutoComposite<StatsAuto>(NESTED_STATS_TYPE)
            } else {
                typeManager.registerAutoComposite<FlatAuto>(FLAT_TYPE)
            }
        }
        client.dynamicTypes.register<CharacterDynamic>("bench_cvd_character_dyn")
        client.dynamicTypes.register<FlatDynamic>("bench_cvd_flat_dyn")

        val compositeType = if (nested) NESTED_CHARACTER_TYPE else FLAT_TYPE
        writeAuto = client.rawQuery(
            "INSERT INTO bench_cvd_write (c) SELECT s FROM UNNEST(@v::$compositeType[]) AS s"
        )
        writeManual = client.rawQuery(
            "INSERT INTO bench_cvd_write (c) SELECT s FROM UNNEST(@v::$compositeType[]) AS s"
        ).registerParameterConverter(
            if (nested) CharacterManualParameterConverter() else FlatManualParameterConverter()
        )
        writeJsonbQuery = client.rawQuery(
            "INSERT INTO bench_cvd_write (j) SELECT s FROM UNNEST(@v::jsonb[]) AS s"
        ).registerParameterConverter(
            if (nested) CharacterJsonParameterConverter() else FlatJsonParameterConverter()
        )
        writeDynamic = client.rawQuery(
            "INSERT INTO bench_cvd_write (d) SELECT s FROM UNNEST(@v::public.dynamic_dto[]) AS s"
        )

        buildRows()
        verifyEveryPath()
    }

    private fun createSchema() {
        client.rawQuery("DROP TABLE IF EXISTS bench_cvd_write").execute()
        client.rawQuery("DROP TYPE IF EXISTS $NESTED_CHARACTER_TYPE CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS $NESTED_STATS_TYPE CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS $FLAT_TYPE CASCADE").execute()

        if (nested) {
            client.rawQuery("CREATE TYPE $NESTED_STATS_TYPE AS (strength int, agility int, intelligence int)")
                .execute()
            client.rawQuery("CREATE TYPE $NESTED_CHARACTER_TYPE AS (id int, name text, stats $NESTED_STATS_TYPE)")
                .execute()
            client.rawQuery("CREATE TABLE bench_cvd_write (c $NESTED_CHARACTER_TYPE, d public.dynamic_dto, j jsonb)")
                .execute()
        } else {
            client.rawQuery(
                "CREATE TYPE $FLAT_TYPE AS (id int, name text, strength int, agility int, intelligence int)"
            ).execute()
            client.rawQuery("CREATE TABLE bench_cvd_write (c $FLAT_TYPE, d public.dynamic_dto, j jsonb)").execute()
        }
    }

    private fun buildRows() {
        val ids = 1..rowCount
        if (nested) {
            autoRows = ids.map { CharacterAuto(it, "char_$it", StatsAuto(it % 100, it % 50, it % 200)) }
            manualRows = ids.map { CharacterManual(it, "char_$it", StatsManual(it % 100, it % 50, it % 200)) }
            jsonRows = ids.map { CharacterJson(it, "char_$it", StatsJson(it % 100, it % 50, it % 200)) }
            dynamicRows = ids.map { CharacterDynamic(it, "char_$it", StatsDynamic(it % 100, it % 50, it % 200)) }
        } else {
            autoRows = ids.map { FlatAuto(it, "char_$it", it % 100, it % 50, it % 200) }
            manualRows = ids.map { FlatManual(it, "char_$it", it % 100, it % 50, it % 200) }
            jsonRows = ids.map { FlatJson(it, "char_$it", it % 100, it % 50, it % 200) }
            dynamicRows = ids.map { FlatDynamic(it, "char_$it", it % 100, it % 50, it % 200) }
        }
    }

    /**
     * Proves each rung writes what it is named for before any of them is timed.
     *
     * A converter that failed to claim its value, or a registration the write strategy handed to the composite
     * path, would leave a rung measuring its neighbour and reporting a tie rather than an error. Reading each
     * column back through its own SQL expression is what makes the encoding visible: a row written as a
     * composite has no `data_payload` to find.
     */
    private fun verifyEveryPath() {
        writeAuto.update("v" to autoRows)
        writeManual.update("v" to manualRows)
        writeJsonbQuery.update("v" to jsonRows)
        writeDynamic.update("v" to dynamicRows)

        val compositeField = if (nested) "((c).stats).strength" else "(c).strength"
        val jsonPath = if (nested) "-> 'stats' ->> 'strength'" else "->> 'strength'"

        val hits = client.rawQuery(
            """
            SELECT count(*) FROM bench_cvd_write
            WHERE $compositeField = 1
               OR (j $jsonPath)::int = 1
               OR ((d).data_payload $jsonPath)::int = 1
            """
        ).fetchFieldStrict<Long>()

        // `strength` is `id % 100`, so every hundredth row carries 1 - on each of the four paths, two of which
        // write the same composite column.
        val perPath = (1..rowCount).count { it % 100 == 1 }
        check(hits == 4L * perPath) {
            "each encoding must be readable on its own terms: expected ${4 * perPath}, got $hits"
        }

        client.rawQuery("TRUNCATE TABLE bench_cvd_write").execute()
    }

    @TearDown(Level.Invocation)
    fun truncateWriteTable() {
        client.rawQuery("TRUNCATE TABLE bench_cvd_write").execute()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        client.rawQuery("DROP TABLE IF EXISTS bench_cvd_write").execute()
        client.rawQuery("DROP TYPE IF EXISTS $NESTED_CHARACTER_TYPE CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS $NESTED_STATS_TYPE CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS $FLAT_TYPE CASCADE").execute()
        client.close()
        pool.close()
    }

    @Benchmark
    fun write_compositeAuto(): Long = writeAuto.update("v" to autoRows)

    @Benchmark
    fun write_compositeManual(): Long = writeManual.update("v" to manualRows)

    @Benchmark
    fun write_jsonb(): Long = writeJsonbQuery.update("v" to jsonRows)

    @Benchmark
    fun write_dynamicDto(): Long = writeDynamic.update("v" to dynamicRows)
}
