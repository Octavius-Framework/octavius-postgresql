plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "com.roma"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.octavius-framework:client:2.13.0")
    implementation("io.github.octavius-framework:client-scanner:2.13.0")
    implementation("io.github.octavius-framework:migrations:2.13.0")
    implementation("com.zaxxer:HikariCP:7.1.0")

    // Log lines go to stderr, so stdout carries only what a command prints
    runtimeOnly("org.slf4j:slf4j-simple:2.0.20")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.roma.census.MainKt")
}

// The smoke test runs the installed application from its jars - see CensusTest for why
tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.installDist)
    systemProperty("census.home", tasks.installDist.get().destinationDir.absolutePath)
}
