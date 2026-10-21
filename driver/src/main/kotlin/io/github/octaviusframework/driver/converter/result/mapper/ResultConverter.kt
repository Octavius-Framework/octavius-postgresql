package io.github.octaviusframework.driver.converter.result.mapper

import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Turns a decoded database value into the Kotlin type the caller asked for.
 *
 * This is the read half of the conversion SPI. By the time a converter runs, the codec has already
 * decoded the column's bytes into [S] — an `Int`, a `String`, a `PgComposite`, a `PgArray` — and the
 * converter's job is to reshape that into [T]. Register one on a session through
 * [TypeManager.registerResultConverter][io.github.octaviusframework.driver.registry.TypeManager.registerResultConverter],
 * or on a single query through
 * [registerResultConverter][io.github.octaviusframework.driver.query.Query.registerResultConverter].
 *
 * Lookup is keyed on [supportedSourceClass] — what the codec produced rather than what the caller asked
 * for, so a `PgComposite`, a `PgArray`, a `Row`, a scalar. Every built-in names one of those, and a
 * converter written for a particular column knows as much about it, so naming the class costs nothing
 * and is what keeps the converter from being asked about every other value in the database. A sealed class
 * names every class below it, since what a codec produces is always one of those: a converter under
 * `PgInterval` is offered `PgInterval.Finite` and both infinities. Within one class the most recent
 * registration is consulted first, so a later converter wins over an earlier one - one under a sealed class
 * included, against one under a class below it.
 *
 * `Any::class` is the slot for a converter that genuinely cannot say — one keying off the PostgreSQL
 * type rather than the decoded class — and it is a fallback rather than an override: that group is
 * reached only once every converter registered under the value's own class has declined, so registering
 * there displaces none of them however late it happens. Displacing one is what a query's own registry is
 * for, being consulted ahead of the session's entirely. Either way [canConvert] has to be precise, since
 * a converter that claims a value it cannot produce raises `MappingException` (`CONVERSION_ERROR`)
 * naming the converter rather than mapping the value.
 *
 * Whether a converter may touch the session it is reading from depends on what invoked it, because that
 * decides whether the exchange with the server is still open. Under `fetchObject*`, `fetchField*` and
 * the `forEach*` family the mapping happens as rows arrive, so a converter runs *inside* an unfinished
 * exchange and issuing a query on that same session collides with it - the driver refuses the call with
 * `InvalidOperationException` (`CONNECTION_BUSY`), surfacing as the `cause` of a
 * `MappingException` (`CONVERSION_ERROR`) carrying the path to the column. `fetchRows` and its
 * siblings hand back undecoded rows instead, so a converter reached later through `Row.get` runs after
 * the exchange has finished and is free to query. Write a converter that needs the database against a
 * second session and the distinction stops mattering.
 *
 * @param S The decoded source type this converter accepts.
 * @param T The Kotlin type it produces.
 */
interface ResultConverter<S : Any, T : Any> {
    /**
     * The decoded class this converter is indexed under, and where it is sealed every class below it. Use
     * `Any::class` to be offered every value.
     */
    val supportedSourceClass: KClass<S>

    /**
     * Decides whether this converter handles the value at hand. Must return `false` unless [convert]
     * can produce an instance of [expectedType].
     *
     * @param sourceClass The class of the decoded value.
     * @param expectedType The Kotlin type the caller asked for, generic arguments included.
     * @param sourceType The PostgreSQL type of the column.
     * @param context Access to the type manager and to nested conversion.
     * @return `true` to claim the value.
     */
    fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean

    /**
     * Converts a value this converter has claimed.
     *
     * Nested values should go through [DeserializationContext.convert] rather than being converted by
     * hand, so that a failure deeper in the structure arrives with the path to it.
     *
     * @param source The decoded value.
     * @param expectedType The Kotlin type the caller asked for.
     * @param sourceType The PostgreSQL type of the column.
     * @param context Access to the type manager and to nested conversion.
     * @return The converted value, which must be an instance of [expectedType].
     */
    fun convert(source: S, expectedType: KType, sourceType: PgType, context: DeserializationContext): T
}
