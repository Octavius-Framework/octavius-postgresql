package io.github.octaviusframework.driver.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/** An executor and a coroutine dispatcher on virtual threads, for the driver's blocking calls. */
object OctaviusDispatchers {

    /** An [ExecutorService] starting a virtual thread per task. */
    val VirtualExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /** A [CoroutineDispatcher] over [VirtualExecutor]. */
    val Virtual: CoroutineDispatcher = VirtualExecutor.asCoroutineDispatcher()
}