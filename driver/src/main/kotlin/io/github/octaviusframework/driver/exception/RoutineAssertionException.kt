package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/** Which assertion of a PL/pgSQL routine failed. */
enum class RoutineAssertionExceptionReason {
    /** `SELECT INTO STRICT` found no row (P0002) or several (P0003). */
    INCORRECT_RESULT_SIZE,

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
        RoutineAssertionExceptionReason.INCORRECT_RESULT_SIZE -> "A SELECT INTO STRICT found no row or several. The DB message says which."
        RoutineAssertionExceptionReason.ASSERT_FAILURE -> "An assertion failed during routine execution."
    }
