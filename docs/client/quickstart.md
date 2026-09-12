# Quickstart

*On the march a legionary carried the lot: stakes, tools, rations, roped to a pole over his shoulder. Inside
the camp he set it down, because the camp already had them. Same soldier, same work — what changed was how
much of it he had to have on him.*

> **Note:** the client is a layer over the [driver](../driver/README.md), not a replacement for it. If you have
> not connected yet, start with the driver's [Quickstart](../driver/quickstart.md); everything below assumes a
> working `DataSource`.

## 1. Add the Dependency

```kotlin
dependencies {
    implementation("io.github.octavius-framework:client:1.0.0")

    // Used by the example below, and by most applications
    implementation("com.zaxxer:HikariCP:7.1.0")
}
```

The driver arrives with it as an `api` dependency — there is no second coordinate to add and no version to keep
in step. Everything the driver brings with it comes too: `kotlinx-datetime`, `kotlinx-serialization-json`,
`kotlinx-coroutines-core`, and the `pg-model` module.

Scanning the classpath for annotated types is a separate coordinate, because it is the only part that needs a
classpath-walking dependency. See [Annotation Scanning](scanner.md).

## 2. Build the Client

```kotlin
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.OctaviusClient

val dataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:octavius://localhost:5432/res_publica"
    username = "postgres"
    password = "…"
})

val db = OctaviusClient.fromDataSource(dataSource)
```

One client per database, built once and kept. It holds no connection of its own: every operation borrows one
from the pool and gives it straight back.

`ownsDataSource` is `false` by default, so `db.close()` closes what the client built and leaves your pool
alone. Pass `true` only where the client is the only thing using it.

Running under a framework that owns its own transactions — Spring, most often — means giving the client a
`SessionProvider` that finds the session where that framework put it, through
`OctaviusClient.fromSessionProvider`. See
[`SessionProvider`](transactions-failures.md#sessionprovider), which is about thirty lines for Spring.

## 3. Run Something

```kotlin
data class Senator(val id: Int, val cognomen: String, val provinceId: Int)

val senators = db.select("id", "cognomen", "province_id")
    .from("senate")
    .where("province_id = @province")
    .orderBy("cognomen")
    .fetchObjects<Senator>("province" to 7)
```

Or write the SQL out, which changes nothing about how it runs:

```kotlin
val senators = db.rawQuery("SELECT id, cognomen, province_id FROM senate WHERE province_id = @province")
    .fetchObjects<Senator>("province" to 7)
```

Both are a `RunnableQuery`, both carry the same terminal family, and both find their own session when a
terminal is called. Nothing is sent until then, and calling two terminals runs the query twice.

A transaction wraps whatever you put in it:

```kotlin
db.transaction {
    val id = insertInto("edicts").values(listOf("title")).returning("id")
        .fetchFieldStrict<Int>("title" to "De Tributis")

    insertInto("edict_items").values(listOf("edict_id", "province"))
        .update("edict_id" to id, "province" to "Gallia")
}
```

The receiver inside is the client itself, so the queries read exactly as they do outside it.

## Where the Session Went

This is the whole of what the client adds that the driver could not:

```kotlin
// The driver: the session is a parameter, so every caller has to have one
fun findSenators(session: OctaviusSessionOperations, provinceId: Int): List<Senator>

// The client: it isn't
fun findSenators(provinceId: Int): List<Senator> =
    db.select("id", "cognomen", "province_id").from("senate").where("province_id = @p")
        .fetchObjects("p" to provinceId)
```

Called on its own, that function borrows a session and gives it back. Called from inside `db.transaction { }`
on the same thread, it joins that transaction, commits with it and rolls back with it — without a parameter
saying so and without knowing which it is. That is the question the driver leaves open, being
session-per-connection, and the one the client answers.

Where the work is not a query at all — a [`COPY`](../driver/copy.md), or several statements that must share a
session — `db.execute { }` hands over the driver's own session operations and gets out of the way:

```kotlin
db.execute {
    copy.copyIn("COPY census(name, tribe) FROM STDIN WITH (FORMAT csv)", file.inputStream())
}
```

**The session lives for the block and no longer**, which decides what belongs here and what does not:

* **[Large objects](../driver/large-objects.md) want `db.transaction { }` instead.** A descriptor is only valid
  inside the transaction that opened it — PostgreSQL's rule, not the driver's — and under `execute` each
  statement commits on its own, so the descriptor is dead by the first write.
* **A [listener](../driver/listen-notify.md) wants the driver directly.** A subscription lives on the
  connection, and a session issues `UNLISTEN *` on its way back to the pool — so a `LISTEN` registered inside
  `execute { }` is gone at the closing brace, with nothing said about it. Something that listens holds its
  connection for as long as it listens, which is a session you keep rather than one the client lends you for a
  block.

## Next

- [Queries](queries.md) — the builders, and what they do and do not touch
- [Transactions and Failures](transactions-failures.md) — propagation, timeouts, and when a failure is a value
- [`dynamic_dto`](dynamic-dto.md) — one column holding several unrelated shapes
