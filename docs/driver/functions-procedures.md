# Functions and Procedures

*Rome distinguished sharply between a question put to an official and an order given to one. Ask the censor for a
citizen's rating and you are owed an answer; instruct a lictor and you are owed the act, not a reply. PostgreSQL keeps
that same distinction between a function and a procedure, and most of this page follows from it — starting with which
command you use to invoke each.*

Calling PostgreSQL functions and procedures through Octavius uses the same query API as everything else — `createNativeQuery` and `createNamedQuery`, the same `fetch*` and `update()` methods, the same type mapping. There is **no `CallableStatement`**: no `{call ...}` escape syntax, no `registerOutParameter(2, Types.INTEGER)` before execution, no `getInt(2)` after it. A routine call is ordinary SQL that happens to name a routine.

What is left to know is therefore not about the driver but about PostgreSQL itself: which command invokes what, and what shape comes back. Those are the parts worth reading below, borrowing a small slice of the Roman civil service for its examples.

## Sending parameters in

Parameters go in exactly as they do for any other query — positionally with `$1`, or by name with `@name`. See [Queries](queries.md) for the full API and [Type System](type-system.md) for how values are converted.

```kotlin
// CREATE FUNCTION calculate_tributum(amount numeric, rate numeric) RETURNS numeric

session.createNativeQuery("SELECT calculate_tributum($1, $2)")
    .fetchField<BigDecimal>(BigDecimal("1500.00"), BigDecimal("0.05"))

session.createNamedQuery("SELECT calculate_tributum(@amount, @rate)")
    .fetchField<BigDecimal>("amount" to BigDecimal("1500.00"), "rate" to BigDecimal("0.05"))
```

Because the SQL stays SQL, PostgreSQL's own **named-argument notation** works too, and composes with Octavius named parameters — the `=>` names belong to the routine's signature, the `@` names to the driver's placeholders:

```kotlin
session.createNamedQuery("SELECT calculate_tributum(rate => @rate, amount => @amount)")
    .fetchField<BigDecimal>("rate" to BigDecimal("0.05"), "amount" to BigDecimal("1500.00"))
```

That is the practical payoff of having no `CallableStatement` layer: anything PostgreSQL accepts in a call — named arguments, defaults, `VARIADIC`, casts — passes straight through, because the driver is not rewriting your call into a JDBC escape form.

### Argument types decide which routine runs

Worth knowing before it bites you: PostgreSQL picks the routine from the *types* of the arguments you send. A mismatched Kotlin type therefore does not surface as a conversion error — it surfaces as **"function does not exist"**.

Octavius sends parameters in binary, which means every argument goes out under a concrete type OID rather than as loose text the server can reinterpret. That OID decides the overload before the value is ever looked at, so there is no leniency to fall back on here.

Passing a `Double` to the `numeric` function above makes the server look for `calculate_tributum(double precision, double precision)`, find nothing, and raise a `StatementException(UNDEFINED_OBJECT)` while the `numeric` overload sits right there untouched. Match the type instead — `BigDecimal` for `numeric`. Forcing it with `withPgType("numeric")` does not help either: the codec for `numeric` encodes `BigDecimal` and rejects a `Double` with a `CodecException`.

### Typed NULLs for overloaded routines

When a routine is overloaded, a bare `null` leaves PostgreSQL unable to pick a variant. `withPgType` declares the exact type the `null` carries:

```kotlin
import io.github.octaviusframework.driver.type.withPgType

// CREATE PROCEDURE appoint_consul(candidate_id int, title text)
session.createNativeQuery("CALL appoint_consul($1, $2)")
    .update(12, null.withPgType("text"))
```

## Getting values back

This is the half that actually differs between routine kinds, and all of it follows from PostgreSQL's behavior rather than the driver's:

| Routine shape                      | Invoke with | Read with                           |
|:-----------------------------------|:------------|:------------------------------------|
| Function returning a scalar        | `SELECT`    | `fetchField<T>()`                   |
| Function `RETURNS TABLE` / `SETOF` | `SELECT`    | `fetchRows()` / `fetchObjects<T>()` |
| Function with `OUT` parameters     | `SELECT`    | any `fetch*` — one column per `OUT` |
| Function returning `void`          | `SELECT`    | `fetchField<Unit>()`                |
| Procedure with no `OUT` / `INOUT`  | `CALL`      | `update()`                          |
| Procedure with `OUT` / `INOUT`     | `CALL`      | `fetchRow()` / `fetchField<T>()`    |

### Functions

Functions are called through `SELECT`, and table-returning ones are queried like a table:

```kotlin
// CREATE FUNCTION active_legions() RETURNS TABLE(id int, name text)
val legions = session.createNativeQuery("SELECT * FROM active_legions()")
    .fetchObjects<Legion>()
```

A function with `OUT` parameters returns them as ordinary columns, named after the parameters — so select from it and read the columns by name:

```kotlin
// CREATE FUNCTION province_census(uid int, OUT population int, OUT taxes numeric)
val row = session.createNativeQuery("SELECT * FROM province_census($1)").fetchRowStrict(3)

val population: Int = row.get("population")
val taxes: BigDecimal = row.get("taxes")
```

Being ordinary columns, they are readable with any of the `fetch*` methods — `fetchObject<T>()` maps them onto a data class, `fetchField<T>()` grabs the first one, and so on. Note what did *not* happen: nothing was registered as an output ahead of time, and nothing is read back out of the statement afterwards. The `OUT` values arrive in the result set like any other columns, because to PostgreSQL that is what they are.

### Unnamed OUT parameters

Naming the `OUT` parameters is optional, and PostgreSQL fills in whatever you leave out:

| Signature                                         | Column names            |
|:--------------------------------------------------|:------------------------|
| `(IN int, OUT population int, OUT taxes numeric)` | `population`, `taxes`   |
| `(IN int, OUT int, OUT int)`                      | `column1`, `column2`    |
| `(IN int, OUT population int, OUT int)`           | `population`, `column2` |
| `(IN int, OUT int)` — a single output             | the function's own name |

Two things are easy to get wrong here. The generated numbering follows the **output column position**, not a separate counter for the unnamed ones — which is why the mixed signature above yields `column2` and never `column1`. And a function with exactly one `OUT` parameter is really just a function returning that type, so its single column takes the routine's name rather than a generated one. Procedures behave identically.

None of this matters if you read by index (`row.get<Int>(0)`), which is the reliable option when the signature is not yours to control.

### Void functions

A `void`-returning function still returns a single row carrying one `void` value — it does not simply vanish. Octavius maps that to Kotlin's `Unit`:

```kotlin
// CREATE FUNCTION restore_pax_romana() RETURNS void
session.createNativeQuery("SELECT restore_pax_romana()")
    .fetchField<Unit>()
```

### Procedures

Procedures are invoked with `CALL`. Without `OUT` parameters there is nothing to read, so `update()` is the method you want:

```kotlin
// CREATE PROCEDURE enroll_legionary(cohort_id int, recruit_name text)
session.createNativeQuery("CALL enroll_legionary($1, $2)")
    .update(10, "Marcus Aurelius")
```

`execute()` also works for a procedure taking no arguments, but it runs through the Simple Query Protocol and cannot bind parameters — so `update()` is the general answer.

## OUT and INOUT parameters in a CALL

This is the one place with real friction, and it is PostgreSQL's rule, not the driver's: **`CALL` expects a value in every argument position, including the outbound ones.** You have two ways to satisfy it.

```kotlin
// CREATE PROCEDURE province_census(uid int, OUT population int)

// Option A: put the placeholder in the SQL, pass only the real argument
val population = session.createNativeQuery("CALL province_census($1, NULL)")
    .fetchFieldStrict<Int>(7)

// Option B: pass null explicitly, occupying the second position
val population = session.createNativeQuery("CALL province_census($1, $2)")
    .fetchFieldStrict<Int>(7, null)
```

Either way the procedure behaves like a query returning a single row, so read it with `fetchRow`, `fetchField` or `fetchObject` — never `update()`. As with functions, the returned columns are named after the `OUT` parameters — including the [generated names](#unnamed-out-parameters) for unnamed ones — so `row.get<Int>("population")` works just as well as reading position `0`.

An `INOUT` parameter is the same story from both ends: you pass the incoming value in its position, and the result comes back in that same position of the returned row.

```kotlin
// CREATE PROCEDURE adjust_tribute(INOUT amount int, increase int)
val adjusted = session.createNativeQuery("CALL adjust_tribute($1, $2)")
    .fetchFieldStrict<Int>(100, 50) // -> 150
```

Compare that with the JDBC route — `prepareCall("{call adjust_tribute(?, ?)}")`, `registerOutParameter(1, Types.INTEGER)`, `setInt(1, 100)`, `execute()`, `getInt(1)`, each step with its own way to go wrong. Here the outbound values are simply the row the call returned.

## When a routine fails

Errors raised inside PL/pgSQL are their own exceptions, separate from the ones ordinary statements produce, and there are two of them because a routine can fail in two different ways.

A `RAISE EXCEPTION` is the routine **deciding** — your own business rules, expressed where the data is:

```kotlin
try {
    session.createNativeQuery("SELECT consult_auspices($1)").fetchField<Unit>(7)
} catch (e: RoutineRaiseException) {
    e.dbMessage   // the text passed to RAISE
    e.hint        // its HINT clause, when there is one
    e.where       // the PL/pgSQL call stack: "PL/pgSQL function consult_auspices(int) line 2 at RAISE"
}
```

A failed `ASSERT`, or a `SELECT INTO STRICT` that matched no row or several, is the routine having **claimed** something the data then falsified — a defect in the routine rather than an answer from it:

```kotlin
catch (e: RoutineAssertionException) {
    e.reason      // NO_DATA_FOUND, TOO_MANY_ROWS, ASSERT_FAILURE
    e.where       // same call stack, naming the line that asserted
}
```

`where` is what makes both genuinely debuggable — it names the routine and the line, not just the statement you sent. The full tables live in [Error Handling and Exceptions](exceptions.md#10-routineraiseexception).

## Summary

| Feature                      | Function                         | Procedure                          |
|:-----------------------------|:---------------------------------|:-----------------------------------|
| **SQL command**              | `SELECT`                         | `CALL`                             |
| **No return / no OUT**       | `fetchField<Unit>()` for `void`  | `update()`                         |
| **Single value / OUT param** | `fetchField<T>()`                | `fetchField<T>()`                  |
| **Several OUT params**       | `fetchRow()`, columns by name    | `fetchRow()`, columns by name      |
| **Placeholders for OUT**     | Not applicable                   | Required — explicit `$n` or `NULL` |
| **Parameter style**          | `$1` / `@name` / `=>` all usable | Same                               |
