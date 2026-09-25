plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

mavenPublishing {
    pom {
        name.set("Octavius Migrations")
        description.set(
            "Schema migrations for PostgreSQL on the Octavius driver: versioned and repeatable, in SQL or in Kotlin, " +
            "with checksums, an advisory lock and a history table it keeps itself."
        )
    }
}

dependencies {
    // Exposed: a code migration is handed a session, so the driver's types are in this module's own signatures.
    api(projects.driver)

    // The reason this is not hand-rolled: a classpath path can mean a jar inside a jar, the module path or a
    // Spring Boot fat jar, and walking that correctly is not a weekend's work.
    implementation(libs.classgraph)

    implementation(libs.kotlin.logging)

    testImplementation(projects.testSupport)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.logback.classic)
}
