package io.github.octaviusframework.driver.notification

/**
 * A notification received on a channel this connection listens on.
 *
 * @property processId The backend that ran the `NOTIFY`, usually another session's.
 * @property payload Empty when the `NOTIFY` carried none.
 */
data class PgNotification(
    val processId: Int,
    val channel: String,
    val payload: String
)