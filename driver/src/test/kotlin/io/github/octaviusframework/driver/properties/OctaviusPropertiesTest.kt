package io.github.octaviusframework.driver.properties

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.ssl.SslMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertFailsWith

class OctaviusPropertiesTest {

    @Test
    fun `should parse host port database and query parameters`() {
        val props = OctaviusProperties.parse("jdbc:octavius://db.local:5433/res_publica?user=consul&socketTimeout=30")

        assertEquals("db.local", props.serverName)
        assertEquals(5433, props.portNumber)
        assertEquals("res_publica", props.databaseName)
        assertEquals("consul", props.user)
        assertEquals(30, props.socketTimeout)
    }

    @Test
    fun `should keep a value containing an equals sign`() {
        val props = OctaviusProperties.parse("jdbc:octavius://localhost:5432/res_publica?password=sen=atus=&user=consul")

        assertEquals("sen=atus=", props.password)
        assertEquals("consul", props.user)
    }

    @Test
    fun `should keep an equals sign inside a startup parameter`() {
        val props = OctaviusProperties.parse("jdbc:octavius://localhost:5432/res_publica?options=-c search_path=curia")

        assertEquals("-c search_path=curia", props.additionalProperties["options"])
    }

    @Test
    fun `should survive a round trip through toUrl`() {
        val original = OctaviusProperties()
        original.serverName = "localhost"
        original.portNumber = 5432
        original.databaseName = "res_publica"
        original.user = "con=sul"
        original.additionalProperties["options"] = "-c search_path=curia"

        val parsed = OctaviusProperties.parse(original.toUrl())

        assertEquals("con=sul", parsed.user)
        assertEquals("-c search_path=curia", parsed.additionalProperties["options"])
    }

    @Test
    fun `toUrl should render neither the password nor the sslPassword`() {
        val original = OctaviusProperties()
        original.serverName = "localhost"
        original.password = "senatus"
        original.sslKey = "/etc/octavius/client.key"
        original.sslPassword = "populusque"

        val url = original.toUrl()

        // Both open something, and a URL is written where neither belongs. The key path is not a
        // secret and stays, which is what makes the omission of the one beside it visible at all.
        assertFalse(url.contains("senatus"), url)
        assertFalse(url.contains("populusque"), url)
        assertTrue(url.contains("sslkey=%2Fetc%2Foctavius%2Fclient.key"), url)

        val parsed = OctaviusProperties.parse(url)
        assertNull(parsed.password)
        assertNull(parsed.sslPassword)
    }

    @Test
    fun `should parse a bracketed IPv6 host with a port`() {
        val props = OctaviusProperties.parse("jdbc:octavius://[2001:db8::1]:5433/res_publica?user=consul")

        // The brackets are the URL's, not the address's: what is stored is what gets connected to
        // and matched against a certificate.
        assertEquals("2001:db8::1", props.serverName)
        assertEquals(5433, props.portNumber)
        assertEquals("res_publica", props.databaseName)
        assertEquals("consul", props.user)
    }

    @Test
    fun `should parse a bracketed IPv6 host without a port`() {
        val props = OctaviusProperties.parse("jdbc:octavius://[::1]/res_publica")

        // Every colon here belongs to the address, so no port is stated and the default applies later.
        assertEquals("::1", props.serverName)
        assertNull(props.portNumber)
        assertEquals("res_publica", props.databaseName)
    }

    @Test
    fun `should read an unbracketed IPv6 address in the url the way serverName reads it`() {
        val props = OctaviusProperties.parse("jdbc:octavius://::1/res_publica")

        // More than one colon is an address, with no room for a port
        assertEquals("::1", props.serverName)
        assertNull(props.portNumber)
    }

    @Test
    fun `should round trip an IPv6 host through toUrl`() {
        val original = OctaviusProperties()
        original.serverName = "::1"
        original.portNumber = 5433
        original.databaseName = "res_publica"

        val parsed = OctaviusProperties.parse(original.toUrl())

        assertEquals("::1", parsed.serverName)
        assertEquals(5433, parsed.portNumber)
        assertEquals("res_publica", parsed.databaseName)
    }

    @Test
    fun `should parse several servers, each with a port of its own or the one for all`() {
        val props = OctaviusProperties.parse("jdbc:octavius://db1:5433,db2,[2001:db8::1]:5434/res_publica?port=6000")

        // Kept as written: the ports that come with a server stay with it, and portNumber is the one for the rest
        assertEquals("db1:5433,db2,[2001:db8::1]:5434", props.serverName)
        assertEquals(
            listOf(ServerAddress("db1", 5433), ServerAddress("db2", 6000), ServerAddress("2001:db8::1", 5434)),
            props.servers()
        )
        assertEquals("res_publica", props.databaseName)
    }

    @Test
    fun `should refuse an entry of the address it cannot read`() {
        val refused = listOf(
            "db.local:abc",
            "db1:5433,db2:abc",
            "db1,,db2"
        )

        for (address in refused) {
            val e = assertFailsWith<InvalidOperationException>(address) {
                OctaviusProperties.parse("jdbc:octavius://$address/res_publica")
            }
            assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, e.reason)
            assertTrue(e.details!!.contains(address), e.details)
        }
    }

    @Test
    fun `should read several servers given as the host parameter`() {
        val props = OctaviusProperties.parse("jdbc:octavius:///res_publica?host=db1,db2&port=5433")

        assertEquals(listOf(ServerAddress("db1", 5433), ServerAddress("db2", 5433)), props.servers())
    }

    @Test
    fun `should read a lone bare IPv6 address as one server`() {
        val props = OctaviusProperties().apply {
            serverName = "::1"
            portNumber = 5433
        }

        assertEquals(listOf(ServerAddress("::1", 5433)), props.servers())
    }

    @Test
    fun `should round trip several servers and how to choose among them through toUrl`() {
        val original = OctaviusProperties().apply {
            serverName = "db1,[::1]:5434"
            portNumber = 5433
            targetSessionAttrs = TargetSessionAttrs.PRIMARY
            loadBalanceHosts = LoadBalanceHosts.RANDOM
        }

        val parsed = OctaviusProperties.parse(original.toUrl())

        assertEquals(original.servers(), parsed.servers())
        assertEquals(TargetSessionAttrs.PRIMARY, parsed.targetSessionAttrs)
        assertEquals(LoadBalanceHosts.RANDOM, parsed.loadBalanceHosts)
    }

    @Test
    fun `should read target_session_attrs and load_balance_hosts under either spelling`() {
        val libpq = OctaviusProperties.parse(
            "jdbc:octavius://db1,db2/res_publica?target_session_attrs=prefer-standby&load_balance_hosts=random"
        )
        val camelCased = OctaviusProperties.parse(
            "jdbc:octavius://db1,db2/res_publica?targetSessionAttrs=PREFER_STANDBY&loadBalanceHosts=RANDOM"
        )

        for (props in listOf(libpq, camelCased)) {
            assertEquals(TargetSessionAttrs.PREFER_STANDBY, props.targetSessionAttrs)
            assertEquals(LoadBalanceHosts.RANDOM, props.loadBalanceHosts)
            assertEquals(emptyMap<String, String>(), props.additionalProperties)
        }
    }

    @Test
    fun `merge should carry how to choose among the servers without erasing it`() {
        val base = OctaviusProperties().apply {
            targetSessionAttrs = TargetSessionAttrs.STANDBY
            loadBalanceHosts = LoadBalanceHosts.RANDOM
        }

        base.merge(OctaviusProperties())
        assertEquals(TargetSessionAttrs.STANDBY, base.targetSessionAttrs)
        assertEquals(LoadBalanceHosts.RANDOM, base.loadBalanceHosts)

        base.merge(OctaviusProperties().apply { targetSessionAttrs = TargetSessionAttrs.READ_WRITE })
        assertEquals(TargetSessionAttrs.READ_WRITE, base.copy().targetSessionAttrs)
        assertEquals(LoadBalanceHosts.RANDOM, base.copy().loadBalanceHosts)
    }

    @Test
    fun `toUrl should render a serverName it cannot read rather than refuse it`() {
        val props = OctaviusProperties().apply { serverName = "db1,,db2" }

        // A URL is for a log line; the connection is what refuses it
        assertTrue(props.toUrl().startsWith("jdbc:octavius://db1,,db2:5432/"), props.toUrl())
        assertFailsWith<InvalidOperationException> { props.servers() }
    }

    @Test
    fun `url should win over info where it states something`() {
        val info = Properties()
        info.setProperty("serverName", "from-info")
        info.setProperty("portNumber", "1111")
        info.setProperty("databaseName", "db-from-info")
        info.setProperty("user", "user-from-info")

        val props = OctaviusProperties.parse("jdbc:octavius://from-url:2222/db-from-url?user=user-from-url", info)

        assertEquals("from-url", props.serverName)
        assertEquals(2222, props.portNumber)
        assertEquals("db-from-url", props.databaseName)
        assertEquals("user-from-url", props.user)
    }

    @Test
    fun `url should leave info alone where it states nothing`() {
        val info = Properties()
        info.setProperty("serverName", "from-info")
        info.setProperty("portNumber", "1111")
        info.setProperty("databaseName", "db-from-info")

        val props = OctaviusProperties.parse("jdbc:octavius://?user=consul", info)

        assertEquals("from-info", props.serverName)
        assertEquals(1111, props.portNumber)
        assertEquals("db-from-info", props.databaseName)
        assertEquals("consul", props.user)
    }

    @Test
    fun `a url without a database should not reset the one info supplied`() {
        val info = Properties()
        info.setProperty("databaseName", "db-from-info")

        val props = OctaviusProperties.parse("jdbc:octavius://from-url:2222", info)

        assertEquals("from-url", props.serverName)
        assertEquals(2222, props.portNumber)
        assertEquals("db-from-info", props.databaseName)
    }

    @Test
    fun `a url without a port should not reset the one info supplied`() {
        val info = Properties()
        info.setProperty("portNumber", "1111")

        val props = OctaviusProperties.parse("jdbc:octavius://from-url/db", info)

        assertEquals("from-url", props.serverName)
        assertEquals(1111, props.portNumber)
        assertEquals("db", props.databaseName)
    }

    @Test
    fun `should leave host port and database unset when the url states none of them`() {
        val props = OctaviusProperties.parse("jdbc:octavius://")

        assertNull(props.serverName)
        assertNull(props.portNumber)
        assertNull(props.databaseName)
    }

    @Test
    fun `should read query parameters from a url that omits the database`() {
        val props = OctaviusProperties.parse("jdbc:octavius://db.local:5433?user=consul&socketTimeout=30")

        assertEquals("db.local", props.serverName)
        assertEquals(5433, props.portNumber)
        assertNull(props.databaseName)
        assertEquals("consul", props.user)
        assertEquals(30, props.socketTimeout)
    }

    @Test
    fun `a query parameter should win over the authority it follows`() {
        val props = OctaviusProperties.parse("jdbc:octavius://from-authority:2222/db?host=from-query&port=3333")

        assertEquals("from-query", props.serverName)
        assertEquals(3333, props.portNumber)
    }

    @Test
    fun `a url that is not ours should leave info untouched`() {
        val info = Properties()
        info.setProperty("serverName", "from-info")
        info.setProperty("databaseName", "db-from-info")

        val props = OctaviusProperties.parse("jdbc:postgresql://elsewhere:9999/other", info)

        assertEquals("from-info", props.serverName)
        assertEquals("db-from-info", props.databaseName)
    }

    @Test
    fun `should read an application name under either spelling`() {
        val underscored = OctaviusProperties.parse("jdbc:octavius://localhost:5432/res_publica?application_name=LegioXIII")
        val camelCased = OctaviusProperties.parse("jdbc:octavius://localhost:5432/res_publica?applicationName=LegioXIII")

        assertEquals("LegioXIII", underscored.applicationName)
        assertEquals("LegioXIII", camelCased.applicationName)

        // Recognised now, so neither spelling is left among the pass-through parameters
        assertEquals(emptyMap<String, String>(), underscored.additionalProperties)
        assertEquals(emptyMap<String, String>(), camelCased.additionalProperties)
    }

    @Test
    fun `should render the application name under PostgreSQL's own spelling`() {
        val original = OctaviusProperties()
        original.serverName = "localhost"
        original.applicationName = "Legio XIII"

        val url = original.toUrl()

        assertTrue(url.contains("application_name=Legio+XIII"), url)
        assertEquals("Legio XIII", OctaviusProperties.parse(url).applicationName)
    }

    @Test
    fun `the application name property should win over one left in additionalProperties`() {
        val props = OctaviusProperties()
        props.additionalProperties["application_name"] = "by-hand"
        props.applicationName = "typed"

        // Both would render under the same key, and the one the startup message sends is the one shown
        val url = props.toUrl()

        assertFalse(url.contains("by-hand"), url)
        assertEquals("typed", OctaviusProperties.parse(url).applicationName)
    }

    @Test
    fun `merge should carry the application name without erasing it`() {
        val base = OctaviusProperties()
        base.applicationName = "base"

        base.merge(OctaviusProperties())
        assertEquals("base", base.applicationName)

        base.merge(OctaviusProperties().apply { applicationName = "overlay" })
        assertEquals("overlay", base.applicationName)
        assertEquals("overlay", base.copy().applicationName)
    }

    @Test
    fun `should refuse a stated value it does not recognise`() {
        // Each of these used to resolve to the property's default, which for the two ssl ones means
        // asking for a guarantee and silently connecting without it.
        val refused = listOf(
            "sslmode=verify-fll",
            "channelBinding=requir",
            "ssl=yes",
            "port=abc",
            "socketTimeout=30s",
            "logParameterValues=tak",
            "target_session_attrs=master",
            "load_balance_hosts=true",
            "host=db1,,db2",
            "host=db1:abc,db2"
        )

        for (parameter in refused) {
            val e = assertFailsWith<InvalidOperationException>(parameter) {
                OctaviusProperties.parse("jdbc:octavius://localhost:5432/res_publica?$parameter")
            }
            assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, e.reason)
            // The offending value is quoted back - `message` being the machine identifier here, the
            // one a human reads is the detail line
            assertTrue(e.details!!.contains(parameter.substringAfter('=')), e.details)
        }
    }

    @Test
    fun `should still take a name it does not recognise as a startup parameter`() {
        // The strictness is about values, not names: an unknown name is not a typo the driver can
        // rule on, since a startup parameter is exactly a name the driver has never heard of.
        val props = OctaviusProperties.parse("jdbc:octavius://localhost:5432/res_publica?statement_timeout=5s&sslmode=verify-full")

        assertEquals("5s", props.additionalProperties["statement_timeout"])
        assertEquals(SslMode.VERIFY_FULL, props.sslMode)
    }

    @Test
    fun `should ignore a query entry that has no value`() {
        val props = OctaviusProperties.parse("jdbc:octavius://localhost:5432/res_publica?ssl&=orphan&user=consul")

        assertNull(props.ssl)
        assertEquals("consul", props.user)
        assertEquals(emptyMap<String, String>(), props.additionalProperties)
    }
}
