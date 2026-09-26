## Version 2.2.0 (v2.2.0)

### Project

#### Changed

- **Every integration test class starts from an empty database.** `test-support`, a module nothing publishes,
  names the test database once and drops every schema and the driver's type catalog before each class, so
  nothing one class creates or registers reaches the next. Test tasks of different modules take turns on it.

### Driver

#### Added

- **`TypeManager.attach(type) { }` keeps a value for a layer built on the driver in the database's catalog, and
  `TypeCatalog.attachment(type)` reads it back.** One per database, like the driver's own registrations:
  carried across a reload, dropped by `removeCatalog`, pinned for an execution.

### Client

#### Fixed

- **`dynamic_dto` names belong to the database, as the documentation said, rather than to each client.** Once a
  second client on the same database registered a class, reads as `Any` or a supertype and writes where
  `dynamic_dto` was declared failed for the classes only the first one knew. **A class is registered once per
  database now**, and written on the strategy and read with the `Json` of the client that registered it,
  whichever client runs the query.

- **One class under two names is refused, as the documentation said, and so is a class registered again on
  another `DynamicWriteStrategy`.** The same class under the same name again changes nothing.

- **Reading a `dynamic_dto` as `DynamicDto` no longer needs its name registered.**

## Version 2.1.2 (v2.1.2)

### Driver

#### Fixed

- **Rows keep the query's own converters as their terminal found them.** 2.0.0 said a converter registered on
  a query after a terminal returned does not reach the rows it returned, and that held only for a query that
  had none of its own. Each terminal copies the query's converters now, so the rows answer as they did
  when they came back, and the next terminal is the one that sees the new converter.

## Version 2.1.1 (v2.1.1)

### Driver

#### Fixed

- **A `RESTRICT` foreign key refusing a delete or a key change reports `FOREIGN_KEY_VIOLATION`, and
  `RESTRICT_VIOLATION` is gone.** An application handles that refusal the same way whichever action the key
  declares, and `sqlState` still tells them apart: `23001` for `RESTRICT`, `23503` for `NO ACTION`. **Code
  naming `RESTRICT_VIOLATION` no longer compiles; `FOREIGN_KEY_VIOLATION` replaces it.**

## Version 2.1.0 (v2.1.0)

### Driver

#### Changed

- **A `RESTRICT` foreign key refusing a delete or a key change reports `RESTRICT_VIOLATION` rather than
  `UNKNOWN`.** PostgreSQL 18 raises `23001` there. A `NO ACTION` key still raises `23503`, which stays
  `FOREIGN_KEY_VIOLATION`. **This adds a constant to a public enum: an exhaustive `when` over
  `ConstraintViolationExceptionReason` with no `else` needs a branch for it.**

## Version 2.0.0 (v2.0.0) - Tabularium

### Driver

#### Added

- **`TypeManager.detached()` returns a `TypeLookup` on the same database with no session behind it**, for
  something that outlives the session it was reached through and only wants to read registrations. It has no
  search path, so `resolveOid` on it resolves only fully qualified names.

- **A registered codec that is bound to nothing says so, at `warn`.** A codec keyed on an OID stops being
  reached the moment that type is dropped and recreated, and one keyed on a name the catalog no longer has
  never was - in both cases the column comes back through the driver's own codec instead, in a different shape
  and without an error. The catalog load and every `reloadTypes()` now name the codec that ended up
  unreachable.

#### Changed

- **A query pins one catalog for the whole of an execution.** Everything a terminal touches, comes from one
  immutable `TypeCatalog`, read once when the terminal starts. **Two behaviours change with it**: a converter
  registered on a query object *after* a terminal returned no longer applies to the rows it returned (it
  applies from the next terminal), and a `registerCodec(...)` between `createNativeQuery(...)` and the
  `fetch*` call is now picked up, where it used to be missed.

- **`ConverterRegistry`, `ResultConverterRegistry` and `ParameterConverterRegistry` are gone, replaced by
  `TypeCatalog`.** **`typeManager.converterRegistry` becomes `typeManager.catalog`**, carrying
  `registeredComposites`, `compositeClassByName` and `registeredEnums` unchanged, alongside `dictionary` and
  `codecs`. A converter reading registrations through the context reads the pinned catalog:
  `context.types.catalog.registeredComposites`. The `TypeException` message that named the registry names the
  catalog now.

- **`Query.resultConverterRegistry` and `Query.parameterConverterRegistry` are gone.** A query keeps its own
  converters in a list of its own rather than a registry chained to the session's, and
  `registerResultConverter(…)` / `registerParameterConverter(…)` - the documented way to reach them - are
  unchanged, precedence included.

- **A converter is handed `TypeLookup`, not `TypeManager`.** `DeserializationContext` and
  `SerializationContext` now carry the reading half of the type system - `catalog`, `dictionary`, `codecs`,
  `containers`, `resolveOid` - and nothing that registers.

- **A conversion context reads `context.types`, and the two dictionaries are `dictionary` and `codecs`.**
  `TypeLookup.typeDictionary` and `codecDictionary` lose the stutter and take the names `TypeCatalog` already
  uses for the same two things, and `TypeManager` follows.

- **`GlobalTypeRegistry` is `GlobalCatalogStore`, and `removeRegistry(url)` is `removeCatalog(url)`.** It
  holds one catalog per database, keyed by `DatabaseKey` - what `RegistryKey` was called. **The logger
  category is `io.github.octaviusframework.driver.registry.GlobalCatalogStore` now** - a logback rule naming
  the old one stops matching the catalog load.

- **The `fetch*` and `forEach*` families are `inline` only for `typeOf<T>()` now.** Each one is a single call
  into the driver rather than a body inlined into the caller, so `sql`, `queryExecutor`, `resultMapper`,
  `parameterSerializer` and the context helpers are no longer `@PublishedApi` and the execution path is no
  longer part of the binary interface. The `block` a `forEach*` takes is `noinline`, which changes nothing at
  a call site - `crossinline` already refused a non-local return.

- **`QueryExecutor`, `ResultMapper` and `ParameterSerializer` are `internal`.** They were public only because
  the terminals were inlined through them. `Row`, `RowMetadata` and the converter SPI - `ResultConverter`,
  `ParameterConverter`, `DeserializationContext`, `SerializationContext` - are unchanged and remain the way
  into the conversion chain.

## Version 1.1.0 (v1.1.0)

### Driver

#### Changed

- **A data source with no connection free reports `CONNECTION_UNAVAILABLE` rather than `CONNECTION_ERROR`.**
  Both are `InitializationException`. It is read off JDBC's `SQLTransientConnectionException`. **This adds a constant to
  a public enum: an exhaustive `when` over `InitializationExceptionReason` with no `else` needs a branch for it.**

- **The driver's performance page is re-measured.** Performance pages name Kotlin and
  `kotlin-reflect` in their environment notes now.

#### Fixed

- **`commit()` refuses a transaction an earlier error aborted, instead of reporting a commit that never
  happened.** PostgreSQL answers a `COMMIT` there with a `ROLLBACK` and no error, and the driver took that for
  success. It raises `InvalidOperationException(COMMIT_OF_FAILED_TRANSACTION)` now without sending anything, 
  and so does setting `autoCommit` back to `true`. **A caller that swallowed a failure inside a transaction and returned
  normally now gets an exception at the commit**, where it used to get nothing - and nothing saved either way.

- **A record read as a `Map` converts its keys instead of stringifying them.** the key half of each pair went through 
  `toString()` where the value half went through the converter chain. Three shapes lost a field to it - a `NULL` key, 
  a key equal to one already read, and an `int4` `1` beside a `text` `'1'` - each collapsing two fields into one entry, 
  and `get<Map<Int, String>>` returned `String` keys that failed as a `ClassCastException` far from the conversion. 
  Both halves go through the chain now, and the three are refused like any other conversion. **`get<Map<String, Any?>>` 
  against a record with non-text keys now fails with `NO_CONVERTER_FOUND`** rather than stringifying them; 
  name the key type the record carries.

### Client

#### Added

- **[A performance page of its own](docs/client/performance.md).** What the layer costs over calling the
  driver by hand, how the stack lands beside Spring's `NamedParameterJdbcTemplate` and JDBI, and whether a
  column should hold a composite or a `dynamic_dto`.

- **The client says what it logs, which is nothing.**

- **The Quickstart stops sending `largeObjects` and `notifications` through `db.execute { }`.**

- **Transaction Plans says what a plan has that a block has not, and what a
  transaction around one does to it.**

- **`dynamic_dto` says where else it goes** - three shapes that
  were in octavius-database's documentation and worked here, unmentioned and untested. `DynamicDtoTest` covers
  them now.

#### Changed

- **The `dynamic_dto` page's "cheaper in every direction" has figures under it.**

- **`client` stops declaring `kotlin-logging` and `slf4j-api`.** It writes no log lines of its own and never
  used either; both arrive through the driver regardless, so nothing changes on a runtime classpath.

#### Fixed

- **`DefaultSessionProvider` binds the session it borrows, so nested client calls share it instead of
  borrowing one each.** `execute` bound nothing outside a transaction, so a client call made from inside
  another took a connection of its own. **A query made from inside a `forEach*` block or a
  `ResultConverter` now raises `InvalidOperationException(CONNECTION_BUSY)` whether or not a transaction is
  open**, where outside one it used to take a second connection quietly.

- **Plans merged the wrong way round are refused before they run.** A step can hold another plan's handle and
  `addPlan` appends, so merging the plan that uses a handle ahead of the plan that produces it put the step
  before the result it needs. It failed partway through the transaction where the documentation said such a
  plan could not be written at all; it is refused before the transaction opens now, still as
  `INVALID_ARGUMENT` and naming both steps.

- **[The Combination That Misleads](docs/client/transactions-failures.md#the-combination-that-misleads) said a
  commit went through that never did.** For a failure the server raised, the `transaction { }` around a
  `dbResult` did not commit over it - the server had rolled the work back. The page and the KDoc of
  `transactionResult` and `dbResult` say what happens now that the driver refuses that commit.

### Migrations

#### Added

- **[Logging](docs/migrations/logging.md) is a page, as the driver's is.**

- **[When a Run Is Refused](docs/migrations/exceptions.md) covers `MigrationException`.** Seven reasons were
  in the enum and two were named anywhere. They are laid out in the order a run can reach them, so what has
  not happened yet is visible.

- **The Quickstart says to call `reloadTypes()` after a run.** The driver says it in three places;
  migrations, which is the thing that actually runs DDL after the pool is up, said it in none, and the
  `TypeException(TYPE_NOT_FOUND)` that follows a migration creating a type says nothing about migrations.
  With it, where it sits in a startup sequence, and a short Spring section.

#### Fixed

- **A migration's label has no `V` in it, and the KDoc of both `label` properties now agrees.** They gave
  `V2 add indexes` as the example where the code renders `2 add indexes`, the prefix being a marker in the
  file name rather than part of the version.

## Version 1.0.0 (v1.0.0) - Renovatio Imperii

### Project

#### Added

- **Octavius is not an ORM, it is a ROME.** A Relational-Object Mapping Engine, because all queries lead to
  ROME. The line comes over from `octavius-database` — under the badges in the README, above a new
  **Philosophy** section stating the five principles that hold here. It stays in the old repository too.
- **Every artifact in the README carries the Roman office it does the work of** — `driver` the road, `client`
  the praetor, `client-scanner` the census, `migrations` the surveyor, `pg-model` the codex and
  `driver-spring-integration` the treaty.
- **[Design Philosophy](DESIGN_PHILOSOPHY.md).** What was decided and what was rejected: why the protocol is
  spoken rather than a driver wrapped, why the type registry is global per database, why registration order
  is the only override mechanism there is, why nothing is created behind your back, and why every query
  blocks while `LISTEN`/`NOTIFY` is the one part of the API that suspends.
- **`pg-model`'s tests now run in CI.** It is the multiplatform module and has no `test` task, so
  `./gradlew test` built its jar and ran none of them. The step is `./gradlew test :pg-model:allTests` now,
  covering both targets it publishes.

#### Fixed

- **The API reference no longer carries `hikari-integration-tests`.** It is not published and its `main` source
  set is empty, but it was in the Dokka aggregation all the same, so the generated site listed an empty module
  beside the six real ones. `benchmarks` was already out; this one was the leftover.

### Driver

#### Added

- **`Row` answers two questions it used to make you work around.** `hasColumn(name)` is what
  `getColumnIndex` answered by raising, for a result whose shape is not fixed. `getRaw(name)` is the
  counterpart of `getRaw(index)`, which stood alone.
- **`MappingException` gains `BLOCK_FAILED`.** An exception thrown by your own block inside `forEachRow`,
  `forEachObject` or `forEachField` used to arrive as `CONVERSION_ERROR`. It is still wrapped, and what the
  block threw is still the `cause`. **Anything matching on `CONVERSION_ERROR` to find these has to match on
  the new one.**
- **`required` and `nested` take the terms the transaction runs under.** `isolation`, `readOnly`,
  `statementTimeout` and `transactionTimeout`, all optional and all scoped to that transaction, joined into
  one statement sent right after the `BEGIN`. Ask for none and nothing is sent at all.
  `TransactionIsolationLevel` gains `sqlName`.
- **Which leaves nothing on the connection to clean up.** The session properties
  `transactionIsolationLevel` and `readOnly` outlive a transaction and a pool has to restore them; these
  parameters end with it. The cost is that those two no longer describe a transaction opened this way — a
  `SERIALIZABLE` block runs while `transactionIsolationLevel` still answers what the *session* is on.
- **A joined transaction honours none of them, and says which it dropped.** Isolation and read-only cannot
  change once a transaction has begun, and `SET LOCAL` would bind a timeout to somebody else's. Same on
  `nested`'s savepoint path. It warns rather than raising.
- **A column reads as `row["cognomen"]`.** The reified `get` is now an `operator`, by name and by index
  alike, so the expected type fixes `T` and its nullability with it: `val name: String = row["cognomen"]`
  refuses a `NULL`. `get<String>("cognomen")` is unchanged.
- **And so do the containers underneath it.** `PgComposite`, `PgArray` and `PgRecord` read as
  `composite["cognomen"]`, `array[0]` and `record[0]` now — `PgMultirange` already did.
  Purely additive: every `get<T>(…)` call goes on working unchanged.
- **`compositesAsMaps()` reads one query's composites as maps, whatever they are registered as.** Registered
  on a query, discarded with it, and it collapses the whole subtree. `compositesAsMaps(name, schema)` names
  one type, the `Collection<QualifiedName>` overload several. Naming a class still answers with the class.

#### Fixed

- **A mapping failure names the column whichever route reached it.** `fetchObjects` named it and `Row.get`
  did not, so one route read `PATH: city` and the other `PATH: residence -> city`. Container accessors name
  the attribute, element or bound too, so a hand-written converter no longer stops one level short. A lookup
  that finds nothing still adds no segment. See [Exceptions](docs/driver/exceptions.md).

#### Changed

- **Five SSL properties are camelCase like everything beside them.** `sslmode`, `sslrootcert`, `sslcert`,
  `sslkey` and `sslpassword`- on `OctaviusProperties` and on `OctaviusDataSource` alike. They were the only lowercase names. 
  **A pool setting bean properties by name is the one caller that has to move** — `addDataSourceProperty` resolves a name to a setter, so
  HikariCP wants `sslPassword` where it took `sslpassword`.
- **`LargeObjectMode` and `SeekWhence` are enums.**
- **`session.getSearchPath()` is `session.searchPath`.**
- **`DataSource.getOctaviusSession(username, pass)` calls its second parameter `password`.** Every other
  signature that takes one does. Only a caller passing it by name is affected.
- **`path` is on `OctaviusException` rather than on `MappingException`.** It sits beside `queryContext`,
  both being things a layer can add without replacing the exception. `MappingException.path` reads as it
  did. **The rendering moved from `Path: a -> b` inside `getDetailedMessage()` to `PATH: a -> b` in the
  exception's own frame**, so anything matching the old line has to be matched again.
- **`TransactionIsolationLevel.fromJdbcValue` raises what its sibling raises.** It documented
  `IllegalArgumentException` and threw `NoSuchElementException`. Now `InvalidOperationException`
  (`INVALID_ARGUMENT`), which `setTransactionIsolation` already raised for the same value going the other
  way.
- **Two KDocs that were wrong are right.** `TransactionState.fromChar` promised `IllegalArgumentException`
  where `error()` raises `IllegalStateException`; `ParameterConverter.getDefaultTypeName` linked
  `[expectedOid]`, which is a parameter of `convert`. Dokka now generates the repository with no unresolved
  links at all.
- **The type dictionary names itself when it loads.** `Loaded 47 types for localhost:5432/mydb in 12ms`
  becomes `ROME (Relational-Object Mapping Engine) open for localhost:5432/mydb - 47 types read in 12ms`,
  and the reload beside it says `ROME rebuilt`. Both stay at INFO, but **anything matching the old text has
  to be matched again**.

### Client

#### Added

- **[Queries](docs/client/queries.md#a-name-that-comes-from-outside) says what passing SQL through unread
  costs.** The builders write the placeholder for a value themselves, so a value is never part of the
  statement — but a name has nowhere else to go, and `orderBy`, `from`, `insertInto(table)` and the keys of
  `values(map)` are all SQL text. Documentation only; no signature changed.

#### Changed

- **`SelectQuery.offset` takes `null`, as `limit` already did.** A page bound that may or may not be there
  needed a `?.let` on one of the two and not on the other. `null` leaves the clause out, which is what `0`
  did and still does.
- **Every failure a step of a `TransactionPlan` raises names that step.** Resolving its parameters already
  did; running its statement and mapping its result did not, so a unique violation on the fourth of twenty
  identical inserts said nothing about which. The executor records `step N` on the `path`, leaving the
  exception otherwise untouched: `PATH: step 1 -> tribute`.
- **`DefaultSessionProvider` opens a transaction in two round trips instead of five.** It set isolation and
  read-only as session characteristics before the `BEGIN` and each timeout as its own `SET LOCAL` after it,
  with two more on the way back as HikariCP restored them. It is the driver's `required(...)` now.
  `applyTimeouts` and `warnIfSettingsCannotBeHonoured` are gone from the class.

#### Fixed

- **A `dynamic_dto` no longer goes where a composite was declared.** Under `PREFER_DYNAMIC_DTO` a class
  registered both ways went out as one into composite attributes and array elements too, which the server
  refuses as `42804`. A known destination type decides now — which also makes `value.withPgType("honour")`
  the counterpart of `toDynamicDto`. See
  [Writing](docs/client/dynamic-dto.md#what-the-modes-do-not-reach).

### Migrations

#### Added

- **Placeholders in `.sql` migrations.** `MigratorConfig.placeholders` is a map, and `${name}` in a file
  becomes what `name` maps to — a schema, a role, a tablespace. A paste and nothing more: no quoting, and no
  idea of where in the statement it landed. `.sql` only. **The parameter sits third in `MigratorConfig`**, so
  a config built positionally has to move.
- **Off until the map has an entry, and loud once it does.** Empty, nothing is scanned for, so a migration
  holding a `${` of its own costs nothing. With one entry, a `${name}` without a value is refused by file and
  line before anything runs. `\${name}` is literal text; substitution happens once.
- **The checksum is taken over the file as written, before substitution.** Changing a value is therefore not
  a change to the migration, and a database that ran it under one value does not refuse it under another.
  The cost is the other side of the same coin: the history records that the file ran, not what it expanded
  to.

#### Changed

- **`MigrationState` is `MigrationOutcome`.** It sat next to `MigrationStatus` — one letter apart, in the
  same module. The history table's `state` column is untouched.
- **`baselineVersion` and `target` are read where they are written.** `MigratorConfig` parses both as it is
  built, so `baselineVersion = "1.x"` is refused by whoever wrote it down rather than by the first
  `migrate()` that reaches it. Same `MIGRATION_EXCEPTION:CONFIGURATION`; only the frame it is raised from
  moves. Both stay `String` — where you hold a `MigrationVersion`, pass `canonical`.

## Version 0.9.9 (v0.9.9)

### Project

#### Changed

- **The repository is now `octavius-postgresql`.** `octavius-driver` named one of the six artifacts inside it
  rather than the thing itself, and collided with the `driver` module every time either was written down.
  GitHub redirects the old URL; GitHub Pages does not, so the API reference has moved to
  https://octavius-framework.github.io/octavius-postgresql/. No Maven coordinate holds the repository name.
- **The `annotations` module is now `pg-model`,** coordinate included -
  `io.github.octavius-framework:annotations` becomes `io.github.octavius-framework:pg-model`. It stopped being
  a module of annotations: it now carries a multiplatform `BigDecimal`, the serializers a JSON payload needs
  to keep one, and the case converter that decides what a type is called. Nothing changed package, so only the
  dependency line moves, and anyone taking `driver` takes it transitively either way.
- **Every published artifact carries its own POM name and description.** They were derived in the root build
  from a `when` over the module name and two fell through it, so `client-scanner` was published with the
  client's description and `driver-spring-integration` with the driver's. Each module declares both itself
  now, and applying the publish plugin is what marks a module as published.

### pg-model

#### Added

- **`octaviusSerializersModule` and `octaviusJson`,** for the types whose JSON form does not match their
  column form mostly for `dynamic_dto` payload. `BigDecimal` has no serializer at
  all, so the class does not encode; a `LocalDate`, `LocalDateTime` or `Instant` holding PostgreSQL's
  `infinity` is written out as the far-away timestamp the constant nominally is, which a `date` or `timestamp`
  will not read back at all and a `timestamptz` reads back quietly as something that is not `infinity`. All
  four are answered contextually, so `@Contextual` on the property is what turns it on. `octaviusJson` is that
  module on a stock `Json`, for the frontend or the HTTP layer that reads the same classes.
- **A date in a payload is spelled the way PostgreSQL spells it**, not the way ISO-8601 does. No single string
  satisfies both once a year leaves `0001`..`9999`: ISO signs a longer year and PostgreSQL reads that sign as
  the start of a timezone offset, so `+5874897-12-31` is refused for a year a `date` holds perfectly well, and
  ISO counts through a year zero where PostgreSQL counts BC from one. The serializers write PostgreSQL's
  spelling and read **either** back, so a payload built in SQL decodes and so does one written before this
  existed.
- **`BigDecimalAsNumberSerializer`** writes an unquoted JSON number rather than a string, so `jsonb` stores it
  as a `numeric` and `jsonb_typeof` says `number` - arithmetic, casts and an index on the value all work
  without a cast written by hand. Reading goes back through the raw token, so a value longer than a `Double`
  survives the round trip.
- **`EnumWithCaseConventionSerializer`**, for an enum inside a payload. Subclass it with the conventions
  `registerEnum` was given - which are also its defaults - and the payload agrees with the column. The client
  does this for you on a `dynamic_dto`; this is the same thing for a `Json` assembled elsewhere.
- **`io.github.octaviusframework.type.BigDecimal`**, a `typealias` for `java.math.BigDecimal` on the JVM and
  the decimal's text on JS, so a class in `commonMain` can declare a `numeric` property at all. Being an alias
  and not a wrapper, it *is* what the driver's codec produces, so nothing converts. The digits are kept as
  text on JS because a `Number` there is a 64-bit float and would round exactly what `numeric` was chosen to
  keep.

#### Changed

- **`CaseConverter` and the date/time infinity markers moved here from the driver.**
  `driver.identifier.CaseConverter` is now `io.github.octaviusframework.identifier.CaseConverter`, next to the
  `CaseConvention` it takes, and `driver.type.datetime.DISTANT_PAST` / `DISTANT_FUTURE` / `LocalTime.MIN` /
  `MAX` / `DateTimePeriod.INFINITY` / `MINUS_INFINITY` are now `io.github.octaviusframework.type.datetime`.
  Both are needed in `commonMain` and both were in a JVM-only module. Same declarations and same values, now
  covered by tests on both targets; `pg-model` is an `api` dependency of the driver, so nothing is added to a
  build file and only the imports change. `PgInterval` and the rest of `driver.type.datetime` stayed.

### Driver

#### Added

- `ConverterRegistry.registeredEnums` says what every enum passed to `registerEnum` was registered as - the
  PostgreSQL type it stands for and the two case conventions - alongside the `registeredComposites` already
  there. The converters held those facts privately and one direction each; an enum means one thing in a column
  of its own and another inside JSON, and only the layer holding the JSON can settle the second.
  `PgEnumRegistration` is what it holds.
- `SqlScript.split` cuts a script into the statements it is made of, each carrying the offset it stood at and
  its own first word, upper-cased and found past whatever comments stand in front of it. It splits on the `;`
  that separate statements, it ignores literals, comments and the parentheses where `CREATE RULE ... DO (a; b)` puts one
  legitimately. Whitespace and comments between separators are not a statement, so a trailing `;` adds nothing
  to the list. It is what it takes to send `CREATE INDEX CONCURRENTLY` or `VACUUM` in a message of its own, a
  script sent whole running inside an implicit transaction.

#### Changed

- `execute()` takes `ignoreRows`, default `false`. Left alone, a statement returning rows is still
  `InvalidOperationException(UNEXPECTED_RESULT)`, which is what catches a `SELECT` sent where `fetchRows` was
  meant; set, the rows are dropped instead - what a script written elsewhere needs, `pg_dump` emitting
  `SELECT pg_catalog.setval(...)` for every sequence it carries. Rows are drained either way; the flag decides
  only whether their arrival is reported. `RawQuery.execute()` in the client takes it too. Source-compatible,
  but a caller compiled against 0.9.8 has to be recompiled.

### Client

#### Added

- **A registered enum is written into a `dynamic_dto` payload under the label PostgreSQL holds**, and read
  back from it. kotlinx.serialization never saw `registerEnum` and wrote the Kotlin name, so one value read
  two ways depending on where it was stored and a query filtering on `payload ->> 'office'` matched neither
  reliably. The labels come off the driver's registry, so the enum named at `registerEnum` and the one a scan
  found by `@PgEnumType` are covered alike. An enum the driver was never told about keeps the default.
- `dynamicTypes.enumSerializers` is that module on its own, for a `Json` built elsewhere - an HTTP layer, or a
  `jsonb` column written through the driver rather than through a `dynamic_dto`. It answers for the enums
  registered when it is read.
- **`TransactionPlan.describe()`,** which renders the plan as text: every step's index, its SQL, and where
  each of its parameters comes from. A step whose query cannot be rendered says so in place of its SQL rather than throwing.

#### Changed

- **`dynamicJson` defaults to `octaviusJson` rather than to a stock `Json`.** A `dynamic_dto` payload is JSON,
  so a registered class with a `BigDecimal` property did not encode at all and one holding an unbounded date
  encoded to a year PostgreSQL will not read back. It is otherwise the same strict `Json` it was, so a payload
  carrying a field the class does not declare is still an error. A `Json` passed in explicitly is untouched -
  put `octaviusSerializersModule` on it yourself; the enum serializers are folded onto whichever `Json` is in
  use, per conversion rather than once, so an enum registered between two queries applies to the second.
- **A `StepHandle` in a `TransactionPlan` reaches one thing, `value()`.** `field(name, rowIndex)` and
  `column(name)` are gone: both threw away the type the step's terminal declared, handing back `Any?` and
  `List<Any?>` for the caller to cast. `value()` carries that type, so taking a column is
  `map { it.get<Int>("id") }` - checked where it is written, and a `List<Int>` the driver sends as an array.
  Asking a scalar or an object result for a column it has not got no longer runs and fails; it does not
  compile.
- **`row(rowIndex)` is replaced by `spread()`**, which marks a value to fill its parameter slot with entries
  of its own rather than with one value.
- The shape failures the plan raised of its own - a result with no columns to take, a column the rows have not
  got, a row index past the end, a row that was not there to spread - are gone from it. Two the compiler
  refuses outright; the other two are whatever the caller's own `map` raised, wrapped as a `MappingException`
  naming the parameter or passed through as the driver's own `COLUMN_NOT_FOUND`.
- **Everything a plan raises while resolving a step's parameters names the step, and every `map` in a chain is
  numbered.** It now reads `Step 1 of the plan, parameter 'amount': map #2 over step 0.map(#1) threw NumberFormatException`;
  `TransactionValue.Transformed` renders as that chain where it rendered as an identity hash, and the driver's
  own failure inside a transformation picks the same three up on its `path`. The number is where the step sits
  in the plan being run, not the index the handle was created at, which after `addPlan` is no longer the same.

See [Transaction Plans](docs/client/plans.md) for the whole of it.

### Migrations

#### Added

- A new `migrations` module: a migrator built on the driver. `V`/`R` naming as in Flyway, migrations written
  as `.sql` files or as Kotlin classes, checksums, an advisory lock, and a history table it keeps itself.
  `OctaviusMigrator(dataSource, MigratorConfig(...)).migrate()` is the whole entry point, and `info()` answers
  the same question without applying anything or creating anything.
- **Two transaction paths, differing in where the history row sits.** By default a migration and its history
  row go into one transaction, so a failure leaves neither the work nor a record of it - nothing to repair,
  and no `repair` command to do it with. A file whose header carries `-- octavius:no-transaction` runs
  statement by statement instead, which is what `CREATE INDEX CONCURRENTLY`, `VACUUM` and `ALTER SYSTEM` need;
  a failure there records which statement stopped it, and the next run refuses to go on until somebody has
  looked.
- **A migration class is read as a name and never constructed by the scan.** `V2_1__Add_indexes` is version
  2.1 - a class name cannot hold a `.`, so `_` separates the parts there - and the class is built once,
  immediately before it runs.
- Checksums for `.sql` files are CRC32. A class records no checksum unless it declares one.
- `outOfOrder`, `baselineVersion` and `target`, for a branch merged late, a database adopted at a version it
  was already at, and a release that ships migrations ahead of the code needing them.
- Documented in [docs/migrations](docs/migrations/README.md).

## Version 0.9.8 (v0.9.8)

### Driver and annotations

#### Added

- `applicationName` connection property, settable like any other rather than through `additionalProperties`, and
  therefore reachable from HikariCP and Spring. Unset, a connection now reports `Octavius Driver`.
- Cleartext password authentication, over TLS and nowhere else — which is what `pg_hba.conf` needs for `ldap`,
  `pam` and `radius`, previously unable to connect at all. `channelBinding=require` still refuses it, and MD5
  is still refused outright.
- `@PgEnumType`, `@PgCompositeType` and `@DynamicallyMappable` in the new multiplatform `annotations` module,
  alongside `@PgName`. The driver reads only `@PgName` as before; they are what a scanner looks for, and living in
  `commonMain` is what lets a class shared with another platform carry them.

#### Changed

- **Breaking:** `@PgName` and `CaseConvention` move to the `annotations` module —
  `io.github.octaviusframework.annotation` and `io.github.octaviusframework.identifier`. Imports change and
  nothing else does. The driver takes the module as `api`.
- **Breaking:** `TypeManager.registerEnum`'s non-reified overload takes `KClass<*>`, not `KClass<T>` where
  `T : Enum<T>`. Source-compatible for `MyEnum::class`; a caller holding a scanned class no longer needs an
  unchecked cast. Being an enum is checked inside and refused as `InvalidOperationException(INVALID_ARGUMENT)`.
- **Breaking:** the abstract base of `NativeQuery` and `NamedParameterQuery` is `Query`, not `OctaviusQuery` —
  the prefix exists to separate a driver type from the `java.sql` interface it implements and for main entry points, and nothing named
  `Query` was in the way. Only a signature naming the base type by hand changes: `Query<*>`.
- **Breaking:** `RoutineExecutionException` is two classes. A routine saying no on purpose is
  `RoutineRaiseException` (`P0001`, `P0000`) and carries no reason enum; a routine whose own assertion failed
  is `RoutineAssertionException` (`NO_DATA_FOUND`, `TOO_MANY_ROWS`, `ASSERT_FAILURE`). One is the database
  deciding, the other is database code being wrong, and only a type lets a caller branch on that.
- **Breaking:** SQLSTATE class `25` is `TransactionStateException`, and
  `StatementExceptionReason.INVALID_TRANSACTION_STATE` is gone. Nothing is wrong with those statements — the
  transaction is — and the server sends no error position for any of them. `25P03` and `25P04` are untouched.
- **Breaking:** `MISSING_NAMED_PARAMETER` and `INCORRECT_RESULT_SIZE` move to
  `InvalidOperationExceptionReason`, both being the caller's mistake rather than the server's.
- **Breaking:** six reasons fewer across three enums, each a distinction `details` was already making.
- **Breaking:** `DatabaseSystemException.errorMessage` is `details`, which is what the other nine exceptions
  call the same thing.
- **Breaking:** `setSchema`, `getSchema`, `setCatalog` and `getCatalog` raise
  `InvalidOperationException(FEATURE_NOT_SUPPORTED)`. Neither is connection state in PostgreSQL: the setters
  accepted anything and did nothing, so a pool configured with a schema set none.
- **Breaking:** a connection property whose value does not parse is refused where it is set, rather than
  leaving the default to apply quietly — `sslmode=verify-fll` used to connect as `prefer`, and `ssl=yes` meant
  `ssl=false`. Raised before any socket exists, so a pool fails while being built.
- **Breaking:** the driver internals are no longer public by omission. Fifteen more classes are `internal` or
  take an internal constructor and three very general `String` extensions are gone. Nothing documented moved.
- `sslpassword` decrypts the client private key, which is what it means to libpq and pgjdbc; before, an
  encrypted `sslkey` failed to load whatever was set. The cipher is resolved from the file, and an encrypted
  key with no password is refused by name.
- A `LargeObject` operation on a connection that has gone reports the connection, not the descriptor.
  `checkClosed` asked a pooled connection whether it was closed, which is a question about whoever borrowed it
  next.

#### Fixed

- Encrypted connections reach TLS 1.3, where every one settled on 1.2 whatever the server offered — the
  `SSLContext` was asked for by version, and that version is a ceiling nothing reports. The suite now asserts
  the negotiated version out of `pg_stat_ssl`.
- An IPv6 address in a JDBC URL is read as one; `jdbc:octavius://[::1]:5432/db` used to connect to a host named
  `[`. The port is taken from the last colon and only after the closing bracket, as libpq and pgjdbc read it.
- A client certificate on a non-RSA key works. The key was read through `KeyFactory.getInstance("RSA")`
  whatever it was, so an EC key — what most modern tooling generates — always failed. The algorithm now comes
  from the certificate it is presented with.
- The exception raised when a certificate or key will not load names both files and no longer asserts a cause
  it cannot know. It used to advise PKCS8-without-password, which for an EC key was already true.
- `toUrl()` no longer renders `sslpassword`. It became a secret this version, now that it unlocks the private
  key rather than an in-memory keystore that was built, read once and dropped.
- A connection that fails to open gives its socket back. Only the socket's construction was guarded, so a
  refused handshake or a rejected password leaked one per attempt.
- A negative `fetchSize` is refused, naming the value, before the connection lock is taken — it used to go
  straight into `Execute`'s row limit, which the protocol defines as a count. `0` is untouched and means the
  whole result in one batch.
- A server whose reported version holds no dot is read for what it is. `18beta1` parsed as `0`, which told
  somebody running 18 that the driver requires 18 or higher.

### Client and scanner

#### Added

- The `client` module: what the driver leaves to the caller, and nothing it already does. Much smaller than a
  port of octavius-database would be, that library having largely existed to work around pgjdbc.
  See [the client's guides](docs/client/README.md)
- `OctaviusClient`, which hands out queries and runs transactions over them. A query taken from it is a
  `RunnableQuery` that finds its own session when a terminal runs, so nothing has to be opened around it. Where
  the work is not a query, `execute { }` hands over the driver's own session operations.
- The terminal family is written once, on `RunnableQuery`: every builder and every hand-written query inherits
  `fetchRows`, `fetchObjects`, `fetchField`, `forEach*` and `update` under the driver's names and meanings. The
  typed ones are `inline` members with a `reified` argument, so they need no import at the call site.
- The query builders — `select`, `insertInto`, `update`, `deleteFrom`. Every clause takes SQL and passes it
  through; what the builder contributes is the keywords, the column list paired with its own placeholders, and
  the clauses that disappear when they have nothing to say.
- **An `UPDATE` or `DELETE` built here requires a `WHERE`.** Emptying a table is a statement worth having to
  mean, and `rawQuery` is where it says so.
- `QueryFragment`, `withParam` and `join`, keeping a runtime-assembled condition and its parameters together.
  `join` drops empty fragments, parenthesises each one so a joined `OR` keeps its precedence, and refuses two
  that name one parameter differently.
- `RunnableQuery.toSql()`, which makes a query a value: no query carries its own parameters, so the rendered
  SQL drops into a `WITH`, a subquery or a `UNION` with its `@name` placeholders intact.
- `copy()` on every builder, for variants off a shared base, carrying registered converters with it.
- `RawQuery.execute()`, on `RawQuery` alone. It speaks the Simple Query Protocol, which binds nothing, so an
  `@name` left in the SQL arrives as literal text — and a builder always has values to bind. Several statements
  in one round trip.
- `registerResultConverter` and `registerParameterConverter` on any query, reaching the per-query registries
  the driver already gives every query — ahead of the session's, discarded with the query. `RunnableQuery` is
  self-typed for them, as the driver's `Query` is, so each returns the builder's own type.
- `SessionProvider`, and `DefaultSessionProvider` binding a transaction's session to the thread that started
  it — so a repository function opens a scope without knowing whether it is already inside a transaction.
  `REQUIRED`, `REQUIRES_NEW` and `NESTED`; isolation and read-only before the transaction begins, both timeouts
  as `SET LOCAL` inside it.
- `DataResult<T>` and `dbResult { }` — the result style as one opt-in function rather than a return type.
  Queries throw, the way the driver throws, which keeps them usable from a `try`/`catch` and from
  `@Transactional`. It carries the driver's own `OctaviusException` rather than a parallel hierarchy.
- `RunnableQuery.asResult()` and `OctaviusClient.transactionResult`, the same boundary at two other widths.
  `transactionResult` is not sugar: a plain transaction rolls back on a throw and on nothing else, so a failure
  caught into a value inside one would be committed over.
- One place decides what `dbResult` catches, `isCallerBug`, and it reads the exception's **type** and nothing
  finer — never the `reason` enum, which exists for a log line. Anything unlisted becomes a `Failure`, future
  exception types included.
- **A `fetch*Strict` that found no row is thrown, where octavius-database returned it as a failure.** A
  deliberate correction: `Strict` asserts one row is there. Absence that is expected says so in the type —
  `fetchRow` returns `Row?`, `fetchField<String?>` returns `null`.
- `TransactionPlan`, `StepHandle` and `executeTransactionPlan`, for when the sequence itself is data — built by
  the layer that knows what has to happen, run by another. A step's parameters may hold a `TransactionValue`
  pointing at what an earlier step produced.
- `TransactionPlan.addPlan(other)`, so two plans run as one transaction. Handles keep working, a result being
  filed under the handle rather than under a position. Merging one twice is refused.
- A plan is validated before its transaction is opened: every step's SQL rendered, every parameter walked for
  handles belonging to another plan. An empty plan opens no transaction.
- An exception out of a `map { }` arrives as a `MappingException` naming the parameter, with what was thrown as
  its cause — a transformation is the one place a plan runs the caller's code, and a bare `ClassCastException`
  travelled past `dbResult` unseen.
- A value carried between steps by `field`, `column` or `row` is what the result converters make of it, the
  same as `row.get<Any?>` anywhere else — so an enum arrives as the enum and `jsonb` as a `JsonElement`, each
  naming its own PostgreSQL type on the way back out.
- `dynamic_dto`: one column holding whichever of several unrelated shapes a row happens to carry — the case a
  `COMPOSITE` cannot cover, fixing the shape per row rather than at schema level. Values built in SQL read the
  same way.
- `dynamic_dto` registration **states the name** rather than deriving it from the class. The discriminator is stored in the
  data, so deriving it means a rename silently orphaning every row written before it. `@DynamicallyMappable`
  declares one instead — `register<T>()` and `register(kClass)` read it — and being multiplatform it can sit on
  a class shared with another platform.
- `DYNAMIC_DTO_DDL` and `dynamicTypes.install()`. The type is not created behind you: put the DDL in a
  migration, or call `install()`, which also reloads the catalogue a connection read when it opened.
- **An instance of a registered class is written as a `dynamic_dto` without being wrapped.**
  `DynamicWriteStrategy` says when, defaulting to `AUTOMATIC_WHEN_UNAMBIGUOUS`; `toDynamicDto` overrides all
  three and is the only way to make a `DynamicDto`, which is where the name is checked against the registry.
- `DynamicDto.dataPayload` is JSON **text**, and no tree is built in either direction — `jsonb`'s codec encodes
  and decodes a `String`, so text is the form both paths already pass through.
- `dynamicJson` and `dynamicWriteStrategy` on `fromDataSource` and `fromSessionProvider`, plus
  `resultConverter(json)`, `parameterConverter(json)` and `toDynamicDto(value, json)` for one query at a time —
  a payload built with `jsonb_build_object` is named the way SQL names things.
- The `client-scanner` module: `registerAnnotatedTypes("com.roma.domain")` walks the named packages and
  registers what it finds. A name the database has no type for goes into `ScanReport.unresolved` and is
  reported, not refused — registering ahead of a migration is a working flow.

#### Notes

- Three ways into the result style, for three widths — `asResult()`, `dbResult { }`, `transactionResult`.
  Reaching for `dbResult` *inside* a plain `transaction` is the one combination that misleads.
- The escaping rule octavius-database needed for `?` is gone rather than ported: the driver rewrites `@name` to
  `$n`, so a JSONB operator written as `?` needs no doubling.
- There is no `client-spring-integration` and none is planned. Implementing `SessionProvider` against Spring is
  under thirty lines over `OctaviusTemplate` and a `PlatformTransactionManager`, and that is the whole of what
  such a module would contain.
  See [`SessionProvider`](docs/client/transactions-failures.md#sessionprovider)

## Version 0.9.7 (v0.9.7)

#### Added

- Logging across the connection, transaction, COPY and statement lifecycle, documented in [Logging](docs/driver/logging.md)
  with every logger name and what each level costs.
- Every exception the driver used to swallow is now logged
- `logParameterValues` connection property (default `false`), putting the values bound to a statement on its `trace`
  line instead of only their count, numbered to match the `$n` placeholders. A property rather than a consequence of the
  level, so turning the driver up to find a slow statement does not start recording who it was about. Exposed on
  `OctaviusDataSource` like every other setting
- `TypeDictionary.size`, the number of types the dictionary holds
- `Throwable.findOctaviusCause()`, which walks a cause chain for the driver's own exception and returns it, or `null`
  when the failure did not start here. It is what the driver now uses wherever it has to decide whether a foreign
  exception is worth restating, and it replaces the walk the Spring exception translator carried separately
- `ownsConnection` on `Connection.getOctaviusSession()`, default `true`. Passing `false` says the connection belongs to
  something else - Spring's `DataSourceUtils`, a pool borrowed from by hand - so closing the session undoes the state it
  left without giving the connection back
- `OctaviusJdbcTransactionManager`, contributed by the auto-configuration in place of Spring's `JdbcTransactionManager`
  and carrying the same exception translator and nested-transaction setting. What it adds is an answer for a transaction
  whose connection has already left
- Column metadata on every result - `row.metadata.getColumn(i)` answers with a `ColumnMetadata`: the name the column
  came back under, the `PgType` it was read as, the raw `atttypmod` that is the only record of a `numeric(10,2)`'s
  precision or a `varchar(64)`'s length, and a `ColumnOrigin` naming the relation, its schema, the attribute number and
  the column's own name where the server tracked one.

#### Changed

- **Breaking:** `IntObjectMap` is internal - the primitive-key map behind the OID lookups on the read path, public by
  omission rather than by intent
- **Breaking:** `RowMetadata.descriptors` is now `columns`, a list of `ColumnMetadata` rather than of
  `FieldDescription`, and `FieldDescription` itself is internal - it was the wire form of a `RowDescription` field,
  every reference an OID and every modifier raw. `formatCode` and `dataTypeSize` are gone rather than renamed: one is
  the driver's own decision about how it asked for the data, the other a copy of `pg_type.typlen`
- A result column whose type the catalog does not describe now fails when the result is described, rather than on the
  first row that happens to carry a value in it - a `SELECT *` over `pg_stats` used to return rows until one of its
  `anyarray` columns was non-null. The exception names the OID and is recorded and drained like a failed row mapping. 
  The type dictionary now starts seeded from the builtin codecs rather than empty, the catalog query being itself a result whose
  columns have to be described; `TypeDictionary.EMPTY` goes with it
- **Breaking:** the block passed to `transaction.required` and `transaction.nested` is `crossinline`, so a bare `return`
  out of it no longer compiles - `return@required` / `return@nested` return from the block as before. A non-local return
  is not an exception, so it slipped past the `catch` while the `finally` still ran: out of `required` it committed
  whatever the block had already done, out of `nested` it left the savepoint standing until the outer transaction ended.
  The compiler names every call site that has to change
- A parameter that serialises past what PostgreSQL accepts for one value is refused by the driver, naming the `$n` it
  was bound to and the size it reached, instead of travelling the whole way out to be refused as a malformed message.
  The ceiling is `1073741819` bytes - `MaxAllocSize` less the four-byte length header - and is not reachable from
  `pg_settings`, so there is nothing to configure. It bounds each parameter rather than the message they share
- A session closed with a `COPY` still in flight evicts its connection instead of ending the transfer and handing it
  back. There is no bounded way to end one from the client side - cancelling an export means reading every row the
  server has left to produce - and `close()` runs on whoever gave the session back, so an export abandoned on its first
  chunk used to hold that thread until the server had finished producing the whole of it. The connection goes out with a
  `warn` line naming the reason, and the pool opens a fresh one in its place
- An index outside a container raises `MappingException(COLUMN_NOT_FOUND)` naming the container and how many positions
  it has, where the list or array underneath used to raise its own `IndexOutOfBoundsException` - `PgArray.get`,
  `PgComposite.get`/`set`/`getAttributeOid`, `PgRecord.get`/`getAttributeOid` and `PgMultirange.get` were the accessors
  that let one through. Outside a converter it is the difference between a failure a `catch (e: OctaviusException)` sees
  and one it does not

#### Fixed

- `IntObjectMap` built with a capacity of one or less came out with a backing table of no slots at all - the size is
  rounded up to a power of two, and one rounds down to zero, leaving a probe mask of `-1` that the first `get` or `put`
  walks off. The floor is now two slots
- Reading or setting the network timeout on a connection the peer had already dropped raised a raw
  `java.net.SocketException` out of `Connection.getNetworkTimeout` and `setNetworkTimeout` without wrapping them in `SQLExceptionWrapper`. Both now raise
  `NetworkException(CONNECTION_ERROR)` wrapped into it and mark the stream broken
- `QueryContext` rendered parameter values straight into the exception message, so a `ByteArray` printed as an identity
  hash rather than its size and a large `text` or `json` parameter printed in full. It now shares one formatter with
  `CodecException` and the trace log
- That formatter bounds a value **as it renders it** rather than trimming the result - cutting `toString()` down to size
  materialises the whole thing first, so a failed bulk insert used to assemble megabytes of string to keep a hundred
  characters of it. Collections, arrays and maps are walked element by element against a budget and then counted (
  `[0, 1, 2, ... +9990 more]`), Kotlin's primitive arrays render their contents instead of an identity hash, and a
  nested `ByteArray` is named like a top-level one
- `isValid()` raised instead of answering `false` when the connection died between its own closed check and the probe -
  reading the timeout it has to save and restore is itself a socket operation, and that read sat outside the guarded
  region
- The parameter buffer sized itself in `Int` arithmetic. A buffer already over a gigabyte that had to grow again doubled
  itself into a negative capacity and from there to `0`, where the growth loop spun forever holding the connection and
  the thread that borrowed it; a single value big enough to overflow `position + needed` skipped growing altogether and
  came out as an out-of-bounds copy. Sizes are computed in `Long` now, and a buffer that would have to outgrow the
  largest array a JVM hands out raises `InvalidOperationException(INVALID_ARGUMENT)`
- `transaction.required` and `transaction.nested` lost the exception that sent them into the rollback whenever the
  cleanup failed in its turn - a rollback on a connection that had just died raised over the top of it, and a throw from
  restoring auto-commit replaced it outright. Both now attach the cleanup failure to the original as a suppressed
  exception. Out of a scope that **committed** there is no original to attach to, and a failed restore is logged at
  `warn` instead of raised: the work is in the database, and an exception over a commit that succeeded reads as a failed
  transaction and invites a retry that writes everything twice
- `session.transactionState` reported whatever the last ordinary statement had left, for as long as a `COPY` had run
  since - the status byte was recorded by `QueryExecutor`, and every COPY path reads `ReadyForQuery` straight off the
  stream, so a transfer that ended in an error left the server in a failed transaction while the session still called
  itself `IN_TRANSACTION`. It is recorded by `PgStream` now, at the single point that decodes the byte
- A session that had already been closed could still reach its connection - by then a pooled one the next borrower was
  running queries on. `createNativeQuery`, `createNamedQuery`, `copy`, `largeObjects`, `notifications`, `cancelQuery`
  and `getSearchPath` reach the wire through the physical connection rather than the pool's proxy, which alone goes dead
  when the connection is handed back. A session now records that it has been given up, by `close()` or by `abort()`, and
  everything asked of it afterwards raises `NetworkException(CONNECTION_CLOSED)`; `isValid()` answers `false` rather
  than raising, and a second `close()` returns instead of resetting a connection that belongs to somebody else by then
- Obtaining a session from a pooled `DataSource` reported the pool's refusals in the pool's own terms -
  `getOctaviusSession()` passed `java.sql.SQLException` straight out, a type this API uses nowhere else. They now arrive
  as `InitializationException(CONNECTION_ERROR)` carrying the original as its cause, and where the pool was restating a
  failure of the driver's own, that one is raised instead of the restatement wrapped around it
- The state-carrying members of a session - `autoCommit`, `readOnly`, `transactionIsolationLevel`, `networkTimeout`,
  `commit()`, `rollback()` and the savepoint methods - delegate through the JDBC connection so that a pool sees the
  changes it has to reset, and a proxy answering for a connection it had taken back or evicted raised a bare
  `java.sql.SQLException` through every one of them. They still delegate; an exception coming back that is not the
  driver's own is restated as `NetworkException`, after a look through its cause chain in case the pool was carrying a
  driver failure it had wrapped
- `OctaviusTemplate` left on the connection everything a session undoes when it closes, because it never closed one - a
  `LISTEN` registered inside an `execute` block rode back into the pool and became the next borrower's starting state,
  as did a transaction opened by a hand-written `BEGIN`. The session is ended now: outside a transaction by the call
  that opened it, inside one by a `TransactionSynchronization` running after the commit or rollback and before Spring
  releases the connection. After rather than before, because a transaction that failed on the server stays failed until
  the rollback goes through (`25P02`), so an earlier reset would raise on exactly those transactions and cost a
  connection on every one of them. A `COPY` left unfinished still costs the connection rather than being reset away
- A transaction works through one session now, rather than one per `execute` call, each of which used to build its own
  notification, copy and type manager over the connection the transaction already held. The session is bound to the
  transaction as a Spring resource, which also unbinds and rebinds it across a suspension - a `REQUIRES_NEW` transaction
  inside another one runs on a connection of its own, and would otherwise have found the outer session still bound to
  the thread
- A connection that left in the middle of a Spring transaction reported the fact as
  `TransactionSystemException: JDBC commit failed`, and on the rollback path replaced the exception that had caused the
  rollback. An evicted pool proxy answers everything with a bare `SQLException` carrying neither SQLState nor cause, so
  nothing was left for an exception translator to recognise. The transaction manager now checks the connection before
  either step - `isClosed()` being the one question such a proxy still answers rather than raising - and the two paths
  part company there: a commit raises `OctaviusDataAccessException` over `NetworkException(CONNECTION_ABORTED)`, silence
  about a commit that did not happen being a claim about durability that is not true, while a rollback stays quiet, the
  server having discarded the transaction along with the connection

## Version 0.9.6 (v0.9.6)

#### Added

- SCRAM-SHA-256-PLUS with channel binding - the client proof covers a hash of the certificate the server presented, so an intermediary terminating TLS with a certificate of its own produces a proof the real server rejects. New `ChannelBinding` enum (`DISABLE`, `PREFER`, `REQUIRE`) and a `channelBinding` property defaulting to `PREFER`
- `cancelSignalTimeout` connection property (default `10` seconds), covering both the connect and the reads of a cancel request - it travels on a connection of its own, so it gets a budget separate from `loginTimeout` and `socketTimeout`
- Every `OctaviusProperties` setting is now exposed on `OctaviusDataSource`, alongside a generic `setProperty(name, value)`
- Documentation index, plus pages for bulk writes, concurrency and virtual threads, arrays/ranges/JSON and composites with reflective mapping; reworked README, `octavius-vs-jdbc`, `spring-integration`, `performance` and `queries`
- `CHANGELOG.md` and `LICENSE` in the repository
- KDoc published for release and snapshot versions side by side, behind a landing page
- KDoc on the parts of the public surface that had none
- Benchmarks measuring what reflection costs against a hand-written converter
- Tests for channel binding, local SSL test infrastructure driven by `scripts/ssl-test-server.ps1`, HikariCP initialization, greedy-converter diagnostics, reflective missing-value handling and parameter type mismatches

#### Changed

- `fetchField` treats a missing row and a `NULL` value alike, and the nullability of `T` decides whether either is allowed** - `fetchField<String>()` used to return `null` on an empty result while raising `MappingException(REQUIRED_ATTRIBUTE_MISSING)` for a row carrying `NULL`; both now raise it, and `fetchField<String?>()` returns `null` for both.
- `fetchField` returns `T` instead of `T?`, following from the above - a non-nullable `T` can no longer come back null, so nullability lives in `T` alone and survives the round trip, matching how `Row.get<T>()`, `fetchFields<T>` and `fetchFieldStrict<T>` have always read it.
- `PgNotice` is a `data class` of named properties, carrying every field a notice can hold mirroring ServerErrorMessage.
- `ServerErrorMessage.severity` and `code` are non-null, and a `localizedSeverity` joins them - the protocol requires both in every `ErrorResponse`.
- `kotlinx.coroutines.core`, `kotlinx.serialization.json` and `kotlinx.datetime` are `api` dependencies.
- `Row.get` is a member function instead of an extension, so nothing has to be imported at the call site
- A cancel request's socket performs the same SSL handshake as the connection it cancels, instead of connecting in the clear
- `ResultMapper` compares what a converter produced against the type that was requested - a converter whose `canConvert` accepts more than it can produce now raises `MappingException(CONVERSION_ERROR)` naming it, instead of a `ClassCastException` in the caller's own frame with nothing in the stack to identify it
- The `unknown` pseudo-type is loaded into the type catalog.
- A default value in a data class constructor covers an **absent** column or attribute only - SQL `NULL` is a value, reaching a nullable property as `null` and raising `REQUIRED_ATTRIBUTE_MISSING` for a non-nullable one whether a default is declared. Previously a default silently replaced any `NULL`
- `registerAutoComposite` rejects a class that is not a data class with `InvalidOperationException(INVALID_ARGUMENT)`.
- `ReflectionCompositeParameterConverter` no longer claims an unregistered data class when the expected OID happens to name a composite
- `PgNotice` carries `processId`. The id comes from the `BackendKeyData` of the startup handshake, so a notice raised before that handshake finished reports `-1`; it is unrelated to `PgNotification.processId`
- A parameter that reaches the end of the converter chain unclaimed is now checked against the codec bound to its target OID, where one is known. A class that codec cannot encode raises `MappingException(NO_CONVERTER_FOUND)` naming both sides, with the attribute or element index in `path`, matching how the read direction reports the same mistake - previously an unregistered nested data class surfaced as a bare `ClassCastException` and a width mismatch such as an `Int` bound for `int8` as `CodecException(ENCODING)` with no path

#### Fixed

- A query-string value containing `=` is no longer dropped from the JDBC URL - only the first `=` separates the key from the value, so a password or an `options=-c search_path=curia` string arrives intact instead of being silently ignored
- A JDBC URL now overrides the `Properties` it is parsed against, consistently - the host and port were read from the URL's authority only if `info` had left them unset, so `getConnection("jdbc:octavius://prod:5432/db", info)` silently connected to whatever host `info` carried, while the database name had the opposite fault and replaced the one `info` supplied even when the URL named none. The URL now wins where it states something and leaves `info` alone where it does not. Parsing no longer fills in defaults either - an unstated host, port or database stays `null` until the connection factory resolves it
- KDoc that described something the code no longer does

## Version 0.9.5 (v0.9.5)

#### Added

- Documentation for queries, COPY, large objects, performance, Spring integration and Octavius vs legacy JDBC; reworked README
- KDoc for exceptions and geometric types
- `UncategorizedDatabaseException` for SQLSTATEs no routing branch claims
- `RegistryKey` - type registries are now cached per host + port + database instead of per full URL, so credentials and SSL settings no longer fragment the cache
- `ServerErrorMessage` - data class carrying every field of the server's `ErrorResponse`, attached to all exceptions
- `ReflectionMappingUtils`
- `UNEXPECTED_RESULT`, `COPY_IN_PROGRESS` and `EXECUTION_IN_PROGRESS` reasons for `InvalidOperationException`
- Benchmarks for `reWriteBatchedInserts`
- Tests for pooled session cleanup, Spring exception translation and `isValid` under a concurrent query

#### Changed

- `session.types` renamed to `session.typeManager`
- `TypeManager` and `ContainerFactory` moved to `registry` package
- `ParameterSerializer` and `QueryExecutor` moved to `execution` package
- `QueryContext` moved to `query` package, `ServerErrorMessage` to `message`, `ExceptionTranslator` to `message.translator`
- `Range` and `MultiRange` moved to `type.range`, `PgInterval` and `DateTimeExtensions` to `type.datetime`
- `ReflectionCompositeCache` renamed to `ReflectionCache` and moved to `util.reflection` - it deserializes `Row` as well, not just composites
- `OctaviusException` is now an abstract class
- `getDefaultTypeName` takes `sourceClass` alongside the context
- `OctaviusTemplate.execute` takes the session as an implicit receiver
- Converters are now internal objects
- Closing a session resets connection state - `UNLISTEN *` when it subscribed, aborting an unfinished `COPY`, rolling back a transaction left open by hand-written SQL
- Using a session from inside a `forEach` block or a converter now fails fast with `EXECUTION_IN_PROGRESS` instead of desynchronizing the connection
- `isValid` no longer shortens the deadline of a query running on another thread, never relaxes a configured socket timeout, and reports misuse instead of answering `false`
- `OctaviusTemplate` translates failures to obtain a connection, and the Spring translator recognizes an `OctaviusException` anywhere in the cause chain - a pool that could not connect now surfaces as `OctaviusDataAccessException` wrapping `InitializationException` instead of a generic pool timeout
- `Multirange`/`Range` parameter converters resolve the type OID for auto-registered composites
- Auto-registered composites no longer resolve their OID twice
- `toUrl` no longer renders the password, and `additionalProperties` is copied before being mutated for the startup message
- `PgSslUpgrader` merged into `SslNegotiator`
- `Authenticator` is now a singleton
- `hikari` module renamed to `hikari-integration-tests`

#### Removed
- `UNSUPPORTED_ISOLATION_LEVEL`, `INVALID_TIMEOUT` and `NULL_SQL` reasons - replaced by `INVALID_ARGUMENT`
- `CompositeRegistration`
- `PgSslUpgrader`
- Naming conventions from `ReflectionCache`

## Version 0.9.4 (v0.9.4)

#### Added

- Codecs for `xml`, `bit`/`varbit` (`java.util.BitSet`), network types (`inet`, `cidr`, `macaddr`, `macaddr8` as `String`) and geometric types (`point`, `line`, `lseg`, `box`, `path`, `polygon`, `circle`)
- `PgPoint`, `PgLine`, `PgLseg`, `PgBox`, `PgPath`, `PgPolygon`, `PgCircle` data classes
- `XML` and `XML_ARRAY` in `PgStandardType`
- `NegotiateProtocolVersionMessage` - a server that does not speak protocol 3.2 now fails with `InitializationException(UNSUPPORTED_SERVER_VERSION)` naming the highest minor version it supports, instead of an unrecognized message
- `benchmarks` module with JMH benchmarks against pgjdbc - simple types, structural data, arrays and inserts
- Documentation for quickstart, session initialization, Spring integration and performance
- Spring Boot example app in `examples/spring-app`
- Codec, SSL and unsupported-version integration tests, plus CI jobs running them against an `ssl=on` server and PostgreSQL 17

#### Changed

- Required Java version lowered from 25 to 21
- `CopyIn` and `CopyOut` are now classes - `CopyInImpl` and `CopyOutImpl` are gone
- `CodecException` carries the type `name` and `schema` instead of `pgType`, and codecs now fill them in
- `NumericCodec` throws on `Infinity` and `-Infinity` - `BigDecimal` cannot represent them, so they were silently decoded as garbage
- `driver-spring-integration` exposes `driver` and `spring-boot-starter-jdbc` as `api` dependencies
- Only `driver` and `driver-spring-integration` are published
- Exception details in `PgComposite` are in English
- README states the requirements - Java 21+ and PostgreSQL 18+

#### Removed

- `CopyIn` and `CopyOut` interfaces
- `transactionStepIndex` and `withTransactionStep` from `QueryContext`
- `docs/properties.md` - merged into `initialization.md`

## Version 0.9.3 (v0.9.3)

#### Added

- `ConcurrencyException` with reasons `LOCK_NOT_AVAILABLE`, `DEADLOCK_DETECTED`, `SERIALIZATION_FAILURE`, `UNKNOWN`
- `ExecutionAbortedException` with reasons `TRANSACTION_TIMEOUT` and `QUERY_CANCELED`
- `TransactionIsolationLevel` enum
- `PgName` annotation - replaces `MapKey`
- Non-reified `TypeManager.registerAutoComposite(KClass, ...)`, `TypeManager.registerEnum(KClass, ...)` and `ConverterRegistry.registerAutoCompositeType(kClass, ...)` - registration works when the class is only known at runtime
- `DeserializationContext.convert` overload taking a source OID instead of a `PgType`
- `JsonObject`, `JsonArray` and `JsonPrimitive` are now accepted as result types for `json`/`jsonb`, not just `JsonElement`
- Documentation for COPY and large objects
- Tests for Spring auto-configuration and bean back-off, and for transaction isolation levels

#### Changed

- `session.transactionIsolationLevel` is a `TransactionIsolationLevel` instead of a `java.sql.Connection` int constant
- `25P03`/`25P04` and `57014` now translate to `ExecutionAbortedException`, `40001`/`40P01`/`55P03` to `ConcurrencyException`
- `TypeManager.registry` is now private - `typeDictionary` and `converterRegistry` are the public entry points
- `registerAutoCompositeType` is no longer `inline` with a `reified` parameter

#### Fixed

- `slf4j-api` added as an `implementation` dependency - `KotlinLogging` no longer throws `ClassNotFoundException` at runtime

#### Removed

- `TransactionException` and `TransactionExceptionReason`
- `MapKey` annotation
- `typeRegistry` from `PgComposite` and `PgRange`, along with `PgComposite.getAttributeType`
- `typeRegistry` from `OctaviusQuery`

## Version 0.9.2 (v0.9.2)

#### Added

- `initialParameterWriterCapacity` and `maxParameterWriterCapacity` connection properties - initial and maximum size in bytes of the per-connection parameter buffer (defaults `1024` and `65536`), documented in `docs/properties.md`

#### Changed

- The buffer for serialized query parameters is allocated once per connection and reused between queries instead of being created on every execution - it is cleared before each use and shrinks back to the initial capacity when it grows past the maximum
- `QueryExecutor` and `ParameterSerializer.serializeAll` take `Array<out Any?>` instead of `List<Any?>`, and `serializeAll` writes into a passed-in writer instead of returning a new one - no `toList()` copy per call, and named parameters are collected straight into an array
- The connection socket sets `tcpNoDelay` - no Nagle delay on small protocol messages
- README wording

## Version 0.9.1 (v0.9.1)

#### Added

- COPY protocol support - `session.copy` (`CopyManager`) with `copyIn`/`copyOut`, streaming `CopyIn`/`CopyOut` handles and `InputStream`/`OutputStream` overloads
- Large object support - `session.largeObjects` (`LargeObjectManager`) with `create`, `open` and `unlink`, and `LargeObject` offering `read`, `write`, `seek`, `tell`, `truncate`, `inputStream()` and `outputStream()`
- `NoticeHandler` and `PgNotice` - server notices can be intercepted by a custom handler set through the `noticeHandler` connection property
- Documentation for queries, transactions, LISTEN/NOTIFY, exceptions and Octavius vs JDBC, plus KDoc across the public API
- Dokka API documentation published to GitHub Pages
- Publication to Maven Central - POM metadata, signing and a release workflow
- Tests for COPY, large objects, notice handling, session lifecycle and Spring exception translation

#### Changed

- Every `*ExceptionMessage` enum is now `*ExceptionReason`, and the `messageEnum` property on exceptions is now `reason`
- `InvalidOperationExceptionReason.STATEMENT_CLOSED` renamed to `OBJECT_CLOSED` - it also covers closed large objects
- `DataException` and `ConstraintViolationException` carry `dbMessage`, `details` and `where` taken from the server's `ErrorResponse` instead of one concatenated string
- `OctaviusException.toString()` reformatted - message, SQLSTATE, details, query context and cause in fixed sections
- `ParameterConverter.getDefaultOid` replaced by `getDefaultTypeName` returning a `QualifiedName` - declaring a default type no longer needs an OID lookup
- Spring classes moved from `io.github.octaviusframework.spring` to `io.github.octaviusframework.driver.spring`
- Spring auto-configuration builds a `JdbcTransactionManager` with `OctaviusExceptionTranslator` instead of a plain `DataSourceTransactionManager`
- `PgByteWriter` moved from the `codec` package to `io`
- Collections are flattened and converted in a single pass, and serialized parameters are written straight to the output stream without an intermediate array copy
- A `DataRow` arriving before `RowDescription` throws `InvalidOperationException(UNEXPECTED_RESULT)` instead of `IllegalStateException` (impossible state in normal PostgreSQL communication)
- README reworked and docs reworded

#### Fixed

- `SQLExceptionWrapper` no longer leaks through the Spring integration - `OctaviusDataAccessException` always wraps the original `OctaviusException`, and a `SQLException` that cannot be translated becomes Spring's `UncategorizedSQLException`

#### Removed

- `OctaviusInternalException` - unreachable paths use `error()` now

## Version 0.8.9 (v0.8.9)

#### Added

- `forEachRow`, `forEachObject` and `forEachField` on native and named-parameter queries - rows are streamed from a portal in batches of `fetchSize` instead of being materialized into a list
- `ContainerFactory`, reachable as `typeManager.containers` - `createComposite`, `createRange`, `createEmptyRange` and `createMultirange` moved out of `TypeManager`
- `TypeDictionary` and `CodecDictionary` - immutable type and codec lookups split out of `TypeRegistry`, with `getRangeType(elementOid)` and `getMultirangeType(rangeOid)` alongside the array lookup
- `ConverterRegistry` - result converters, parameter converters and composite registrations in one place, exposed as `typeManager.converterRegistry`
- `CodecException` - encoding and decoding failures report the action, the offending value, the PostgreSQL type and the Kotlin class
- `rangeOf` and `multiRangeOf` helper functions
- `supportedClass` and `getDefaultOid` in `ParameterConverter`
- `path` on `MappingException` - nested conversions record where they failed
- `Flush` and `Close` frontend messages
- Documentation for functions and procedures
- Test suites for codec, mapping, type, network, data, constraint, permission, routine, statement and transaction exceptions, plus complex data and record integration tests

#### Changed

- `TypeRegistry` is now just a holder for the dictionaries and the converter registry
- `ParameterConverter.canConvert` takes a `KClass` and a `SerializationContext` instead of the value and a `TypeManager`; `convert` gets the typed source and reads the `TypeManager` from the context
- `ResultConverter.canConvert` likewise takes a `KClass` and the `DeserializationContext`
- `Range<T>` and `MultiRange<T>` require `T : Any` and carry `elementClass`
- `PgArray` and `PgRecord` no longer hold a `TypeRegistry`
- Deserializing into `Any` resolves enums, collections and registered composites instead of failing
- Exception messages are prefixed with the exception type - e.g. `TYPE_EXCEPTION:MISSING_CODEC`
- `MappingException` reasons trimmed to `NO_CONVERTER_FOUND`, `COLUMN_NOT_FOUND`, `CONVERSION_ERROR` and `REQUIRED_ATTRIBUTE_MISSING`
- `TypeException` reasons trimmed to `TYPE_NOT_FOUND`, `NOT_A_CONTAINER`, `MISSING_CODEC`, `ANONYMOUS_RECORD_NOT_SUPPORTED` and `NESTED_PGTYPED_NOT_ALLOWED`
- `TransactionException` reasons `ROLLBACK` and `INVALID_TRANSACTION_STATE` replaced by `DEADLOCK_DETECTED` and `SERIALIZATION_FAILURE`
- `ParameterMapper` and `ResultMapper` wrap anything a converter throws in a `MappingException` carrying the path
- Built-in converters throw plain Kotlin exceptions for their own validation - the mappers translate them
- Array parameters resolve their element type from `PgContainer` values as well

#### Fixed

- A codec or type exception thrown while reading a row no longer desynchronizes the protocol - the error is held until the server finishes the exchange
- `getOctaviusSession` merges properties parsed from the URL with the explicitly passed `OctaviusProperties` instead of dropping the URL ones
- Reading an array containing nulls into a collection of a non-nullable type throws instead of returning nulls

#### Removed

- `getCodecByOid`, `getCodecByClass`, `getOidForCodec` and `resolveOid` from `TypeRegistry`
- Container factory methods from `TypeManager`
- `set` operator on `PgArray`

## Version 0.8.1 (v0.8.1)

#### Added

- `fetchObjectStrict` on `NativeQuery` and `NamedParameterQuery` - the object counterpart of `fetchRowStrict`/`fetchFieldStrict`, throws unless the result is exactly one row

#### Changed

- Terminal query methods renamed to one `fetch<What>` / `fetch<What>Strict` scheme on both `NativeQuery` and `NamedParameterQuery`:
    - `fetchAll` → `fetchRows`
    - `fetchOne` → `fetchRow`
    - `fetchOneStrict` → `fetchRowStrict`
    - `fetchListOf` → `fetchObjects`
    - `fetchSingleOf` → `fetchObject`
    - `fetchColumn` → `fetchFields`
    - `fetchField` and `fetchFieldStrict` keep their names
- `fetchObject` returns `T?` and no longer throws on an empty result - `fetchObjectStrict` covers the old behaviour
- Result-size exception messages unified across all terminal methods

## Version 0.8.0 (v0.8.0)

#### Added

- A full exception hierarchy, one class per file - `DataException`, `ConstraintViolationException`, `StatementException`, `TransactionException`, `InitializationException`, `InvalidOperationException`, `PermissionDeniedException`, `RoutineExecutionException`, `DatabaseSystemException`, `MappingException`, `TypeException` and `OctaviusInternalException`
- `ExceptionTranslator` routes SQLSTATE classes 08, 0A/21/3D/3F, 22, 23, 25, 28, 40, 42, 53/54/55/57/58/XX to those exceptions with a specific reason instead of returning a bare `OctaviusException` with a text message
- `ConstraintViolationException` carries `schema`, `table`, `column` and `constraint` from the server's `ErrorResponse`; `StatementException` carries the error position
- `MappingException` - deserialization failures were plain `IllegalArgumentException` before
- `INCORRECT_RESULT_SIZE` and `MISSING_NAMED_PARAMETER` reasons on `StatementException`, thrown inside the query context so the failure reports the SQL and parameters
- `UNRESOLVED_OID` constant and the `Int.isKnownOid` extension
- `containerOid` on `PgContainer`
- Integration tests for constraint violations and statement exceptions, plus more parameter serializer tests

#### Changed

- `OctaviusTypeException` renamed to `TypeException`, `BadStatementException` to `StatementException`
- `AuthException` and the connection-level parts of `OctaviusJdbcException` (`INVALID_URL`, `SSL_ERROR`, `UNSUPPORTED_SERVER_VERSION`) merged into `InitializationException`
- The remaining `OctaviusJdbcException` reasons and `UnsupportedFeatureException` merged into `InvalidOperationException`
- `ConstraintViolationExceptionMessage` renamed to `ConstraintViolationExceptionReason`, same for `StatementException` and `TransactionException`
- Result-size checks throw `StatementException` instead of `check()` failing with `IllegalStateException`, and they run inside the query context
- `expectedOid` in the parameter converters is a plain `Int` using `UNRESOLVED_OID` instead of `Int?`
- Nested `PgTyped` throws `TypeException(NESTED_PGTYPED_NOT_ALLOWED)` instead of being silently wrapped
- `PgTyped` and `withPgType` moved to their own file; `PgStandardType.kt` renamed to `Oid.kt`
- `TypeRegistryLoader.load` no longer takes a search path, and the registry uses a single lock instead of a separate load lock

#### Fixed

- A codec that is not bound to an OID resolves it by type name instead of falling back to `UNRESOLVED_OID`
- `isClosed()` and the internal close check detect a broken stream, so a dead connection reports itself as closed instead of handing out a usable one (matters for pool eviction)
- `fetchSingleOf` throws when the query returns no rows and the target type is not nullable

#### Removed

- `Exceptions.kt` monolith, `AuthException` and `UnsupportedFeatureException`

## Version 0.7.0 (v0.7.0)

#### Added

- `NetworkException` with reasons `CONNECTION_ERROR`, `CONNECTION_TIMEOUT`, `CONNECTION_CLOSED`, `CONNECTION_CLOSED_BY_PEER` and `CONNECTION_ABORTED` - IO failures no longer surface as raw `IOException`/`SocketTimeoutException`
- `fetchOneStrict` and `fetchFieldStrict` on `NativeQuery` and `NamedParameterQuery`
- `notificationBufferCapacity` connection property (default `256`)
- Server notices are logged under a dedicated logger name, `io.github.octaviusframework.driver.Notice`, mapped by severity (`WARNING` → warn, `NOTICE`/`INFO`/`LOG` → info, `DEBUG` → debug), so they can be filtered on their own in logback
- `docs/properties.md`, plus KDoc for `PgStream` and the wire-protocol messages
- `NotificationManagerTest`

#### Changed

- `fetchOne` returns `Row?` - the old strict behaviour lives in `fetchOneStrict`, and `fetchOneOrNull` is gone
- `fetchField` returns `T?`, with `fetchFieldStrict` for the strict variant
- `fetchSingleOfOrNull` folded into `fetchSingleOf`, which no longer requires `T : Any`
- Object and single-column methods deserialize row by row inside the query context instead of fetching a `Row` first, so a mapping failure reports the SQL and parameters
- `PgStream` and `SslNegotiator` are internal, and `OctaviusConnection`, `QueryExecutor`, `NativeQuery`, `NamedParameterQuery` and `OctaviusQuery` only have internal constructors
- `OctaviusTemplate.execute` hands the block an `OctaviusSessionOperations` instead of an `OctaviusSession` - transaction management stays with Spring
- The notification listener rethrows a genuine network failure instead of quietly ending the loop; it exits silently only when the connection was closed or the coroutine cancelled
- Polling for notifications returns `null` on a socket timeout instead of throwing
- `TypeManager`, `NotificationManager` and `TransactionManager` on a session, and the cached attribute lists on `PgType.Composite`, are built eagerly instead of `by lazy`
- `additionalProperties` are sorted when rendering a URL, so `toUrl()` is stable

#### Fixed

- A blank identifier is quoted like any other name instead of being turned into an empty string

## Version 0.6.2 (v0.6.2)

#### Added

- `maxCachedRowSize` connection property (default `65536` bytes) - a row bigger than this gets its own array instead of the shared buffer, so a single huge value does not pin memory

#### Changed

- `DataRow` deserialization reuses per-connection buffers for the row bytes and the column offset/length arrays instead of allocating three arrays per row - the arrays in `DataRowMessage` are valid only until the next row is read, which is safe because `Row` parses every value eagerly
- `PgInputStream.readFully` takes an optional length, so it can fill part of a larger buffer

## Version 0.6.1 (v0.6.1)

#### Added

- `Range<T>` and `MultiRange<T>` - plain Kotlin views of PostgreSQL ranges and multiranges, with result and parameter converters, so custom ranges subtypes can be mapped by other converters instead of only by codecs
- `PgInterval` (`Finite`, `Infinity`, `MinusInfinity`) and its codec for the `interval` type
- Conversions between `PgInterval` and kotlinx-datetime types - `toDateTimePeriod`, `toPgInterval`, `toDurationExact`, `toPgIntervalExact`, `toDurationApproximate`, `toPgIntervalApproximate`
- `DateTimePeriod.INFINITY` and `DateTimePeriod.MINUS_INFINITY` markers for PostgreSQL's infinite intervals
- Range converter, range integration and interval integration tests

#### Changed

- `PgInputStream` and `PgOutputStream` use their own 8 KB buffer instead of `BufferedInputStream`/`BufferedOutputStream` and `DataInputStream`
- Registering a codec for a sealed class also maps its subclasses, so `PgInterval.Finite` and the infinity objects resolve to the interval codec
- `QueryExecutor` locks on the shared `PgStream.lock` instead of its own lock, and the notification listener takes the same lock while polling - a listener and a query can no longer read from the socket at once
- `NumericCodec` writes into the `PgByteWriter` directly instead of building an intermediate byte array
- `OctaviusTemplate` releases the connection with `DataSourceUtils.releaseConnection`
- `docs/type-system.md` covers ranges and intervals

#### Fixed

- `getOctaviusSession(url, user, password)` parses the properties from the URL instead of starting from an empty set, so settings carried in the URL are no longer dropped

#### Removed

- Unused `toByteArrayBE` helpers and `setShortBE` from `ByteExtensions`

## Version 0.6.0 (v0.6.0)

#### Added

- `driver-spring-integration` module - `OctaviusTemplate`, `OctaviusSpringAutoConfiguration` (registers the template and a `DataSourceTransactionManager` with nested transactions enabled), `OctaviusExceptionTranslator` and `OctaviusDataAccessException`
- `OctaviusProperties` - typed connection settings (`serverName`, `portNumber`, `databaseName`, `user`, `password`, `loginTimeout`, `socketTimeout`, SSL settings) with URL parsing, merging and `toUrl()`; unrecognized keys land in `additionalProperties` and are sent as startup parameters
- `OctaviusConnectionFactory` - connection creation extracted from `OctaviusDriver` and shared by the driver, the data source and `getOctaviusSession`
- JDBC savepoints on `OctaviusConnection` - `setSavepoint()`, `setSavepoint(name)`, `rollback(savepoint)` and `releaseSavepoint`, with a public `OctaviusSavepoint` interface
- `Connection.getOctaviusSession()`
- GitHub Actions workflow running the test suite
- Data source initialization, Hikari initialization and Spring integration tests

#### Changed

- `OctaviusDataSource` is backed by `OctaviusProperties` and exposes bean-style `serverName`, `portNumber` and `databaseName` setters, so a pool can configure it without a URL
- Property names follow the JDBC bean convention - `host` → `serverName`, `port` → `portNumber`, `database` → `databaseName` - and `sslmode` is an `SslMode` enum instead of a string
- `OctaviusProperties` holds its own fields instead of wrapping `java.util.Properties`
- Property values are URL-encoded when rendering a URL and decoded when parsing one
- `OctaviusSessionImpl` unwraps the Octavius connection itself instead of taking it as a second constructor argument
- `OctaviusSavepoint` split into a public interface and an internal `OctaviusSavepointImpl` implementing `java.sql.Savepoint`
- `isClosedFlag` on the connection is `@Volatile`
- Version catalogs split - `gradle/spring.versions.toml` and `gradle/hikari.versions.toml` alongside `libs.versions.toml`, and the Hikari tests moved into the `hikari` module

## Version 0.5.2 (v0.5.2)

#### Added

- `OctaviusSessionOperations` - the session API without transaction lifecycle control, so a transaction block cannot call `commit`, `rollback` or flip `autoCommit`; `OctaviusSession` extends it
- `TransactionManager.required` and `TransactionManager.nested` - `required` joins an active transaction or starts a new one, `nested` creates a savepoint when a transaction is already running
- `RowMetadata` and `FieldDescription` - the row description is parsed once per result instead of per row
- `DynamicContainerCodec` - a single codec for arrays, composites, ranges, multiranges and records, delegating to `ContainerCodec`
- `UnsupportedFeatureException` with reasons `UNSUPPORTED_ISOLATION_LEVEL`, `INVALID_TIMEOUT`, `UNWRAP_ERROR`, `FEATURE_NOT_SUPPORTED` and `NULL_SQL`
- `SQLExceptionWrapper` - the JDBC surface wraps an `OctaviusException` in a `SQLException`, so HikariCP's connection reset sees the exception type it expects
- `PortalSuspended` message handling and `maxRows` support in `QueryExecutor`
- KDoc for the session, transaction manager and connection

#### Changed

- `Row` is a class instead of an interface, and moved from the `query` package to `row` together with `FieldDescription`
- `fetchOne` and `fetchOneOrNull` run with `maxRows = 2` instead of fetching the whole result just to check the count
- `TransactionManager`'s `invoke` operator replaced by the explicit `required` and `nested` functions
- Standard codecs are named after their PostgreSQL types - `StringCodec` → `TextCodec`
- `OctaviusAuthException` renamed to `AuthException`
- Connection methods route their failures through a shared `wrapSqlException` helper

#### Fixed

- `NumericCodec` reads `sign` and `dscale` as unsigned shorts, and `NaN` throws `OctaviusTypeException(VALUE_OUT_OF_RANGE)` instead of a bare `error()`
- The SSL private key is decoded by dropping every PEM header line, so keys with headers other than `PRIVATE KEY`/`RSA PRIVATE KEY` load correctly

## Version 0.5.1 (v0.5.1)

#### Added

- `notifications.listen`, `unlisten`, `unlistenAll` and `notify` on `NotificationManager` - channel names are quoted as identifiers, no hand-written `LISTEN` needed
- `QueryContext` and `ExceptionTranslator` - a failed query reports the high-level SQL, the SQL actually sent and the parameters, and SQLSTATE codes are categorized instead of surfacing raw error text
- `OctaviusDispatchers` - a shared virtual-thread `ExecutorService` and the `Virtual` coroutine dispatcher
- `readOnly`, `networkTimeout` and `isValid(timeout)` on `OctaviusSession`
- `DISTANT_PAST`/`DISTANT_FUTURE` for `LocalDate` and `LocalDateTime`, plus `LocalTime.MIN`/`MAX`, mapping PostgreSQL's `infinity`, `-infinity` and `24:00:00`
- Type name and array-type caches in `TypeRegistry` - `typesByName` and `getArrayTypeByElementOid`, so resolving a type by name or finding an element's array type no longer scans every type
- `codecToOid` map, a `loaded` flag on `TypeRegistry`, and logging across authentication, the stream and registry loading
- `docs/type-system.md`, codec KDoc, and codec registration, datetime and notification tests

#### Changed

- Every query parameter is serialized into one shared `PgByteWriter` with the OIDs collected in an `IntArray`, instead of one byte array per parameter
- Parameter serialization moved inside `QueryExecutor`
- `PgType.Record` and `PgType.Void` are singletons instead of data classes carrying an OID
- `resolveOid` returns just the OID
- `codecsByName` removed - lookups go through `codecToOid` and the name cache
- Query methods use a `ReentrantLock` instead of `synchronized`
- The session delegates the plain JDBC methods to the raw connection, and `octaviusConnection` is private
- Aborting a session throws a `SQLException` with SQLSTATE `08000` so HikariCP evicts the connection, replacing the reflective call into Hikari internals
- `PgArray` dropped `setElement`/`setDimension` in favor of flat indexing
- Root `build.gradle.kts` holds the shared configuration, and HikariCP moved into the version catalog
- Exceptions during connection close are suppressed

#### Fixed

- A socket timeout while polling for notifications no longer marks the connection as broken - only a timeout in the middle of a message does

## Version 0.5.0 (v0.5.0)

#### Added

- `OctaviusSession` - the Kotlin-facing API over a JDBC `Connection`: queries, `types`, `notifications`, `transaction`, savepoints, search path, transaction state and `abort()` (which forces a pool like HikariCP to evict the connection)
- `getOctaviusSession` extensions for `DataSource` and `Connection`
- SSL support - `SslMode` (`disable`, `prefer`, `require`, `verify-ca`, `verify-full`), `SslNegotiator`, `PgSslUpgrader` and `SslConfiguration`, plus SSL fields and a login timeout on `OctaviusDataSource`
- SCRAM server signature verification - the server's proof in the SASL final message is checked instead of being ignored
- Multi-module build - the driver moved into a `driver` subproject and a `hikari` module holds the HikariCP integration test
- `IntObjectMap` - an int-keyed map used for OID lookups instead of boxing keys
- `PrimitiveArrayConverter` and `CollectionArrayConverter` - one converter per array shape, resolving the element type once instead of per element
- `TypeManager.registerCodec`
- `QualifiedName`, and `CompositeRegistration` in the registry package
- Transaction integration tests

#### Changed

- `ResultConverter` takes two type parameters (source and target) instead of one
- `query` accepts a mapper
- `connection.transactions.transaction { }` is now `session.transaction { }`, and `transactions.savepoint` is `withSavepoint`; `TransactionManager` moved to the `transaction` package and works on a session - it opens a transaction when auto-commit is on and creates a savepoint when one is already running
- Savepoints split out of the connection into `OctaviusSavepoint`
- `PgStream` owns the whole protocol conversation, pulled out of `OctaviusConnection`
- Packages reorganized - `registry`, `container`, `identifier`, `message`, `session`, `ssl` and `transaction`
- Converter registries iterate by index instead of allocating iterators and lambdas per lookup
- Composite attribute OIDs are stored directly, `attributeNames` is cached, and arrays are created with `arrayOfNulls`
- Parameters are written through `PgByteWriter`
- `AuthException` moved to its own file, and the auth package documented
- Exceptions inherits from `RuntimeException` instead of `SQLException`

#### Fixed

- The enum converter resolves the type OID through the registry and checks the expected Kotlin class, instead of matching on the type name alone
- The enum parameter converter accepts subclasses of an enum class (enum constants with bodies)

## Version 0.4.1 (v0.4.1)

#### Added

- Terminal query methods beyond raw rows - `fetchListOf`, `fetchSingleOf`, `fetchSingleOfOrNull`, `fetchField` and `fetchColumn` on `NativeQuery` and `NamedParameterQuery`
- `cancelQuery()` - opens a second connection and sends a `CancelRequest` for the running statement
- Protocol 3.2 support, including the variable-length cancel key in `BackendKeyData`
- Multidimensional array parameters - nested collections are flattened with their dimensions
- Primitive array support - `PrimitiveArrayParameterConverter` and `PrimitiveArrayConverter` handle `IntArray`, `LongArray` and others
- `ParameterMapper` - parameter conversion pulled out of `ParameterSerializer`
- `PgNotification` data class, and `NotificationManager` moved to its own `notification` package
- Publishing to the local Maven repository

#### Changed

- Converter packages reorganized - `converter/parameter/{array,composite,mapper,standard}` and `converter/result/{array,composite,mapper,record,row,standard}`, and the container types moved from `type.containter` to `type.container`
- `ResultConverter` declares the source class it supports, so the registry looks converters up by class instead of trying all of them
- `QueryExecutor` builds `Row` objects while reading the stream instead of collecting `DataRowMessage`s and mapping afterwards
- OIDs are plain `Int` instead of `UInt`
- Array elements and range bounds carry nullability, so a null element in a non-nullable position is reported
- Wire protocol messages are internal
- Codecs read straight from the raw byte array with an offset and length

#### Fixed

- The reflection composite converter takes the parameter type from its cached metadata
- `_record` (array of records) is loaded from the catalog and mapped as an array type, so an array of records deserializes

#### Removed

- `ByteArrayWindow` and its extensions
- `ContainerField`


## Version 0.4.0 (v0.4.0)

First tagged release.

#### Added

**Protocol and connection**

- PostgreSQL Wire Protocol v3 spoken directly, with no other driver wrapped underneath - startup, authentication, query and termination messages implemented on both sides
- SCRAM-SHA-256 authentication; cleartext and MD5 requests are rejected with a clear message
- SSL/TLS negotiation
- PostgreSQL 18+ enforced - an older server is rejected during connect
- `PgStream`, `PgInputStream`, `PgOutputStream` and `PgByteWriter` - the binary IO layer over the socket

**JDBC surface**

- `OctaviusDriver` (registered through `META-INF/services`), `OctaviusDataSource`, `OctaviusConnection` and a minimal `OctaviusStatement` - enough of `java.sql` to run under a pool like HikariCP
- Transactions - auto-commit control, commit and rollback, isolation levels, read-only mode, `TransactionManager` for transaction blocks, and savepoints through `OctaviusSavepoint`
- `isValid`, network timeout, and reading and setting the `search_path`

**Queries**

- `createNativeQuery` with positional parameters, and `createNamedQuery` with named parameters translated by `SqlParameterParser` - which understands quotes, dollar quoting, comments and escape literals
- `fetchAll`, `fetchOne`, `fetchOneOrNull`, `update` and `execute`
- Extended Query Protocol (Parse/Bind/Execute/Sync) by default, with the Simple Query Protocol kept for statements like `BEGIN` and `SET`
- `Row` - values decoded eagerly, read by index or column name
- `LISTEN`/`NOTIFY` exposed as a coroutine `SharedFlow`, with a listener loop and a drop-oldest buffer

**Types**

- `TypeRegistry` loaded from the system catalog, cached per URL by `GlobalTypeRegistry`, resolving names against the search path
- Binary codecs for the standard types - integers, floats, `numeric`, `bool`, `text`/`varchar`/`bpchar`, `bytea`, `uuid`, `json`/`jsonb`, the date and time types, `void` and `record`
- Container types - `PgArray` (multidimensional), `PgComposite`, `PgRange`, `PgMultirange` and `PgRecord` - with dynamic codecs and factory methods on `TypeManager`
- Dynamic codecs for user-defined enums and domains
- Automatic composite mapping from Kotlin data classes (`registerAutoComposite`) and enum mapping (`registerEnum`), with configurable naming conventions and the `@MapKey` annotation
- Result converters for data classes, maps and records, parameter converters for collections, primitive arrays, composites and `JsonElement`, all pluggable through the converter registries
- `PgTyped` and `withPgType` for pinning a parameter's PostgreSQL type explicitly
- `OctaviusException` hierarchy with enum-based reasons
