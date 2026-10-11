package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.properties.ServerAddress
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * One catalog for several servers holds only while they are one cluster. The one test database is a single
 * cluster, so a second one is told apart here by the identifier alone.
 */
class ClusterAdmissionTest {

    private val primary = ServerAddress("db1", 5432)
    private val standby = ServerAddress("db2", 5432)
    private val stranger = ServerAddress("db3", 5432)

    @Test
    fun `the first server admitted decides the cluster, and only servers of it are admitted after`() {
        val holder = CatalogHolder()

        assertEquals(7L, holder.admit(primary, 7L))
        assertEquals(7L, holder.admit(standby, 7L))
        assertEquals(7L, holder.admit(stranger, 8L))

        assertEquals(7L, holder.systemIdentifier)
        assertTrue(holder.isAdmitted(primary))
        assertTrue(holder.isAdmitted(standby))
        assertFalse(holder.isAdmitted(stranger))
    }

    @Test
    fun `the servers make one key whatever order they come in`() {
        val forward = DatabaseKey(setOf(primary, standby), "curia")
        val backward = DatabaseKey(setOf(standby, primary), "curia")

        assertEquals(forward, backward)
        assertEquals(forward.hashCode(), backward.hashCode())
        assertNotEquals(forward, DatabaseKey(setOf(primary), "curia"))
        assertEquals("db1:5432,db2:5432/curia", backward.toString())
    }
}
