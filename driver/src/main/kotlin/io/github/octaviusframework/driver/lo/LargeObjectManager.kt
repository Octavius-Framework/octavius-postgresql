package io.github.octaviusframework.driver.lo

import io.github.octaviusframework.driver.session.OctaviusSession


private const val INV_WRITE = 0x00020000
private const val INV_READ = 0x00040000

/**
 * How a Large Object is opened.
 *
 * @property value The bit mask `lo_open` takes.
 */
enum class LargeObjectMode(val value: Int) {
    /** Writing only. */
    WRITE(INV_WRITE),

    /** Reading only. */
    READ(INV_READ),

    /** Reading and writing. */
    READ_WRITE(INV_READ or INV_WRITE)
}

/**
 * Creates, opens and deletes large objects on this session. Use them inside a transaction. An OID above
 * `Int.MAX_VALUE` reads as a negative `Int`, which the server takes back as it was.
 */
class LargeObjectManager internal constructor(private val session: OctaviusSession) {

    /** Creates an empty large object and returns its OID. */
    fun create(): Int {
        return session.createNativeQuery("SELECT lo_create(0)")
            .fetchFieldStrict<Int>()
    }

    /** Opens the large object [oid] in [mode]. */
    fun open(oid: Int, mode: LargeObjectMode = LargeObjectMode.READ_WRITE): LargeObject {
        val fd = session.createNativeQuery("SELECT lo_open($1, $2)")
            .fetchFieldStrict<Int>(oid, mode.value)

        return LargeObject(session, oid, fd)
    }

    /** Deletes the large object [oid]. */
    fun unlink(oid: Int) {
        session.createNativeQuery("SELECT lo_unlink($1)")
            .fetchFieldStrict<Int>(oid)
    }
}
