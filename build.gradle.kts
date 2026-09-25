import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.publish) apply false
}

allprojects {
    group = "io.github.octavius-framework"
    version = "2.2.0"

    repositories {
        mavenCentral()
    }
}

/**
 * The one database every integration test shares. A test class starts by dropping its `public` schema, so two test
 * tasks on it at once - under `--parallel` - would drop it under each other. A service with a single usage runs
 * them one after another whatever Gradle is asked to do.
 */
abstract class TestDatabaseService : BuildService<BuildServiceParameters.None>

val testDatabase = gradle.sharedServices.registerIfAbsent("testDatabase", TestDatabaseService::class) {
    maxParallelUsages.set(1)
}

dependencies {
    dokka(projects.pgModel)
    dokka(projects.driver)
    dokka(projects.client)
    dokka(projects.clientScanner)
    dokka(projects.migrations)
    dokka(projects.driverSpringIntegration)
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")

    extensions.configure<DokkaExtension> {
        moduleName.set(name)

        dokkaSourceSets.configureEach {
            documentedVisibilities.set(
                setOf(
                    VisibilityModifier.Public,
                    VisibilityModifier.Protected,
                    VisibilityModifier.Internal
                )
            )
            skipEmptyPackages.set(true)
        }
    }

    // The pg-model module is the one multiplatform project here, so its JVM target is set through the
    // multiplatform extension rather than the JVM one below.
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
        }
        
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        tasks.withType<Test> {
            useJUnitPlatform()
            usesService(testDatabase)
        }
    }

    // Everything a published POM carries that is the same for every module. Applying the publish plugin is
    // what marks a module as published - the module's own build file adds the two fields that are not
    // shared: what it is called and what it is for.
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            coordinates(group.toString(), project.name, version.toString())

            pom {
                url.set("https://github.com/Octavius-Framework/octavius-postgresql")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("PolskiAnonim")
                        name.set("PolskiAnonim")
                        email.set("115878440+PolskiAnonim@users.noreply.github.com")
                        organization.set("Octavius Framework")
                        organizationUrl.set("https://github.com/Octavius-Framework")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/Octavius-Framework/octavius-postgresql.git")
                    developerConnection.set("scm:git:ssh://github.com/Octavius-Framework/octavius-postgresql.git")
                    url.set("https://github.com/Octavius-Framework/octavius-postgresql")
                }
            }

            publishToMavenCentral()

            val isSigningKeyPresent = project.hasProperty("signingInMemoryKey") || project.hasProperty("signingKey") || System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
            if (isSigningKeyPresent) {
                signAllPublications()
            }
        }
    }
}
