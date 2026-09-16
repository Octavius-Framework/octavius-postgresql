package io.github.octaviusframework.driver.benchmarks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.query.RawQuery
import io.github.octaviusframework.client.query.SelectQuery
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.query.NamedParameterQuery
import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.session.OctaviusSession
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * What the client costs over calling the driver by hand.
 *
 * Every other benchmark in this suite compares Octavius against pgjdbc. This one compares Octavius against
 * itself: one driver, one pool, one server, one statement, and the client layer switched on a rung at a time.
 * Nothing here is a comparison with another data-access library.
 *
 * ## The ladder
 *
 * The five paths per shape are ordered so that each adds exactly one thing to the one above it:
 *
 * 1. `driver_heldSession` - a session borrowed once and the query built once, both kept for the trial.
 *    The floor: every rung below is this plus something.
 * 2. `driver_borrowedPerCall` - the same work, taking a session out of the pool and giving it back around
 *    every call, and building the query inside the call. Still no client on the stack, which is what makes it
 *    the control for the borrow: whatever separates it from the floor is charged to the pool and not to the
 *    layer above.
 * 3. `client_rawQuery` - a `rawQuery` built once, a terminal called per invocation. Over rung 2 this adds
 *    the client and nothing else: `SessionProvider.execute`, finding the session the terminal runs on.
 * 4. `client_builderPrebuilt` - a builder built once, a terminal called per invocation. Adds `querySql()`,
 *    which walks the clauses and assembles the string again on every call.
 * 5. `client_builderPerCall` - the builder constructed inside the invocation too, which is how a repository
 *    function writes it and what `RunnableQuery` describes as the ordinary case. Adds the builder's own
 *    allocation.
 *
 * ## The two shapes
 *
 * A primary-key lookup returning one row, and a 10 000-row read. Both run every rung.
 *
 * The lookup is here for the same reason [PointLookupBenchmark] uses one: it is close to the cheapest
 * statement PostgreSQL can be asked to run, so a fixed per-call cost is the largest share of it that it will
 * ever be. The wide read is the other end - a statement doing enough work that the same fixed cost has
 * something to disappear into. Both are reported as average time in microseconds, because the quantity under
 * test is an additive cost per call rather than a rate.
 *
 * ## What is held still
 *
 * Every rung runs the same statement, rendered once in setup from the builder itself, through the same
 * driver query type. The driver rungs take `createNamedQuery` and `@id` exactly as the client's terminals do,
 * rather than `createNativeQuery` and `$1`: `@name` parameters are the driver's own feature, resolved by
 * `SqlParameterParser.parse` inside `NamedParameterQuery` on every terminal call, so a ladder that changed
 * query type half way up would charge that parse to the client. Here it is in all five rungs and cancels.
 * What it costs against positional parameters is a question about the driver and belongs in a benchmark that
 * holds the client out of it.
 *
 * Every session also comes from one `HikariDataSource`, the driver rungs included, so a pooled connection is
 * never being compared against a directly opened one. The pool is pre-filled and set never to retire a
 * connection, which keeps a handshake out of the middle of a measurement. It carries no `connectionTestQuery`:
 * Octavius's `Statement` refuses anything that returns rows, so the reflex `SELECT 1` would throw on every
 * probe.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Threads(1)
// Longer and more numerous than the rest of the suite, which runs 3x1 s and 5x1 s. What separates two
// neighbouring rungs here is a microsecond or two against a statement of about fifty, where the rest of the
// suite is separating one driver from another. The extra samples buy the precision that needs - and even at
// 24 of them no single step of the ladder resolves, which is itself the reading.
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 8, time = 2, timeUnit = TimeUnit.SECONDS)
open class ClientOverheadBenchmark {

    private lateinit var pool: HikariDataSource
    private lateinit var dataSource: DataSource
    private lateinit var client: OctaviusClient

    /** Borrowed once and kept for the trial - the held-session rungs run on this one. */
    private lateinit var heldSession: OctaviusSession

    private lateinit var lookupNamedQuery: NamedParameterQuery
    private lateinit var wideNamedQuery: NamedParameterQuery

    private lateinit var lookupRawQuery: RawQuery
    private lateinit var wideRawQuery: RawQuery

    private lateinit var lookupPrebuilt: SelectQuery
    private lateinit var widePrebuilt: SelectQuery

    /** The rendered statements, so that every rung is demonstrably running the same text. */
    private lateinit var lookupSql: String
    private lateinit var wideSql: String

    private val rowCount = 10000

    /** Rotated on every invocation, so no two consecutive lookups ask for the same row. */
    private var cursor = 0

    private fun nextId(): Int {
        cursor++
        if (cursor > rowCount) cursor = 1
        return cursor
    }

    private fun buildLookup(): SelectQuery =
        client.select("id", "text_data").from("benchmark_client_overhead").where("id = @id")

    private fun buildWide(): SelectQuery =
        // 3.14 is a numeric literal, so (i * 3.14) is numeric on its own; the cast is what keeps that
        // column float8, and is the only one here doing any work.
        client.select(
            "i AS i",
            "('hello world ' || i) AS s",
            "(i % 2 = 0) AS b",
            "(i * 3.14)::float8 AS d"
        ).from("generate_series(1, $rowCount) AS i")

    @Setup(Level.Trial)
    fun setup() {
        Class.forName("io.github.octaviusframework.driver.jdbc.OctaviusDriver")

        val config = HikariConfig()
        config.jdbcUrl = "jdbc:octavius://localhost:5432/octavius_test"
        config.username = "postgres"
        config.password = "1234"
        // Two connections are in hand at once on the borrowing rungs - the held one and the borrowed one -
        // and the headroom above that costs nothing on a single thread.
        config.maximumPoolSize = 4
        config.minimumIdle = 4
        config.idleTimeout = 0  // never retire an idle connection
        config.maxLifetime = 0  // nor a live one

        pool = HikariDataSource(config)
        dataSource = pool
        client = OctaviusClient.fromDataSource(pool)

        heldSession = pool.getOctaviusSession()

        heldSession.createNativeQuery("DROP TABLE IF EXISTS benchmark_client_overhead").execute()
        heldSession.createNativeQuery(
            "CREATE TABLE benchmark_client_overhead (id INT PRIMARY KEY, text_data TEXT NOT NULL)"
        ).execute()
        heldSession.createNativeQuery(
            "INSERT INTO benchmark_client_overhead SELECT i, 'senator ' || i FROM generate_series(1, $rowCount) i"
        ).execute()
        // Without stats the planner is guessing, and a plan chosen by guesswork is not the plan this
        // benchmark means to time.
        heldSession.createNativeQuery("ANALYZE benchmark_client_overhead").execute()

        // The builder renders the statement, and every other rung is handed what it rendered, unchanged.
        lookupSql = buildLookup().toSql()
        wideSql = buildWide().toSql()

        lookupNamedQuery = heldSession.createNamedQuery(lookupSql)
        wideNamedQuery = heldSession.createNamedQuery(wideSql)

        lookupRawQuery = client.rawQuery(lookupSql)
        wideRawQuery = client.rawQuery(wideSql)

        lookupPrebuilt = buildLookup()
        widePrebuilt = buildWide()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        heldSession.createNativeQuery("DROP TABLE IF EXISTS benchmark_client_overhead").execute()
        heldSession.close()
        client.close()
        pool.close()
    }

    /** Read the same way on every rung, so the only thing separating them is how the row was asked for. */
    private fun readRow(row: Row): Int = row.get<Int>(0) + row.get<String>(1).length

    private fun sum(rows: List<Row>): Int {
        var count = 0
        for (row in rows) count += readRow(row)
        return count
    }

    // --- A single row by primary key ------------------------------------------------------------------

    @Benchmark
    fun pointLookup_driver_heldSession(): Int = readRow(lookupNamedQuery.fetchRowStrict("id" to nextId()))

    @Benchmark
    fun pointLookup_driver_borrowedPerCall(): Int =
        dataSource.getOctaviusSession().use { session ->
            readRow(session.createNamedQuery(lookupSql).fetchRowStrict("id" to nextId()))
        }

    @Benchmark
    fun pointLookup_client_rawQuery(): Int = readRow(lookupRawQuery.fetchRowStrict("id" to nextId()))

    @Benchmark
    fun pointLookup_client_builderPrebuilt(): Int = readRow(lookupPrebuilt.fetchRowStrict("id" to nextId()))

    @Benchmark
    fun pointLookup_client_builderPerCall(): Int = readRow(buildLookup().fetchRowStrict("id" to nextId()))

    // --- Ten thousand rows ----------------------------------------------------------------------------
    //
    // `fetchRows` on every rung on purpose: mapping onto a data class would put the row mapper in the
    // measurement, and what that costs is a question the performance page answers on its own.

    @Benchmark
    fun wideRead_driver_heldSession(): Int = sum(wideNamedQuery.fetchRows())

    @Benchmark
    fun wideRead_driver_borrowedPerCall(): Int =
        dataSource.getOctaviusSession().use { session ->
            sum(session.createNamedQuery(wideSql).fetchRows())
        }

    @Benchmark
    fun wideRead_client_rawQuery(): Int = sum(wideRawQuery.fetchRows())

    @Benchmark
    fun wideRead_client_builderPrebuilt(): Int = sum(widePrebuilt.fetchRows())

    @Benchmark
    fun wideRead_client_builderPerCall(): Int = sum(buildWide().fetchRows())
}
