package io.github.octaviusframework.client.dynamic

/**
 * When an object registered as a dynamic type is written as one without being asked to be.
 *
 * This governs the write side only. Reading needs no policy: a value comes back as a `dynamic_dto` because
 * the **column** is one, and the discriminator inside it names the class - neither of which depends on what
 * the calling code declared.
 *
 * Writing has no such anchor. The driver declares the types of the parameters it sends rather than asking
 * the server what it wants, so at a top-level parameter there is no expected type to consult and the Kotlin
 * class is the only thing to go on. Where a class is registered here **and** as a composite, that is not
 * enough to say which of the two was meant, and this is what settles it.
 *
 * A mode therefore reaches only as far as that ignorance does. An attribute of a composite, an element of an
 * array and a value wrapped in [PgTyped][io.github.octaviusframework.driver.type.PgTyped] all carry the type
 * they are going into, and that type decides: a class is written as a `dynamic_dto` there when the
 * destination is one and as the composite when it is that, under every mode alike.
 *
 * [DynamicTypes.toDynamicDto] overrides whichever mode is in force: a value already wrapped is written as a
 * `dynamic_dto` under all three.
 *
 * A mode is given to a client and goes with every class registered through it: that class is written on that
 * mode whichever client a query runs through. A class is registered once per database, so a second client
 * registering it on another mode is refused rather than overriding the first.
 */
enum class DynamicWriteStrategy {
    /**
     * Only a value wrapped in [DynamicTypes.toDynamicDto] is written as a `dynamic_dto`.
     *
     * A registered class passed unwrapped is refused rather than written some other way - unless it is also
     * a registered composite, which is a real destination and takes it.
     */
    EXPLICIT_ONLY,

    /**
     * A registered class is written as a `dynamic_dto` unless it is also a registered composite, in which
     * case the composite takes it and wrapping is how you ask for the other.
     *
     * The default, and the mode in which a class registered one way only - which is nearly all of them -
     * needs no wrapping anywhere.
     */
    AUTOMATIC_WHEN_UNAMBIGUOUS,

    /**
     * A registered class is written as a `dynamic_dto` even where it is also a registered composite.
     *
     * For a class stored both ways whose usual destination is the dynamic one. The composite is then the
     * form you have to ask for, which is what naming the type does:
     * `value.withPgType("honour")` sends it there as it would under any other mode.
     */
    PREFER_DYNAMIC_DTO
}
