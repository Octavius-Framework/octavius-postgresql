plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    implementation(projects.driver)
    implementation(hikari.hikaricp)

    testImplementation(projects.testSupport)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
