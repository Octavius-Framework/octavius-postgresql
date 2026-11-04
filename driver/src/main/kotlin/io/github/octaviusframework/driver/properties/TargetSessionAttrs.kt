package io.github.octaviusframework.driver.properties

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * Which kind of server to settle for among those listed, as `libpq` means it, read from what the server reports at
 * login.
 *
 * @property value The spelling in a URL and in `libpq`.
 */
enum class TargetSessionAttrs(val value: String) {
    /** Any server that accepts the connection. The default. */
    ANY("any"),

    /** A server whose sessions accept writes by default: not in hot standby, `default_transaction_read_only` off. */
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
         * The setting [value] spells or names, case-insensitively, or [ANY] for `null`.
         *
         * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] matches nothing.
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
