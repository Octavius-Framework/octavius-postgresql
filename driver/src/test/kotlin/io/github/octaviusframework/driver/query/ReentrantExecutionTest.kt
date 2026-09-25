package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A connection carries one exchange at a time. Code called back into while rows are being read -
 * a `forEach` block, a converter - must not be able to start its own statement on that session:
 * it would interleave its messages into the exchange in flight and desynchronize the connection.
 */
class ReentrantExecutionTest : AbstractIntegrationTest() {

    private lateinit var session: OctaviusSession

    override val schema = "CREATE TABLE reentrant_test (id INT, name TEXT)"

    @BeforeEach
    fun setup() {
        session = openSession()
        session.createNativeQuery("TRUNCATE reentrant_test").execute()
        session.createNativeQuery("INSERT INTO reentrant_test SELECT g, 'n' || g FROM generate_series(1, 6) g").update()
    }

    @AfterEach
    fun teardown() {
        session.close()
    }

    @Test
    fun `query from inside a forEach block is refused`() {
        val error = assertFailsWith<InvalidOperationException> {
            session.createNativeQuery("SELECT id FROM reentrant_test ORDER BY id").forEachRow(fetchSize = 2) {
                session.createNativeQuery("SELECT 99").fetchFieldStrict<Int>()
            }
        }
        assertEquals(InvalidOperationExceptionReason.CONNECTION_BUSY, error.reason)
        // The reason is shared with the COPY branch, so the details are what prove this is reentrancy.
        assertTrue(error.details!!.contains("statement is already executing"))

        // Refused before anything was sent, so the connection is still healthy
        assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
    }

    @Test
    fun `execute from inside a forEach block is refused and leaves the session usable`() {
        val error = assertFailsWith<InvalidOperationException> {
            session.createNativeQuery("SELECT id FROM reentrant_test ORDER BY id").forEachRow(fetchSize = 2) {
                session.createNativeQuery("SET application_name = 'nope'").execute()
            }
        }
        assertEquals(InvalidOperationExceptionReason.CONNECTION_BUSY, error.reason)
        assertTrue(error.details!!.contains("statement is already executing"))
        assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
    }

    @Test
    fun `starting a COPY from inside a forEach block is refused`() {
        val error = assertFailsWith<InvalidOperationException> {
            session.createNativeQuery("SELECT id FROM reentrant_test ORDER BY id").forEachRow(fetchSize = 2) {
                session.copy.copyOut("COPY reentrant_test TO STDOUT")
            }
        }
        assertEquals(InvalidOperationExceptionReason.CONNECTION_BUSY, error.reason)
        assertTrue(error.details!!.contains("statement is already executing"))
        assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
    }

    @Test
    fun `query from inside a converter is refused, streaming or not`() {
        // Non-streaming: the converter still runs while the result is being read
        val materialized = assertFailsWith<MappingException> {
            session.createNativeQuery("SELECT name FROM reentrant_test ORDER BY id")
                .registerResultConverter(SelfQueryingConverter(session))
                .fetchFields<String>()
        }
        assertTrue(materialized.cause is InvalidOperationException, "expected the guard underneath, got ${materialized.cause}")

        val streamed = assertFailsWith<MappingException> {
            session.createNativeQuery("SELECT name FROM reentrant_test ORDER BY id")
                .registerResultConverter(SelfQueryingConverter(session))
                .forEachField<String>(fetchSize = 2) { }
        }
        assertTrue(streamed.cause is InvalidOperationException, "expected the guard underneath, got ${streamed.cause}")

        assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
    }

    @Test
    fun `converting after the exchange has finished is allowed`() {
        // fetchRows defers conversion to the caller, so a querying converter is fine here
        val rows = session.createNativeQuery("SELECT name FROM reentrant_test ORDER BY id")
            .registerResultConverter(SelfQueryingConverter(session))
            .fetchRows()

        assertEquals(6, rows.size)
    }

    @Test
    fun `sequential queries are unaffected`() {
        repeat(3) {
            assertEquals(6L, session.createNativeQuery("SELECT count(*) FROM reentrant_test").fetchFieldStrict<Long>())
        }
    }

    @Test
    fun `a failed statement releases the connection`() {
        runCatching { session.createNativeQuery("SELECT * FROM no_such_table").fetchRows() }
        assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
    }

    private class SelfQueryingConverter(private val session: OctaviusSession) : ResultConverter<String, String> {
        override val supportedSourceClass = String::class
        override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
            sourceClass == String::class

        override fun convert(source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext): String {
            session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
            return source
        }
    }
}
