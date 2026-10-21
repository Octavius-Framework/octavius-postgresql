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
> **Environment.** One developer laptop on its performance profile, JDK 25, Kotlin 2.4.20 with `kotlin-reflect`
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
| Driver, session and query held    | 47.69 ± 0.63 | 1 698 B   | the floor                        |
| Driver, session borrowed per call | 48.71 ± 0.68 | 2 001 B   | +303 B — the pool borrow         |
| Client, `rawQuery`                | 49.17 ± 0.58 | 2 089 B   | +88 B — finding the session      |
| Client, builder held              | 50.63 ± 1.03 | 2 389 B   | +300 B — rendering the SQL again |
| Client, builder built per call    | 49.77 ± 0.91 | 2 650 B   | +261 B — the builder itself      |

**On the clock there is nothing to see, and that is the finding rather than a failure to measure it.** The whole
ladder spans 2.9 µs on a statement of 48, and it is not ordered: the held builder comes out 0.9 µs slower than
the one built per call, which does everything it does and more. Every rung's interval overlaps that of the rung
it adds to, and every client rung's overlaps the borrowing driver's except that held builder — whose distance
cannot be cost, since the rung built per call pays for the same work and overlaps it. This is the
cheapest statement PostgreSQL can be asked to run, chosen so that a fixed per-call cost would be the largest
share of one it will ever be; at this resolution the client's own cost is bounded at a microsecond or two rather
than merely unmeasured.

**On allocation the ladder is clean, and its shape is more useful than its total.** The client adds 649 B over a
driver that borrows the same way — and **561 of those 649 are the builder**: rendering its SQL again on every
terminal call, and constructing it in the first place. Finding the session, which is the thing the client exists
to do, is 88 B. The pool borrow that neither of them can avoid is 303 B, more than three times the client's own
share.

So the cost of the client is almost entirely the cost of *assembling a query per call*, which is what
[`toSql()`](queries.md#a-query-is-a-value) warns about in its own words: composing a statement once per request
is nothing, doing it per row is not.

On 10 000 rows the same ladder is further out of order. Microseconds per operation:

| Path                              | Time       | Allocated   |
|:----------------------------------|:-----------|:------------|
| Driver, session and query held    | 4 782 ± 47 | 2 251 702 B |
| Driver, session borrowed per call | 4 943 ± 65 | 2 251 954 B |
| Client, `rawQuery`                | 4 952 ± 71 | 2 252 233 B |
| Client, builder held              | 4 932 ± 54 | 2 252 727 B |
| Client, builder built per call    | 4 779 ± 50 | 2 254 570 B |

The nominally fastest rung is the one doing the most work, the builder built per call, and its interval clears
`rawQuery`'s, which does a part of the same. Nothing there is a result; the point is that at this size the
per-call cost is not merely inside the noise but smaller than the ordering of it. On allocation the whole client
layer is **2 616 B against 2.25 MB: 0.12%**.

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
| Spring, `pgjdbc` defaults                  | 29.12 ± 1.19 | 2 526 B   |
| JDBI, `pgjdbc` defaults                    | 37.17 ± 1.20 | 14 049 B  |
| **Octavius client**                        | 49.86 ± 0.98 | 2 243 B   |
| Spring, `pgjdbc` with `prepareThreshold=0` | 50.53 ± 1.42 | 2 933 B   |

**The whole gap between this stack and Spring's is server-side prepared statements.** The two Spring rows are
one library, one pool configuration, and a single connection property between them: 29.12 against 50.53, a
factor of **1.7**. Octavius lands on the second of those two — 49.86 against 50.53, inside each other's
intervals — because it
[never promotes a statement to a named one](../driver/octavius-vs-jdbc.md#nothing-is-prepared-server-side) and
has no mode in which it would. Nothing else — not the mapper, not the client layer, not the driver — is visible
once that one property is held still.

[`PointLookupBenchmark`](../driver/performance.md#what-not-preparing-costs) measures the same feature between the
two drivers alone in this run, at a factor of 1.70. It is also a ceiling rather than a typical case, for the
reason given there: what a prepared statement saves is a fixed cost per statement, and this is the cheapest
statement there is.

**The JDBI row is the one genuine library comparison on this page**, because it sits on the same `pgjdbc` and
the same pool as the Spring row above it. It costs 8.0 µs and **5.6× the allocation** — 14 049 B against 2 526 B.
JDBI is called here through `withHandle` per operation, which is its documented idiom for short work and the
counterpart of Spring's `queryForObject`; a long-lived `Handle` or an `SqlObject` would be a different
measurement, and this page does not make it.

Microseconds for 10 000 rows mapped onto a data class:

| Stack               | Time        | Allocated |
|:--------------------|:------------|:----------|
| **Octavius client** | 4 340 ± 141 | 3.94 MB   |
| JDBI                | 6 337 ± 76  | 11.48 MB  |
| Spring              | 7 546 ± 182 | 9.71 MB   |

Octavius is ahead of JDBI by **1.46×** and of Spring by **1.74×**, with every pair of intervals clear of the
others, and allocates the least of the three, under half of either. The allocation column is the softer of the
two here: Spring's figure carries ±1.0 MB across the three forks, against ±1.2 KB on JDBI's.

**This row is attributable to the layer rather than to the driver, and that is unusual enough to say why.** It
is the same row shape, the same 10 000-row statement and the same data class that
[`SimpleDataBenchmark`](../driver/performance.md#reading) uses to compare the two drivers alone — where, in this
same run, the result is a tie (0.233 ± 0.002 against 0.228 ± 0.004). Two drivers that cannot be told apart on
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

| Encoding                           | Write (nested)   | Write (flat)    | Read (nested)   | Read (flat)     | Filter (nested) | Filter (flat)   |
|:-----------------------------------|:-----------------|:----------------|:----------------|:----------------|:----------------|:----------------|
| Composite, hand-written converter  | **11.72 ± 0.63** | **9.76 ± 0.25** | **4.20 ± 0.06** | **3.35 ± 0.06** | —               | —               |
| Composite, `registerAutoComposite` | 15.12 ± 0.50     | 12.50 ± 0.26    | **4.30 ± 0.11** | **3.41 ± 0.07** | **0.86 ± 0.06** | **0.72 ± 0.01** |
| Plain `jsonb`                      | 30.83 ± 4.19     | 31.94 ± 2.80    | 5.38 ± 0.16     | 4.85 ± 0.13     | 1.44 ± 0.01     | 1.26 ± 0.03     |
| `dynamic_dto`                      | 37.99 ± 2.85     | 35.57 ± 3.70    | 7.24 ± 0.22     | 6.91 ± 0.34     | 1.59 ± 0.02     | 1.44 ± 0.02     |

The filter columns scan all 10 000 rows and return the 4% that match, so what they time is the server reaching
into the structure — `((c).stats).strength` against `(payload -> 'stats' ->> 'strength')::int` — rather than
decoding a result. No index exists on any of these columns; an expression index is available to all of them and
would move all of them. Where a column has two figures in bold, their intervals overlap and neither is ahead.

**A composite is ahead of `dynamic_dto` in every column**, at either depth and in either variant: with a
hand-written converter 3.2× on writing and 1.7× on reading, and 1.8× on filtering even through the reflective
path. It is ahead of the plain `jsonb` rung in every cell as well, the reflective variant included.

**The two composite rows are further apart than the two styles usually are, and it is worth knowing why.** A
hand-written converter for a nested type can read the inner structure itself in one pass, or hand it back to the
converter chain so that whatever is registered for that type is honoured. The one here does the former, while
`registerAutoComposite` re-enters the chain at every attribute and at every level — so the pair is the widest
the two styles can be apart, not the ordinary distance. `dynamic_dto` sits on the same side as the hand-written
converter in this respect: one call decodes the whole document, however deep it goes.

What reflection costs shows in a different column in each direction. Reading, it is the allocation: 1.7× the
hand-written converter on a nested row and 1.5× on a flat one, while the times sit inside each other's
intervals. Writing, it is the clock: 29% on the nested shape and 28% on the flat one, intervals clear, while the
allocation is the hand-written converter's to within 0.1% at either depth (3.81 MB against 3.80 MB nested). What
costs the time on that path is not garbage, and looking for it among allocations would be looking in the wrong
place.

**The envelope costs about a quarter to a third of the format's own price on the client side, and a fraction of
a millisecond on the server's.** Against the plain `jsonb` rung on the nested shape, `dynamic_dto` adds 7.2 ms
writing (23%) and 1.9 ms reading (34%); filtering, it adds 0.15 ms on the nested shape and 0.19 ms on the flat
one.

## Summary

* **The client's own cost is under 100 bytes per call.** Everything else the layer adds is a query being
  assembled — 561 B of the 649 B it costs in total, and the pool borrow underneath both is 303 B.
* **On the clock the client does not resolve as a cost**, on either a 48 µs statement or a 5 ms one; at either
  size the ladder is not even ordered.
* **Against Spring, the entire primary-key gap is server-side prepared statements** — a factor of 1.7 measured
  with Spring against itself, which Octavius's own row lands on.
* **On 10 000 mapped rows this stack is 1.46× ahead of JDBI and 1.74× ahead of Spring**, on a workload where the
  two drivers underneath are a tie in the same run — so that one is the layer.
* **Use a composite where the shape is fixed**: ahead of `dynamic_dto` in every column measured, by 3.2× on
  writes, 1.7× on reads and 1.8× on filters. Reach for a hand-written converter where the path is hot and writes:
  reflection costs about 30% there at either depth, and reading it costs allocation rather than time.
