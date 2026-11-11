package io.github.octaviusframework.driver.converter.result.mapper

import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Turns a value a codec decoded - an `Int`, a `PgComposite`, a `PgArray` - into the Kotlin type asked for.
 * Register one with
 * [TypeManager.registerResultConverter][io.github.octaviusframework.driver.registry.TypeManager.registerResultConverter]
 * or on one query.
 *
 * A converter is offered only values of its [supportedSourceClass], newest registration first. Those under
 * `Any::class` are offered a value only after every converter of its own class declined. The `sourceType` it is
 * shown is never a [PgType.Domain]: a value of one, at any depth, comes as the type under it.
 *
 * Under `fetchObject*`, `fetchField*` and `forEach*` a converter runs while the result is still arriving, so a
 * query on the same session is refused with `CONNECTION_BUSY`. Through `Row.get` after `fetchRows` it may query.
 *
 * @param S The decoded class this converter accepts.
 * @param T The Kotlin type it produces.
 */
interface ResultConverter<S : Any, T : Any> {
    /** The decoded class this converter is offered, and every class below it where it is sealed. */
    val supportedSourceClass: KClass<S>

    /**
     * Whether this converter takes the value. Returns `true` only where [convert] can produce [expectedType]: a
     * claimed value that fails is `CONVERSION_ERROR`, not handed to the next converter.
     */
    fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean

    /** Converts a claimed value into an instance of [expectedType]. */
    fun convert(source: S, expectedType: KType, sourceType: PgType, context: DeserializationContext): T
}
