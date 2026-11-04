package io.github.octaviusframework.driver.type

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.identifier.QualifiedName

/**
 * A parameter sent as the type [pgType] names rather than the one its class would give.
 *
 * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] is itself a [PgTyped].
 */
data class PgTyped(val value: Any?, val pgType: QualifiedName) {
    init {
        if (value is PgTyped) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                "A PgTyped cannot wrap another PgTyped - name the target type once, on the value itself."
            )
        }
    }
}

/** This value, sent as the built-in [pgType]. */
fun Any?.withPgType(pgType: PgStandardType): PgTyped =
    PgTyped(this, QualifiedName("", pgType.typeName, isArray = pgType.isArray))

/** This value, sent as the type [name], or an array of it; an empty [schema] resolves through the search path. */
fun Any?.withPgType(name: String, schema: String = "", isArray: Boolean = false): PgTyped =
    PgTyped(this, QualifiedName(schema, name, isArray))

/** This value, sent as [pgType]. */
fun Any?.withPgType(pgType: QualifiedName): PgTyped =
    PgTyped(this, pgType)

