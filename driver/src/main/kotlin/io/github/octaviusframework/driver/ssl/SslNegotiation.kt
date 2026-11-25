package io.github.octaviusframework.driver.ssl

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * How the connection starts TLS, as `libpq`'s `sslnegotiation` means it.
 *
 * @property value The spelling in a URL and in `libpq`.
 */
enum class SslNegotiation(val value: String) {
    /** Ask with an `SSLRequest` and start TLS once the server agrees. Every server and pooler understands it. The default. */
    POSTGRES("postgres"),

    /**
     * Start the TLS handshake as soon as the socket connects, a round trip sooner, offering ALPN `postgresql`. Needs
     * [SslMode.REQUIRE] or stronger. A pooler or proxy that expects an `SSLRequest` fails the handshake.
     */
    DIRECT("direct");

    companion object {
        /**
         * The negotiation [value] spells or names, case-insensitively, or [POSTGRES] for `null`.
         *
         * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] matches nothing.
         */
        fun of(value: String?): SslNegotiation {
            if (value == null) return POSTGRES
            return entries.find { it.value.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "Unknown sslnegotiation '$value'. Expected one of: ${entries.joinToString(", ") { it.value }}."
                )
        }
    }
}
