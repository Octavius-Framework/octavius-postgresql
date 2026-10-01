package io.github.octaviusframework.driver.exception

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
    }
}
