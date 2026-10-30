package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.ssl.SslMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

class OctaviusDataSourceInitializationTest {

    @Test
    fun `should parse url correctly and set properties`() {
        val ds = OctaviusDataSource()
        ds.url = "jdbc:octavius://myhost:5433/mydb?user=testuser&password=testpass&ssl=true&sslmode=require"

        assertEquals("myhost", ds.serverName)
        assertEquals(5433, ds.portNumber)
        assertEquals("mydb", ds.databaseName)
        assertEquals("testuser", ds.user)
        assertEquals("testpass", ds.password)
        assertEquals(true, ds.ssl)
        assertEquals(SslMode.REQUIRE, ds.sslMode)
    }

    @Test
    fun `should merge properties when url is set after explicit setters`() {
        val ds = OctaviusDataSource()
        ds.user = "explicituser"
        ds.loginTimeout = 30
        
        // This will override explicituser to urluser, because url parsing merges into existing
        ds.url = "jdbc:octavius://myhost:5432/mydb?user=urluser"

        assertEquals("urluser", ds.user)
        assertEquals(30, ds.loginTimeout)
        assertEquals("myhost", ds.serverName)
        assertEquals(5432, ds.portNumber)
    }

    @Test
    fun `should round-trip tuning knobs through the url`() {
        val ds = OctaviusDataSource()
        ds.url = "jdbc:octavius://myhost:5432/mydb?user=testuser&socketTimeout=30&cancelSignalTimeout=3"

        assertEquals(30, ds.socketTimeout)
        assertEquals(3, ds.cancelSignalTimeout)

        val rendered = ds.url
        assertTrue(rendered.contains("socketTimeout=30"), rendered)
        assertTrue(rendered.contains("cancelSignalTimeout=3"), rendered)
    }

    @Test
    fun `should expose every tuning knob as a bean property`() {
        val ds = OctaviusDataSource()

        // Unset, each reports the driver's documented default rather than null
        assertEquals(0, ds.socketTimeout)
        assertEquals(10, ds.cancelSignalTimeout)
        assertEquals(65536, ds.maxCachedRowSize)
        assertEquals(256, ds.notificationBufferCapacity)
        assertEquals(1024, ds.initialParameterWriterCapacity)
        assertEquals(65536, ds.maxParameterWriterCapacity)
        assertNull(ds.noticeHandlerClassName)

        ds.socketTimeout = 30
        ds.cancelSignalTimeout = 3
        ds.maxCachedRowSize = 8192
        ds.notificationBufferCapacity = 512
        ds.initialParameterWriterCapacity = 2048
        ds.maxParameterWriterCapacity = 131072
        ds.noticeHandlerClassName = "com.example.MyNoticeHandler"

        // Set through the accessors, they reach the same place the url would have put them
        val rendered = ds.url
        assertTrue(rendered.contains("socketTimeout=30"), rendered)
        assertTrue(rendered.contains("cancelSignalTimeout=3"), rendered)
        assertTrue(rendered.contains("maxCachedRowSize=8192"), rendered)
        assertTrue(rendered.contains("notificationBufferCapacity=512"), rendered)
        assertTrue(rendered.contains("initialParameterWriterCapacity=2048"), rendered)
        assertTrue(rendered.contains("maxParameterWriterCapacity=131072"), rendered)
        assertTrue(rendered.contains("noticeHandlerClassName=com.example.MyNoticeHandler"), rendered)
    }

    @Test
    fun `should reach startup parameters through setProperty`() {
        val ds = OctaviusDataSource()
        ds.setProperty("search_path", "castra, public")
        // Unrecognised keys are startup parameters, so they ride along in the url too
        assertTrue(ds.url.contains("search_path=castra%2C+public"), ds.url)
    }

    @Test
    fun `should expose the application name as a bean property`() {
        val ds = OctaviusDataSource()
        assertNull(ds.applicationName)

        // A String accessor, so HikariCP's addDataSourceProperty can set it from a properties file
        ds.applicationName = "curia-api"

        assertEquals("curia-api", ds.applicationName)
        assertTrue(ds.url.contains("application_name=curia-api"), ds.url)
    }

    @Test
    fun `setProperty should reach the application name property`() {
        val ds = OctaviusDataSource()
        ds.setProperty("application_name", "LegioXIII-App")

        assertEquals("LegioXIII-App", ds.applicationName)
    }

    @Test
    fun `should expose every scalar property of OctaviusProperties as a bean property`() {
        // The point of this class is that a pool configuring it through JavaBean setters can set
        // anything a URL could. A field added to OctaviusProperties without an accessor here
        // would silently be unreachable that way, so the check is reflective rather than a list.
        val exposed = OctaviusDataSource::class.memberProperties.map { it.name }.toSet() +
                setOf("loginTimeout") // getLoginTimeout/setLoginTimeout, from the DataSource interface

        val expected = OctaviusProperties::class.memberProperties
            .filter { it.name != "additionalProperties" } // a map, reached through setProperty
            .map { it.name }

        // Guard: an empty list here would make the assertion below vacuously true
        assertTrue(expected.size > 10, "Reflection found almost nothing to check: $expected")
        assertTrue("socketTimeout" in expected, "Expected list looks wrong: $expected")

        val missing = expected.filter { it !in exposed }
        assertTrue(missing.isEmpty(), "OctaviusDataSource is missing accessors for: $missing")
    }

    @Test
    fun `should never render the password into the url`() {
        val ds = OctaviusDataSource()
        ds.url = "jdbc:octavius://myhost:5433/mydb?user=testuser&password=testpass&sslmode=require"

        val rendered = ds.url
        assertFalse(rendered.contains("testpass"), "Password leaked into the URL: $rendered")
        assertFalse(rendered.contains("password"), "Password key present in the URL: $rendered")

        // Everything else still round-trips, and the password is still readable on its own
        assertTrue(rendered.contains("user=testuser"), rendered)
        assertTrue(rendered.contains("sslmode=require"), rendered)
        assertEquals("testpass", ds.password)
    }

    @Test
    fun `should keep explicit properties when set after url`() {
        val ds = OctaviusDataSource()
        ds.url = "jdbc:octavius://myhost:5432/mydb?user=urluser"
        ds.user = "explicituser"

        assertEquals("explicituser", ds.user)
        assertEquals("myhost", ds.serverName)
    }
}
