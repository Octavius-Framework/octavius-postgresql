package io.github.octaviusframework.driver.copy

/**
 * Base interface for COPY operations.
 */
interface CopyOperation : AutoCloseable {
    /**
     * Whether the transfer is still in progress, and with it whether the connection is still in copy
     * mode and unusable for ordinary queries.
     */
    val isActive: Boolean

    /**
     * Abandons the transfer and returns the connection to a usable state.
     *
     * Does nothing if the transfer has already finished, or if the connection has already gone - the server
     * discarded the transfer along with it. For a COPY IN this discards everything already written; the
     * server never commits a partial copy.
     */
    fun cancelCopy()

    /**
     * Cancels the transfer if it is still running, so `use { }` cannot leave a connection in copy mode.
     */
    override fun close() {
        if (isActive) {
            cancelCopy()
        }
    }
}


