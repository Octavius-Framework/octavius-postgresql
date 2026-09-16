package io.github.octaviusframework.driver.benchmarks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.query.RawQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.jdbi.v3.core.kotlin.mapTo
import org.openjdk.jmh.annotations.*
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.concurrent.TimeUnit

/** The point-lookup row. [SimpleData] from [SimpleDataBenchmark] is the wide one. */
data class StackSenator(val id: Int, val cognomen: String)

/**
 * Three stacks at the same level of convenience, doing the same two reads.
 *
 * **Three stacks, not three libraries, and the difference matters for what may be concluded.** Spring's
 * `NamedParameterJdbcTemplate` and JDBI both run on `pgjdbc`; the Octavius client runs on the Octavius driver,
 * and it cannot run on anything else - `PreparedStatement` and `ResultSet` are not implemented, so no pair
 * here could be put on a shared driver. Every figure below therefore contains a driver difference as well as a
 * layer difference, and no row of it says that one of these libraries is faster than another.
 *
 * What makes that bearable rather than fatal is that the driver half is already measured on its own:
 * [the performance page](../../../../../../../../../docs/driver/performance.md) puts Octavius against `pgjdbc`
 * across several shapes, from a tie on object mapping to 28% behind on arrays. A reader who wants to know how
 * much of a gap here is the driver underneath can look it up rather than guess.
 *
 * ## The same bargain, three ways
 *
 * All three write their own SQL and have it mapped onto a data class by reflection. None of them is an ORM,
 * none generates the statement, and none is asked to do anything the other two are not:
 *
 * | | Statement | Parameters | Mapping |
 * |:--|:--|:--|:--|
 * | Octavius client | written out | `@id` | the driver's reflective row mapper |
 * | Spring | written out | `:id` | `DataClassRowMapper` |
 * | JDBI | written out | `:id` | `KotlinPlugin`'s mapper |
 *
 * The statement itself is the same text on all three, down to the placeholder: one base string, with `@id`
 * for Octavius and `:id` for the other two. The wide read takes no parameters, so there the text is identical.
 *
 * ## Server-side prepared statements
 *
 * `pgjdbc` promotes a statement to a named server-side one after its fifth execution, by default; Octavius
 * never does, and has no mode in which it would. On a primary-key lookup that is the largest single thing
 * separating these stacks - [PointLookupBenchmark] measures it at a factor of 1.64 with everything else held
 * still - and reading the lookup rows without it in view would mean crediting a mapper for it.
 *
 * So the lookup runs Spring twice: once on a pool with `pgjdbc`'s defaults, once on a pool with
 * `prepareThreshold=0`. Those two rows differ in that one property and nothing else, which is what makes the
 * rest of the column legible. The wide read has no second variant: it is one statement per operation against
 * 10 000 rows, where a per-statement fixed cost has nothing left to be a share of.
 *
 * ## What is held still
 *
 * One PostgreSQL, one table seeded and `ANALYZE`d once, and three `HikariDataSource`es configured alike -
 * pre-filled, and set never to retire a connection, so no handshake lands in the middle of a measurement.
 * Neither pool carries a `connectionTestQuery`: Octavius's `Statement` refuses anything that returns rows, so
 * the reflex `SELECT 1` would throw on every probe there.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Threads(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
open class StackComparisonBenchmark {

    private lateinit var octaviusPool: HikariDataSource
    private lateinit var pgPool: HikariDataSource
    private lateinit var pgNoPreparePool: HikariDataSource

    private lateinit var client: OctaviusClient
    private lateinit var octaviusLookup: RawQuery
    private lateinit var octaviusWide: RawQuery

    private lateinit var spring: NamedParameterJdbcTemplate
    private lateinit var springNoPrepare: NamedParameterJdbcTemplate
    private lateinit var jdbi: Jdbi

    private val senatorMapper: RowMapper<StackSenator> = DataClassRowMapper(StackSenator::class.java)
    private val simpleMapper: RowMapper<SimpleData> = DataClassRowMapper(SimpleData::class.java)

    private lateinit var lookupSqlNamed: String
    private lateinit var lookupSqlColon: String
    private lateinit var wideSql: String

    private val rowCount = 10000

    /** Rotated on every invocation, so no two consecutive lookups ask for the same row. */
    private var cursor = 0

    private fun nextId(): Int {
        cursor++
        if (cursor > rowCount) cursor = 1
        return cursor
    }

    private fun hikari(url: String): HikariDataSource {
        val config = HikariConfig()
        config.jdbcUrl = url
        config.username = "postgres"
        config.password = "1234"
        config.maximumPoolSize = 4
        config.minimumIdle = 4
        config.idleTimeout = 0
        config.maxLifetime = 0
        return HikariDataSource(config)
    }

    @Setup(Level.Trial)
    fun setup() {
        Class.forName("io.github.octaviusframework.driver.jdbc.OctaviusDriver")
        Class.forName("org.postgresql.Driver")

        octaviusPool = hikari("jdbc:octavius://localhost:5432/octavius_test")
        pgPool = hikari("jdbc:postgresql://localhost:5432/octavius_test")
        // The one property under test, on a pool that is otherwise the twin of the one above.
        pgNoPreparePool = hikari("jdbc:postgresql://localhost:5432/octavius_test?prepareThreshold=0")

        client = OctaviusClient.fromDataSource(octaviusPool)
        spring = NamedParameterJdbcTemplate(pgPool)
        springNoPrepare = NamedParameterJdbcTemplate(pgNoPreparePool)
        jdbi = Jdbi.create(pgPool).installPlugin(KotlinPlugin())

        client.rawQuery("DROP TABLE IF EXISTS benchmark_stack").execute()
        client.rawQuery("CREATE TABLE benchmark_stack (id INT PRIMARY KEY, cognomen TEXT NOT NULL)").execute()
        client.rawQuery(
            "INSERT INTO benchmark_stack SELECT i, 'senator ' || i FROM generate_series(1, @n) i"
        ).update("n" to rowCount)
        // Without stats the planner is guessing, and a plan chosen by guesswork is not the plan this
        // benchmark means to time.
        client.rawQuery("ANALYZE benchmark_stack").execute()

        val lookupBase = "SELECT id, cognomen FROM benchmark_stack WHERE id = "
        lookupSqlNamed = lookupBase + "@id"
        lookupSqlColon = lookupBase + ":id"

        // The same row shape the suite's other read benchmarks use, so this one is not a shape of its own.
        wideSql = "SELECT i::int4 AS i, ('hello world ' || i::text) AS s, (i % 2 = 0)::boolean AS b, " +
                "(i * 3.14)::float8 AS d FROM generate_series(1, $rowCount) AS i"

        octaviusLookup = client.rawQuery(lookupSqlNamed)
        octaviusWide = client.rawQuery(wideSql)

        verifyEveryStack()
    }

    /**
     * Proves the three stacks agree before any of them is timed.
     *
     * A mapper that silently produced defaults, or a stack reading a different statement, would otherwise be
     * reported as a speed rather than as a fault.
     */
    private fun verifyEveryStack() {
        val expected = StackSenator(7, "senator 7")
        val lookups = listOf(
            octaviusLookup.fetchObjectStrict<StackSenator>("id" to 7),
            spring.queryForObject(lookupSqlColon, mapOf("id" to 7), senatorMapper),
            springNoPrepare.queryForObject(lookupSqlColon, mapOf("id" to 7), senatorMapper),
            jdbi.withHandle<StackSenator, RuntimeException> { handle ->
                handle.createQuery(lookupSqlColon).bind("id", 7).mapTo<StackSenator>().one()
            }
        )
        check(lookups.all { it == expected }) { "the stacks must read the same row, got $lookups" }

        val wides = listOf(
            octaviusWide.fetchObjects<SimpleData>(),
            spring.query(wideSql, emptyMap<String, Any>(), simpleMapper),
            jdbi.withHandle<List<SimpleData>, RuntimeException> { handle ->
                handle.createQuery(wideSql).mapTo<SimpleData>().list()
            }
        )
        check(wides.all { it.size == rowCount }) {
            "every stack must read all $rowCount rows, got ${wides.map { it.size }}"
        }
        check(wides.all { it.first() == wides.first().first() }) {
            "the stacks must decode a row alike, got ${wides.map { it.first() }}"
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        client.rawQuery("DROP TABLE IF EXISTS benchmark_stack").execute()
        client.close()
        octaviusPool.close()
        pgPool.close()
        pgNoPreparePool.close()
    }

    // --- A single row by primary key -------------------------------------------------------------------

    @Benchmark
    fun pointLookup_octaviusClient(): StackSenator =
        octaviusLookup.fetchObjectStrict<StackSenator>("id" to nextId())

    @Benchmark
    fun pointLookup_springJdbc(): StackSenator =
        spring.queryForObject(lookupSqlColon, mapOf("id" to nextId()), senatorMapper)!!

    /** The row above with `prepareThreshold=0`: `pgjdbc` parsing and planning on every execution. */
    @Benchmark
    fun pointLookup_springJdbc_noServerPrepare(): StackSenator =
        springNoPrepare.queryForObject(lookupSqlColon, mapOf("id" to nextId()), senatorMapper)!!

    @Benchmark
    fun pointLookup_jdbi(): StackSenator =
        jdbi.withHandle<StackSenator, RuntimeException> { handle ->
            handle.createQuery(lookupSqlColon).bind("id", nextId()).mapTo<StackSenator>().one()
        }

    // --- Ten thousand rows, mapped ---------------------------------------------------------------------

    @Benchmark
    fun wideRead_octaviusClient(): Int = octaviusWide.fetchObjects<SimpleData>().size

    @Benchmark
    fun wideRead_springJdbc(): Int = spring.query(wideSql, emptyMap<String, Any>(), simpleMapper).size

    @Benchmark
    fun wideRead_jdbi(): Int =
        jdbi.withHandle<List<SimpleData>, RuntimeException> { handle ->
            handle.createQuery(wideSql).mapTo<SimpleData>().list()
        }.size
}
