plugins {
    alias(libs.plugins.kotlin.jvm)
    // For the test fixtures only: dynamic_dto payloads are kotlinx-serialized, so the tests declare
    // @Serializable classes. Nothing in main does.
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.vanniktech.publish)
}

mavenPublishing {
    pom {
        name.set("Octavius Client")
        description.set(
            "SQL-first data access on the Octavius PostgreSQL driver: session scoping, thread-bound transactions, " +
            "query builders and transaction plans."
        )
    }
}

dependencies {
    // Exposed in the public API throughout: sessions and their operations, Row, and the exception
    // hierarchy DataResult carries are all the driver's own types. The client renames none of them.
    api(projects.driver)

    implementation(libs.kotlin.reflect)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(hikari.hikaricp)
    testImplementation(libs.logback.classic)
}
