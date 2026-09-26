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
| Driver, session and query held    | 38.39 ± 0.67 | 1 663 B   | the floor                        |
| Driver, session borrowed per call | 38.63 ± 0.58 | 1 990 B   | +327 B — the pool borrow         |
| Client, `rawQuery`                | 38.74 ± 0.75 | 2 043 B   | +53 B — finding the session      |
| Client, builder held              | 38.80 ± 0.71 | 2 355 B   | +312 B — rendering the SQL again |
| Client, builder built per call    | 39.14 ± 0.78 | 2 681 B   | +326 B — the builder itself      |

**On the clock there is nothing to see, and that is the finding rather than a failure to measure it.** The whole
ladder spans 0.75 µs on a statement of 38, and every interval overlaps the floor's. This is the cheapest
statement PostgreSQL can be asked to run, chosen so that a fixed per-call cost would be the largest share of one
it will ever be; at this resolution the client's own cost is bounded at about a microsecond rather than
merely unmeasured.

**On allocation the ladder is clean, and its shape is more useful than its total.** The client adds 691 B over a
driver that borrows the same way — and **638 of those 691 are the builder**: rendering its SQL again on every
terminal call, and constructing it in the first place. Finding the session, which is the thing the client exists
to do, is 53 B. The pool borrow that neither of them can avoid is 327 B, six times the client's own share.

So the cost of the client is almost entirely the cost of *assembling a query per call*, which is what
[`toSql()`](queries.md#a-query-is-a-value) warns about in its own words: composing a statement once per request
is nothing, doing it per row is not.

On 10 000 rows the same ladder does not merely tie — it stops being ordered. Microseconds per operation:

| Path                              | Time       | Allocated   |
|:----------------------------------|:-----------|:------------|
| Driver, session and query held    | 4 991 ± 74 | 2 253 015 B |
| Driver, session borrowed per call | 4 940 ± 65 | 2 253 109 B |
| Client, `rawQuery`                | 4 903 ± 65 | 2 253 427 B |
| Client, builder held              | 5 061 ± 71 | 2 254 064 B |
| Client, builder built per call    | 4 961 ± 99 | 2 254 724 B |

The nominally fastest rung is a client one, and the driver's own floor comes fourth of five. Nothing there is a
result; the point is that at this size the per-call cost is not merely
inside the noise but smaller than the ordering of it. On allocation the whole client layer is **1 615 B against
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
| Spring, `pgjdbc` defaults                  | 25.22 ± 0.47 | 2 473 B   |
| JDBI, `pgjdbc` defaults                    | 31.61 ± 0.84 | 14 069 B  |
| **Octavius client**                        | 39.17 ± 1.36 | 2 421 B   |
| Spring, `pgjdbc` with `prepareThreshold=0` | 40.48 ± 1.05 | 2 848 B   |

**The whole gap between this stack and Spring's is server-side prepared statements.** The two Spring rows are
one library, one pool configuration, and a single connection property between them: 25.22 against 40.48, a
factor of **1.6**. Octavius lands on the second of those two — 39.17 against 40.48, inside each other's
intervals — because it
[never promotes a statement to a named one](../driver/octavius-vs-jdbc.md#nothing-is-prepared-server-side) and
has no mode in which it would. Nothing else — not the mapper, not the client layer, not the driver — is visible
once that one property is held still.

[`PointLookupBenchmark`](../driver/performance.md#what-not-preparing-costs) measures the same feature between the
two drivers alone in this run, at a factor of 1.47. It is also a ceiling rather than a typical case, for the
reason given there: what a prepared statement saves is a fixed cost per statement, and this is the cheapest
statement there is.

**The JDBI row is the one genuine library comparison on this page**, because it sits on the same `pgjdbc` and
the same pool as the Spring row above it. It costs 6.4 µs and **5.7× the allocation** — 14 069 B against 2 473 B.
JDBI is called here through `withHandle` per operation, which is its documented idiom for short work and the
counterpart of Spring's `queryForObject`; a long-lived `Handle` or an `SqlObject` would be a different
measurement, and this page does not make it.

Microseconds for 10 000 rows mapped onto a data class:

| Stack               | Time        | Allocated |
|:--------------------|:------------|:----------|
| **Octavius client** | 4 365 ± 133 | 7.14 MB   |
| JDBI                | 6 433 ± 116 | 11.48 MB  |
| Spring              | 7 507 ± 181 | 9.07 MB   |

Octavius is ahead of JDBI by **1.47×** and of Spring by **1.72×**, with every pair of intervals clear of the
others, and allocates the least of the three. The allocation column is the softer of the two here: Spring's
figure carries ±1.0 MB across the three forks, against ±1.2 KB on JDBI's.

**This row is attributable to the layer rather than to the driver, and that is unusual enough to say why.** It
is the same row shape, the same 10 000-row statement and the same data class that
[`SimpleDataBenchmark`](../driver/performance.md#reading) uses to compare the two drivers alone — where, in this
same run, the result is a tie (0.222 ± 0.006 against 0.220 ± 0.006). Two drivers that cannot be told apart on
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
| Composite, hand-written converter  | **11.49 ± 0.40** | **9.44 ± 0.28** | **4.21 ± 0.13** | **3.30 ± 0.07** | —               | —               |
| Composite, `registerAutoComposite` | 17.42 ± 0.67     | 13.14 ± 0.30    | 5.80 ± 0.13     | 4.19 ± 0.08     | **0.89 ± 0.01** | **0.76 ± 0.01** |
| Plain `jsonb`                      | 30.34 ± 4.52     | 30.91 ± 3.64    | 5.41 ± 0.12     | 5.03 ± 0.15     | 1.46 ± 0.03     | 1.32 ± 0.02     |
| `dynamic_dto`                      | 37.43 ± 2.48     | 34.44 ± 3.92    | 7.24 ± 0.36     | 6.54 ± 0.23     | 1.60 ± 0.02     | 1.52 ± 0.02     |

The filter columns scan all 10 000 rows and return the 4% that match, so what they time is the server reaching
into the structure — `((c).stats).strength` against `(payload -> 'stats' ->> 'strength')::int` — rather than
decoding a result. No index exists on any of these columns; an expression index is available to all of them and
would move all of them.

**A composite is ahead of `dynamic_dto` in every column**, at either depth and in either variant: with a
hand-written converter 3.3× on writing and 1.7× on reading, and 1.8× on filtering even through the reflective
path. Against the plain `jsonb` rung one cell goes the other way: the reflective composite reading the nested
shape, 5.80 against 5.41.

**The two composite rows are further apart than the two styles usually are, and it is worth knowing why.** A
hand-written converter for a nested type can read the inner structure itself in one pass, or hand it back to the
converter chain so that whatever is registered for that type is honoured. The one here does the former, while
`registerAutoComposite` re-enters the chain at every attribute and at every level — so the pair is the widest
the two styles can be apart, not the ordinary distance. `dynamic_dto` sits on the same side as the hand-written
converter in this respect: one call decodes the whole document, however deep it goes.

What reflection costs is steadiest read off the allocation: 3.4× the hand-written converter on a nested read and
2.8× on a flat one, 2.7× and 2.2× on the corresponding writes.

**The envelope costs about a quarter to a third of the format's own price on the client side, and a fraction of
a millisecond on the server's.** Against the plain `jsonb` rung on the nested shape, `dynamic_dto` adds 7.1 ms
writing (23%) and 1.8 ms reading (34%); filtering, it adds 0.14 ms on the nested shape and 0.20 ms on the flat
one.

One reading is worth pausing on: `dynamic_dto` takes 2.1× the time of the reflective composite to write while
allocating **less** than it (9.72 MB against 10.21 MB). What costs the time on that path is serialization, not
garbage, and looking for it among allocations would be looking in the wrong place.

## Summary

* **The client's own cost is under 100 bytes per call.** Everything else the layer adds is a query being
  assembled — 638 B of the 691 B it costs in total, and the pool borrow underneath both is 327 B.
* **On the clock the client does not resolve at all**, on either a 38 µs statement or a 5 ms one; at the larger
  size the ladder is not even ordered.
* **Against Spring, the entire primary-key gap is server-side prepared statements** — a factor of 1.6 measured
  with Spring against itself, which Octavius's own row lands on.
* **On 10 000 mapped rows this stack is 1.47× ahead of JDBI and 1.72× ahead of Spring**, on a workload where the
  two drivers underneath are a tie in the same run — so that one is the layer.
* **Use a composite where the shape is fixed**: ahead of `dynamic_dto` in every column measured, by 3.3× on
  writes, 1.7× on reads and 1.8× on filters. Reach for a hand-written converter where the structure is nested and the
  path is hot.
