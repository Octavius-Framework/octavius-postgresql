package io.github.octaviusframework.driver.hikari

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.driver.jdbc.OctaviusDataSource
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.ssl.SslMode
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HikariInitializationTest {

    @Test
    fun `should initialize hikari with octavius data source via class name`() {
        val config = HikariConfig()
        config.dataSourceClassName = "io.github.octaviusframework.driver.jdbc.OctaviusDataSource"
        config.addDataSourceProperty("serverName", TestDatabase.HOST)
        config.addDataSourceProperty("portNumber", "${TestDatabase.PORT}")
        config.addDataSourceProperty("databaseName", TestDatabase.DATABASE)
        config.addDataSourceProperty("user", TestDatabase.USER)
        config.addDataSourceProperty("password", TestDatabase.PASSWORD)

        val ds = HikariDataSource(config)
        assertDoesNotThrow {
            ds.connection.use { conn ->
                assertNotNull(conn)
            }
        }
        ds.close()
    }

    @Test
    fun `should set tuning knobs through addDataSourceProperty`() {
        val config = HikariConfig()
        config.dataSourceClassName = "io.github.octaviusframework.driver.jdbc.OctaviusDataSource"
        config.addDataSourceProperty("serverName", TestDatabase.HOST)
        config.addDataSourceProperty("portNumber", "${TestDatabase.PORT}")
        config.addDataSourceProperty("databaseName", TestDatabase.DATABASE)
        config.addDataSourceProperty("user", TestDatabase.USER)
        config.addDataSourceProperty("password", TestDatabase.PASSWORD)

        // Every value a string, which is what a properties file or Spring's data-source-properties
        // would hand over - Hikari coerces to the accessor's type, and only manages that for
        // primitives, boxed Boolean and String. Accessor types on OctaviusDataSource are chosen
        // to stay inside that set.
        config.addDataSourceProperty("ssl", "false")
        config.addDataSourceProperty("socketTimeout", "30")
        config.addDataSourceProperty("cancelSignalTimeout", "3")
        config.addDataSourceProperty("maxCachedRowSize", "8192")
        config.addDataSourceProperty("notificationBufferCapacity", "512")
        config.addDataSourceProperty("initialParameterWriterCapacity", "2048")
        config.addDataSourceProperty("maxParameterWriterCapacity", "131072")
        config.addDataSourceProperty("applicationName", "curia-api")

        val ds = HikariDataSource(config)
        assertDoesNotThrow {
            ds.connection.use { conn ->
                assertNotNull(conn)
            }
        }

        // The startup parameter with an accessor of its own: reachable from a properties file, where
        // every other one still needs the url.
        ds.connection.use { conn ->
            val reported = conn.getOctaviusSession(ownsConnection = false).use { session ->
                session.createNativeQuery("SELECT current_setting('application_name')")
                    .fetchRowStrict()
                    .get<String>(0)
            }
            assertEquals("curia-api", reported)
        }
        ds.close()
    }

    @Test
    fun `should accept a preconfigured OctaviusDataSource instance`() {
        // Handing Hikari the instance skips its reflective property setting entirely, so the
        // configuration is typed all the way - including sslMode, which cannot be set as a string
        // through addDataSourceProperty.
        val octavius = OctaviusDataSource().apply {
            serverName = TestDatabase.HOST
            portNumber = TestDatabase.PORT
            databaseName = TestDatabase.DATABASE
            user = TestDatabase.USER
            password = TestDatabase.PASSWORD
            sslMode = SslMode.DISABLE
            socketTimeout = 30
            cancelSignalTimeout = 3
        }

        val config = HikariConfig()
        config.dataSource = octavius
        config.maximumPoolSize = 1

        val ds = HikariDataSource(config)
        assertDoesNotThrow {
            ds.connection.use { conn ->
                assertNotNull(conn)
            }
        }
        ds.close()
    }

    @Test
    fun `should reject a property name that has no accessor`() {
        val config = HikariConfig()
        config.dataSourceClassName = "io.github.octaviusframework.driver.jdbc.OctaviusDataSource"
        config.addDataSourceProperty("serverName", TestDatabase.HOST)
        config.addDataSourceProperty("databaseName", TestDatabase.DATABASE)
        config.addDataSourceProperty("thisIsNotAProperty", "1")

        // Hikari fails the pool rather than dropping the setting silently, which is the reason
        // every field of OctaviusProperties needs an accessor here.
        assertThrows<RuntimeException> { HikariDataSource(config) }
    }

    @Test
    fun `should initialize hikari with jdbc url directly`() {
        val config = HikariConfig()
        config.jdbcUrl = TestDatabase.URL
        config.username = TestDatabase.USER
        config.password = TestDatabase.PASSWORD
        
        // When using jdbcUrl, Hikari will try to use DriverManager to find the driver
        val ds = HikariDataSource(config)
        assertDoesNotThrow {
            ds.connection.use { conn ->
                assertNotNull(conn)
            }
        }
        ds.close()
    }

    @Test
    fun `should initialize hikari using properties`() {
        val props = java.util.Properties()
        props.setProperty("dataSourceClassName", "io.github.octaviusframework.driver.jdbc.OctaviusDataSource")
        props.setProperty("dataSource.serverName", TestDatabase.HOST)
        props.setProperty("dataSource.portNumber", "${TestDatabase.PORT}")
        props.setProperty("dataSource.databaseName", TestDatabase.DATABASE)
        props.setProperty("dataSource.user", TestDatabase.USER)
        props.setProperty("dataSource.password", TestDatabase.PASSWORD)
        
        val config = HikariConfig(props)
        val ds = HikariDataSource(config)
        assertDoesNotThrow {
            ds.connection.use { conn ->
                assertNotNull(conn)
            }
        }
        ds.close()
    }
}
