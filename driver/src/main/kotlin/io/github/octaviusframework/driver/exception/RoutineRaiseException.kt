package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/**
 * A PL/pgSQL routine refused on purpose with `RAISE EXCEPTION` (P0001, or P0000). A `RAISE` with another
 * `ERRCODE` arrives as the exception for that code.
 *
 * @property dbMessage The message the routine raised.
 * @property dbDetail The server's detail.
 * @property hint The server's hint.
 * @property where The PL/pgSQL call stack.
 */
class RoutineRaiseException(
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException("ROUTINE_RAISE_EXCEPTION", sqlState, serverErrorMessage) {

    val dbMessage: String get() = serverErrorMessage!!.message
    val dbDetail: String? get() = serverErrorMessage!!.detail
    val hint: String? get() = serverErrorMessage!!.hint
    val where: String? get() = serverErrorMessage!!.where

    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: A PL/pgSQL function or procedure raised an exception of its own.")
        appendLine("DB Message: $dbMessage")
        if (dbDetail != null) appendLine("DB Detail: $dbDetail")
        if (hint != null) appendLine("Hint: $hint")
        if (where != null) appendLine("Where: $where")
    }
}
