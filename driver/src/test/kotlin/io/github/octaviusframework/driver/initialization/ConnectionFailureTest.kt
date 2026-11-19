package io.github.octaviusframework.driver.initialization

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A session that could not be opened, against the one real server there is. */
class ConnectionFailureTest : AbstractIntegrationTest() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Test
    fun `a password the server rejects is SERVER_REJECTED_CREDENTIALS`() {
        val exception = assertFailsWith<InitializationException> {
            openSession { password = "wrong_password" }
        }
        logger.error(exception) { "" }
        assertEquals(InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS, exception.reason)
        assertEquals("28P01", exception.sqlState)
    }

    @Test
    fun `a database that does not exist is CONNECTION_ERROR, carrying the server's FATAL`() {
        // A login the server refuses is no session at all, whatever its code - 3D000 would route to a statement
        // error, for a statement that was never sent
        val exception = assertFailsWith<InitializationException> {
            getOctaviusSession("jdbc:octavius://${TestDatabase.HOST}:${TestDatabase.PORT}/nonexistent_curia", TestDatabase.properties())
        }
        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, exception.reason)
        assertEquals("3D000", exception.sqlState)
        assertEquals("FATAL", exception.serverErrorMessage?.severity)
    }

    @Test
    fun `a host nothing listens on is CONNECTION_ERROR`() {
        val exception = assertFailsWith<InitializationException> {
            getOctaviusSession("jdbc:octavius://${TestDatabase.HOST}:54321/${TestDatabase.DATABASE}", TestDatabase.properties())
        }
        logger.error(exception) { "" }
        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, exception.reason)
    }
}
