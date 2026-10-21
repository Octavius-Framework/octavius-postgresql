package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * How hard the driver insists on binding the SCRAM exchange to the TLS channel underneath it.
 *
 * Channel binding stops an intermediary from relaying an authentication exchange it cannot compute
 * a proof for: the client proof covers a hash of the certificate the server presented, so a proxy
 * that terminates TLS with a certificate of its own produces a proof the real server rejects. It
 * earns its keep under `sslmode=require`, where the connection is encrypted but nothing checks who
 * is on the other end of it.
 */
enum class ChannelBinding(val value: String) {
    /** Never offer channel binding; the exchange declares no support for it. */
    DISABLE("disable"),

    /**
     * Bind when the connection is encrypted and the server offers it, carry on when not. Default.
     *
     * A server certificate that cannot be bound to - one signed with Ed25519 or Ed448 - is refused
     * here as under [REQUIRE], not carried past unbound.
     */
    PREFER("prefer"),

    /** Refuse to authenticate at all unless the exchange is bound to the TLS channel. */
    REQUIRE("require");

    companion object {
        /**
         * Parses a setting from its URL spelling, accepting the enum name as well.
         *
         * A stated setting that matches nothing is refused.
         *
         * @param value `disable`, `prefer` or `require`, case-insensitive. `null` means the setting
         *   was not stated at all.
         * @return The matching setting, or [PREFER] when [value] is `null`.
         * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] is stated but unrecognized.
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
