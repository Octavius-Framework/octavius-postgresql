package io.github.octaviusframework.driver.properties

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * In what order the listed servers are tried, as `libpq` means it.
 *
 * @property value The spelling in a URL and in `libpq`.
 */
enum class LoadBalanceHosts(val value: String) {
    /** As listed, and each server's addresses as they resolve. The default. */
    DISABLE("disable"),

    /** Servers and addresses in an order drawn afresh for every connection, which spreads a pool across them. */
    RANDOM("random");

    companion object {
        /**
         * The setting [value] spells or names, case-insensitively, or [DISABLE] for `null`.
         *
         * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] matches nothing.
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
