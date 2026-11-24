package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Logins with passwords the server put through SASLprep, or tried to, when the role's password was set.
 *
 * A candidate for office stood out in a whitened toga, the toga candida; here `candidatus` stands out by a password
 * that is not plain ASCII, and each test sets it with `CREATE ROLE`, so the server derives the stored key itself.
 */
class SaslPrepTest : AbstractIntegrationTest() {

    private fun assertLogsInWith(password: String) {
        openSession().use { session ->
            session.createNativeQuery("DROP ROLE IF EXISTS candidatus").execute()
            session.createNativeQuery("CREATE ROLE candidatus LOGIN PASSWORD '$password'").execute()
        }
        openSession { user = "candidatus"; this.password = password }.use { session ->
            assertEquals("candidatus", session.createNativeQuery("SELECT current_user").fetchFieldStrict<String>())
        }
    }

    @AfterEach
    fun dropRole() {
        openSession().use { it.createNativeQuery("DROP ROLE IF EXISTS candidatus").execute() }
    }

    @Test
    fun `logs in with a password whose non-ASCII space SASLprep maps to an ASCII one`() =
        assertLogsInWith("Roma aeterna")

    @Test
    fun `logs in with a password holding a character SASLprep maps to nothing`() =
        assertLogsInWith("Senatus­Populusque")

    @Test
    fun `logs in with a password NFKC rewrites`() =
        // A ligature, full-width letters and an accent given as a combining mark
        assertLogsInWith("ﬁdes ＳＰＱＲ Clélia")

    @Test
    fun `logs in with a password SASLprep refuses for a prohibited character, hashed as it is`() =
        assertLogsInWith("Roma aeterna‎")

    @Test
    fun `logs in with a password SASLprep refuses for a character it counts as unassigned, hashed as it is`() =
        assertLogsInWith("Roma aeternaȡ")

    @Test
    fun `logs in with a password SASLprep refuses for mixing right-to-left and left-to-right, hashed as it is`() =
        assertLogsInWith("שלום Roma")

    @Test
    fun `logs in with a password SASLprep refuses for mapping it to nothing, hashed as it is`() =
        assertLogsInWith("­­")
}
