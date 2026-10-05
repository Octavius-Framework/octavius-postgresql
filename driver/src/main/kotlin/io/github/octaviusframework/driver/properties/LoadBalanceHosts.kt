package io.github.octaviusframework.driver.properties

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * In what order the listed servers are tried, in `libpq`'s vocabulary and with its meaning.
 *
 * @property value The spelling used in a JDBC URL and by `libpq`.
 */
enum class LoadBalanceHosts(val value: String) {
    /**
     * The servers in the order they are listed, and each one's addresses in the order its name resolves to. The
     * default.
     */
    DISABLE("disable"),

    /**
     * The servers, and each one's addresses, in an order drawn afresh for every connection - which spreads a pool's
     * connections across the servers that `target_session_attrs` accepts.
     */
    RANDOM("random");

    companion object {
        /**
         * Parses a setting from its URL spelling, accepting the enum name as well.
         *
         * A stated setting that matches nothing is refused.
         *
         * @param value `disable` or `random`, case-insensitive. `null` means the setting was not stated at all.
         * @return The matching setting, or [DISABLE] when [value] is `null`.
         * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] is stated but unrecognized.
         */
        fun of(value: String?): LoadBalanceHosts {
            if (value == null) return DISABLE
            return entries.find { it.value.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "Unknown load_balance_hosts '$value'. Expected one of: ${entries.joinToString(", ") { it.value }}."
                )
        }
    }
}
