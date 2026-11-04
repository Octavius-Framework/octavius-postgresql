package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/** Which assertion of a PL/pgSQL routine failed. */
enum class RoutineAssertionExceptionReason {
    /** `SELECT INTO STRICT` found no row (P0002). */
    NO_DATA_FOUND,

    /** `SELECT INTO STRICT` found several rows (P0003). */
    TOO_MANY_ROWS,

    /** An `ASSERT` failed (P0004). */
    ASSERT_FAILURE
}

/**
 * A PL/pgSQL routine's `INTO STRICT` or `ASSERT` turned out false: a defect in the routine. A routine refusing on
 * purpose is [RoutineRaiseException].
 *
 * @property dbMessage The server's message.
 * @property dbDetail The server's detail.
 * @property hint The server's hint.
 * @property where The PL/pgSQL call stack.
 */
class RoutineAssertionException(
    val reason: RoutineAssertionExceptionReason,
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException("ROUTINE_ASSERTION_EXCEPTION:${reason.name}", sqlState, serverErrorMessage) {

    val dbMessage: String get() = serverErrorMessage!!.message
    val dbDetail: String? get() = serverErrorMessage!!.detail
    val hint: String? get() = serverErrorMessage!!.hint
    val where: String? get() = serverErrorMessage!!.where

    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        appendLine("DB Message: $dbMessage")
        if (dbDetail != null) appendLine("DB Detail: $dbDetail")
        if (hint != null) appendLine("Hint: $hint")
        if (where != null) appendLine("Where: $where")
    }
}

private fun generateDeveloperMessage(reason: RoutineAssertionExceptionReason): String =
    when (reason) {
        RoutineAssertionExceptionReason.NO_DATA_FOUND -> "A query intended to return a single row returned no data."
        RoutineAssertionExceptionReason.TOO_MANY_ROWS -> "A query intended to return a single row returned multiple rows."
        RoutineAssertionExceptionReason.ASSERT_FAILURE -> "An assertion failed during routine execution."
    }
