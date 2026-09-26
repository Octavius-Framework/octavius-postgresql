package io.github.octaviusframework.driver.hikari

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class HikariIntegrationTest {

    @Test
    fun testHikariConnectionAndEviction() {
        val config = HikariConfig()
        config.jdbcUrl = TestDatabase.URL
        config.username = TestDatabase.USER
        config.password = TestDatabase.PASSWORD
        config.maximumPoolSize = 1 // A pool of one, so a connection that was not returned shows up immediately

        val dataSource = HikariDataSource(config)

        try {
            val session = dataSource.getOctaviusSession()
            val nativeQuery = session.createNativeQuery("SELECT 1 AS num")
            val row = nativeQuery.fetchRowStrict()
            Assertions.assertEquals(1, row.get<Int>("num"))

            // Abort the session
            session.abort()

            // Try to use it again, should fail because it was aborted
            assertFailsWith<Exception> {
                session.createNativeQuery("SELECT 2 AS num").execute()
            }

            session.close()

            // The connection should be evicted from Hikari.
            // Without eviction newSession would be bypass isValid check by aliveBypassWindowMs and throw connection closed error
            val newSession = dataSource.getOctaviusSession()
            val newRow = newSession.createNativeQuery("SELECT 3 AS num").fetchRowStrict()
            Assertions.assertEquals(3, newRow.get<Int>("num"))
            newSession.close()

        } finally {
            dataSource.close()
        }
    }
}