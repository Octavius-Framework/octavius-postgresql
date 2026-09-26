enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    versionCatalogs {
        create("spring") {
            from(files("gradle/spring.versions.toml"))
        }
        create("hikari") {
            from(files("gradle/hikari.versions.toml"))
        }
    }
}

rootProject.name = "octavius-postgresql"
include("pg-model")
include("driver")
include("client")
include("client-scanner")
include("migrations")
include("hikari-integration-tests")
include("test-support")
include("driver-spring-integration")
include("benchmarks")
