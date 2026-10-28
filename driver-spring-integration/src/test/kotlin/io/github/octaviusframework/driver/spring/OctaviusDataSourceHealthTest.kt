package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator

/**
 * Actuator's `db` health indicator as Boot builds it over a pool with no `connection-test-query`: it labels its
 * report with the database's name and checks the connection with `isValid()`.
 */
class OctaviusDataSourceHealthTest {

    @Test
    fun `Actuator's db health indicator reports the database up`() {
        TestDatabase.dataSource().use { dataSource ->
            val health = DataSourceHealthIndicator(dataSource, null).health()

            assertEquals(Status.UP, health.status, "details: ${health.details}")
            assertEquals("PostgreSQL", health.details["database"])
        }
    }
}
