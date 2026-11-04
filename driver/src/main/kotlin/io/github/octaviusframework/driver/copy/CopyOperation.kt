package io.github.octaviusframework.driver.copy

/** A `COPY` in progress. */
interface CopyOperation : AutoCloseable {
    /** Whether the transfer is in progress, and the connection unusable for anything else. */
    val isActive: Boolean

    /**
     * Abandons the transfer and returns the connection to use. A COPY IN keeps none of what was written. Does
     * nothing once the transfer or the connection is over.
     */
    fun cancelCopy()

    /** Cancels the transfer if it is still running. */
    override fun close() {
        if (isActive) {
            cancelCopy()
        }
    }
}


