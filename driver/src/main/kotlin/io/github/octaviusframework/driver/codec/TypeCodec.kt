package io.github.octaviusframework.driver.codec

import io.github.octaviusframework.driver.io.PgByteWriter
import kotlin.reflect.KClass

/** Reads and writes one PostgreSQL type's binary form as the Kotlin type [T]. */
interface TypeCodec<T : Any> {
    /** PostgreSQL type name. */
    val pgTypeName: String
    /** The type's schema. Left empty, the codec binds to every type of [pgTypeName], in any schema. */
    val pgSchema: String get() = ""

    /** The OID to bind to, in place of the name. */
    val oid: Int? get() = null
    /** The Kotlin class this codec handles. */
    val kotlinClass: KClass<T>
    /** Whether this codec is the default for its Kotlin type. */
    val isDefaultForKotlinType: Boolean get() = false

    /** Reads a value from the bytes, at an offset and for a length. */
    val fromBinary: (ByteArray, Int, Int) -> T
    /** Writes a value. */
    val toBinary: (T, PgByteWriter) -> Unit
}

