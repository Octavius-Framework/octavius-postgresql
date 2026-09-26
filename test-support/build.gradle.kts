plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Where the integration tests of every module connect, and the empty schema each test class starts from. Not
// published: it exists for the tests of the modules that are.
dependencies {
    api(projects.driver)
    api(hikari.hikaricp)
    api(libs.junit.jupiter)
}
