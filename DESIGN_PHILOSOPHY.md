# Design Philosophy

*Augustus was offered the dictatorship and refused it, taking instead the powers that came with offices Rome
already recognised. He changed what the machinery did without changing what it looked like from outside.
Octavius does the same to JDBC: a `Connection`, a `DataSource`, a pool that recognises both — and none of JDBC
underneath.*

The [README](README.md) states five principles. This is the file that argues for them: the decisions that were
made deliberately, the alternatives that were considered and rejected, and the places where a reasonable person
would have chosen otherwise. It says *why*; the [guides](docs/README.md) say *how*, and each section points at
the one that does.

## Contents

* [SQL is not a problem to be solved](#sql-is-not-a-problem-to-be-solved)
* [PostgreSQL is not "just a database"](#postgresql-is-not-just-a-database)
* [The ORM tax](#the-orm-tax)
* [Why speak the protocol rather than wrap a driver](#why-speak-the-protocol-rather-than-wrap-a-driver)
* [One standard, no fallback](#one-standard-no-fallback)
* [One coordinate per decision](#one-coordinate-per-decision)
* [Why the type registry is global per database](#why-the-type-registry-is-global-per-database)
* [Why registration order is the override mechanism](#why-registration-order-is-the-override-mechanism)
* [Why nothing is created behind your back](#why-nothing-is-created-behind-your-back)
* [Why the class asked for is enough to read but not to write](#why-the-class-asked-for-is-enough-to-read-but-not-to-write)
* [Why blocking, and not coroutines-first](#why-blocking-and-not-coroutines-first)
* [Why exceptions, and `DataResult` when you ask for it](#why-exceptions-and-dataresult-when-you-ask-for-it)
* [Why `@name` and not `:name`](#why-name-and-not-name)
* [Dynamic queries are just strings](#dynamic-queries-are-just-strings)
* [Why the version number is not semver](#why-the-version-number-is-not-semver)
* [The line this deliberately does not cross](#the-line-this-deliberately-does-not-cross)
* [When this is not the right tool](#when-this-is-not-the-right-tool)

## SQL is not a problem to be solved

Most data access layers treat SQL as an implementation detail to be hidden — a thing the library generates so
you do not have to look at it. That trade buys you portability you will never use, and sells you the one
language in your stack that is declarative, decades-stable, and understood by every profiler, every DBA and
every `EXPLAIN` you will ever run.

Here the SQL you write is the SQL that runs. Nothing rewrites it, reorders it, or adds a query you did not ask
for (parameters aside: `@name` goes out as `$1`). When a query is slow you paste it into `psql` with its values
in place of the parameters, and it behaves the same way, because it *is* the same query.

The driver takes a statement as written. The client adds builders, which write the repetitive parts of a
statement for you: the column list of an `INSERT` and the parameters that repeat it, the keywords in the order
they go, a `WHERE` left out when a filter assembled at runtime has nothing to say. Every clause is still SQL
text, passed through. A `rawQuery` ends in the same terminals as any builder, so writing the whole statement by
hand costs only that repetition.

## PostgreSQL is not "just a database"

A layer that runs on several engines needs a type model of its own, since types are the part the engines do not
share, and it maps each engine's types into that model.

Supporting PostgreSQL alone, the driver needs no such model, because the database has one. Its catalog says what
every value is: a composite and its attributes, an enum and its labels, a domain and the type under it, a range
and its subtype. The driver reads the catalog and decodes each value by it, binary and as deeply nested as it
comes, so a composite, enum, domain or range created by a migration needs no codec written for it. A class is
registered only to write it as a composite or an enum, to read an enum as that class rather than as a string, and
to say what a composite read as `Any` becomes. What a value is, the database says; what it is read as, the call
does.

The rest follows from that choice rather than adding to it. The driver speaks PostgreSQL's wire protocol instead
of wrapping a portable interface, which is open only to a driver with one engine to speak to. The client's
builders render one grammar, so they carry PostgreSQL's clauses as they are — `RETURNING` on all three writes,
`ON CONFLICT`, `OVERRIDING … VALUE`, `UPDATE … FROM`, `DELETE … USING` — where a builder over several engines
renders each one per engine, emulates it, or leaves it out.

The cost is stated plainly: this runs against PostgreSQL and nothing else, ever.

## The ORM tax

An ORM buys you a mapping between rows and objects. What it charges for it is a second model of your data,
sitting between you and the first and deciding which statements run.

The decision shows most plainly in `save()`. Handed an object, the library has to work out whether it means an
`INSERT` or an `UPDATE`, which columns changed, and what became of the rows that hang off it. Hibernate answers
from a session that tracked what it loaded, and the session is where the lazy-loading exception thrown outside
it comes from, the N+1 query, the `flush()` that reorders your statements, and the afternoon spent reading
generated SQL to find out what the framework thought you meant. Spring Data JDBC dropped the session and answers
from the object alone: an id that is still `null` means new, and an aggregate that is not new has every entity
it references deleted and inserted again, since nothing remembers which of them changed. With a session or
without one, something has to work out from an object what a write means, and that is the tax.

Here there is no `save()`, so there is nothing to work out. A write is a statement you wrote or a builder you
filled in, and it says `INSERT` or `UPDATE` because you did. A `data class` is a typed container for a row that
came back or a value that goes out, and stops being interesting once it has crossed. The mapping works one
value at a time, remembers nothing, and is the only thing between the row and your object.

*It is not an ORM. It is a **ROME** — a Relational-Object Mapping Engine, because all rows lead to ROME.*

## Why speak the protocol rather than wrap a driver

The previous generation of this project sat on pgjdbc, and much of its type handling was there to work around
what that cost: composites arriving over the text protocol with their attributes' OIDs already gone, and a type
map that could only be registered per physical connection, which is a difficult thing to arrange behind a pool.
Both come from the same place: the layer that knows the types is not the layer you can reach. Wrapping a driver
means inheriting its model of what a value is, and then building the model you wanted on top of a lossy version
of it.

Speaking the wire protocol directly removes the intermediary rather than working around it. Values arrive in
binary, a composite with the OID of every attribute inside it, so a decoded value knows what it is without
anything being declared twice.

The placeholder comes with it. Named parameters go out as `$1`, which is PostgreSQL's own, where over JDBC they
would go out as `?`. That is also a `jsonb` operator, as are `?|` and `?&`, and pgjdbc has them written `??` to
tell them apart. Here `?` is never a placeholder, so those operators need no escaping rule.

## One standard, no fallback

The driver requires PostgreSQL 18 and refuses anything older at login, so PostgreSQL 17 fails to connect
rather than half-working.

What is needed from 18 is one thing: `search_path` became a reported parameter there. The server announces it
in `ParameterStatus` and re-announces it when it moves, so the driver knows the live search path at all times
without asking, including after a hand-written `SET search_path` mid-session. Unqualified type names resolve
against that. On 17 the driver would have to query for it and would still not know when it changed underneath.
So what is checked, once the login is through, is what the server says about itself: a `server_version` of 18
or above, and a `search_path` reported at all.

The wire protocol is a separate question, and it is worth being straight about how it was answered, because it
was answered the other way first. The driver asks for v3.2, which arrived in 18, and for a while it refused any
server that offered less — demanding the protocol looked like a cheap and exact way of demanding the server, one
check at the handshake. It was cheap, but it was not exact. Nothing here needs v3.2 as such — its headline change
is a longer cancel key, kept at whatever length it arrives — while what sits between an application and its
database often speaks v3.0 and nothing newer. Most poolers do, because libpq still asks for v3.0 by default and
nothing has made them move. Refusing v3.0 refused PostgreSQL 18 behind every one of them. So a v3.0 answer is
taken, and the server behind it is judged by what it reports.

The alternative that stays rejected is the one the heading is about — keeping a path for older *servers*. That
means resolving the search path a second way, and carrying that way forever, because a compatibility shim is
the hardest thing there is to remove. It would also be the path nobody develops against, which is where the
bugs would live. Refusing at login makes the failure immediate, total and legible, rather than a feature that
quietly does not work in production three months later. Taking v3.0 adds no such path: the driver does the same
thing over either version.

## One coordinate per decision

Six artifacts are published, dependencies run one way only, and no module assumes the ones above it exist.
Each coordinate is separate because taking it costs something that whoever does not need it should not pay:

| Artifact                    | Why it is not folded into the one below it                                                                                                                                               |
|:----------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `driver`                    | A complete stack on its own — sessions, queries, the type system, transactions, `LISTEN`/`NOTIFY`, `COPY`. Everything else is optional beside it.                                        |
| `client`                    | Builders, transaction plans, `dynamic_dto`. A data access layer is a set of opinions, and this is where they live rather than in the driver.                                             |
| `client-scanner`            | Walking a classpath correctly — jars in jars, the module path, a fat jar — needs ClassGraph. Registering types by hand needs no such dependency, and most applications register by hand. |
| `migrations`                | Beside the client, not under it: a migrator is a thing you may already have.                                                                                                             |
| `driver-spring-integration` | Pulls `spring-boot-starter-jdbc` and HikariCP. Nobody outside Spring should inherit that.                                                                                                |
| `pg-model`                  | Multiplatform, with no driver behind it — the annotations and serializers a class shared with a Kotlin/JS frontend needs, on a target where there is no JVM to put a driver on.          |

So the split is not packaging hygiene, it is a statement about what you are allowed to disagree with. Want the
driver and your own data access layer? Take the driver and write it. Already have a migrator, or register your
types by hand? Those coordinates are not in your build file. A library that ships as one artifact makes each of
those an argument to have with its maintainer; separate artifacts make them choices you make quietly.

One row of that table is less of a choice than it looks, and it is worth saying so rather than leaving it to be
discovered. **A JDBC migration tool cannot run on this driver.** The JDBC surface goes exactly as far as
connection pools need — `Connection`, `DataSource`, `Statement` — and `executeQuery` is not implemented,
because there is no `ResultSet` here and nothing internal wants one. So Flyway and Liquibase need pgjdbc,
which means a second driver and a second connection story alongside this one. `migrations` exists because of
that, not despite it. Running your migrations outside the application entirely — `psql` in a pipeline, a
container step — remains as available as it ever was.

## Why the type registry is global per database

Register a composite through one session and every other session pointing at that database sees it —
including sessions from a different pool. The registry is keyed by the servers and the database name, and it is
JVM-wide.

Most of what it holds belongs to the database anyway. A composite, an enum or a domain has one OID there, and
the codec that decodes it follows from the catalog; neither depends on the connection that reads it. Which
Kotlin class a value becomes is the application's, and it is registered in the same catalog because there is no
better place for it.

A connection is not one. A registration there would have to be repeated on every physical connection and undone
whenever one went back to a pool; Npgsql dropped its per-connection mappings for that cost. A `DataSource` is
where Npgsql moved them, and it works there because the data source is Npgsql's own pool, built in code and
handed out by dependency injection. Here it would mean refusing configuration by string, which is how a pool or
Spring Boot is usually set up: given a URL or a class name, they construct the driver themselves, and the
application never holds a driver object to register anything on. A notice handler runs into the same limit:
what a string can name is `noticeHandlerClassName`, and the handler itself, `noticeHandler`, reaches only
configuration done in code.

So the registry is keyed by what a URL can say. What that costs is that two parts of one application cannot
register different classes for the same type on one database. Registration is a startup step, done once, from
one thread, and everything after it is lock-free on the read path — the catalog is one immutable value, and a
registration replaces it whole.

What it means in practice is that registration is *not* a per-request tool, and the scoped alternative is a
query's own converters rather than a second global registry. See
[Scope: a session handle over global state](docs/driver/type-system.md#scope-a-session-handle-over-global-state).

## Why registration order is the override mechanism

Converters are consulted newest-first and the first one whose `canConvert` says yes takes the value. There is
no priority number, no `@Order`, and no way to remove a converter once registered — a later registration simply
shadows an earlier one.

The previous generation settled overrides by rule instead: a custom handler replaced the built-in one for its
type, a handler could be marked the default for its Kotlin class, and two custom handlers for one type, or two
defaults for one class, were refused at startup. Every override meant knowing those rules and which side of them
each handler sat on. A priority number would be no better: it needs an annotation to carry it, a rule for how it
ranks against a converter registered by hand, and renumbering whenever a new one goes between two others.
Registration order needs none of that. It is a total order nobody has to agree on in advance, visible in the one
place it matters, the block where you register.

Two consequences follow, and both are deliberate. A query's own converters are consulted entirely before the
session's, which is what makes
[per-query overrides](docs/client/queries.md#per-query-converters) possible without touching global state. And
the annotation scanner does **not** discover converters, because a classpath scan has no defined order and
would make priority differ between builds — see
[What It Does Not Scan](docs/client/scanner.md#what-it-does-not-scan).

## Why nothing is created behind your back

The library issues no DDL you did not ask for. `dynamic_dto` is a type you install — in a migration, which is
the reading this project prefers, or through an explicit `install()` call. No type is created on first use, and
no schema is inspected and quietly corrected. The one table the library creates is the migrator's history, when
you call `migrate()`.

The reason is that a library which writes to your schema has taken a decision that belongs to whoever owns the
migration history — and it takes it at a moment nobody is watching, on whichever connection happened to be
first, possibly in production, possibly concurrently with a deploy. The failure modes of that are not worth the
line of setup it saves.

What it costs is that every setup step is a call you make: `install()`, the scan, and the migrator, which is a
library you embed rather than a command that runs itself.

## Why the class asked for is enough to read but not to write

A row, a composite and a `ROW(...)` of keys and values read into the data class the call asks for, with nothing
registered. The source brings the names and each field's own type, the class brings the shape, and nothing is
left to say. A write is missing something: the parameter goes out declared as some type, and a class does not
say which. `registerAutoComposite` pairs the class with that type, and read the other way the same pairing names
the class where the call names none, which is how a composite read as `Any`, or as a value in a
`Map<String, Any?>`, comes back as that class.

Whether the class asked for can be trusted on a read depends on where the shape comes from:

| Source              | Its shape is set by                           |
|:--------------------|:----------------------------------------------|
| a row, a `ROW(...)` | the query that reads it                       |
| a composite         | the DDL, which every stored value obeys       |
| a `jsonb` column    | whatever wrote it, perhaps for an older class |

The first two are why a read needs no registration, and the third is why a `jsonb` value does not read into the
class asked for. A stored document has to say what it is itself, and
[`dynamic_dto`](docs/client/dynamic-dto.md) is that: its class comes from the `type_name` in the value, not from
the call. Where a class is wanted from a plain `jsonb` column anyway, read it as a `JsonElement` and decode it
with the application's own `Json`, which is one line. See
[Reading needs a class, writing needs a type](docs/driver/composites-reflection.md#reading-needs-a-class-writing-needs-a-type).

## Why blocking, and not coroutines-first

The terminal methods block the calling thread. No query anywhere in the API is `suspend`.

The one place coroutines do appear is `LISTEN`/`NOTIFY`, and it is the exception that shows the rule:
`NotificationManager` exposes its notifications as a `SharedFlow` and its listener loops as `suspend`
functions, because waiting for something the server will send *whenever it likes* is the one thing here that
genuinely has nothing to occupy a thread with. A query does — it has a connection out on loan for its whole
length.

The old argument for this was that JDBC is blocking and no library can change that. It does not apply here —
this driver *is* the protocol, and a suspending API over non-blocking I/O was genuinely on the table.

The argument that survives is different. **The concurrency limit is the server, not the threads.** A statement
runs in a backend process, one at a time per connection, and `max_connections` — 100 by default, three of them
held back for superusers — caps how many backends there are. A pool repeats that number on the client side, and
a pooler such as PgBouncer lets more clients connect than there are backends, but neither makes more of them:
the statements running at once are at most the backends the server has. A thousand coroutines are a thousand
tasks waiting for those, and no amount of suspension changes that number. What a `suspend` API buys is not
throughput but the ability to hold a great many waiting callers cheaply — and since JDK 21, a virtual thread does
that too, at the cost of a blocking call that reads exactly like the sequential code it is.

So the API stays blocking and honest about it, and `OctaviusDispatchers.Virtual` is shipped for launching that
work from a coroutine. The reader can see where the thread boundary is. See
[Concurrency](docs/driver/concurrency.md), which is also where the shape that actually matters is spelled out:
a session per coroutine, not one session shared by many.

None of which stops you having the call-site shape, and it is worth being clear that the library is not
withholding anything difficult — a suspending façade is a few lines in your own code:

```kotlin
suspend inline fun <reified T : Any> RunnableQuery<*>.awaitObjects(
    vararg params: Pair<String, Any?>
): List<T> = withContext(OctaviusDispatchers.Virtual) { fetchObjects<T>(*params) }
```

That it is this short is the argument, not a consolation. Shipped from here the same five lines would read as
a claim about concurrency that they do not make — `suspend` in a signature is taken to mean the call does not
occupy anything while it waits, and a query still holds a connection, with a backend behind it, for as long as
it waits. Written in your project
it is what it is: a thread boundary you placed, on a dispatcher you named.

The session a transaction runs on is bound to the thread, which is why work handed to another thread does not
inherit it — and why `SessionProvider` is a seam rather than a fixed `ThreadLocal`, for the frameworks that
decide that question differently.

## Why exceptions, and `DataResult` when you ask for it

Failures throw. `DataResult<T>` exists, is a sealed `Success`/`Failure`, and is opt-in — `asResult()` on a
builder, or a `dbResult { }` block around a stretch of code.

The previous generation had this the other way round: every operation returned a `DataResult`. The case for that
is real: the signature is honest, and failure is handled at the call site. What it costs is that *every* call is a
failure to handle, including those inside a transaction block that is going to roll back anyway, where the only
sensible response is to abort. That is ceremony at every step, and it makes nothing safer.

Throwing by default puts the ceremony where the decision is. An exception you actually intend to handle — a
`ConstraintViolationException` on a duplicate insert, a `ConcurrencyException` worth retrying — is caught where
you handle it, and everything else propagates to the boundary that logs it. Where you want the result shape, you
say so, and the same boundary applies either way.

What makes this work is that the exceptions are worth catching. They descend from one `OctaviusException`, the
class says what kind of failure it is and, where there are several, a `reason` enum says which one, `path` says
where in a nested structure it happened, and the query context travels with it — so
`catch (e: ConstraintViolationException)` is a precise thing to write and the message is worth reading when nothing
catches it. See [Exceptions](docs/driver/exceptions.md).

A failure of the driver's leaves Spring the same way: `OctaviusTemplate` and `OctaviusJdbcTransactionManager` raise
it as it is, with no `DataAccessException` around it. A single wrapper descending from `DataAccessException` would
bring none of Spring's taxonomy, being neither a `DuplicateKeyException` nor anything `Transient`, and every handler
would have to unwrap it. Mapping each failure onto the taxonomy would work, and buy nothing here: that hierarchy
keeps code independent of the data access technology, and `JdbcTemplate`, Spring Data and Hibernate do not run on
this driver. It would add a second way of catching one failure, the driver's class inside an `execute` block and
Spring's outside it, and some forty classes still put a `NOT NULL` and a `CHECK` in one
`DataIntegrityViolationException`. The `reason`, the `path` and the query context would stay only in the cause, one
`findOctaviusCause()` away, with little more than a message string on the Spring class itself. See
[Spring integration](docs/driver/spring-integration.md#exceptions).

## Why `@name` and not `:name`

Named parameters are `@name`, not the `:name` that Spring's `NamedParameterJdbcTemplate` made familiar.

`:` is already PostgreSQL's, in array slice syntax: `array[1:5]`, `array[:n]`, `array[m:]`. Under `:name` the two
collide in a way no parser can settle: `service_record[:tours]` is a slice up to the column `tours` and the
element at the parameter `tours`, and the statement does not say which. With `@` each reading has a spelling of
its own:

```sql
SELECT service_record[:tours]  FROM legionnaires;  -- a slice up to the column tours
SELECT service_record[@tours]  FROM legionnaires;  -- the element at the parameter tours
SELECT service_record[:@tours] FROM legionnaires;  -- a slice up to the parameter tours
```

`@` is PostgreSQL's too — the absolute-value operator, and part of `@>`, `<@`, `@@`, `@?` and a handful more —
so the two do meet. What matters is that wherever they meet, there is a spelling with one reading. A parameter
is an `@` with a name straight after it, and nothing else is: `@x` and `a <@b` are parameters here, while `@ x`
and `a <@ b` are PostgreSQL's absolute value and containment. Everything PostgreSQL says with `@` can be
written the second way — `@>`, `@@` and `@?` cannot be written any other way to begin with — so none of it is
out of reach, and no statement is left open to two readings.

## Dynamic queries are just strings

A filter that appears only when a parameter is present, a sort key chosen at runtime, a statement assembled from
parts: these are the cases a criteria API or a type-safe DSL exists for, and there is neither here.

Such a DSL is a second query language. It has to be learned on top of SQL, which every query still ends up as,
and a query written in it is debugged through the SQL it generates. It also covers only what has been mirrored
in Kotlin. PostgreSQL is extended inside the database: an extension brings its own types, functions and
operators, and `CREATE OPERATOR` adds one in a single statement. Each would have to be mirrored in the DSL before
a query could use it, by its maintainers or by you, or else dropped into it as a raw string, which is where this
library starts.

So a dynamic query is SQL text assembled in Kotlin. A
[`QueryFragment`](docs/client/queries.md#queryfragment) carries a piece of it with the parameters it names, and
the builders take the pieces as they are, so whatever the database understands can be written the day it
exists.

## Why the version number is not semver

The number says how far a change reaches. Semantic versioning asks it to say whether anything broke, and for a
Kotlin library the answer is yes in most releases.

A constant added to a public enum stops an exhaustive `when` from compiling, and so does a new subclass of a
sealed class. Taken strictly, semver makes each of those a major. Of the first ten releases after 1.0.0, eight
carried a `[BREAKING]` entry, a patch among them: eight majors under semver, in under seven weeks. A major that
frequent stops telling anyone anything.

It misses the other way too. Semver reads the API, and a library that queries pass through can change how all
of them run and leave its API exactly as it was. The previous generation took 7.0.0 for replacing the
`JdbcTemplate` that ran its queries with one of its own. Its API module did not change by a line, so by semver's
measure that was a minor at most, while every query an application ran went through new code.

So the two questions are carried apart. The number says how much runs differently, which is how much of an
application is worth testing again. The `[BREAKING]` entries in the changelog say what has to change in your
code, and they turn up in a minor or a patch as well as in a major. How each part of the number is decided is in
[Versioning](README.md#versioning).

## The line this deliberately does not cross

Things that would be possible, that a data access layer is usually expected to have, and that are not going to
happen here:

* **A query language.** No criteria API, no type-safe DSL over columns. The result is SQL either way, and the
  intermediate language is a thing to learn, debug and version.
* **A schema generator.** Classes do not create tables. The schema belongs to migrations, and a class that
  disagrees with it should fail loudly rather than reshape it.
* **A session, a cache, or an identity map.** Two queries that return the same row return two objects. Anything
  else means tracking, and tracking is the thing being avoided.
* **Lazy anything.** A row is decoded when it arrives; a value is converted when you ask for it. Nothing is
  fetched later, so nothing can fail later, in a frame that no longer knows why.
* **A full JDBC implementation.** The `Connection`, `DataSource` and `Statement` exist so that HikariCP and
  Spring recognise this as a driver and manage it as one. They are not there to run JDBC tooling, and
  `executeQuery` is unimplemented rather than emulated — half a `ResultSet` would be worse than none.

## When this is not the right tool

* **You need to support more than PostgreSQL.** Nothing here is portable and none of it tries to be.
* **You want a mapping layer to own the schema.** Entities-first development, generated DDL, migrations
  derived from classes — that is a coherent way to work, and it is the opposite of this one.
* **Your team wants to write no SQL.** That is a legitimate position and this library will make it worse, not
  better.
* **You are on PostgreSQL 17 or older.** The connection refuses it at login, on purpose.
