package io.github.octaviusframework.driver.benchmarks

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.query.NativeQuery
import io.github.octaviusframework.driver.type.PgStandardType
import io.github.octaviusframework.driver.type.withPgType
import org.openjdk.jmh.annotations.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.TimeUnit
import java.util.Properties

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Threads(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
open class InsertBenchmark {

    private lateinit var pgConnection: Connection
    private lateinit var pgInsertStatement: PreparedStatement
    private lateinit var pgInsertBatchStatement: PreparedStatement
    private lateinit var pgUnnestStatement: PreparedStatement

    private lateinit var pgRewriteConnection: Connection
    private lateinit var pgRewriteBatchStatement: PreparedStatement

    private lateinit var octaviusSession: OctaviusSession
    private lateinit var octaviusInsertQuery: NativeQuery
    private lateinit var octaviusUnnestQuery: NativeQuery

    private val insertCount = 10000
    private lateinit var ids: List<Int>
    private lateinit var texts: List<String>
    private lateinit var idsArray: Array<Int>
    private lateinit var textsArray: Array<String>

    @Setup(Level.Trial)
    fun setup() {
        Class.forName("org.postgresql.Driver")
        Class.forName("io.github.octaviusframework.driver.jdbc.OctaviusDriver")

        val props = Properties()
        props["user"] = "postgres"
        props["password"] = "1234"

        pgConnection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/octavius_test", props)

        val rewriteProps = Properties()
        rewriteProps["user"] = "postgres"
        rewriteProps["password"] = "1234"
        rewriteProps["reWriteBatchedInserts"] = "true"

        pgRewriteConnection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/octavius_test", rewriteProps)

        pgConnection.createStatement().use { st ->
            st.execute("DROP TABLE IF EXISTS benchmark_insert")
            st.execute("CREATE TABLE benchmark_insert (id INT, text_data TEXT)")
        }

        pgInsertStatement = pgConnection.prepareStatement("INSERT INTO benchmark_insert (id, text_data) VALUES (?, ?)")
        pgInsertBatchStatement = pgConnection.prepareStatement("INSERT INTO benchmark_insert (id, text_data) VALUES (?, ?)")
        pgUnnestStatement = pgConnection.prepareStatement("INSERT INTO benchmark_insert (id, text_data) SELECT * FROM UNNEST(?, ?)")
        pgRewriteBatchStatement = pgRewriteConnection.prepareStatement("INSERT INTO benchmark_insert (id, text_data) VALUES (?, ?)")

        octaviusSession = getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", "postgres", "1234")
        octaviusInsertQuery = octaviusSession.createNativeQuery("INSERT INTO benchmark_insert (id, text_data) VALUES ($1, $2)")
        octaviusUnnestQuery = octaviusSession.createNativeQuery("INSERT INTO benchmark_insert (id, text_data) SELECT * FROM UNNEST($1, $2)")

        ids = (1..insertCount).toList()
        texts = ids.map { "data-$it" }
        idsArray = ids.toTypedArray()
        textsArray = texts.toTypedArray()
    }

    @TearDown(Level.Invocation)
    fun truncateTable() {
        pgConnection.createStatement().use { st ->
            st.execute("TRUNCATE TABLE benchmark_insert")
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        pgInsertStatement.close()
        pgInsertBatchStatement.close()
        pgUnnestStatement.close()
        pgRewriteBatchStatement.close()
        pgConnection.createStatement().use { st ->
            st.execute("DROP TABLE IF EXISTS benchmark_insert")
        }
        pgConnection.close()
        pgRewriteConnection.close()
        octaviusSession.close()
    }

    @Benchmark
    fun pgjdbc_single_inserts_tx() {
        pgConnection.autoCommit = false
        try {
            for (i in 0 until insertCount) {
                pgInsertStatement.setInt(1, ids[i])
                pgInsertStatement.setString(2, texts[i])
                pgInsertStatement.executeUpdate()
            }
            pgConnection.commit()
        } finally {
            pgConnection.autoCommit = true
        }
    }

    @Benchmark
    fun pgjdbc_batch_inserts_tx() {
        pgConnection.autoCommit = false
        try {
            for (i in 0 until insertCount) {
                pgInsertBatchStatement.setInt(1, ids[i])
                pgInsertBatchStatement.setString(2, texts[i])
                pgInsertBatchStatement.addBatch()
            }
            pgInsertBatchStatement.executeBatch()
            pgConnection.commit()
        } finally {
            pgConnection.autoCommit = true
        }
    }

    @Benchmark
    fun pgjdbc_rewrite_batch_inserts_tx() {
        pgRewriteConnection.autoCommit = false
        try {
            for (i in 0 until insertCount) {
                pgRewriteBatchStatement.setInt(1, ids[i])
                pgRewriteBatchStatement.setString(2, texts[i])
                pgRewriteBatchStatement.addBatch()
            }
            pgRewriteBatchStatement.executeBatch()
            pgRewriteConnection.commit()
        } finally {
            pgRewriteConnection.autoCommit = true
        }
    }

    @Benchmark
    fun pgjdbc_unnest_inserts_tx() {
        pgConnection.autoCommit = false
        try {
            val idArray = pgConnection.createArrayOf("integer", idsArray)
            val textArray = pgConnection.createArrayOf("text", textsArray)
            pgUnnestStatement.setArray(1, idArray)
            pgUnnestStatement.setArray(2, textArray)
            pgUnnestStatement.executeUpdate()
            pgConnection.commit()
        } finally {
            pgConnection.autoCommit = true
        }
    }

    @Benchmark
    fun octavius_single_inserts_tx() {
        octaviusSession.autoCommit = false
        try {
            for (i in 0 until insertCount) {
                octaviusInsertQuery.update(ids[i], texts[i])
            }
            octaviusSession.commit()
        } finally {
            octaviusSession.autoCommit = true
        }
    }

    @Benchmark
    fun octavius_unnest_inserts_tx() {
        octaviusSession.autoCommit = false
        try {
            octaviusUnnestQuery.update(ids.withPgType(PgStandardType.INT4_ARRAY), texts.withPgType(PgStandardType.TEXT_ARRAY))
            octaviusSession.commit()
        } finally {
            octaviusSession.autoCommit = true
        }
    }
}
