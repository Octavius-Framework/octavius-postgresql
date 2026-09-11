# Octavius Driver Documentation

*An itinerarium never described the empire — it listed the roads: which ones existed, where each began, and what a
traveler would meet along the way. Nobody carried one to admire it; they carried it to find out which turn to take.
These pages are the itinerarium of the **driver**. The API reference has every declaration in the library; what
follows is the map of how they behave together.*

Detailed documentation for Octavius Driver — a PostgreSQL driver for Kotlin that speaks Wire Protocol v3.2.

## Guides

| Document                                            | Description                                                                                               |
|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| [Quickstart](quickstart.md)                         | Dependency, connection, first query — from an empty project to a returned row                             |
| [Octavius vs Legacy JDBC](octavius-vs-jdbc.md)      | Which JDBC surface is kept and why, what is unsupported, what answers quietly, migration map              |
| [Session Initialization](initialization.md)         | Entry points, `OctaviusProperties`, HikariCP pooling, startup parameters, notices, property reference     |
| [Executing Queries](queries.md)                     | Positional and named parameters, the `fetch*` family, streaming, cancellation, per-query converters       |
| [Transaction Management](transactions.md)           | Block API, manual control, savepoints, transaction state, isolation levels and read-only mode             |
| [Type System & Mapping](type-system.md)             | Catalog loading, the 2-layer architecture, codecs and converters, registering enums, composites and more  |
| [Arrays, Ranges and JSON](arrays-ranges-json.md)    | Nullable elements, multiple dimensions, inclusive and exclusive bounds, multiranges, JSON DTOs            |
| [Composites & Reflection](composites-reflection.md) | Rows and composites onto data classes, what reflection reads, hand-written converters, `PgComposite`      |
| [Bulk Writes](bulk-writes.md)                       | `UNNEST` inserts, updates and deletes, upserts, `RETURNING`, batch sizing, when to reach for COPY instead |
| [Error Handling & Exceptions](exceptions.md)        | The SQLSTATE-keyed hierarchy, message format, query context, catching at the right altitude               |
| [Logging](logging.md)                               | Logger names, what each level says, what never reaches the log, and what tracing costs                    |
| [Spring Integration](spring-integration.md)         | `OctaviusTemplate`, autoconfiguration, `application.yml`, transaction manager, exception translation      |
| [Concurrency and Virtual Threads](concurrency.md)   | What one connection serializes, pinning, `OctaviusDispatchers`, cancellation, where the real limit is     |
| [Functions and Procedures](functions-procedures.md) | `SELECT` against `CALL`, OUT and INOUT parameters, overload resolution, no `CallableStatement`            |
| [COPY Protocol](copy.md)                            | COPY IN and COPY OUT, data formats, operation lifecycle, cancelling, transactions                         |
| [Listen & Notify](listen-notify.md)                 | Subscribing and emitting, when delivery actually happens, receiving as a flow, listener loops             |
| [Large Objects](large-objects.md)                   | Creating, reading, seeking, resizing and deleting data beyond what `bytea` can hold                       |
| [Performance](performance.md)                       | JMH benchmarks against `pgjdbc`, every figure reported with its confidence interval                       |

## Quick Links

### Getting Started
- [Add the Dependency](quickstart.md#1-add-the-dependency) — Gradle coordinates for the driver and Hikari
- [Establish a Connection](quickstart.md#2-establish-a-connection) — First session, with and without a pool
- [Execute a Query](quickstart.md#3-execute-a-query) — Rows, objects, and a first transaction
- [The "Trojan Horse" Strategy](octavius-vs-jdbc.md#the-trojan-horse-strategy-connection-pools) — Why `java.sql.Connection` is implemented at all
- [What Is Explicitly Unsupported](octavius-vs-jdbc.md#what-is-explicitly-unsupported) — `PreparedStatement`, `ResultSet`, batching, LOBs, metadata
- [What Answers Quietly Instead of Throwing](octavius-vs-jdbc.md#what-answers-quietly-instead-of-throwing) — Calls a pool makes that must not fail
- [Migration Map](octavius-vs-jdbc.md#migration-map) — JDBC idiom on the left, Octavius on the right
- [Nothing Is Prepared Server-Side](octavius-vs-jdbc.md#nothing-is-prepared-server-side) — What re-parsing every statement costs, and what it saves you from

### Initialization & Configuration
- [Getting a Session](initialization.md#getting-a-session) — Typed properties, `DriverManager`, `OctaviusDataSource`
- [Adding HikariCP](initialization.md#adding-hikaricp-connection-pooling) — Via URL, via `OctaviusDataSource`, via a configured instance
- [What Survives a Return to the Pool](initialization.md#what-survives-a-return-to-the-pool) — Session state a pooled connection carries forward
- [What Happens When a Session Opens](initialization.md#what-happens-when-a-session-opens) — Handshake, authentication, catalog load
- [Authentication](initialization.md#authentication-is-scram-sha-256-or-a-password-inside-tls) — What the driver answers, what it refuses, and why a server asks for it
- [Channel Binding](initialization.md#channel-binding) — Tying the login to the certificate on the wire, and when to demand it
- [Startup Parameters](initialization.md#startup-parameters) — Unrecognized keys sent to the server
- [Notices from the Server](initialization.md#notices-from-the-server) — `NoticeHandler`, `PgNotice`, and the thread it runs on
- [Configuration Reference](initialization.md#configuration-reference) — Every property: connection, network, SSL

### Executing Queries
- [Two Ways to Pass Parameters](queries.md#two-ways-to-pass-parameters) — Positional `$1` and named `@name`
- [Quoting a Name From Outside](queries.md#quoting-a-name-that-comes-from-outside) — `quoteAsPgIdentifier()` for an identifier you have to interpolate
- [Choosing a Fetch Method](queries.md#choosing-a-fetch-method) — `fetchRows`, `fetchObjects<T>`, `fetchField<T>`
- [Reading a `Row`](queries.md#reading-a-row) — `get<T>()` by name or index
- [Nullability and the Strict Variants](queries.md#nullability-and-the-strict-variants) — When a missing row throws
- [Streaming Large Results](queries.md#streaming-large-results) — `forEach*` for results too large to hold
- [Do Not Re-Enter the Session While Reading](queries.md#do-not-re-enter-the-session-while-rows-are-being-read) — The one rule streaming imposes
- [`execute()` Takes a Whole Script](queries.md#execute-takes-a-whole-script) — Several statements, one round trip, all or nothing
- [Cancelling a Query in Flight](queries.md#cancelling-a-query-in-flight) — Out-of-band cancellation requests
- [Per-Query Converters](queries.md#per-query-converters) — Overriding mapping for a single call

### Transactions
- [Auto-Commit, the Default](transactions.md#auto-commit-the-default) — What happens without a transaction
- [Block API](transactions.md#block-api) — `required { }` and `nested { }`
- [Manual Control](transactions.md#manual-control) — `begin()`, `commit()`, `rollback()`
- [Savepoints](transactions.md#savepoints) — Partial rollback inside a transaction
- [Transaction State](transactions.md#transaction-state) — Inspecting where the session stands
- [Terms for One Transaction](transactions.md#terms-for-one-transaction) — Isolation, read-only and both timeouts, scoped to the block and sent in one round trip
- [Isolation Levels and Read-Only Mode](transactions.md#isolation-levels-and-read-only-mode) — The session-wide door, and what a pool does about it

### Type System
- [Where Types Come From](type-system.md#where-types-come-from) — The catalog is read from your database, not hardcoded
- [Keeping the Catalog Fresh](type-system.md#keeping-the-catalog-fresh--reloadtypes) — `reloadTypes()` after a migration
- [2-Layer Architecture](type-system.md#2-layer-architecture) — Codecs on the wire, converters on the objects
- [How a Value Actually Travels](type-system.md#how-a-value-actually-travels) — Both directions, end to end
- [`TypeManager`](type-system.md#typemanager--the-entry-point) — The entry point, and what it exposes
- [Scope: a Session Handle over Global State](type-system.md#scope-a-session-handle-over-global-state) — Per-database, JVM-wide registry
- [Basic Codecs](type-system.md#basic-codecs) — The full PostgreSQL ↔ Kotlin table
- [Basic Converters](type-system.md#basic-converters) — Result and parameter converters, and how one is chosen
- [Registering Your Own Mappings](type-system.md#registering-your-own-mappings) — Enums, composites, custom converters and codecs

### Arrays, Ranges and JSON
- [Arrays](arrays-ranges-json.md#arrays) — Nulls, multiple dimensions, empty collections, `PgArray`
- [Ranges and Multiranges](arrays-ranges-json.md#ranges-and-multiranges) — Bounds `ClosedRange` cannot express
- [JSON and JSONB](arrays-ranges-json.md#json-and-jsonb) — `JsonElement`, your own DTOs, `json` against `jsonb`
- [Practical Rules and Gotchas](arrays-ranges-json.md#practical-rules-and-gotchas) — The corners collected in one place

### Composites and Reflection
- [Two Reflective Mappers, One Asymmetry](composites-reflection.md#two-reflective-mappers-one-asymmetry) — Rows need no registration, composite values do
- [Rows onto Data Classes](composites-reflection.md#rows-onto-data-classes) — And why `fetchObjects<T>()` is not `row.get<T>(0)`
- [Composites onto Data Classes](composites-reflection.md#composites-onto-data-classes) — `registerAutoComposite`, and what a composite reads as without it
- [Writing a Whole Object as One Parameter](composites-reflection.md#writing-a-whole-object-as-one-parameter) — Entity in and out via `SELECT (@row).*` and `RETURNING *`, and why a nested `emptyList()` needs no `withPgType`
- [What Reflection Reads](composites-reflection.md#what-reflection-reads-and-what-it-ignores) — The primary constructor, and everything it ignores
- [When a Value Is Missing](composites-reflection.md#when-a-value-is-missing) — Absent, `NULL`, nullable, defaulted — the whole matrix
- [Reading Them All as Maps](composites-reflection.md#reading-them-all-as-maps) — `compositesAsMaps()` for one query, subtree and all, without touching a registration
- [Writing the Converters by Hand](composites-reflection.md#writing-the-converters-by-hand) — Replacing reflection, and what `getDefaultTypeName` is really for
- [`PgComposite` and `PgRecord`](composites-reflection.md#the-raw-forms-pgcomposite-and-pgrecord) — The raw forms, and why `ROW(...)` is not a row
- [`toDataObject` and `toDataMap`](composites-reflection.md#maps-in-and-out-todataobject-and-todatamap) — The same matching, without a database

### Bulk Writes
- [Why One Statement Beats a Batch](bulk-writes.md#why-one-statement-beats-a-batch) — Round trips, not driver speed
- [Inserting](bulk-writes.md#inserting) — `UNNEST` with one array per column
- [Updating](bulk-writes.md#updating) — `UPDATE ... FROM unnest(...)`
- [Deleting, and the `IN` Clause](bulk-writes.md#deleting-and-the-in-clause) — `= ANY($1)` instead of a generated `IN`
- [Upserts](bulk-writes.md#upserts) — `ON CONFLICT` over a bulk insert
- [Reading Values Back](bulk-writes.md#reading-values-back-with-returning) — `RETURNING` from a bulk statement
- [Composites, Enums and Your Own Classes](bulk-writes.md#composites-enums-and-your-own-classes) — Arrays of registered types
- [Choosing a Batch Size](bulk-writes.md#choosing-a-batch-size) — Where the curve flattens
- [When to Reach for COPY Instead](bulk-writes.md#when-to-reach-for-copy-instead) — The crossover point

### Error Handling
- [The Base Class](exceptions.md#the-base-class) — `OctaviusException` and the context it carries
- [From Wire to Exception](exceptions.md#from-wire-to-exception) — Server errors, mapping errors, and errors that never left the JVM
- [Message Format and Logging](exceptions.md#message-format-and-logging) — What a thrown exception prints
- [Query Context](exceptions.md#query-context) — The SQL and parameters your application sent
- [SQLSTATE Routing](exceptions.md#sqlstate-routing) — Which code becomes which class
- [Exception Reference](exceptions.md#exception-reference) — All seventeen types, one by one
- [Catching at the Right Altitude](exceptions.md#catching-at-the-right-altitude) — Including retrying concurrency failures
- [Crossing into JDBC and Spring](exceptions.md#crossing-into-jdbc-and-spring) — `SQLExceptionWrapper`, `OctaviusDataAccessException`, and unwrapping a pool's own exception

### Logging
- [You Supply the Backend](logging.md#you-supply-the-backend) — SLF4J with nothing behind it discards every line
- [Logger Names](logging.md#logger-names) — Every name, and what each one carries
- [What Each Level Says](logging.md#what-each-level-says) — Split by how often a line appears, not by how grave it sounds
- [Server Notices](logging.md#server-notices) — Their own logger name, and a level taken from the server's severity
- [The Driver Does Not Log What It Throws](logging.md#the-driver-does-not-log-what-it-throws) — And the failures it does log, because nothing else would
- [What Never Reaches the Log](logging.md#what-never-reaches-the-log) — Passwords, SCRAM proofs, parameter values
- [Following One Connection](logging.md#following-one-connection) — The backend process id, and joining to `pg_stat_activity`
- [What Tracing Costs](logging.md#what-tracing-costs) — When it is on, and when it is off
- [Configuration Recipes](logging.md#configuration-recipes) — Logback snippets for the usual questions

### Spring Integration
- [Setup](spring-integration.md#setup) — Dependencies and what gets wired
- [Auto-Configuration](spring-integration.md#auto-configuration) — With Spring Boot, and without it
- [Configuring from `application.yml`](spring-integration.md#configuring-the-driver-from-applicationyml) — Driver properties in Spring's config
- [Using `OctaviusTemplate`](spring-integration.md#using-octaviustemplate) — The template API and connection lifetime
- [Registering Types at Startup](spring-integration.md#registering-types-at-startup) — Enums and composites in a Spring context
- [Transaction Management](spring-integration.md#transaction-management) — `@Transactional`, read-only, isolation, nesting
- [Exceptions](spring-integration.md#exceptions) — Translation into Spring's `DataAccessException`
- [What Else in Spring Works](spring-integration.md#what-else-in-spring-works) — And what cannot, including Actuator's health check

### Concurrency
- [One Connection Is One Wire](concurrency.md#one-connection-is-one-wire) — What the protocol serializes
- [Two Threads on One Session](concurrency.md#two-threads-on-one-session) — What actually happens
- [Reentrancy Is Refused, Not Queued](concurrency.md#reentrancy-is-refused-not-queued) — Fail fast instead of deadlock
- [Virtual Threads and Pinning](concurrency.md#virtual-threads-and-pinning) — Why nothing in the driver is `synchronized`
- [`OctaviusDispatchers`](concurrency.md#octaviusdispatchers) — Coroutine dispatchers backed by virtual threads
- [Where the Concurrency Limit Actually Is](concurrency.md#where-the-concurrency-limit-actually-is) — The pool, not the threads
- [What Is Shared Beyond the Connection](concurrency.md#what-is-shared-beyond-the-connection) — The type registry, and what a reload promises
- [What Can Cross a Thread Boundary](concurrency.md#what-can-cross-a-thread-boundary) — Sessions, rows, managers
- [Cancelling a Query in Flight](concurrency.md#cancelling-a-query-in-flight) — And why a cancel can hit the wrong statement

### Functions and Procedures
- [Sending Parameters In](functions-procedures.md#sending-parameters-in) — Including how argument types pick the overload
- [Getting Values Back](functions-procedures.md#getting-values-back) — Scalars, rows, sets, and void
- [OUT and INOUT Parameters in a `CALL`](functions-procedures.md#out-and-inout-parameters-in-a-call) — Procedures that return values
- [When a Routine Fails](functions-procedures.md#when-a-routine-fails) — A routine deciding, and a routine turning out to be wrong
- [Summary](functions-procedures.md#summary) — Function against procedure, side by side

### COPY Protocol
- [Two Levels of API](copy.md#two-levels-of-api) — Stream-based, or chunks you control
- [COPY IN (Import)](copy.md#copy-in-import) — From a stream, or writing chunks yourself
- [COPY OUT (Export)](copy.md#copy-out-export) — Into a stream, or reading chunks yourself
- [Data Formats](copy.md#data-formats) — Text, CSV and binary
- [Operation Lifecycle](copy.md#operation-lifecycle) — What holds the connection, and until when
- [Cancelling](copy.md#cancelling) — Aborting an operation in progress
- [Transactions](copy.md#transactions) — How COPY interacts with a surrounding transaction
- [Performance Notes](copy.md#performance-notes) — Where COPY wins against `UNNEST`

### Listen & Notify
- [Subscribing and Emitting](listen-notify.md#subscribing-and-emitting) — `listen()` and `notify()`
- [When a Notification Is Actually Delivered](listen-notify.md#when-a-notification-is-actually-delivered) — Commit boundaries
- [Receiving Them](listen-notify.md#receiving-them) — The `SharedFlow`, and what triggers a read
- [Listener Loops](listen-notify.md#listener-loops) — Polling and interruptible variants
- [Practical Rules](listen-notify.md#practical-rules) — A dedicated connection, and what is never replayed

### Large Objects
- [Creating and Writing](large-objects.md#creating-and-writing) — `create()`, `open()`, and the transaction requirement
- [Reading](large-objects.md#reading) — Whole, or in chunks
- [Moving Around and Resizing](large-objects.md#moving-around-and-resizing) — `seek()`, `tell()`, `truncate()`
- [Deleting](large-objects.md#deleting) — `unlink()`

### Performance
- [What Is Measured](performance.md#what-is-measured) — Benchmarks, modes, and the row shape each one fixes
- [Reading](performance.md#reading) — Rows, objects and arrays against `pgjdbc`
- [Writing](performance.md#writing) — Single statements, JDBC batching, and `UNNEST`
- [Reflection or a Hand-Written Converter](performance.md#reflection-or-a-hand-written-converter) — What reflective mapping costs
- [Memory](performance.md#memory) — Allocation per operation
- [Summary](performance.md#summary) — The whole table in one place

## API Reference

For signatures, properties and enum values, see the generated KDoc — rebuilt on every push to `master`:

- [API Reference](https://octavius-framework.github.io/octavius-postgresql/) — `driver`, `driver-spring-integration`
