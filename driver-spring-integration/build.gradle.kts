plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.vanniktech.publish)
}

mavenPublishing {
    pom {
        name.set("Octavius Spring Boot Integration")
        description.set(
            "Spring Boot autoconfiguration for the Octavius PostgreSQL driver: OctaviusTemplate, a transaction " +
            "manager, DataSource wiring and exception translation. Requires Spring Boot 4.x."
        )
    }
}

dependencies {
    api(projects.driver)
    api(spring.spring.boot.starter.jdbc)
    implementation(hikari.hikaricp)
    implementation(libs.kotlin.logging)

    testImplementation(projects.testSupport)
    // Only for the SpringSessionProvider the client's documentation offers, which nothing else compiles
    testImplementation(projects.client)
    testImplementation(spring.spring.boot.starter.test)
    testImplementation(spring.spring.boot.health)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

