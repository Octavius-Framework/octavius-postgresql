package io.github.octaviusframework.driver.notice

/**
 * Receives the server's notices, set as `noticeHandler` or named in `noticeHandlerClassName`. It runs on the
 * connection's thread mid-read, so anything slow belongs on another thread.
 */
interface NoticeHandler {
    /** Called for every notice; a shared handler tells connections apart by [PgNotice.processId]. */
    fun handleNotice(notice: PgNotice)
}
