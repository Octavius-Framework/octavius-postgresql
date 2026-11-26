package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.session.OctaviusSessionOperations

/**
 * A slot [startPgOutput] creates before its stream starts. A [temporary] one goes when the stream closes. [copy]
 * runs in a read-only `REPEATABLE READ` transaction that reads the database as of the slot's start, which the
 * stream carries on from.
 */
class NewSlot(
    val temporary: Boolean = false,
    val copy: (OctaviusSessionOperations.() -> Unit)? = null
)
