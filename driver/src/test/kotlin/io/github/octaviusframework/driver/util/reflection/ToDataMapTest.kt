package io.github.octaviusframework.driver.util.reflection

import io.github.octaviusframework.annotation.PgName
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToDataMapTest {

    data class Senator(val cognomen: String, val legioStatus: String?, @PgName("provincia") val province: String) {
        val title: String = "senator"
    }

    class Magistrate(val cognomen: String, val office: String)

    data class Informer(val cognomen: String, private val source: String)

    @Test
    fun `the map holds the primary constructor properties in their order, a null under its key`() {
        val map = Senator("Cicero", null, "Cilicia").toDataMap()

        assertEquals(listOf("cognomen", "legio_status", "provincia"), map.keys.toList())
        assertEquals(mapOf("cognomen" to "Cicero", "legio_status" to null, "provincia" to "Cilicia"), map)
    }

    @Test
    fun `excluded keys are mapped names, not property names`() {
        val senator = Senator("Cicero", null, "Cilicia")

        assertEquals(mapOf("cognomen" to "Cicero", "provincia" to "Cilicia"), senator.toDataMap("legio_status"))
        assertEquals(senator.toDataMap(), senator.toDataMap("legioStatus"))
    }

    @Test
    fun `a private constructor property goes into the map like any other`() {
        assertEquals(mapOf("cognomen" to "Curius", "source" to "Fulvia"), Informer("Curius", "Fulvia").toDataMap())
    }

    @Test
    fun `a class that is not a data class is refused, naming it`() {
        val ex = assertThrows<InvalidOperationException> { Magistrate("Cato", "censor").toDataMap() }

        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, ex.reason)
        assertTrue(ex.details!!.contains("Magistrate"), ex.details)
    }
}
