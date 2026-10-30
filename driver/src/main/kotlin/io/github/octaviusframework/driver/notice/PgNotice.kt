package io.github.octaviusframework.driver.notice

import io.github.octaviusframework.driver.message.backend.ErrorOrNoticeMessage

/**
 * Represents a notice or warning message received from the PostgreSQL backend.
 *
 * A `NoticeResponse` carries the same field set as an `ErrorResponse`, so this holds the same fields
 * [ServerErrorMessage][io.github.octaviusframework.driver.message.ServerErrorMessage] does, under the
 * same names. Which of them arrive depends on what raised the notice: [file], [line] and [routine] come
 * with every one, while [schema], [table], [column], [datatype] and [constraint] are what
 * `RAISE ... USING` puts there.
 *
 * @property processId Process id of the backend serving this connection, the one that raised the
 *   notice. It is not a field of the `NoticeResponse` message - the driver carries it over from the
 *   `BackendKeyData` received at startup, so a notice arriving before that one reports `-1`. That
 *   window is a single message wide and nothing reaches it at the default verbosity; connecting
 *   with `client_min_messages=debug5` fills it with the backend's own catalog-reading transaction.
 *   What it is for is a handler shared between connections: a Kotlin `object` [NoticeHandler], or
 *   one set as an instance, sees the notices of every connection in a pool, and this is what tells
 *   them apart. Note that
 *   [PgNotification][io.github.octaviusframework.driver.notification.PgNotification] carries a
 *   process id of its own meaning something else - the *foreign* backend that executed `NOTIFY`,
 *   not this connection's.
 * @property severity `NOTICE`, `WARNING`, `INFO`, `DEBUG` or `LOG`, always in English: it is taken from
 *   the non-localized field, which every server the driver will talk to sends. Safe to compare as a string.
 * @property localizedSeverity The same severity as the server translated it, which is what a non-English
 *   `lc_messages` changes. Fit for showing to a person, not for comparing against.
 * @property code The five-character SQLSTATE
 * @property message The primary human-readable message.
 * @property detail Secondary detail carrying more about the problem.
 * @property hint A suggestion of what to do about it.
 * @property position The 1-based character position of the offending token in the submitted statement.
 * @property internalPosition The equivalent position within [internalQuery].
 * @property internalQuery The server-generated statement the notice arose in, such as the body of a
 *   PL/pgSQL function.
 * @property where Where it arose - the call stack of a PL/pgSQL function, for one.
 * @property schema The schema of the object the notice concerns.
 * @property table The table it concerns.
 * @property column The column it concerns.
 * @property datatype The data type it concerns.
 * @property constraint The constraint it concerns.
 * @property file The PostgreSQL source file that reported it - server internals, not your SQL.
 * @property line The line in that source file.
 * @property routine The PostgreSQL C function that reported it.
 */
data class PgNotice(
    val processId: Int,
    val severity: String,
    val localizedSeverity: String,
    val code: String,
    val message: String,
    val detail: String?,
    val hint: String?,
    val position: Int?,
    val internalPosition: Int?,
    val internalQuery: String?,
    val where: String?,
    val schema: String?,
    val table: String?,
    val column: String?,
    val datatype: String?,
    val constraint: String?,
    val file: String?,
    val line: Int?,
    val routine: String?
) {
    internal companion object {
        fun from(processId: Int, noticeMsg: ErrorOrNoticeMessage): PgNotice = PgNotice(
            processId = processId,
            severity = noticeMsg.severity,
            localizedSeverity = noticeMsg.localizedSeverity,
            code = noticeMsg.code,
            message = noticeMsg.message,
            detail = noticeMsg.detail,
            hint = noticeMsg.hint,
            position = noticeMsg.position,
            internalPosition = noticeMsg.internalPosition,
            internalQuery = noticeMsg.internalQuery,
            where = noticeMsg.where,
            schema = noticeMsg.schema,
            table = noticeMsg.table,
            column = noticeMsg.column,
            datatype = noticeMsg.datatype,
            constraint = noticeMsg.constraint,
            file = noticeMsg.file,
            line = noticeMsg.line,
            routine = noticeMsg.routine
        )
    }

    override fun toString(): String = buildString {
        append("[PID: $processId] Postgres $severity [$code]: $message")
        if (detail != null) append(" | Detail: $detail")
        if (hint != null) append(" | Hint: $hint")
        if (where != null) append(" | Where: $where")
    }
}
