package io.github.octaviusframework.driver.properties

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * Which kind of server a connection settles for when several are listed, in `libpq`'s vocabulary and with its
 * meaning.
 *
 * A server's kind is read from what it reports at login - `in_hot_standby`, and for the two read/write modes
 * `default_transaction_read_only` too. A server that is not the kind asked for is logged out of, and the next one in
 * the list is tried.
 *
 * @property value The spelling used in a JDBC URL and by `libpq`.
 */
enum class TargetSessionAttrs(val value: String) {
    /** Any server that accepts the connection. The default. */
    ANY("any"),

    /** A server whose sessions accept writes by default: not in hot standby, and `default_transaction_read_only` off. */
    READ_WRITE("read-write"),

    /** The converse of [READ_WRITE]: a server in hot standby, or one with `default_transaction_read_only` on. */
    READ_ONLY("read-only"),

    /** A server that is not in hot standby, whatever its sessions default to. */
    PRIMARY("primary"),

    /** A server in hot standby. */
    STANDBY("standby"),

    /** A server in hot standby where the list has one; where it has none, a second pass takes any server. */
    PREFER_STANDBY("prefer-standby");

    companion object {
        /**
         * Parses a setting from its URL spelling, accepting the enum name as well.
         *
         * A stated setting that matches nothing is refused.
         *
         * @param value `any`, `read-write`, `read-only`, `primary`, `standby` or `prefer-standby`, or the
         *   equivalent enum name; case-insensitive. `null` means the setting was not stated at all.
         * @return The matching setting, or [ANY] when [value] is `null`.
         * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] is stated but unrecognized.
         */
        fun of(value: String?): TargetSessionAttrs {
            if (value == null) return ANY
            return entries.find { it.value.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "Unknown target_session_attrs '$value'. Expected one of: ${entries.joinToString(", ") { it.value }}."
                )
        }
    }
}
