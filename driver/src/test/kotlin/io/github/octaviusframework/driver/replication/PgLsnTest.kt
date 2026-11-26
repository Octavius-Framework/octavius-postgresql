package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PgLsnTest {

    @Test
    fun `a position reads and writes as the server does`() {
        assertEquals(0x16_B374D848L, PgLsn.parse("16/B374D848").value)
        assertEquals("16/B374D848", PgLsn(0x16_B374D848L).toString())
        assertEquals("0/1", PgLsn(1).toString())
        assertEquals(PgLsn(0xAB), PgLsn.parse("0/ab"))
    }

    @Test
    fun `positions are ordered as unsigned`() {
        assertTrue(PgLsn.parse("FFFFFFFF/0") > PgLsn.parse("1/0"))
        assertTrue(PgLsn.parse("0/FFFFFFFF") < PgLsn.parse("1/0"))
    }

    @Test
    fun `text that is not a position is refused`() {
        for (text in listOf("16B374D848", "1/123456789", "-1/0", "+1/0", "1/", "/1", "")) {
            val e = assertFailsWith<InvalidOperationException>(text) { PgLsn.parse(text) }
            assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, e.reason)
        }
    }
}
