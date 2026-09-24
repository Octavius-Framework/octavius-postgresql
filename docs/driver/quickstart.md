# Quickstart

*Every road in the empire measured itself from a single gilded column standing in the Forum — the Milliarium Aureum,
mile zero, the point from which all distances were counted. This page is that column: the shortest way from an empty
project to a row coming back.*

> **Note:** Octavius requires **Java 21+** and **PostgreSQL 18+**. Attempting to connect to older PostgreSQL versions will fail because the driver uses Protocol v3.2 exclusively.

## 1. Add the Dependency

Add the Octavius driver to your project dependencies.

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    implementation("io.github.octavius-framework:driver:2.1.2")

    // Optional, but used by the example below
    implementation("com.zaxxer:HikariCP:7.1.0")
}
```

Three Kotlin libraries come with the driver as `api` dependencies, because they appear in its public API — there is nothing to add for them, and no version to keep in step:

| Library                      | Where it surfaces                                                                      |
|:-----------------------------|:---------------------------------------------------------------------------------------|
| `kotlinx-datetime`           | `date`, `time` and `timestamp` columns as `LocalDate` / `LocalTime` / `LocalDateTime`. |
| `kotlinx-serialization-json` | `json` and `jsonb` columns as `JsonElement`.                                           |
| `kotlinx-coroutines-core`    | `LISTEN`/`NOTIFY` as a `SharedFlow`, and `OctaviusDispatchers`.                        |

`timestamptz` and `uuid` need nothing at all: they map to `kotlin.time.Instant` and `kotlin.uuid.Uuid` from the standard library.

## 2. Establish a Connection

While Octavius uses the standard JDBC `Connection` as an entry point, it replaces legacy JDBC `ResultSet`s with its own modern API.

You can connect directly using `HikariCP`:

```kotlin
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

fun main() {
    val config = HikariConfig().apply {
        jdbcUrl = "jdbc:octavius://localhost:5432/my_database"
        username = "my_user"
        password = "my_password"
    }
    
    val dataSource = HikariDataSource(config)
    
    // Pull a session through HikariCP via the custom jdbc:octavius protocol
    val session = dataSource.getOctaviusSession()
    
    // Proceed with querying...
    
    session.close() // Safely returns the connection to the pool
}
```

## 3. Execute a Query

Once you have a session, you can execute named queries and extract strongly-typed results without dealing with `ResultSet`s.

```kotlin
// Run a query with named parameters
val rows = session.createNamedQuery("SELECT id, name FROM users WHERE active = @active")
    .fetchRows("active" to true)

// Strongly typed extraction
for (row in rows) {
    val id: Int = row.get("id")
    val name: String = row.get("name")
    println("User: $id - $name")
}

// Inserting data
session.createNamedQuery("INSERT INTO users (id, name, active) VALUES (@id, @name, @active)")
    .update("id" to 1, "name" to "Marcus", "active" to true)
```

## Next Steps
- [Executing Queries](queries.md) — the full `fetch*` family, streaming, and named parameters
- [Session Initialization](initialization.md) — every connection option, and connection pooling in depth
- [Type System](type-system.md) — how columns become Kotlin types, and how to extend that
- [Arrays, Ranges and JSON](arrays-ranges-json.md) — nullable elements, multiple dimensions, range bounds, `json` against `jsonb`
- [Bulk Writes](bulk-writes.md) — inserting thousands of rows in one statement
- [Spring Integration](spring-integration.md) — `OctaviusTemplate` and autoconfiguration
