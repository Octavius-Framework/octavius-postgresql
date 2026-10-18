package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage
import org.junit.jupiter.api.Test
import java.sql.SQLNonTransientConnectionException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SQLExceptionWrapperTest {

    @Test
    fun `the wrapper records no stack trace and leaves the wrapped exception's intact`() {
        val wrapped = NetworkException(NetworkExceptionReason.CONNECTION_ABORTED, sqlState = "08000")

        val wrapper = SQLExceptionWrapper(wrapped)

        assertEquals(0, wrapper.stackTrace.size)
        assertTrue(wrapped.stackTrace.isNotEmpty())
        assertSame(wrapped, wrapper.wrappedException)
        assertEquals("08000", wrapper.sqlState)
        assertNull(wrapper.cause)
        assertNull(wrapper.nextException)
    }

    @Test
    fun `a terminated session keeps the server's code and chains the one a pool evicts on`() {
        val terminated = SessionTerminatedException(
            SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT,
            "25P04",
            ServerErrorMessage(
                severity = "FATAL", localizedSeverity = "FATAL", code = "25P04",
                message = "terminating connection due to transaction timeout",
                detail = null, hint = null, position = null, internalPosition = null, internalQuery = null,
                where = null, schema = null, table = null, column = null, datatype = null, constraint = null,
                file = null, line = null, routine = null
            )
        )

        val wrapper = SQLExceptionWrapper(terminated)

        // The code that says why stays first - it is what a translator reads - and the class a pool reads to
        // evict a connection comes next, the way a driver chains the I/O failure behind a FATAL.
        assertEquals("25P04", wrapper.sqlState)
        val next = assertIs<SQLNonTransientConnectionException>(wrapper.nextException)
        assertEquals("08006", next.sqlState)
        assertTrue(next.message!!.contains("transaction timeout"), next.message)
        assertEquals(0, next.stackTrace.size)
    }
}
