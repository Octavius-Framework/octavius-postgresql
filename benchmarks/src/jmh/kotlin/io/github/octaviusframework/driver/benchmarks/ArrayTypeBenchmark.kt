package io.github.octaviusframework.driver.benchmarks

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.query.NativeQuery
import org.openjdk.jmh.annotations.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.TimeUnit
import java.util.Properties

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@Fork(3)
@Threads(1)
// No `time` here on purpose: throughput iterations keep JMH's 10 s default.
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class ArrayTypeBenchmark {

    private lateinit var pgConnection: Connection
    private lateinit var pgStatement: PreparedStatement

    private lateinit var octaviusSession: OctaviusSession
    private lateinit var octaviusQuery: NativeQuery

    @Setup(Level.Trial)
    fun setup() {
        Class.forName("org.postgresql.Driver")
        Class.forName("io.github.octaviusframework.driver.jdbc.OctaviusDriver")

        val props = Properties()
        props["user"] = "postgres"
        props["password"] = "1234"

        pgConnection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/octavius_test", props)
        // The one surviving cast is load-bearing: without it the ARRAY constructor resolves the unknown
        // literals against i, settles on int4, and rejects 'a'.
        val query = "SELECT ARRAY[1, 2, 3, 4, 5, i, 7, 8, 9, 10], ARRAY['a', 'b', 'c', 'd', i::text] FROM generate_series(1, 10000) AS i"
        pgStatement = pgConnection.prepareStatement(query)

        octaviusSession = getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", "postgres", "1234")
        octaviusQuery = octaviusSession.createNativeQuery(query)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        pgStatement.close()
        pgConnection.close()
        octaviusSession.close()
    }

    @Benchmark
    fun pgjdbc_arrays(): Int {
        var count = 0
        pgStatement.executeQuery().use { rs ->
            while (rs.next()) {
                val intArray = rs.getArray(1).array
                val textArray = rs.getArray(2).array
                count += java.lang.reflect.Array.getLength(intArray) + java.lang.reflect.Array.getLength(textArray)
            }
        }
        return count
    }

    @Benchmark
    fun octavius_arrays(): Int {
        var count = 0
        val rows = octaviusQuery.fetchRows()
        for (row in rows) {
            val intArray = row.get<List<Int>>(0)
            val textArray = row.get<List<String>>(1)
            count += intArray.size + textArray.size
        }
        return count
    }
}
