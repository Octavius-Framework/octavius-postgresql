# Octavius — Spring Integration

[![Maven Central](https://img.shields.io/maven-central/v/io.github.octavius-framework/driver-spring-integration)](https://central.sonatype.com/artifact/io.github.octavius-framework/driver-spring-integration)

Octavius under Spring's transaction manager, reporting failure in Spring's vocabulary.

> Part of [Octavius for PostgreSQL](../README.md), released with it and on the same version. Brings the
> [driver](../driver/README.md) transitively; the core driver has no Spring dependency at all.

```kotlin
@Service
class SenateService(private val octavius: OctaviusTemplate) {

    @Transactional
    fun enrol(cognomen: String): Int = octavius.execute { session ->
        session.createNamedQuery("INSERT INTO senate (cognomen) VALUES (@cognomen) RETURNING id")
            .fetchFieldStrict<Int>("cognomen" to cognomen)
    }
}
```

`OctaviusSpringAutoConfiguration` runs after Boot's `DataSourceAutoConfiguration` and contributes two beans —
the `OctaviusTemplate` above and an `OctaviusJdbcTransactionManager`. Both are `@ConditionalOnMissingBean`, so
declaring your own replaces them.

## What it does

**`OctaviusTemplate` runs a block against the current transaction's connection.** Inside `@Transactional` it
joins the transaction Spring opened; outside one it borrows a connection and gives it back at the closing
brace. No session is threaded through a signature either way.

**`@Transactional` works in full, `Propagation.NESTED` included** — the transaction manager is an
`OctaviusJdbcTransactionManager`, which also answers truthfully when a connection dies mid-transaction rather
than reporting a rollback that never reached the server.

**A failure that came from Octavius survives the crossing.** It arrives as `OctaviusDataAccessException`, whose
`octaviusException` hands back the driver's own exception — type, reason enum, SQLSTATE and query context
intact — however many layers the JDBC surface and the pool wrapped it in on the way. Genuinely foreign
`SQLException`s go through Spring's own translator into the standard `DataAccessException` hierarchy.

**The driver's properties are configurable from `application.yml`**, alongside `spring.datasource` and
`spring.datasource.hikari.*`.

## What does not work, and why it cannot

This module does not turn Octavius into an ordinary JDBC driver. It never was one: `PreparedStatement`,
`ResultSet` and `DatabaseMetaData` are the JDBC surface the driver deliberately throws away — see
[Octavius vs Legacy JDBC](../docs/driver/octavius-vs-jdbc.md).

So `JdbcTemplate`, `NamedParameterJdbcTemplate` and `JdbcClient` do not work, and neither do Spring Data JDBC,
Spring Data JPA, Hibernate, Flyway or Liquibase. Boot still auto-configures the `JdbcTemplate` and `JdbcClient`
beans, because this module depends on `spring-boot-starter-jdbc` — **the beans exist and they fail when
called**, with the driver's own `InvalidOperationException(FEATURE_NOT_SUPPORTED)` rather than a tidy Spring
exception, since a `RuntimeException` that was never a `SQLException` gives the translator nothing to
translate.

Actuator's `db` health indicator fails for a shallower reason and is worth replacing rather than working
around: it labels its response with `getDatabaseProductName()` before checking anything, so the component sits
permanently `DOWN` in front of a connection that is fine. One bean replaces it, in
[the documentation](../docs/driver/spring-integration.md#replacing-actuators-database-health-check).

Where a tool genuinely needs full JDBC, the practical answer is a second `DataSource` on `pgjdbc` dedicated to
it, with the Octavius one left to the application.

## Requirements

Spring Boot 4.x, on top of [the driver's own](../README.md#requirements) — Java 21, Kotlin 2.4, PostgreSQL 18.

## Documentation

- [Spring Integration](../docs/driver/spring-integration.md) — setup, `application.yml`, registering types at
  startup, transaction management, and the full table of what works
- [Octavius vs Legacy JDBC](../docs/driver/octavius-vs-jdbc.md) — which JDBC surface is kept, and why the rest
  is not
- [Error Handling & Exceptions](../docs/driver/exceptions.md#crossing-into-jdbc-and-spring) — what the
  translation preserves

## License

Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
