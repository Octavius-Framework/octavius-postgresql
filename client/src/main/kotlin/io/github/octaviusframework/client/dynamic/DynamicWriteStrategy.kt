package io.github.octaviusframework.client.dynamic

/**
 * When an instance of a class registered as a dynamic type is written as a `dynamic_dto` without being wrapped.
 *
 * A mode decides only for a top-level parameter, whose type nothing but its Kotlin class declares. Where the
 * destination's type is known - an attribute of a composite, an element of an array, a value wrapped in
 * [PgTyped][io.github.octaviusframework.driver.type.PgTyped] - that type decides under every mode. Reading needs
 * no mode: the column's type and the discriminator decide. A value wrapped by [DynamicTypes.toDynamicDto] is a
 * `dynamic_dto` under every mode.
 *
 * A mode goes with every class registered through the client built on it, whichever client runs the query. A
 * second client registering the class on another mode is refused.
 */
enum class DynamicWriteStrategy {
    /**
     * Only a value wrapped in [DynamicTypes.toDynamicDto] is written as a `dynamic_dto`. A registered class passed
     * unwrapped is refused, unless it is also a registered composite, which takes it.
     */
    EXPLICIT_ONLY,

    /**
     * The default. A registered class is written as a `dynamic_dto` unless it is also a registered composite,
     * which then takes it; wrapping asks for the `dynamic_dto`.
     */
    AUTOMATIC_WHEN_UNAMBIGUOUS,

    /**
     * A registered class is written as a `dynamic_dto` even where it is also a registered composite;
     * `value.withPgType("honour")` sends it to the composite.
     */
    PREFER_DYNAMIC_DTO
}
