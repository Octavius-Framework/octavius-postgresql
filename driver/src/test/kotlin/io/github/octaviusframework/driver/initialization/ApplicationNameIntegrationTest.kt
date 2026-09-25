package io.github.octaviusframework.driver.initialization

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * What the property is for: the name the *server* reports back for this connection. Everything else
 * about it - parsing, rendering, precedence - is settled without a server; this is the one part that
 * only PostgreSQL can answer.
 */
class ApplicationNameIntegrationTest : AbstractIntegrationTest() {

    private fun properties() = OctaviusProperties.parse(TestDatabase.URL).apply {
        user = TestDatabase.USER
        password = TestDatabase.PASSWORD
    }

    /** What `pg_stat_activity` shows for this connection's own backend. */
    private fun reportedName(properties: OctaviusProperties): String =
        getOctaviusSession(properties).use { session ->
            session.createNativeQuery("SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()")
                .fetchRowStrict()
                .get<String>(0)
        }

    @Test
    fun `should reach the server as a startup parameter`() {
        val props = properties()
        props.applicationName = "LegioXIII-App"

        assertEquals("LegioXIII-App", reportedName(props))
    }

    @Test
    fun `should reach the server when it comes from a url`() {
        val props = OctaviusProperties.parse("${TestDatabase.URL}?application_name=CuriaApi")
        props.user = TestDatabase.USER
        props.password = TestDatabase.PASSWORD

        assertEquals("CuriaApi", reportedName(props))
    }

    @Test
    fun `should win over an application_name left in additionalProperties`() {
        val props = properties()
        props.additionalProperties["application_name"] = "by-hand"
        props.applicationName = "typed"

        assertEquals("typed", reportedName(props))
    }

    @Test
    fun `should leave an application_name set only in additionalProperties alone`() {
        // The driver's own name fills a gap; it does not overwrite the way this was configured
        // before the property existed.
        val props = properties()
        props.additionalProperties["application_name"] = "by-hand"

        assertEquals("by-hand", reportedName(props))
    }

    @Test
    fun `should name the driver when nothing is set`() {
        assertEquals("Octavius Driver", reportedName(properties()))
    }
}
