package io.github.octaviusframework.driver.container

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason

/**
 * A `CONVERSION_ERROR` with [segment] - an attribute's name, or `[i]` - as the last step of its path. Only a value
 * that is there gets one; an index out of bounds is not a location.
 */
@PublishedApi
internal fun conversionErrorAt(segment: String, details: String): MappingException =
    MappingException(MappingExceptionReason.CONVERSION_ERROR, details = details).apply { path.add(segment) }

/**
 * An array, composite, range, multirange or record as the codec layer has it: each value inside is what the codec
 * for its OID decoded, with no converter run - a nested composite is a [PgComposite], an enum its label. One built
 * by hand is filled with what those codecs encode. [Row.get][io.github.octaviusframework.driver.row.Row.get] is
 * what converts the values inside.
 */
interface PgContainer {
    /** OID of the container's own type: the array type rather than its element type. A parameter goes out as it. */
    val containerOid: Int
}
