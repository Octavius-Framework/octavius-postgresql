plugins {
    alias(libs.plugins.kotlin.jvm)
    // CompositeVsDynamicDtoBenchmark declares @Serializable payloads, as a dynamic_dto class must.
    alias(libs.plugins.kotlin.plugin.serialization)
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    implementation(project(":driver"))
    implementation(project(":client"))
    implementation("org.postgresql:postgresql:42.7.3") // Latest pgjdbc
    // ClientOverheadBenchmark takes every session it measures out of one pool, the driver rungs included.
    implementation(hikari.hikaricp)

    // StackComparisonBenchmark's other two stacks. Spring comes from the catalogue the integration module
    // already resolves, so this pins no version of its own; JDBI is declared here rather than in a catalogue
    // because nothing outside this module has any use for it.
    implementation(spring.spring.boot.starter.jdbc)
    implementation("org.jdbi:jdbi3-core:3.54.0")
    implementation("org.jdbi:jdbi3-kotlin:3.54.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)

}

jmh {
    jmhVersion.set("1.37")

    // gc reports allocation per operation; stack samples thread states and the frames underneath
    // them, which is where a difference too small for the clock still shows up.
    profilers.add("gc")
    profilers.add("stack")

    providers.gradleProperty("jmh").orNull?.let { includes.add(it) }
}
