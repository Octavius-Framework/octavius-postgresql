package io.github.octaviusframework.driver.initialization

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.jdbc.OctaviusConnectionFactory
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Properties

class UnsupportedVersionTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_UNSUPPORTED_PG_VERSION", matches = "true")
    fun `should throw UNSUPPORTED_SERVER_VERSION when connecting to older PostgreSQL`() {
        val props = Properties().apply {
            setProperty("user", TestDatabase.USER)
            setProperty("password", TestDatabase.PASSWORD)
        }

        val exception = assertThrows(InitializationException::class.java) {
            OctaviusConnectionFactory.createConnection(TestDatabase.URL, props)
        }
        
        assertEquals(InitializationExceptionReason.UNSUPPORTED_SERVER_VERSION, exception.reason)
        assertTrue(exception.details!!.contains("does not support the requested protocol version 3.2"), "Unexpected detail message: ${exception.details}")
    }
}
