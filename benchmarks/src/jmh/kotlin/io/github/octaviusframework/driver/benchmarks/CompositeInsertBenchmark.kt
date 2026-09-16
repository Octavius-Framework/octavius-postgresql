package io.github.octaviusframework.driver.benchmarks

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.query.NativeQuery
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.type.isKnownOid
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * The same 10 000 rows inserted through `UNNEST`, three ways: as two parallel scalar arrays, and
 * as one array of composites built either reflectively or by a hand-written converter.
 *
 * The scalar row is the baseline - it is what the composite paths are paying extra for.
 */
data class SenatorReflect(val id: Int, val cognomen: String)

data class SenatorExplicit(val id: Int, val cognomen: String)

class SenatorExplicitParameterConverter : ParameterConverter<SenatorExplicit> {
    override val supportedClass = SenatorExplicit::class

    override fun convert(source: SenatorExplicit, expectedOid: Int, context: SerializationContext): Any {
        val composite = if (expectedOid.isKnownOid) {
            context.types.containers.createComposite(expectedOid)
        } else {
            context.types.containers.createComposite("bench_senator")
        }
        composite["id"] = source.id
        composite["cognomen"] = source.cognomen
        return composite
    }

    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext) =
        QualifiedName("", "bench_senator")
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Threads(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
open class CompositeInsertBenchmark {

    private lateinit var octaviusSession: OctaviusSession
    private lateinit var scalarQuery: NativeQuery
    private lateinit var reflectionQuery: NativeQuery
    private lateinit var explicitQuery: NativeQuery

    private val insertCount = 10000
    private lateinit var ids: List<Int>
    private lateinit var texts: List<String>
    private lateinit var reflectRows: List<SenatorReflect>
    private lateinit var explicitRows: List<SenatorExplicit>

    @Setup(Level.Trial)
    fun setup() {
        Class.forName("io.github.octaviusframework.driver.jdbc.OctaviusDriver")

        octaviusSession = getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", "postgres", "1234")

        octaviusSession.createNativeQuery("DROP TABLE IF EXISTS bench_composite_insert").execute()
        octaviusSession.createNativeQuery("DROP TYPE IF EXISTS bench_senator CASCADE").execute()
        octaviusSession.createNativeQuery("CREATE TYPE bench_senator AS (id int, cognomen text)").execute()
        octaviusSession.createNativeQuery("CREATE TABLE bench_composite_insert (id int, cognomen text)").execute()
        octaviusSession.reloadTypes()

        // Global, and deliberately only for the reflective class - the other one is served by the
        // query-scoped converter below, so neither path can be mistaken for the other.
        octaviusSession.typeManager.registerAutoComposite<SenatorReflect>("bench_senator")

        scalarQuery = octaviusSession.createNativeQuery(
            "INSERT INTO bench_composite_insert (id, cognomen) SELECT * FROM UNNEST($1, $2)"
        )
        reflectionQuery = octaviusSession.createNativeQuery(
            "INSERT INTO bench_composite_insert (id, cognomen) SELECT s.id, s.cognomen FROM UNNEST($1) AS s"
        )
        explicitQuery = octaviusSession.createNativeQuery(
            "INSERT INTO bench_composite_insert (id, cognomen) SELECT s.id, s.cognomen FROM UNNEST($1) AS s"
        ).registerParameterConverter(SenatorExplicitParameterConverter())

        ids = (1..insertCount).toList()
        texts = ids.map { "data-$it" }
        reflectRows = ids.map { SenatorReflect(it, "data-$it") }
        explicitRows = ids.map { SenatorExplicit(it, "data-$it") }
    }

    @TearDown(Level.Invocation)
    fun truncateTable() {
        octaviusSession.createNativeQuery("TRUNCATE TABLE bench_composite_insert").execute()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        octaviusSession.createNativeQuery("DROP TABLE IF EXISTS bench_composite_insert").execute()
        octaviusSession.createNativeQuery("DROP TYPE IF EXISTS bench_senator CASCADE").execute()
        octaviusSession.close()
    }

    /** Baseline: no composite involved, one array per column. */
    @Benchmark
    fun octavius_unnest_scalar_arrays() {
        octaviusSession.autoCommit = false
        try {
            scalarQuery.update(ids, texts)
            octaviusSession.commit()
        } finally {
            octaviusSession.autoCommit = true
        }
    }

    /** One array of composites, each built by `registerAutoComposite` reflection. */
    @Benchmark
    fun octavius_unnest_composites_reflection() {
        octaviusSession.autoCommit = false
        try {
            reflectionQuery.update(reflectRows)
            octaviusSession.commit()
        } finally {
            octaviusSession.autoCommit = true
        }
    }

    /** The same array, each composite built by a hand-written converter. */
    @Benchmark
    fun octavius_unnest_composites_explicit() {
        octaviusSession.autoCommit = false
        try {
            explicitQuery.update(explicitRows)
            octaviusSession.commit()
        } finally {
            octaviusSession.autoCommit = true
        }
    }
}
