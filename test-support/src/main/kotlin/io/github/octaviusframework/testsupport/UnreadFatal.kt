package io.github.octaviusframework.testsupport

import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Whether [TestDatabase] runs on Windows, where a `FATAL` that arrives while the client is not reading is lost.
 *
 * A stock PostgreSQL there leaves the socket for the kernel to close at backend exit, which it does abortively,
 * and the reset that follows makes Winsock discard what the client had received but not yet read. Whether the
 * `FATAL` survives is then a race the client mostly loses, whatever it does.
 */
val serverOnWindows: Boolean by lazy {
    TestDatabase.openSession().use { it.createNativeQuery("SELECT version()").fetchFieldStrict<String>() }
        .contains("-windows")
}

/**
 * Skips a test whose `FATAL` arrives while the client is not reading, on a server that loses it - see
 * [serverOnWindows]. `TEST_GRACEFUL_CLOSE=true` says the server is a build that closes gracefully, such as an
 * instance `scripts/test-server.ps1 -GracefulClose` stands up, and runs the test anyway.
 */
fun assumeUnreadFatalSurvives() {
    assumeTrue(
        !serverOnWindows || System.getenv("TEST_GRACEFUL_CLOSE") == "true",
        "a Windows server loses a FATAL sent while nothing reads; TEST_GRACEFUL_CLOSE=true runs this against a build that closes gracefully"
    )
}
