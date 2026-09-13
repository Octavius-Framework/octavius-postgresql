# Performance

*A scriba was paid, and the sum stands in the accounts beside what the case itself cost — the couriers, the
witnesses' journeys, the days the court sat. Nobody proposed doing without him on that evidence. These figures
are the same accounting: what the client adds, set beside what the statement it carried was going to cost
anyway.*

JMH benchmarks for the client layer. Three questions, none of them the one
[the driver's page](../driver/performance.md) answers:

1. What the client costs over calling the driver by hand.
2. What this stack costs against Spring's `NamedParameterJdbcTemplate` and JDBI.
3. Whether a column should hold a composite or a [`dynamic_dto`](dynamic-dto.md).

Every figure carries JMH's ± confidence interval, and that number is the point: **a difference smaller than the
intervals it sits between is not a result.** Several rows below are ties for exactly that reason, and the
largest single finding on this page is a tie.

> [!NOTE]
> **Environment.** One developer laptop on its performance profile, JDK 25, Kotlin 2.4.0 with `kotlin-reflect`
> on the classpath, JMH 1.37, PostgreSQL 18.4 over a local connection. Iteration counts are declared on the benchmark classes. Every figure on this page and on
> [the driver's](../driver/performance.md) comes from one run of the whole suite, which is what makes them
> comparable to each other. Absolute figures will not reproduce on your hardware — across a change of the
> machine's own power profile every time doubled while the ratios moved by under 2%, so the ratios are what
> travels.

## What is measured

| Benchmark                              | Mode         | Work per operation                                                             |
|:---------------------------------------|:-------------|:-------------------------------------------------------------------------------|
| `ClientOverheadBenchmark`              | Average time | One row by primary key, and 10 000 rows, through five paths from driver to client. |
| `StackComparisonBenchmark`             | Average time | The same two reads through the Octavius client, Spring and JDBI.                |
| `CompositeVsDynamicDtoBenchmark`       | Average time | 10 000 rows read whole, and filtered on, through four encodings in two shapes.  |
| `CompositeVsDynamicDtoWriteBenchmark`  | Average time | The same 10 000 rows written through those four encodings.                      |

Run them with `./gradlew :benchmarks:jmh`, or narrow to one class with `-Pjmh="ClientOverheadBenchmark"`.

## What the client costs

Five paths, ordered so each adds exactly one thing to the one above it. The first two have no client on the
stack at all: the second borrows a session from the pool around every call, which is what makes it the control
for the borrow rather than a charge against the layer above.

Microseconds per primary-key lookup, and bytes allocated — lower is better.

| Path                              | Time         | Allocated | What it adds                     |
|:----------------------------------|:-------------|:----------|:---------------------------------|
| Driver, session and query held    | 44.92 ± 0.79 | 1 533 B   | the floor                        |
| Driver, session borrowed per call | 45.56 ± 0.70 | 2 060 B   | +527 B — the pool borrow         |
| Client, `rawQuery`                | 44.74 ± 0.78 | 2 118 B   | +58 B — finding the session      |
| Client, builder held              | 45.56 ± 0.80 | 2 441 B   | +323 B — rendering the SQL again |
| Client, builder built per call    | 47.07 ± 2.02 | 2 762 B   | +321 B — the builder itself      |

**On the clock there is nothing to see, and that is the finding rather than a failure to measure it.** The whole
ladder spans 2.3 µs on a statement of 45, and every interval overlaps the floor's. This is the cheapest
statement PostgreSQL can be asked to run, chosen so that a fixed per-call cost would be the largest share of one
it will ever be; at this resolution the client's own cost is bounded at a couple of microseconds rather than
merely unmeasured.

**On allocation the ladder is clean, and its shape is more useful than its total.** The client adds 702 B over a
driver that borrows the same way — and **645 of those 702 are the builder**: rendering its SQL again on every
terminal call, and constructing it in the first place. Finding the session, which is the thing the client exists
to do, is 58 B. The pool borrow that neither of them can avoid is 527 B, nine times the client's own share.

So the cost of the client is almost entirely the cost of *assembling a query per call*, which is what
[`toSql()`](queries.md#a-query-is-a-value) warns about in its own words: composing a statement once per request
is nothing, doing it per row is not.

On 10 000 rows the same ladder does not merely tie — it stops being ordered. Microseconds per operation:

| Path                              | Time         | Allocated   |
|:----------------------------------|:-------------|:------------|
| Driver, session and query held    | 5 210 ± 123  | 2 253 029 B |
| Driver, session borrowed per call | 5 383 ± 79   | 2 253 518 B |
| Client, `rawQuery`                | 5 227 ± 97   | 2 253 732 B |
| Client, builder held              | 5 147 ± 28   | 2 254 262 B |
| Client, builder built per call    | 5 159 ± 55   | 2 255 122 B |

The two rungs carrying the most work are nominally the two fastest, and the driver borrowing per call is the
slowest of the five. Nothing there is a result; the point is that at this size the per-call cost is not merely
inside the noise but smaller than the ordering of it. On allocation the whole client layer is **1 604 B against
2.25 MB: 0.07%**.

## Against other stacks

Spring's `NamedParameterJdbcTemplate` and JDBI both run on `pgjdbc`; the Octavius client runs on the Octavius
driver and cannot run on anything else, since [`PreparedStatement` and `ResultSet` are not
implemented](../driver/octavius-vs-jdbc.md). **So these are three stacks, not three libraries, and no row here
says one of these libraries is faster than another.** What makes that readable rather than useless is that the
driver half is measured separately on [the driver's page](../driver/performance.md) in this same run, and that
one row below is a controlled pair within a single stack.

All three write their own SQL and have it mapped onto a data class by reflection. None is an ORM, none generates
the statement.

Microseconds per primary-key lookup — lower is better.

| Stack                                      | Time         | Allocated |
|:-------------------------------------------|:-------------|:----------|
| Spring, `pgjdbc` defaults                  | 27.47 ± 0.38 | 2 404 B   |
| JDBI, `pgjdbc` defaults                    | 35.66 ± 2.27 | 14 047 B  |
| Spring, `pgjdbc` with `prepareThreshold=0` | 45.57 ± 1.24 | 2 831 B   |
| **Octavius client**                        | 45.72 ± 1.58 | 2 472 B   |

**The whole gap between this stack and Spring's is server-side prepared statements.** The two Spring rows are
one library, one pool configuration, and a single connection property between them: 27.47 against 45.57, a
factor of **1.66**. Octavius lands on the second of those two — 45.72 against 45.57, which is as close as two
separate benchmarks get — because it
[never promotes a statement to a named one](../driver/octavius-vs-jdbc.md#nothing-is-prepared-server-side) and
has no mode in which it would. Nothing else — not the mapper, not the client layer, not the driver — is visible
once that one property is held still.

That is the same conclusion [`PointLookupBenchmark`](../driver/performance.md#what-not-preparing-costs) reaches
between the two drivers alone in this run, reproduced here through an entirely different layer on top. It is
also a ceiling rather than a typical case, for the reason given there: what a prepared statement saves is a
fixed cost per statement, and this is the cheapest statement there is.

**The JDBI row is the one genuine library comparison on this page**, because it sits on the same `pgjdbc` and
the same pool as the Spring row above it. It costs 8 µs and **5.8× the allocation** — 14 047 B against 2 404 B.
JDBI is called here through `withHandle` per operation, which is its documented idiom for short work and the
counterpart of Spring's `queryForObject`; a long-lived `Handle` or an `SqlObject` would be a different
measurement, and this page does not make it.

Microseconds for 10 000 rows mapped onto a data class:

| Stack               | Time         | Allocated |
|:--------------------|:-------------|:----------|
| **Octavius client** | 4 618 ± 133  | 6.76 MB   |
| JDBI                | 6 546 ± 127  | 11.16 MB  |
| Spring              | 7 636 ± 214  | 8.75 MB   |

Octavius is ahead of JDBI by **1.42×** and of Spring by **1.65×**, with every pair of intervals clear of the
others, and allocates the least of the three. The allocation column is the softer of the two here: Spring's
figure carries ±1.0 MB across the three forks, against ±1.2 KB on JDBI's.

**This row is attributable to the layer rather than to the driver, and that is unusual enough to say why.** It
is the same row shape, the same 10 000-row statement and the same data class that
[`SimpleDataBenchmark`](../driver/performance.md#reading) uses to compare the two drivers alone — where, in this
same run, the result is a tie (0.216 ± 0.003 against 0.215 ± 0.003). Two drivers that cannot be told apart on
this workload, carrying three layers that can, leaves the difference above the driver.

## A composite or a `dynamic_dto`

Where a column's shape is fixed, both will hold it. `dynamic_dto` is itself a composite —
`(type_name text, data_payload jsonb)` — so the comparison is a composite of typed attributes against a
composite of a discriminator and a JSON document, through the same container machinery on the wire.

Four encodings, in two shapes: `nested` puts three of the five fields inside a structure of their own, `flat`
puts all five side by side. The `jsonb` column is a rung written for this page rather than a feature the library
offers; it carries the same document with no envelope, which is what separates the price of the format from the
price of the flexibility.

Milliseconds per 10 000 rows — lower is better.

| Encoding                           | Write (nested)   | Write (flat)     | Read (nested)   | Read (flat)     | Filter (nested) | Filter (flat)   |
|:-----------------------------------|:-----------------|:-----------------|:----------------|:----------------|:----------------|:----------------|
| Composite, hand-written converter  | **12.32 ± 0.66** | **10.14 ± 0.36** | **4.38 ± 0.19** | **3.67 ± 0.09** | —               | —               |
| Composite, `registerAutoComposite` | 18.36 ± 0.63     | 14.45 ± 0.36     | 5.81 ± 0.06     | 4.31 ± 0.11     | **0.92 ± 0.01** | **0.76 ± 0.02** |
| Plain `jsonb`                      | 33.00 ± 3.98     | 30.28 ± 4.14     | 6.12 ± 0.21     | 5.01 ± 0.13     | 1.49 ± 0.03     | 1.32 ± 0.02     |
| `dynamic_dto`                      | 41.46 ± 2.08     | 38.47 ± 3.74     | 7.95 ± 0.21     | 7.43 ± 0.40     | 1.66 ± 0.01     | 1.49 ± 0.03     |

The filter columns scan all 10 000 rows and return the 4% that match, so what they time is the server reaching
into the structure — `((c).stats).strength` against `(payload -> 'stats' ->> 'strength')::int` — rather than
decoding a result. No index exists on any of these columns; an expression index is available to all of them and
would move all of them.

**A composite is ahead in every column**, at either depth and in either variant: against `dynamic_dto` with a
hand-written converter, 3.4× on writing and 1.8× on reading, and 1.8× on filtering even through the reflective
path.

**The two composite rows are further apart than the two styles usually are, and it is worth knowing why.** A
hand-written converter for a nested type can read the inner structure itself in one pass, or hand it back to the
converter chain so that whatever is registered for that type is honoured. The one here does the former, while
`registerAutoComposite` re-enters the chain at every attribute and at every level — so the pair is the widest
the two styles can be apart, not the ordinary distance. `dynamic_dto` sits on the same side as the hand-written
converter in this respect: one call decodes the whole document, however deep it goes.

What reflection costs is steadiest read off the allocation: 3.2× the hand-written converter on a nested read and
2.7× on a flat one, 2.6× and 2.2× on the corresponding writes.

**The envelope costs about a quarter to a third of the format's own price on the client side, and a fixed amount
on the server's.** Against the plain `jsonb` rung, `dynamic_dto` adds 8.5 ms writing (26%), 1.8 ms reading
(30%), and 0.17 ms filtering — the last of those the same in both shapes, because unwrapping the composite to
reach `data_payload` is one operation per row however deep the document inside it goes.

One reading is worth pausing on: `dynamic_dto` takes 2.3× the time of the reflective composite to write while
allocating **less** than it (9.72 MB against 10.05 MB). What costs the time on that path is serialization, not
garbage, and looking for it among allocations would be looking in the wrong place.

## Summary

* **The client's own cost is under 100 bytes per call.** Everything else the layer adds is a query being
  assembled — 645 B of the 702 B it costs in total, and the pool borrow underneath both is 527 B.
* **On the clock the client does not resolve at all**, on either a 45 µs statement or a 5 ms one; at the larger
  size the ladder is not even ordered.
* **Against Spring, the entire primary-key gap is server-side prepared statements** — a factor of 1.66 measured
  with Spring against itself, which Octavius's own row lands on.
* **On 10 000 mapped rows this stack is 1.42× ahead of JDBI and 1.65× ahead of Spring**, on a workload where the
  two drivers underneath are a tie in the same run — so that one is the layer.
* **Use a composite where the shape is fixed**: ahead of `dynamic_dto` in every column measured, by 3.4× on
  writes and 1.8× on reads and filters. Reach for a hand-written converter where the structure is nested and the
  path is hot.
