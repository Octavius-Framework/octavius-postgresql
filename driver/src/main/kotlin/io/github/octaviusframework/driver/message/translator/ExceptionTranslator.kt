package io.github.octaviusframework.driver.message.translator

import io.github.octaviusframework.driver.exception.*
import io.github.octaviusframework.driver.message.ServerErrorMessage
import io.github.octaviusframework.driver.message.backend.ErrorOrNoticeMessage

/**
 * Turns a server `ErrorResponse` into the exception the caller catches, choosing by `SQLSTATE`.
 *
 * The distinction the whole hierarchy exists for is drawn here: a unique violation and a deadlock are things
 * an application handles, a syntax error and a missing column are things it fixes, and they arrive as
 * different types so that `catch` can tell them apart without reading a string. Where a code says nothing
 * useful, `UncategorizedDatabaseException` carries it through with the server's own message rather than
 * guessing at a category.
 *
 * The mapping is by SQLSTATE class where PostgreSQL's classes are meaningful and by individual code where
 * they are not — `40001` and `40P01` are both concurrency but want different reasons, and `57014` is a
 * cancelled statement inside a class that is otherwise about the server being in trouble.
 *
 * One distinction comes before the SQLSTATE, and it is the severity. A `FATAL` or a `PANIC` in an open session
 * ends that session, whatever its code says, and goes through [translateTermination]; a `FATAL` at login is the
 * server refusing one, and goes through [translateRefusal]. What reaches [translate] is always an `ERROR`.
 */
internal object ExceptionTranslator {

    /**
     * The exception for [errorMsg], an `ERROR`.
     *
     * Chosen by SQLSTATE alone. A `FATAL` is [translateTermination]'s in an open session and [translateRefusal]'s
     * at login.
     *
     * @param errorMsg The `ErrorResponse` the server sent.
     * @return The exception to raise, carrying the server's message, SQLSTATE and detail fields.
     */
    fun translate(errorMsg: ErrorOrNoticeMessage): OctaviusException {
        val serverErrorMessage = ServerErrorMessage.from(errorMsg)
        val state = errorMsg.code

        return when {
            // Class 08 — Connection Exception
            state.startsWith("08") -> NetworkException(
                NetworkExceptionReason.CONNECTION_ERROR,
                details = serverErrorMessage.message,
                sqlState = state,
                serverErrorMessage = serverErrorMessage,
            )

            // Class 22 — Data Exception (Invalid data provided by the user)
            state.startsWith("22") -> {
                val reason = when (state) {
                    "22001", "22008", "22015" -> DataExceptionReason.DATA_TRUNCATION
                    "22003", "22022" -> DataExceptionReason.NUMERIC_OUT_OF_RANGE
                    "22012" -> DataExceptionReason.DIVISION_BY_ZERO
                    "22007", "22P02", "22P03", "22018" -> DataExceptionReason.INVALID_FORMAT
                    "2202E" -> DataExceptionReason.ARRAY_SUBSCRIPT_ERROR
                    "22004", "22002" -> DataExceptionReason.NULL_VALUE_NOT_ALLOWED
                    "2201B" -> DataExceptionReason.REGEX_ERROR
                    "22019", "2200D", "22025", "22P06", "2200C", "2200B" -> DataExceptionReason.ESCAPE_CHARACTER_ERROR
                    "2200L", "2200M", "2200N", "2200S", "2200T" -> DataExceptionReason.XML_ERROR
                    else -> if (state.startsWith("2203")) DataExceptionReason.JSON_ERROR else DataExceptionReason.UNKNOWN
                }
                DataException(
                    reason = reason, sqlState = state, serverErrorMessage = serverErrorMessage
                )
            }

            // Class 28 - Invalid Authorization Specification
            state.startsWith("28") -> InitializationException(
                InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS,
                details = serverErrorMessage.message,
                sqlState = state,
                serverErrorMessage = serverErrorMessage
            )

            state.startsWith("21") || state.startsWith("0A") || state.startsWith("3D") || state.startsWith("3F") -> StatementException(
                StatementExceptionReason.INVALID_DEFINITION,
                details = serverErrorMessage.message,
                position = errorMsg.position,
                sqlState = state,
                serverErrorMessage = serverErrorMessage
            )

            // Class 23 — Integrity Constraint Violation
            state.startsWith("23") -> {
                val reason = when (state) {
                    "23505" -> ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION
                    "23503", "23001" -> ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION
                    "23502" -> ConstraintViolationExceptionReason.NOT_NULL_VIOLATION
                    "23514" -> ConstraintViolationExceptionReason.CHECK_CONSTRAINT_VIOLATION
                    "23P01" -> ConstraintViolationExceptionReason.EXCLUSION_CONSTRAINT_VIOLATION
                    else -> ConstraintViolationExceptionReason.UNKNOWN
                }
                ConstraintViolationException(reason, state, serverErrorMessage)
            }

            // Class 25 — Invalid Transaction State
            state.startsWith("25") -> {
                val reason = when (state) {
                    "25P02" -> TransactionStateExceptionReason.IN_FAILED_TRANSACTION
                    "25006" -> TransactionStateExceptionReason.READ_ONLY_TRANSACTION
                    "25P01" -> TransactionStateExceptionReason.NO_ACTIVE_TRANSACTION
                    "25001" -> TransactionStateExceptionReason.ACTIVE_TRANSACTION
                    else -> TransactionStateExceptionReason.UNKNOWN
                }
                TransactionStateException(reason, state, serverErrorMessage)
            }

            // Class 40 — Transaction Rollback
            state.startsWith("40") -> {
                val reason = when (state) {
                    "40001" -> ConcurrencyExceptionReason.SERIALIZATION_FAILURE
                    "40P01" -> ConcurrencyExceptionReason.DEADLOCK_DETECTED
                    else -> ConcurrencyExceptionReason.UNKNOWN
                }

                if (state == "40002") {
                    ConstraintViolationException(ConstraintViolationExceptionReason.UNKNOWN, state, serverErrorMessage)
                } else {
                    ConcurrencyException(reason, state, serverErrorMessage)
                }
            }

            // Class 42 — Syntax Error or Access Rule Violation
            state.startsWith("42") -> {
                if (state == "42501") {
                    PermissionDeniedException(state, serverErrorMessage)
                } else {
                    val reason = when (state) {
                        "42601", "42602", "42622", "42939", "42000" -> StatementExceptionReason.SYNTAX_ERROR
                        "42703", "42883", "42P01", "42P02", "42704" -> StatementExceptionReason.UNDEFINED_OBJECT
                        "42701", "42723", "42P03", "42P04", "42P05", "42P06", "42P07", "42712", "42710" -> StatementExceptionReason.DUPLICATE_OBJECT
                        "42702", "42725", "42P08", "42P09" -> StatementExceptionReason.AMBIGUOUS_OBJECT
                        "42804", "42P18", "42846", "42P21", "42P22" -> StatementExceptionReason.DATA_TYPE_ERROR
                        else -> StatementExceptionReason.INVALID_DEFINITION
                    }
                    StatementException(
                        reason,
                        details = serverErrorMessage.message,
                        position = errorMsg.position,
                        sqlState = state,
                        serverErrorMessage = ServerErrorMessage.from(errorMsg)
                    )
                }
            }

            state.startsWith("54") -> StatementException(
                StatementExceptionReason.SYNTAX_ERROR,
                details = serverErrorMessage.message,
                position = errorMsg.position,
                sqlState = state,
                serverErrorMessage = ServerErrorMessage.from(errorMsg)
            )

            state.startsWith("55") -> {
                if (state == "55P03") { // lock_not_available
                    ConcurrencyException(ConcurrencyExceptionReason.LOCK_NOT_AVAILABLE, state, serverErrorMessage)
                } else {
                    DatabaseSystemException(
                        "Database object state error ($state): ${serverErrorMessage.message}",
                        sqlState = state,
                        serverErrorMessage = serverErrorMessage
                    )
                }
            }

            state == "57014" -> ExecutionAbortedException(sqlState = state, serverErrorMessage = serverErrorMessage)

            state.startsWith("57") || state.startsWith("53") || state.startsWith("58") || state.startsWith("XX") -> DatabaseSystemException(
                "Database system error ($state): ${serverErrorMessage.message}", sqlState = state, serverErrorMessage = serverErrorMessage
            )

            // Class P0 — PL/pgSQL Error. Split by what the routine did: an assertion of its own that the data
            // falsified is a defect, a RAISE is the routine deciding something, and the two are handled apart.
            state.startsWith("P0") -> {
                val assertionReason = when (state) {
                    "P0002" -> RoutineAssertionExceptionReason.NO_DATA_FOUND
                    "P0003" -> RoutineAssertionExceptionReason.TOO_MANY_ROWS
                    "P0004" -> RoutineAssertionExceptionReason.ASSERT_FAILURE
                    else -> null
                }

                if (assertionReason != null) {
                    RoutineAssertionException(assertionReason, state, serverErrorMessage)
                } else {
                    // P0001, P0000, and any code the class gains later: all of them are raised deliberately.
                    RoutineRaiseException(state, serverErrorMessage)
                }
            }

            else -> UncategorizedDatabaseException(
                details = "Unknown database error ($state): ${serverErrorMessage.message}", sqlState = state, serverErrorMessage = serverErrorMessage
            )
        }
    }

    /**
     * The exception for a `FATAL` or `PANIC` the server sent in a session that was open.
     *
     * Chosen by severity before SQLSTATE, because the severity is what decides where the caller stands: the
     * server closes the connection behind either one, so whatever the code says, the session is over - a
     * `FATAL` `57P01` is not a server in trouble, and a `FATAL` `40001` from a standby's recovery conflict is not
     * a serialization failure to retry on the same session. The code then names why, for the causes an
     * application would tell apart, and [SessionTerminatedExceptionReason.UNKNOWN] carries the rest with the code
     * intact.
     *
     * Not for login, where there is no session yet to end: a `FATAL` there is [translateRefusal]'s.
     *
     * @param errorMsg The `ErrorResponse` the server sent, severity `FATAL` or `PANIC`.
     * @return The exception to raise, carrying the server's message, SQLSTATE and detail fields.
     */
    fun translateTermination(errorMsg: ErrorOrNoticeMessage): SessionTerminatedException {
        val reason = when (errorMsg.code) {
            "25P04", "25P03" -> SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT
            "57P05" -> SessionTerminatedExceptionReason.IDLE_SESSION_TIMEOUT
            "57P01" -> SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND
            else -> SessionTerminatedExceptionReason.UNKNOWN
        }
        return SessionTerminatedException(reason, errorMsg.code, ServerErrorMessage.from(errorMsg))
    }

    /**
     * The exception for the `ErrorResponse` the server refused a login with.
     *
     * Every refusal arrives as a `FATAL`, and whatever its code, the caller is left without a session - which is
     * what [InitializationException] says. Its SQLSTATE would route it elsewhere in [translate]: a database that
     * does not exist (`3D000`) to a statement error for a statement never sent, too many connections (`53300`) or
     * a server starting up (`57P03`) to the server being in trouble. Credentials (class `28`) keep their own
     * reason; the rest is [InitializationExceptionReason.CONNECTION_ERROR], with the code in `sqlState` for anyone
     * who tells them apart.
     *
     * @param errorMsg The `ErrorResponse` the server sent during login.
     * @return The exception to raise, carrying the server's message, SQLSTATE and detail fields.
     */
    fun translateRefusal(errorMsg: ErrorOrNoticeMessage): InitializationException {
        val serverErrorMessage = ServerErrorMessage.from(errorMsg)
        val reason = if (errorMsg.code.startsWith("28")) {
            InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS
        } else {
            InitializationExceptionReason.CONNECTION_ERROR
        }
        return InitializationException(
            reason,
            details = serverErrorMessage.message,
            sqlState = errorMsg.code,
            serverErrorMessage = serverErrorMessage
        )
    }
}
