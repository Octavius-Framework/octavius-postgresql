package io.github.octaviusframework.driver.initialization

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Reads one startup message, answers it with [script], and then waits for the client to hang up.
 *
 * What a real PostgreSQL 18 never answers on demand - a negotiation, a parameter left out, a standby's report, a
 * refusal - is played by one of these, sending only the messages a case is about. It takes one client and takes
 * whatever that client sends first for the startup message, so the driver connects to it with `sslmode=disable`.
 */
internal class ScriptedServer(private val script: ByteArray) : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    val port: Int = serverSocket.localPort

    /** The protocol version the startup message asked for, or `-1` while no client has come. */
    @Volatile
    var startupVersion: Int = -1

    private val worker = thread(isDaemon = true) {
        try {
            serverSocket.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                startupVersion = input.readInt()
                input.skipNBytes(length - 8L)
                socket.getOutputStream().apply { write(script); flush() }
                while (input.read() != -1) { /* a Terminate, then the socket closed */ }
            }
        } catch (_: IOException) {
            // The client dropped the socket, or the test is over and closed ours
        }
    }

    override fun close() {
        serverSocket.close()
        worker.join(5_000)
    }
}

internal fun message(type: Char, body: DataOutputStream.() -> Unit): ByteArray {
    val payload = ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()
    return ByteArrayOutputStream().also { out ->
        DataOutputStream(out).run {
            writeByte(type.code)
            writeInt(payload.size + 4)
            write(payload)
        }
    }.toByteArray()
}

internal fun DataOutputStream.writeCString(value: String) {
    write(value.toByteArray())
    writeByte(0)
}

internal fun negotiate(version: Int) = message('v') { writeInt(version); writeInt(0) }
internal val authenticationOk = message('R') { writeInt(0) }
internal fun parameter(name: String, value: String) = message('S') { writeCString(name); writeCString(value) }
internal fun backendKey(length: Int) = message('K') { writeInt(4242); write(ByteArray(length) { it.toByte() }) }
internal val readyForQuery = message('Z') { writeByte('I'.code) }

/** A `FATAL` error with [code] as its SQLSTATE, the way a server refuses a login. */
internal fun fatal(code: String, text: String) = message('E') {
    writeByte('S'.code); writeCString("FATAL")
    writeByte('V'.code); writeCString("FATAL")
    writeByte('C'.code); writeCString(code)
    writeByte('M'.code); writeCString(text)
    writeByte(0)
}
