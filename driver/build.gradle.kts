plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.vanniktech.publish)
}

mavenPublishing {
    pom {
        name.set("Octavius Driver")
        description.set(
            "A PostgreSQL driver for Kotlin that speaks Wire Protocol v3.2 directly: types read from your catalog, " +
            "COPY, LISTEN/NOTIFY, large objects and TLS. Blocking by design and pin-free on virtual threads. Requires " +
            "PostgreSQL 18 or newer."
        )
    }
}

dependencies {
    // Part of the public contract, not an implementation detail: `@PgName` goes on the caller's own classes
    // and `BigDecimal` is the type a `numeric` column comes back as, so anyone reading either has to be able
    // to write it without adding a dependency by hand.
    api(projects.pgModel)

    // Exposed in the public API: SharedFlow notifications and OctaviusDispatchers,
    // JsonElement for json/jsonb, and kotlinx.datetime types for date/time columns.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)

    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)

    testImplementation(projects.testSupport)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.logback.classic)
}
