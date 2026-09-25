# Spring Integration

*The legions were Roman, but half the army was not. Auxiliary cohorts marched in Roman order and took orders from Roman
officers while keeping their own weapons and their own way of fighting — useful precisely because they had not been made
into legionaries. This module is that arrangement: Octavius takes its orders from Spring's transaction manager and
reports failure in Spring's vocabulary, without pretending to be the JDBC driver it never was.*

Octavius provides seamless integration with the Spring Framework and Spring Boot via the `driver-spring-integration` module.

The module is deliberately small — one template, one transaction manager, one exception translator. What it does *not* do is turn Octavius into an ordinary JDBC driver: `JdbcTemplate`, Spring Data and Hibernate all need the JDBC surface Octavius throws away, and none of them work here. [What else in Spring works](#what-else-in-spring-works) is the section to read before planning an application around it.

Contents:
* [Setup](#setup)
* [Auto-configuration](#auto-configuration)
* [Configuring the driver from `application.yml`](#configuring-the-driver-from-applicationyml)
* [Using `OctaviusTemplate`](#using-octaviustemplate)
* [Registering types at startup](#registering-types-at-startup)
* [Transaction management](#transaction-management)
* [Exceptions](#exceptions)
* [What else in Spring works](#what-else-in-spring-works)

## Setup

Add the integration module to your project:

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    // Brings the driver in transitively - no separate dependency needed
    implementation("io.github.octavius-framework:driver-spring-integration:2.2.0")
}
```

Then configure the data source in your `application.yml` or `application.properties`:

```yaml
spring:
  datasource:
    url: jdbc:octavius://localhost:5432/my_database
    username: my_user
    password: my_password
    driver-class-name: io.github.octaviusframework.driver.jdbc.OctaviusDriver
```

`driver-class-name` is not optional here. Boot infers a driver by matching the URL against `DatabaseDriver`, a closed enum of known prefixes that `octavius` is not in — the lookup returns `UNKNOWN`, there is nothing else to fall back to, and startup fails with *"Failed to determine a suitable driver class"*.

## Auto-configuration

`OctaviusSpringAutoConfiguration` runs after Boot's `DataSourceAutoConfiguration` and contributes two beans:

| Bean                         | What it gives you                                                                                                                              |
|:-----------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------|
| `OctaviusTemplate`           | Runs a block against an `OctaviusSession` on the current transaction's connection, translating exceptions on the way out.                      |
| `PlatformTransactionManager` | An `OctaviusJdbcTransactionManager`: exception translation, nested transactions, and a truthful answer when a connection dies mid-transaction. |

Exception translation follows from that second bean and from the template, and the rule is one sentence: **if the failure came from Octavius, `octaviusException` hands you back the driver's own exception** — its type, its reason enum, its SQLSTATE and its query context all intact. What it was wrapped in on the way does not matter and never reaches you; the JDBC surface adds a layer to satisfy `SQLException`, a pool adds another, and both are unwrapped however deep they sit. A connection HikariCP could not hand over arrives as an `InitializationException`, never as the pool's own `SQLException` — carrying the driver's reason where there was one underneath, and `CONNECTION_UNAVAILABLE` where the pool simply had none free. Genuinely foreign `SQLException`s go through Spring's `SQLStateSQLExceptionTranslator` and land in the standard `DataAccessException` hierarchy.

Both beans are `@ConditionalOnMissingBean`, so declaring your own replaces them. That is the hook for a custom `SQLExceptionTranslator`:

```kotlin
@Configuration
class OctaviusConfig {

    @Bean
    fun octaviusTemplate(dataSource: DataSource): OctaviusTemplate =
        OctaviusTemplate(dataSource, MyExceptionTranslator())
}
```

### Without Spring Boot

Plain Spring has no auto-configuration to run, so declare the same two beans by hand:

```kotlin
@Configuration
@EnableTransactionManagement
class OctaviusConfig {

    @Bean
    fun dataSource(): DataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:octavius://localhost:5432/res_publica"
        username = "consul"
        password = "senatus_populusque"
    })

    @Bean
    fun octaviusTemplate(dataSource: DataSource) = OctaviusTemplate(dataSource)

    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        OctaviusJdbcTransactionManager(dataSource)
}
```

## Configuring the driver from `application.yml`

`spring.datasource.*` covers the address and the credentials. Everything else the driver understands — timeouts, SSL, buffer sizes, the full [configuration reference](initialization.md#configuration-reference) — goes on the URL as a query parameter, and so does any [startup parameter](initialization.md#startup-parameters) you want the server to receive:

```yaml
spring:
  datasource:
    url: jdbc:octavius://localhost:5432/res_publica?sslmode=require&socketTimeout=30&application_name=curia-api
    username: consul
    password: senatus_populusque
    driver-class-name: io.github.octaviusframework.driver.jdbc.OctaviusDriver
    hikari:
      maximum-pool-size: 20
      pool-name: curia-pool
```

`application_name` is a property of the driver's own — spelled either way, `applicationName` or `application_name` — and reaches `pg_stat_activity` as such. A key the driver does *not* recognize is not an error either: it is kept aside and sent to PostgreSQL in the startup message, which is how a `search_path` or a `statement_timeout` on that URL would reach the session. See [Startup parameters](initialization.md#startup-parameters).

`spring.datasource.hikari.*` configures the pool exactly as it would for any other driver, with one setting to leave alone:

> [!WARNING]
> Do not set `spring.datasource.hikari.auto-commit: false`. Pools apply that while *preparing* a connection, not while you use it, so every connection in the pool reports `idle in transaction` for as long as it sits there waiting to be borrowed. See [Transactions](transactions.md#manual-control) for the full picture.

## Using `OctaviusTemplate`

Once auto-configured, you can inject `OctaviusTemplate` into your services. It manages getting the connection, extracting the `OctaviusSession`, and translating exceptions. The session is the receiver of the `execute` block, so its operations are called directly on `this`.

```kotlin
import io.github.octaviusframework.driver.spring.OctaviusTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(private val template: OctaviusTemplate) {

    @Transactional
    fun createUser(id: Int, name: String) {
        template.execute {
            createNamedQuery("INSERT INTO users (id, name) VALUES (@id, @name)")
                .update("id" to id, "name" to name)
        }
    }
    
    @Transactional(readOnly = true)
    fun getUser(id: Int): String {
        return template.execute {
            val row = createNamedQuery("SELECT name FROM users WHERE id = @id")
                .fetchRow("id" to id)
                
            row?.get<String>("name") ?: throw RuntimeException("User not found")
        }
    }
}
```

### Connection lifetime

`execute` takes its connection through `DataSourceUtils`, so it joins an active `@Transactional` transaction rather than opening its own, and hands the connection back at the end. The connection's lifetime belongs to Spring. The session's does not, and the two are kept apart: closing a session opened over a Spring-owned connection undoes the state it left and stops there, rather than returning a connection Spring still counts as borrowed.

**Inside a transaction, one session is bound to it** and every `execute` in that transaction works through the same one — so the notification, copy and type managers are built once rather than per call, and there is a single point at which the session's state is undone. That point is after the commit or rollback and before Spring releases the connection. It has to be after, because a transaction that failed on the server is still failed until the rollback goes through, and PostgreSQL ignores every statement sent in the meantime (`25P02`). Cleaning up any earlier would raise on exactly those transactions, and a session that cannot reset its connection gives the connection up — so every failed transaction would cost one. **Outside a transaction, the session ends with the `execute` call that opened it.**

Either way, what the session left is undone before the connection goes back to the pool — `LISTEN` registrations, and a transaction opened by a hand-written `BEGIN`, which is rolled back rather than committed. A `COPY` you start and never finish is the exception, because it cannot be reset away: ending a `COPY OUT` means reading the whole export first. The connection is evicted instead and the pool opens a fresh one, and a `COPY IN` that never reached `endCopy()` lands nothing either way.

What still does not belong in a template block is anything that outlives the call: a listener loop holds Spring's connection for as long as it runs, so give it a session of its own through [`getOctaviusSession`](initialization.md#getting-a-session). And the wider rule is unchanged — state you set by running the SQL yourself is invisible to both the pool and the driver, so nothing undoes it. See [What survives a return to the pool](initialization.md#what-survives-a-return-to-the-pool).

## Registering types at startup

Enums, composites, codecs and custom converters are registered through `typeManager` — and the registry they write to is **global per database, not per session**. That makes registration a startup step rather than something a repository does on every call. A `CommandLineRunner` (or an `@PostConstruct`) is the natural place; every session opened afterwards, from any pool in the JVM, already knows about them:

```kotlin
@Bean
fun registerOctaviusTypes(template: OctaviusTemplate) = CommandLineRunner {
    template.execute {
        typeManager.registerEnum<LegioStatus>()
        typeManager.registerAutoComposite<Address>()
        typeManager.registerAutoComposite<SenatorProfile>()

        typeManager.registerParameterConverter(MapParameterConverter(dbObjectMapper))
        typeManager.registerResultConverter(MapResultConverter(dbObjectMapper))
    }
}
```

> [!WARNING]
> Registering per request is not merely wasted work. Converter registries are copy-on-write lists and a repeat registration **prepends another copy** — the newest one wins so behavior stays correct, but the list grows without bound and every lookup gets slower. Register once. See [Scope: a session handle over global state](type-system.md#scope-a-session-handle-over-global-state).

If your application creates types at runtime — a schema bootstrap on first start, a migration — call `reloadTypes()` from the same block once the DDL has run, so the new OIDs are in the catalog before the first query needs them.

When a global registration is too broad, converters can also be scoped to a single query object inside an `execute` block; see [Query-scoped overrides](type-system.md#query-scoped-overrides).

## Transaction management

The auto-configured `PlatformTransactionManager` supports Spring's declarative transaction management (`@Transactional`). Connections are properly synchronized, meaning `template.execute` will reuse the active transaction's session.

By default, **nested transactions are allowed**. This means you can use `Propagation.NESTED` safely with the Octavius driver.

```kotlin
@Transactional(propagation = Propagation.NESTED)
fun updateUserDetails() {
    // Executes inside a savepoint if an active transaction already exists
}
```

### Read-only and isolation

`@Transactional(readOnly = true)` and `@Transactional(isolation = ...)` reach the connection through the JDBC setters Octavius implements, and HikariCP restores both when the connection goes back to the pool.

Read-only is enforced by *PostgreSQL*, not by a client-side check, so a write attempted inside such a transaction comes back as a `TransactionStateException(READ_ONLY_TRANSACTION)` inside an `OctaviusDataAccessException`. Setting either of these by running the SQL yourself escapes Hikari's tracking entirely and leaks to the next borrower — see [What survives a return to the pool](initialization.md#what-survives-a-return-to-the-pool).

### `session.transaction` inside a block

`session.transaction` is reachable from inside `execute`, and using it breaks nothing: both blocks key off auto-commit, which Spring has already turned off, so `required { }` joins the surrounding transaction rather than opening one and `nested { }` takes a savepoint — the same mechanism `Propagation.NESTED` uses. It just earns nothing either, since `@Transactional` already drew the boundary. Reach for it only where there is no annotation to rely on.

A block cannot break Spring's boundary in any case: `execute` hands you an `OctaviusSessionOperations` receiver, which deliberately hides `commit()`, `rollback()` and `autoCommit`.

### Large objects need a transaction

[Large Objects](large-objects.md) are only valid inside the transaction that opened them — PostgreSQL's rule, not the driver's. In a Spring application that means `@Transactional` on the method, not merely a template block:

```kotlin
@Transactional
fun storeScroll(bytes: ByteArray): Int = template.execute {
    val oid = largeObjects.create()
    largeObjects.open(oid, LargeObjectMode.WRITE).use { it.write(bytes) }
    oid
}
```

Without it each statement commits on its own and the descriptor is dead by the first write, surfacing as `StatementException(UNDEFINED_OBJECT)`.

## Exceptions

Database failures leave `execute` as Spring `DataAccessException`s, and the Octavius-shaped ones keep the original available as `octaviusException` — including failures to obtain the connection in the first place, which are translated on the same path. Exceptions of your own thrown inside the block travel out unchanged; the template only rewrites what it recognizes.

```kotlin
@ExceptionHandler(OctaviusDataAccessException::class)
fun handle(ex: OctaviusDataAccessException): ResponseEntity<*> {
    val root = ex.octaviusException

    if (root is ConstraintViolationException &&
        root.reason == ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("constraint" to root.constraint))
    }

    return ResponseEntity.internalServerError().body(mapOf("error" to "Unexpected database error."))
}
```

The full hierarchy, the reason enums worth branching on, and a longer `@ControllerAdvice` example are in [Error Handling](exceptions.md#crossing-into-jdbc-and-spring).

### When the connection dies mid-transaction

A connection can leave in the middle of a transaction — the driver aborts one whose stream is broken or that was left mid-`COPY`, and `session.abort()` does it on request. The pool evicts it, and from then on its proxy answers everything with a bare `SQLException` that carries neither SQLState nor cause, so there is nothing left for an exception translator to recognize.

The auto-configured `OctaviusJdbcTransactionManager` answers for that case, and treats the two paths differently:

| Path         | What happens                                                                                            |
|:-------------|:--------------------------------------------------------------------------------------------------------|
| **Commit**   | Raises `OctaviusDataAccessException` wrapping `NetworkException(CONNECTION_ABORTED)`.                   |
| **Rollback** | Stays quiet. The server discarded the transaction along with the connection, so there is nothing to do. |

The asymmetry is the point. A commit that did not happen must be reported, or the caller is told its work is durable when it is not. A rollback, on the other hand, has already got what it asked for — and raising there would replace the exception that *caused* the rollback with one about the cleanup. Without this, an aborted connection turned every such failure into `TransactionSystemException: JDBC commit failed`, and Spring logged `Application exception overridden by rollback exception` over the diagnostic you actually needed.

Declaring a `PlatformTransactionManager` of your own replaces this, as it replaces the rest of the auto-configuration.

## What else in Spring works

`driver-spring-integration` depends on `spring-boot-starter-jdbc`, so Boot auto-configures `JdbcTemplate` and `JdbcClient` beans alongside `OctaviusTemplate`. Those beans exist. They do not work: `JdbcTemplate` binds parameters through `Connection.prepareStatement` and reads rows through `ResultSet`, and Octavius implements neither — see [Octavius vs Legacy JDBC](octavius-vs-jdbc.md).

| Feature                                                    | Works | Notes                                                                                         |
|:-----------------------------------------------------------|:------|:----------------------------------------------------------------------------------------------|
| `OctaviusTemplate`, `@Transactional`, `Propagation.NESTED` | Yes   | The supported path.                                                                           |
| HikariCP and `spring.datasource.hikari.*`                  | Yes   | Validation runs through `isValid()`. Leave `connection-test-query` unset — `SELECT 1` throws. |
| `spring.sql.init` — `schema.sql` and `data.sql`            | Yes   | Those scripts run through a plain `Statement`, which Octavius implements.                     |
| `JdbcTemplate`, `NamedParameterJdbcTemplate`, `JdbcClient` | No    | Need `PreparedStatement` and `ResultSet`.                                                     |
| Spring Data JDBC, Spring Data JPA, Hibernate               | No    | The same, plus `DatabaseMetaData`.                                                            |
| Flyway, Liquibase                                          | No    | Both identify the database through `DatabaseMetaData`.                                        |
| Actuator's `db` health indicator                           | No    | Calls `getMetaData().getDatabaseProductName()`. Replaceable, see below.                       |

`spring.sql.init` comes with a footnote: it needs `spring.sql.init.mode: always`, because PostgreSQL is not an embedded database and Boot skips the scripts otherwise.

Reaching for anything else in the `No` rows does not produce a tidy Spring exception either. `prepareStatement` throws `InvalidOperationException(FEATURE_NOT_SUPPORTED)`, which is a `RuntimeException` that was never a `SQLException` — so there is nothing for the translator to translate, and `jdbcTemplate.queryForObject("SELECT 1", Int::class.java)` fails with the raw Octavius exception.

Where a tool genuinely needs full JDBC — running Flyway migrations at startup, for instance — the practical answer is a second `DataSource` on `pgjdbc`, dedicated to that tool, with the Octavius one left to the application.

### Replacing Actuator's database health check

If Actuator is on the classpath, `DataSourceHealthIndicator` contributes the `db` component of `/actuator/health` — usually the thing a Kubernetes readiness probe reads. It fails here for a shallow reason: before checking anything it labels the response with `getMetaData().getDatabaseProductName()`, and that is precisely the call Octavius refuses. The exception does not escape — `AbstractHealthIndicator` catches it — so the symptom is not a broken endpoint but a `db` component permanently `DOWN`, carrying `INVALID_OPERATION_EXCEPTION:FEATURE_NOT_SUPPORTED` as its `error` detail.

What that label was standing in front of is `connection.isValid()`, which Octavius *does* implement. So keep the check and drop the label. Boot's auto-configuration backs off from a bean named `dbHealthIndicator` or `dbHealthContributor`, which means declaring one replaces it outright — nothing to disable, and `db` stays in the response:

```kotlin
@Bean
fun dbHealthIndicator(template: OctaviusTemplate) = HealthIndicator {
    try {
        if (template.execute { isValid(1) }) Health.up().withDetail("database", "PostgreSQL").build()
        else Health.down().withDetail("database", "PostgreSQL").build()
    } catch (ex: Exception) {
        Health.down(ex).build()
    }
}
```

`management.health.db.enabled: false` stays available if you would rather have no database component at all.
