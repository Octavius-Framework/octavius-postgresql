package io.github.octaviusframework.driver.util.reflection

import kotlin.test.Test
import kotlin.test.assertEquals

class SealedFamilyTest {

    sealed interface Magistracy
    sealed class Curule : Magistracy
    class Consul : Curule()
    class Praetor : Curule()
    data object Dictator : Magistracy

    // Sealed under both, so reached twice
    sealed interface Elected
    class Aedile : Magistracy, Elected

    sealed class Promagistracy
    open class Proconsul : Promagistracy()
    class ProconsulOfAsia : Proconsul()

    @Test
    fun `a sealed class brings every class below it, through sealed classes all the way down`() {
        assertEquals(
            setOf(Magistracy::class, Curule::class, Consul::class, Praetor::class, Dictator::class, Aedile::class),
            Magistracy::class.sealedFamily()
        )
    }

    @Test
    fun `a class below an open one is not listed`() {
        assertEquals(setOf(Promagistracy::class, Proconsul::class), Promagistracy::class.sealedFamily())
    }

    @Test
    fun `a class that is not sealed is only itself`() {
        assertEquals(setOf(Consul::class), Consul::class.sealedFamily())
        assertEquals(setOf(String::class), String::class.sealedFamily())
    }
}
