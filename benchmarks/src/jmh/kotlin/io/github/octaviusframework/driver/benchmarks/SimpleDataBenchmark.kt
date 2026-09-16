package io.github.octaviusframework.driver.benchmarks

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.query.NativeQuery
import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KType
import org.openjdk.jmh.annotations.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.TimeUnit
import java.util.Properties
import kotlin.reflect.KClass

data class SimpleData(val i: Int, val s: String, val b: Boolean, val d: Double)

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@Fork(3)
@Threads(1)
// No `time` here on purpose: throughput iterations keep JMH's 10 s default.
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class SimpleDataBenchmark {

    private lateinit var pgConnection: Connection
    private lateinit var pgStatement: PreparedStatement

    private lateinit var octaviusSession: OctaviusSession
    private lateinit var octaviusQuery: NativeQuery
    private lateinit var octaviusReflectionQuery: NativeQuery

    @Setup(Level.Trial)
    fun setup() {
        Class.forName("org.postgresql.Driver")
        Class.forName("io.github.octaviusframework.driver.jdbc.OctaviusDriver")

        val props = Properties()
        props["user"] = "postgres"
        props["password"] = "1234"

        pgConnection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/octavius_test", props)
        // 3.14 is a numeric literal, so (i * 3.14) is numeric on its own; the cast is what keeps that
        // column float8, and is the only one here doing any work.
        val query =
            "SELECT i, 'hello world ' || i, (i % 2 = 0), (i * 3.14)::float8 FROM generate_series(1, 10000) AS i"
        pgStatement = pgConnection.prepareStatement(query)

        octaviusSession = getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", "postgres", "1234")
        octaviusQuery =
            octaviusSession.createNativeQuery(query).registerResultConverter(object : ResultConverter<Row, SimpleData> {
                override val supportedSourceClass = Row::class
                override fun canConvert(
                    sourceClass: KClass<*>,
                    expectedType: KType,
                    sourceType: PgType,
                    context: DeserializationContext
                ): Boolean = expectedType.classifier == SimpleData::class

                override fun convert(
                    source: Row,
                    expectedType: KType,
                    sourceType: PgType,
                    context: DeserializationContext
                ): SimpleData {
                    return SimpleData(source.get(0), source.get(1), source.get(2), source.get(3))
                }
            })

        // The same work with no converter registered, so mapping falls to ReflectionRowConverter.
        // It matches constructor parameters to columns by name, hence the aliases.
        octaviusReflectionQuery = octaviusSession.createNativeQuery(
            "SELECT i AS i, ('hello world ' || i) AS s, (i % 2 = 0) AS b, (i * 3.14)::float8 AS d " +
                    "FROM generate_series(1, 10000) AS i"
        )
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        pgStatement.close()
        pgConnection.close()
        octaviusSession.close()
    }

    @Benchmark
    fun pgjdbc_simpleData(): Int {
        var count = 0
        pgStatement.executeQuery().use { rs ->
            while (rs.next()) {
                val data = SimpleData(rs.getInt(1), rs.getString(2), rs.getBoolean(3), rs.getDouble(4))
                count += data.i + (if(data.b) 1 else 0) + data.s.length + data.d.toInt()
            }
        }
        return count
    }

    @Benchmark
    fun octavius_simpleData(): Int {
        var count = 0
        val rows = octaviusQuery.fetchObjects<SimpleData>()
        for (data in rows) {
            count += data.i + (if(data.b) 1 else 0) + data.s.length + data.d.toInt()
        }
        return count
    }

    @Benchmark
    fun octavius_simpleData_reflection(): Int {
        var count = 0
        val rows = octaviusReflectionQuery.fetchObjects<SimpleData>()
        for (data in rows) {
            count += data.i + (if(data.b) 1 else 0) + data.s.length + data.d.toInt()
        }
        return count
    }
}
