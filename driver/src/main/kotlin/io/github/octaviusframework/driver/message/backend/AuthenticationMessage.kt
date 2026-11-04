package io.github.octaviusframework.driver.message.backend

/** A message the server sends during authentication (tag `R`). */
internal sealed interface AuthenticationMessage : BackendMessage {
    /** The login succeeded. */
    object Ok : AuthenticationMessage

    /** The server asks for a cleartext password. */
    object CleartextPassword : AuthenticationMessage

    /** The server asks for an MD5 password, which the driver refuses. */
    class MD5Password(val salt: ByteArray) : AuthenticationMessage

    /** The server asks for SASL with one of [mechanisms]. */
    class SASL(val mechanisms: List<String>) : AuthenticationMessage

    /** The server's SASL challenge. */
    class SASLContinue(val data: ByteArray) : AuthenticationMessage

    /** The server's final SASL message. */
    class SASLFinal(val data: ByteArray) : AuthenticationMessage

    // Any other request raises where it is parsed.
}