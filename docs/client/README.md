# Octavius Client Documentation

*A magistrate's authority was his own, and none of it belonged to the scriba at his elbow. What the clerk did
was the part that repeated: the standing phrases, the order they had to go in, the clauses struck out when they
did not apply. Nobody mistook him for the magistrate, and no case came out differently because he was there.*

The client adds no power over the database. Every clause you give a builder is SQL and is passed through
unread; the terminal methods are the driver's own, under the driver's names, meaning what they mean there. What
it does is the part that repeats — and the one thing the driver genuinely leaves open, which is *which session*
an operation runs on.

These pages assume the driver's. Where something is the driver's behaviour they point at it rather than saying
it again.

## Guides

| Document                                              | Description                                                                                   |
|-------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| [Quickstart](quickstart.md)                           | From a pool to a row, and the one line that stops appearing in your signatures                |
| [Queries](queries.md)                                 | The builders, clauses that disappear, `QueryFragment`, `toSql`, raw SQL, per-query converters |
| [Transactions and Failures](transactions-failures.md) | Propagation, isolation, timeouts, `SessionProvider`, and when a failure is a value            |
| [Transaction Plans](plans.md)                         | Graphs, create-or-edit, fragments, plans inside a block, and what is checked before it runs   |
| [`dynamic_dto`](dynamic-dto.md)                       | One column, several unrelated shapes, and the three ways a value gets written as one          |
| [Annotation Scanning](scanner.md)                     | `client-scanner`: finding annotated types and registering them, what it reports and logs      |

## Logging

The client writes **no log lines of its own**, at any level. Everything that appears while a query built here
runs is the driver's, under the driver's logger names, meaning what it means there — so [the driver's Logging
page](../driver/logging.md) is the whole of it, and there is nothing on this side to turn up.

That is the claim at the top of this page, in the place it is easiest to doubt. A builder that logged the SQL
it assembled would be reporting a second time on a statement the driver already traces in the form the server
actually received — which is the form worth having, [`toSql()`](queries.md#a-query-is-a-value) being there for
the other question. A transaction block that logged its own propagation would be describing a `BEGIN` or a
`SAVEPOINT` that the driver names as it issues it.

The exception is the separate `client-scanner` artifact, which has something to say that happens nowhere else —
see [What a Scan Logs](scanner.md#what-a-scan-logs).

## Quick Links

### Getting Started
- [Add the Dependency](quickstart.md#1-add-the-dependency) — Gradle coordinates, and what comes transitively
- [Build the Client](quickstart.md#2-build-the-client) — Over a pool, or over a provider of your own
- [Run Something](quickstart.md#3-run-something) — A query, a transaction, and where the session went

### Queries
- [Every Clause Is SQL](queries.md#every-clause-is-sql) — What the builder does and what it refuses to parse
- [A Name That Comes From Outside](queries.md#a-name-that-comes-from-outside) — Values are placeholders; names are SQL
- [Clauses That Disappear](queries.md#clauses-that-disappear) — `where(null)`, and the filter assembled at runtime
- [`QueryFragment`](queries.md#queryfragment) — A condition and the parameters it names, kept together
- [A Query Is a Value](queries.md#a-query-is-a-value) — `toSql()`, `copy()`, and embedding one in another
- [Raw SQL](queries.md#raw-sql) — `rawQuery`, and the one terminal only it has
- [Per-Query Converters](queries.md#per-query-converters) — A mapping for one call and nothing else

### Transactions and Failures
- [Which Session Am I On](transactions-failures.md#which-session-am-i-on) — The question the client exists to answer
- [One Session per Thread, Not One per Level](transactions-failures.md#one-session-per-thread-not-one-per-level) — Why nesting does not multiply connections
- [Querying From Inside a Result](transactions-failures.md#querying-from-inside-a-result) — A nested query in a `forEach*` block, and the one limit on sharing
- [Propagation](transactions-failures.md#propagation) — `REQUIRED`, `REQUIRES_NEW`, `NESTED`
- [Isolation, Read-Only and Timeouts](transactions-failures.md#isolation-read-only-and-timeouts) — What applies where, and why
- [Thrown or Returned](transactions-failures.md#thrown-or-returned) — The split, and that it reads only the exception's type
- [Three Doors, Three Widths](transactions-failures.md#three-doors-three-widths) — `asResult`, `dbResult`, `transactionResult`
- [The Combination That Misleads](transactions-failures.md#the-combination-that-misleads) — `dbResult` inside a plain `transaction`
- [`SessionProvider`](transactions-failures.md#sessionprovider) — The seam, and Spring in under thirty lines

### Transaction Plans
- [When a Block Is Not Enough](plans.md#when-a-block-is-not-enough) — What each has that the other has not
- [Writing a Graph](plans.md#writing-a-graph) — A handle for a key that does not exist yet
- [Creating or Editing](plans.md#creating-or-editing) — The same save as a block and as a plan
- [Returning a Fragment](plans.md#returning-a-fragment) — `addPlan`, handles across plans, and merge order
- [Inside a Block](plans.md#inside-a-block) — Propagation, and where a joined plan's failure goes
- [Handles and What They Reach](plans.md#handles-and-what-they-reach) — `value()`, and reaching into it
- [What Binds and What Does Not](plans.md#what-binds-and-what-does-not) — The matrix, and the way across when
  a value cannot be sent
- [`map` and the Spread](plans.md#map-and-the-spread) — Transforming a value, and the one thing that takes away
- [Checked Before It Runs](plans.md#checked-before-it-runs) — Every step rendered, every handle in order
- [Running One Twice](plans.md#running-one-twice) — Retrying a serialization failure as a plain loop

### `dynamic_dto`
- [The Case a Composite Cannot Cover](dynamic-dto.md#the-case-a-composite-cannot-cover) — Shape per row, not per schema
- [Where It Goes](dynamic-dto.md#where-it-goes) — Arrays, objects built in the query, plain columns
- [Creating the Type](dynamic-dto.md#creating-the-type) — `DYNAMIC_DTO_DDL`, a migration, or `install()`
- [Registering a Class](dynamic-dto.md#registering-a-class) — And why the name is stated rather than derived
- [Reading](dynamic-dto.md#reading) — As the class, as a supertype, as the raw form, as a map
- [Writing](dynamic-dto.md#writing) — `DynamicWriteStrategy`, and when wrapping is still required
- [A Different `Json` for One Query](dynamic-dto.md#a-different-json-for-one-query) — Payloads named the way SQL names things

### Annotation Scanning
- [Why It Is a Module of Its Own](scanner.md#why-it-is-a-module-of-its-own) — One dependency, kept off everyone else
- [The Annotations](scanner.md#the-annotations) — What each one registers, and where they live
- [What a Scan Reports](scanner.md#what-a-scan-reports) — `ScanReport`, and why `unresolved` is not a refusal
- [What a Scan Logs](scanner.md#what-a-scan-logs) — The four lines, and the one part of the client that writes any
- [What It Does Not Scan](scanner.md#what-it-does-not-scan) — Converters, and why their order is not a scanner's to decide

## API Reference

- [API Reference](https://octavius-framework.github.io/octavius-postgresql/) — `client`, `client-scanner`
