# Spring Integration

*The legions were Roman, but half the army was not. Auxiliary cohorts marched in Roman order and took orders from Roman
officers while keeping their own weapons and their own way of fighting — useful precisely because they had not been made
into legionaries. This module is that arrangement: Octavius takes its orders from Spring's transaction manager but
fights with its own weapons — a failure stays the driver's own exception — and does not pretend to be the JDBC driver
it never was.*

`driver-spring-integration` gives Spring one template and one transaction manager. It does not turn Octavius into an
ordinary JDBC driver: `JdbcTemplate`, Spring Data and Hibernate need the JDBC surface Octavius does without, and none of
them work here. Read [What else in Spring works](#what-else-in-spring-works) before planning an application around it.

Contents:
* [Setup](#setup)
* [Auto-configuration](#auto-configuration)
* [Using `OctaviusTemplate`](#using-octaviustemplate)
* [Registering types at startup](#registering-types-at-startup)
* [Transaction management](#transaction-management)
* [Exceptions](#exceptions)
* [What else in Spring works](#what-else-in-spring-works)

## Setup

```kotlin
dependencies {
    // Brings the driver in transitively
    implementation("io.github.octavius-framework:driver-spring-integration:2.12.0")
}
```

```yaml
spring:
  datasource:
    url: jdbc:octavius://localhost:5432/curia?sslmode=require&socketTimeout=30&search_path=senatus
    username: consul
    password: senatus_populusque
    driver-class-name: io.github.octaviusframework.driver.jdbc.OctaviusDriver
    hikari:
      maximum-pool-size: 20
```

`driver-class-name` is required: Boot does not know the `jdbc:octavius:` prefix, and without it startup fails with
*"Failed to determine a suitable driver class"*.

Everything else the driver understands, from the [configuration reference](initialization.md#configuration-reference),
goes on the URL as a query parameter. A key it does not recognize, like `search_path` above, goes to PostgreSQL as a
[startup parameter](initialization.md#startup-parameters).

`spring.datasource.hikari.*` configures the pool as for any other driver. Leave `auto-commit` at its default: turning it
off gains `@Transactional` nothing, since switching auto-commit sends nothing to the server, and outside `@Transactional`
it leaves every session in a transaction nobody commits, so writes made through `OctaviusTemplate` there are rolled back
when the call ends.

## Auto-configuration

With Spring Boot the module contributes two beans:

| Bean                             | What it gives you                                                                   |
|:---------------------------------|:------------------------------------------------------------------------------------|
| `OctaviusTemplate`               | Runs a block on an `OctaviusSession`, the current transaction's where there is one. |
| `OctaviusJdbcTransactionManager` | The `PlatformTransactionManager` behind `@Transactional`, in place of Boot's.       |

Declaring an `OctaviusTemplate` of your own replaces the first, and declaring any `TransactionManager` replaces the
second, as it would replace Boot's. With several `DataSource` beans both are built on the `@Primary` one, and with none
primary the module contributes nothing, as Boot's own JDBC auto-configuration does.

### Without Spring Boot

Plain Spring has no auto-configuration, so declare the same two beans by hand:

```kotlin
@Configuration
@EnableTransactionManagement
class OctaviusConfig {

    @Bean
    fun dataSource(): DataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:octavius://localhost:5432/curia"
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

## Using `OctaviusTemplate`

`execute` runs its block with the session as the receiver and returns what the block returns:

```kotlin
import io.github.octaviusframework.driver.spring.OctaviusTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class Censor(private val template: OctaviusTemplate) {

    @Transactional
    fun enrol(id: Int, cognomen: String) {
        template.execute {
            createNamedQuery("INSERT INTO senators (id, cognomen) VALUES (@id, @cognomen)")
                .executeUpdate("id" to id, "cognomen" to cognomen)
        }
    }

    @Transactional(readOnly = true)
    fun cognomenOf(id: Int): String? = template.execute {
        createNamedQuery("SELECT cognomen FROM senators WHERE id = @id")
            .fetchField<String?>("id" to id)
    }
}
```

### Connection lifetime

Inside a `@Transactional` transaction every `execute` works through the one session bound to that transaction. Outside
one, `execute` borrows a connection for the call and gives it back when the call ends.

Once the transaction or the call is over, what the session left on the connection is undone before the connection goes
back to the pool: `LISTEN` registrations are dropped, and a transaction left open, never committed or opened by a
hand-written `BEGIN`, is rolled back. An unfinished `COPY` cannot be reset, so that connection is evicted and the pool
opens a fresh one. State you set by running the SQL yourself stays; see
[What survives a return to the pool](initialization.md#what-survives-a-return-to-the-pool).

Nothing that outlives the call belongs in a block. A listener loop would hold Spring's connection for as long as it
runs, so give it a session of its own through [`getOctaviusSession`](initialization.md#getting-a-session).

## Registering types at startup

Enums, composites, codecs and converters registered through `typeManager` go into a catalog shared by every session on
that database in the JVM, from any pool. Registration is therefore a startup step, done once, and a `CommandLineRunner`
is a natural place for it:

```kotlin
@Bean
fun registerOctaviusTypes(template: OctaviusTemplate) = CommandLineRunner {
    template.execute {
        typeManager.registerEnum<LegioStatus>()
        typeManager.registerAutoComposite<SenatorProfile>()
    }
}
```

Registering per request is not only wasted work: a converter registered again is added again, and the lists grow
without bound; see [Scope: a session handle over global state](type-system.md#scope-a-session-handle-over-global-state).

If the application creates types at runtime, in a schema bootstrap or a migration, call `reloadTypes()` in a block once
the DDL has run, so the catalog knows them before the first query needs them.

## Transaction management

`@Transactional` runs through the auto-configured manager, and `template.execute` inside it runs on the transaction's
session. `Propagation.NESTED` runs the method under a savepoint of the running transaction.

A client built with `OctaviusClient.fromDataSource` takes no part in this: its queries and its `db.transaction` borrow a
connection of their own and commit apart from Spring's transaction, staying committed when it rolls back. Built with
`fromSessionProvider` over the template and the transaction manager, it runs in Spring's transaction — see
[`SessionProvider`](../client/transactions-failures.md#sessionprovider).

### Timeouts

`@Transactional(timeout = …)`, a `TransactionTemplate`'s timeout and `spring.transaction.default-timeout` are enforced
by the server. The manager sends the timeout as a `SET LOCAL` when the transaction begins, so it ends with the
transaction and nothing of it follows the connection back to the pool. That statement also sends the transaction's
`BEGIN`, which the driver otherwise holds back until the first statement: a transaction with a timeout costs one round
trip more, and the server's clock starts when the method does.

PostgreSQL has no timeout that bounds a whole transaction and leaves the connection standing, so `timeoutScope` chooses
which setting the timeout goes out as:

|                                  | `STATEMENT` — the default                                                                      | `TRANSACTION`                                                                                                                        |
|:---------------------------------|:-----------------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------|
| Sent when the transaction begins | `SET LOCAL statement_timeout`                                                                  | `SET LOCAL transaction_timeout`                                                                                                      |
| A statement that runs past it    | Cancelled: `ExecutionAbortedException`, and the connection stays                               | The server ends the session: `SessionTerminatedException(TRANSACTION_TIMEOUT)`, and the connection goes with it                      |
| The transaction as a whole       | Not bounded: each statement gets the whole timeout, so several can run longer than it together | Bounded by the server, time between statements included: at the deadline it ends the session, even while your code is busy elsewhere |

`STATEMENT` is the default because it never costs a connection. It is not `JdbcTemplate`'s timeout, which gives each
statement only what is left of the transaction's time.

`TRANSACTION` replaces any `transaction_timeout` the database or role sets by default. A transaction that ran out between
statements is found by whatever touches it next, and at the commit that is `SessionTerminatedException(TRANSACTION_TIMEOUT)`
instead of a commit. Every transaction that runs out costs the pool a connection, and timeouts come in crowds when a
database is struggling: choose it where a lock held past its time is worse than a reconnect.

A customizer switches it on the auto-configured manager:

```kotlin
@Bean
fun wholeTransactionTimeouts() = TransactionManagerCustomizer<OctaviusJdbcTransactionManager> {
    it.timeoutScope = TransactionTimeoutScope.TRANSACTION
}
```

Only a transaction the manager begins gets a timeout. A method that joins a running transaction, or takes a savepoint
under `NESTED`, keeps the terms already in force, and a transaction without a timeout sends nothing.

### Read-only and isolation

`@Transactional(readOnly = true)` and `@Transactional(isolation = …)` apply to the transaction's session, and the manager
puts back what it changed when the transaction ends. Read-only is enforced by PostgreSQL, so a write inside such a
transaction fails with `TransactionStateException(READ_ONLY_TRANSACTION)`.

### `session.transaction` inside a block

A block cannot end Spring's transaction: its receiver, `OctaviusSessionOperations`, has no `commit()`, `rollback()` or
`autoCommit`. `transaction` is there and breaks nothing, since `required { }` joins the surrounding transaction and
`nested { }` takes a savepoint, but under `@Transactional` it adds nothing. It is for code with no annotation to rely on.

### Large objects need a transaction

A [large object](large-objects.md) is valid only inside the transaction that opened it, so in a Spring application the
method needs `@Transactional`, not only a template block:

```kotlin
@Transactional
fun storeScroll(bytes: ByteArray): Int = template.execute {
    val oid = largeObjects.create()
    largeObjects.open(oid, LargeObjectMode.WRITE).use { it.write(bytes) }
    oid
}
```

Without it each statement commits on its own, the descriptor is gone by the first write, and the write fails with
`StatementException(UNDEFINED_OBJECT)`.

## Exceptions

Failures leave `execute` as the driver's own `OctaviusException`s, the ones a session used directly throws, with their
reason, SQLSTATE and query context intact. A connection the pool would not hand over is an `InitializationException`,
with the driver's reason where there was one underneath and `CONNECTION_UNAVAILABLE` where the pool had none free.
Exceptions of your own thrown inside the block leave unchanged.

The transaction manager does the same. It reports a connection it could not obtain as above, where Spring's own
`JdbcTransactionManager` raises `CannotCreateTransactionException`, and a commit, rollback or savepoint that failed in
the driver raises the driver's exception.

So a handler names the driver's type:

```kotlin
@ExceptionHandler(ConstraintViolationException::class)
fun handle(ex: ConstraintViolationException): ResponseEntity<*> {
    if (ex.reason == ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("constraint" to ex.constraint))
    }

    return ResponseEntity.internalServerError().body(mapOf("error" to "Unexpected database error."))
}
```

None of these is a `DataAccessException`, so a `catch` or an `@ExceptionHandler` for one, or for any class of Spring's
hierarchy, `DuplicateKeyException` included, does not see them. `@Transactional` still rolls back on them, as on every
`RuntimeException`.

Spring's `spring.sql.init` runs its scripts itself, so a failing `schema.sql` or `data.sql` arrives as Spring's
`ScriptStatementFailedException`, naming the statement, with the driver's exception in its cause chain, where
`findOctaviusCause()` finds it.

The full hierarchy and the reasons worth branching on are in [Error Handling](exceptions.md#exception-reference).

### When the connection dies mid-transaction

A connection can go in the middle of a transaction: the driver aborts one whose stream broke or that was left in a
`COPY`, `session.abort()` does it on request, and the server ends one at a `transaction_timeout`, on
`pg_terminate_backend()` or at a shutdown. The manager answers differently at the two ends of a transaction:

| Path         | What happens                                                                                                                                                                                                 |
|:-------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Commit**   | Raises the server's reason, `SessionTerminatedException`, where the server ended the session, and `NetworkException(CONNECTION_ABORTED)` where the connection had already left, or left during the `COMMIT`. |
| **Rollback** | Stays quiet, including when the rollback is what found the connection gone. The server discarded the transaction along with the connection, so there is nothing to do.                                       |

A commit that did not happen has to be reported, or the caller is told its work is durable when it is not. A rollback
has already got what it asked for, and raising there would replace the exception that caused it.

A `NESTED` method's savepoint follows the same rules. A method that returned releases its savepoint, which raises
`NetworkException(CONNECTION_ABORTED)` on a connection that is gone: what it did went with the connection. A method that
threw rolls back to its savepoint quietly, so its own exception is what leaves it. One begun on a connection already
gone raises before it runs.

A commit that raises, for any reason, is followed by Spring's rollback, whatever
`spring.transaction.rollback-on-commit-failure` says: a `TransactionSynchronization` hears of the transaction as rolled
back, and an `@TransactionalEventListener(phase = AFTER_ROLLBACK)` runs. Where the connection went while the `COMMIT`
waited for its answer, say with `NetworkException(CONNECTION_TIMEOUT)` from a `socketTimeout` shorter than a commit held
up by synchronous replication, they hear the same, though the server may have committed: nothing on this side of the
connection can tell.

## What else in Spring works

`driver-spring-integration` depends on `spring-boot-starter-jdbc`, so Boot creates `JdbcTemplate` and `JdbcClient` beans
as well. They exist but do not work: they bind parameters through `PreparedStatement` and read rows through `ResultSet`,
and Octavius implements neither; see [Octavius vs Legacy JDBC](octavius-vs-jdbc.md).

| Feature                                                    | Works | Notes                                                                                         |
|:-----------------------------------------------------------|:------|:----------------------------------------------------------------------------------------------|
| `OctaviusTemplate`, `@Transactional`, `Propagation.NESTED` | Yes   | The supported path.                                                                           |
| `OctaviusClient`                                           | Yes   | Through a [`SessionProvider`](../client/transactions-failures.md#sessionprovider).            |
| HikariCP and `spring.datasource.hikari.*`                  | Yes   | Validation runs through `isValid()`. Leave `connection-test-query` unset — `SELECT 1` throws. |
| `spring.sql.init` — `schema.sql` and `data.sql`            | Yes   | Those scripts run through a plain `Statement`, which Octavius implements.                     |
| Actuator's `db` health indicator                           | Yes   | Checked with `isValid()` — `DOWN` whenever `connection-test-query` is set.                    |
| `JdbcTemplate`, `NamedParameterJdbcTemplate`, `JdbcClient` | No    | Need `PreparedStatement` and `ResultSet`.                                                     |
| Spring Data JDBC, Spring Data JPA, Hibernate               | No    | The same, plus `DatabaseMetaData`.                                                            |
| Flyway, Liquibase                                          | No    | Both identify the database through `DatabaseMetaData`.                                        |

Something from the `No` rows fails with the raw `InvalidOperationException(FEATURE_NOT_SUPPORTED)` that
`prepareStatement` throws, not with a Spring exception: it is not a `SQLException`, so `JdbcTemplate` has nothing to
translate.

Schema migrations need neither Flyway nor Liquibase: [`migrations`](../migrations/README.md) runs on the driver. A tool
that needs full JDBC all the same gets a `DataSource` of its own on `pgjdbc`, with the Octavius one marked `@Primary`,
which is the one the [auto-configuration](#auto-configuration) builds on.
