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
* [Why `Map<String, Any?>` and not data classes everywhere](#why-mapstring-any-and-not-data-classes-everywhere)
* [Why `jsonb` always maps to `JsonElement`](#why-jsonb-always-maps-to-jsonelement)
* [Why blocking, and not coroutines-first](#why-blocking-and-not-coroutines-first)
* [Why exceptions, and `DataResult` when you ask for it](#why-exceptions-and-dataresult-when-you-ask-for-it)
* [Why `@name` and not `:name`](#why-name-and-not-name)
* [Dynamic queries are just strings](#dynamic-queries-are-just-strings)
* [The line this deliberately does not cross](#the-line-this-deliberately-does-not-cross)
* [When this is not the right tool](#when-this-is-not-the-right-tool)

## SQL is not a problem to be solved

Most data access layers treat SQL as an implementation detail to be hidden — a thing the library generates so
you do not have to look at it. That trade buys you portability you will never use, and sells you the one
language in your stack that is declarative, decades-stable, and understood by every profiler, every DBA and
every `EXPLAIN` you will ever run.

Here the SQL you write is the SQL that runs. Nothing rewrites it, reorders it, or adds a query you did not ask
for. When a query is slow you paste it into `psql` and it behaves the same way, because it *is* the same query.

The builders exist for the parts that are genuinely tedious — placeholders, `ON CONFLICT`, a `WHERE` that
disappears when its condition is null — and every one of them is a thin shell over the text. `rawQuery` is not
an escape hatch for when the abstraction fails; it is the same road with fewer signposts.

## PostgreSQL is not "just a database"

Portability across engines is the assumption behind most of what a data access layer gives up. Drop it, and
what is left is a very large amount of PostgreSQL that was previously unreachable: composite types, arrays of
them, ranges and multiranges, enums, domains, `jsonb`, `LISTEN`/`NOTIFY`, `COPY`, large objects, procedures
with `INOUT` parameters.

These are not exotica. A composite type is what a value object looks like in the schema, and an array of them
is a 1:N relation that does not need a second query. A library that abstracts over three engines cannot map
them, because two of the three have no such thing.

The cost is stated plainly: this runs against PostgreSQL and nothing else, ever.

## The ORM tax

An ORM buys you a mapping between rows and objects. What it charges for it is a second model of your data — one
that has opinions about identity, lifetime and change — sitting between you and the first.

That model is what produces the N+1 query, the lazy-loading exception thrown outside the session, the
`flush()` that reorders your statements, and the afternoon spent reading generated SQL to find out what the
framework thought you meant. None of those are bugs in any particular ORM. They are what a stateful mapping
layer costs.

There is no session here, nothing is tracked, nothing is lazy, and nothing is dirty-checked. A `data class` is
a typed container for what came back and stops being interesting the moment you hold it. The mapping is
reflective and one-directional, per query, and it is the only thing standing between the row and your object.

*It is not an ORM. It is a **ROME** — a Relational-Object Mapping Engine, because all queries lead to ROME.*

## Why speak the protocol rather than wrap a driver

The previous generation of this project sat on pgjdbc, and most of its complexity was there to work around
what that cost: composites arriving over the text protocol with their per-field OIDs already gone, enums the
library had to be taught about separately, a stateful `ResultSet`, no named parameters, and a type map that
could only be registered per physical connection — which is a difficult thing to arrange behind a pool.

Every one of those is a consequence of the same thing: the layer that knows the types is not the layer you can
reach. Wrapping a driver means inheriting its model of what a value is, and then building the model you wanted
on top of a lossy version of it.

Speaking wire protocol v3.2 directly removes the intermediary rather than working around it. Values arrive in
binary with their OIDs in the row description, so a decoded value knows what it is without anything being
declared twice. Named parameters are rewritten to `$1` on the way out, so there is no second escaping rule to
remember. And the pieces that used to be workarounds are simply gone — see
[where each of them went](README.md#relation-to-octavius-database).

## One standard, no fallback

The driver asks for wire protocol v3.2 and refuses to continue if the server offers less, so PostgreSQL 17
fails the handshake rather than half-working.

It is worth being straight about which way that dependency runs, because the protocol version is the
*enforcement* and not the reason. Nothing here needs v3.2 as such — its headline change is a longer cancel
key, and it is backwards compatible. What is needed is **PostgreSQL 18**, because that is where `search_path`
became a reported parameter: the server announces it in `ParameterStatus` and re-announces it when it moves, so
the driver knows the live search path at all times without asking, including after a hand-written
`SET search_path` mid-session. Unqualified type names resolve against that. On 17 the driver would have to
query for it and would still not know when it changed underneath.

Since v3.2 arrived in 18, demanding the protocol is a cheap and exact way of demanding the server — one check,
at the handshake, before anything else can go wrong.

The alternative — negotiate down and keep a path for older servers — means resolving the search path a second
way, and carrying that way forever, because a compatibility shim is the hardest thing there is to remove. It
would also be the path nobody develops against, which is where the bugs would live. Refusing at the handshake
makes the failure immediate, total and legible, rather than a feature that quietly does not work in production
three months later.

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
including sessions from a different pool. The registry is keyed by host, port and database name, and it is
JVM-wide.

The obvious alternative is a registry per session, which is wrong for a reason that is easy to miss: **the
catalog is a property of the database, not of your connection to it.** A composite type has one OID in that
database. Reading it into two different Kotlin classes depending on which pool the row came through is not
flexibility, it is a bug waiting for the day two code paths disagree.

Making it global also makes the cost honest. Registration is a startup step, done once, from one thread, and
everything after it is lock-free on the read path — the catalog is one immutable value, and a registration
replaces it whole. A per-session registry would have to be built per session, which is a cost paid on every
connection for a benefit nobody asked for.

What it means in practice is that registration is *not* a per-request tool, and the scoped alternative is a
query's own converters rather than a second global registry. See
[Scope: a session handle over global state](docs/driver/type-system.md#scope-a-session-handle-over-global-state).

## Why registration order is the override mechanism

Converters are consulted newest-first and the first one whose `canConvert` says yes takes the value. There is
no priority number, no `@Order`, and no way to remove a converter once registered — a later registration simply
shadows an earlier one.

The alternative is explicit priorities, and it fails in a specific way: a number is meaningful only against
every other number in the system, so the moment two libraries both register at priority 100 you are reading
someone else's source to find out what happens. Registration order is a total ordering that nobody has to
agree on in advance, and it is visible in the one place it matters — the block where you register.

Two consequences follow, and both are deliberate. A query's own converters are consulted entirely before the
session's, which is what makes
[per-query overrides](docs/client/queries.md#per-query-converters) possible without touching global state. And
the annotation scanner does **not** discover converters, because a classpath scan has no defined order and
would make priority differ between builds — see
[What It Does Not Scan](docs/client/scanner.md#what-it-does-not-scan).

## Why nothing is created behind your back

The library issues no DDL you did not ask for. `dynamic_dto` is a type you install — in a migration, which is
the reading this project prefers, or through an explicit `install()` call. No type is created on first use, no
table appears to hold metadata, and no schema is inspected and quietly corrected.

The reason is that a library which writes to your schema has taken a decision that belongs to whoever owns the
migration history — and it takes it at a moment nobody is watching, on whichever connection happened to be
first, possibly in production, possibly concurrently with a deploy. The failure modes of that are not worth the
line of setup it saves.

It has a cost, and the cost is real: there is no moment that belongs to the library. You hand it a
`DataSource` you built, `install()` is a call you make, the scan runs when you run it, and the migrator is a
library you embed rather than a command you run. Nothing is left where a framework would print its banner.
That is the same property, seen from the other side.

## Why `Map<String, Any?>` and not data classes everywhere

A `data class` is the right answer when the shape is known and stable. It is the wrong answer for a report
assembled at runtime, an admin tool that renders whatever it is given, or a projection that exists for one
query — and defining a class per projection is how a codebase ends up with forty of them, each used once.

So every row is readable as a map without any registration at all, and a composite too. That is not a fallback
for when mapping fails; it is a first-class way to read, and it is why
[`compositesAsMaps()`](docs/driver/composites-reflection.md#reading-them-all-as-maps) exists to collapse a
whole subtree for one query without touching what those classes are registered as anywhere else.

Where a map is not enough, `toDataObject<T>()` maps one into a class by the same rules, with no database in the
picture — so the choice between the two is made at the call site rather than at the schema.

The furthest version of this is that the shape need not exist anywhere but in the query. An anonymous
`ROW(...)` comes back as a `PgRecord` and reads as a map, keeping each field's own type — so a projection
assembled in SQL, nested arrays and all, is a `Map<String, Any?>` on this side with a `LocalDate` still a
`LocalDate` in it. No type to declare, no class to write, and nothing registered: the query decided the shape
and the driver carried it. See
[the raw forms](docs/driver/composites-reflection.md#the-raw-forms-pgcomposite-and-pgrecord).

## Why `jsonb` always maps to `JsonElement`

A `jsonb` column could deserialize straight into your class. It does not: it produces a `JsonElement`, and
turning that into a class is a call you make.

The reason is that `jsonb` is the one column type whose contents the schema does not describe. Every other
column has a type the catalog knows, and the driver maps it because it can be right. A `jsonb` column is a
document whose shape is a convention held somewhere in your application, which the database has never had
explained to it — so a library that guesses the target class is guessing, and it will guess confidently on the
row where the convention drifted.

Handing back a `JsonElement` puts the decoding where the convention lives, with your `Json` and your
serializers.

`dynamic_dto` is what happens when you want it resolved automatically anyway, and it is fairer to call it what
it is: a **workaround for the same gap**, not a cleaner answer to it. Since a `jsonb` payload cannot say what
class it holds, the type is written down beside it — a discriminator in one attribute, the payload in the
other — so the resolution is reading a name that was stored rather than inferring one that was not. It is a
convention like any other; the only difference is that it lives in the row instead of in your head, and the
registry checks it on the way in. That is the whole of the improvement, and it is worth having, but it is not
the schema having learned anything.

## Why blocking, and not coroutines-first

The terminal methods block the calling thread. No query anywhere in the API is `suspend`.

The one place coroutines do appear is `LISTEN`/`NOTIFY`, and it is the exception that shows the rule:
`NotificationManager` exposes its notifications as a `SharedFlow` and its listener loops as `suspend`
functions, because waiting for something the server will send *whenever it likes* is the one thing here that
genuinely has nothing to occupy a thread with. A query does — it has a connection out on loan for its whole
length.

The old argument for this was that JDBC is blocking and no library can change that. It does not apply here —
this driver *is* the protocol, and a suspending API over non-blocking I/O was genuinely on the table.

The argument that survives is different. **The concurrency limit is the connection pool, not the threads.** A
thousand coroutines against a ten-connection pool are a thousand tasks waiting for ten wires, and no amount of
suspension changes that number. What a `suspend` API buys is not throughput but the ability to hold a great
many waiting callers cheaply — and since JDK 21, a virtual thread does that too, at the cost of a blocking call
that reads exactly like the sequential code it is.

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
occupy anything while it waits, and against a fixed pool of connections it still does. Written in your project
it is what it is: a thread boundary you placed, on a dispatcher you named.

The session a transaction runs on is bound to the thread, which is why work handed to another thread does not
inherit it — and why `SessionProvider` is a seam rather than a fixed `ThreadLocal`, for the frameworks that
decide that question differently.

## Why exceptions, and `DataResult` when you ask for it

Failures throw. `DataResult<T>` exists, is a sealed `Success`/`Failure`, and is opt-in — `asResult()` on a
builder, or a `dbResult { }` block around a stretch of code.

The previous generation had this the other way round: every operation returned a `DataResult`. The case for
that is real — the signature is honest, and failure is handled at the call site rather than somewhere up the
stack. What it costs is that *every* call is a failure to handle, including the ninety percent that are inside
a transaction block which is going to roll back anyway. Threading a result type through code whose only
sensible response is to abort adds ceremony at every step and does not make anything safer.

Throwing by default puts the ceremony where the decision is. A `ConstraintViolationException` you actually
intend to handle — a duplicate on insert, a serialization failure worth retrying — is caught where you handle
it, and everything else propagates to the boundary that logs it. Where you want the result shape, you say so,
and the same boundary applies either way.

What makes this work is that the exceptions are worth catching. They descend from one `OctaviusException`, the
class says what kind of failure it is and a `reason` enum says which one, `path` says where in a nested
structure it happened, and the query context travels with it — so `catch (e: ConstraintViolationException)`
is a precise thing to write and the message is worth reading when nothing catches it. See
[Exceptions](docs/driver/exceptions.md).

## Why `@name` and not `:name`

Named parameters are `@name`, not the `:name` that Spring's `NamedParameterJdbcTemplate` made familiar.

`:` is already PostgreSQL's, in array slice syntax: `array[1:5]`, `array[:n]`, `array[m:]`. That is a genuine
ambiguity rather than a theoretical one — tooling cannot reliably tell `:name` inside brackets from a slice
bound, and under `:param` you simply cannot use a parameter *as* a slice bound:

```sql
-- :param — ambiguous inside brackets, and the index cannot be one
SELECT service_record[:index] FROM legionnaires WHERE name = :name;

-- @param — unambiguous, and it can
SELECT service_record[@index] FROM legionnaires WHERE name = @name;
```

`@` is PostgreSQL's too — the absolute-value operator, and part of `@>`, `<@`, `@@`, `@?` and a handful more —
so the two do meet. What matters is that wherever they meet, there is a spelling with one reading. A parameter
is an `@` with a name straight after it, and nothing else is: `@x` and `a <@b` are parameters here, while `@ x`
and `a <@ b` are PostgreSQL's absolute value and containment. Everything PostgreSQL says with `@` can be
written the second way — `@>`, `@@` and `@?` cannot be written any other way to begin with — so none of it is
out of reach, and no statement is left open to two readings.

It also means `?` is never a placeholder here, so the `jsonb` operators that use it — `?`, `?|`, `?&` — need
no escaping rule.

## Dynamic queries are just strings

A filter that appears only when a parameter is present, a sort key chosen at runtime, a `WHERE` assembled from
four optional conditions — these are the cases a criteria API exists for, and the reason to not have one is
that a criteria API is a second query language that is worse at the job.

`QueryFragment` is the whole of what is offered: a piece of SQL text with its parameters attached, composable
with `join`, and absent when its condition is null. Clauses that receive null disappear rather than producing
`WHERE true`. See [`QueryFragment`](docs/client/queries.md#queryfragment).

The line worth being careful about is that a *value* is never part of the statement — the builders write the
placeholder themselves — but a *name* has nowhere else to go, so a column or table name taken from outside is
SQL text and has to be treated as such. That is stated where it can be acted on, in
[A name that comes from outside](docs/client/queries.md#a-name-that-comes-from-outside).

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
* **Guessing a type nobody declared.** Where the destination's type is known it decides; where it is not, the
  ambiguity is settled by a policy you chose or refused by name. It is never resolved by picking the likeliest.
* **A full JDBC implementation.** The `Connection`, `DataSource` and `Statement` exist so that HikariCP and
  Spring recognise this as a driver and manage it as one. They are not there to run JDBC tooling, and
  `executeQuery` is unimplemented rather than emulated — half a `ResultSet` would be worse than none.

## When this is not the right tool

* **You need to support more than PostgreSQL.** Nothing here is portable and none of it tries to be.
* **You want a mapping layer to own the schema.** Entities-first development, generated DDL, migrations
  derived from classes — that is a coherent way to work, and it is the opposite of this one.
* **Your team wants to write no SQL.** That is a legitimate position and this library will make it worse, not
  better.
* **You are on PostgreSQL 17 or older.** The handshake refuses it, on purpose.
