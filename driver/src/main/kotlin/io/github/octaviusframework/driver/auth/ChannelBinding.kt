package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * How hard to insist on binding SCRAM to the TLS channel, which defeats a proxy terminating TLS with its own
 * certificate even under `sslmode=require`.
 */
enum class ChannelBinding(val value: String) {
    /** Never bind. */
    DISABLE("disable"),

    /**
     * Bind where the connection is encrypted and the server offers it. The default. A certificate that cannot be
     * bound to, signed with Ed25519 or Ed448, is refused as under [REQUIRE].
     */
    PREFER("prefer"),

    /** Refuse to authenticate unbound. */
    REQUIRE("require");

    companion object {
        /**
         * The setting [value] spells or names, case-insensitively, or [PREFER] for `null`.
         *
         * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] matches nothing.
         */
        fun of(value: String?): ChannelBinding {
            if (value == null) return PREFER
            return entries.find { it.value.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "Unknown channelBinding '$value'. Expected one of: ${entries.joinToString(", ") { it.value }}."
                )
        }
    }
}
