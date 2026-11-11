# Performance

*A scriba was paid, and the sum stands in the accounts beside what the case itself cost — the couriers, the
witnesses' journeys, the days the court sat. Nobody proposed doing without him on that evidence. These figures
are the same accounting: what the client adds, set beside what the statement it carried was going to cost
anyway.*

JMH benchmarks for the client layer, on three questions [the driver's page](../driver/performance.md) does not answer:
what the client costs over the driver, what this stack costs against Spring's `NamedParameterJdbcTemplate` and JDBI,
and whether a column should hold a composite or a [`dynamic_dto`](dynamic-dto.md). The figures come from the same run
as the driver's, in the environment its note describes, and **a difference smaller than the ± intervals it sits between
is not a result.**

## What is measured

| Benchmark                             | Mode         | Work per operation                                                                 |
|:--------------------------------------|:-------------|:-----------------------------------------------------------------------------------|
| `ClientOverheadBenchmark`             | Average time | One row by primary key, and 10 000 rows, through five paths from driver to client. |
| `StackComparisonBenchmark`            | Average time | The same two reads through the Octavius client, Spring and JDBI.                   |
| `CompositeVsDynamicDtoBenchmark`      | Average time | 10 000 rows read whole, and filtered on, through four encodings in two shapes.     |
| `CompositeVsDynamicDtoWriteBenchmark` | Average time | The same 10 000 rows written through those four encodings.                         |

Run them with `./gradlew :benchmarks:jmh`, or one class with `-Pjmh="ClientOverheadBenchmark"`.

## What the client costs

Five paths, each adding one thing to the one above. The second has no client either and borrows a session per call, so
it is the control for the pool borrow. Microseconds per primary-key lookup, and bytes allocated:

| Path                              | Time         | Allocated | What it adds                     |
|:----------------------------------|:-------------|:----------|:---------------------------------|
| Driver, session and query held    | 41.28 ± 0.85 | 1 503 B   | the floor                        |
| Driver, session borrowed per call | 40.98 ± 1.43 | 1 822 B   | +320 B — the pool borrow         |
| Client, `rawQuery`                | 40.37 ± 0.94 | 1 882 B   | +60 B — finding the session      |
| Client, builder held              | 41.83 ± 1.59 | 2 206 B   | +324 B — rendering the SQL again |
| Client, builder built per call    | 41.15 ± 0.70 | 2 464 B   | +258 B — the builder itself      |

**On the clock there is nothing to see.** The ladder spans 1.5 µs on a 41 µs statement, every interval overlaps every
other, and it is not even ordered. This is the cheapest statement PostgreSQL runs, where a fixed per-call cost would
show most, so the client's own cost is bounded at a microsecond or two.

**On allocation the ladder is clean.** Of the 642 B the client adds, 582 are the builder: rendering its SQL again, and
constructing it. Finding the session is 60 B, and the pool borrow underneath is 320 B. The cost is *assembling a query
per call*, which is what [`toSql()`](queries.md#a-query-is-a-value) warns about.

On 10 000 rows, 4.7 to 4.9 ms, the ladder is out of order again, `rawQuery` nominally the fastest, and the whole client
layer allocates 1 512 B of 2.25 MB: 0.07%.

## Against other stacks

Spring and JDBI run on `pgjdbc`, the Octavius client only on the Octavius driver, so **these are three stacks, not three
libraries**. The drivers alone are compared on [the driver's page](../driver/performance.md), in the same run. All three
map their own SQL onto a data class by reflection.

Microseconds per primary-key lookup:

| Stack                                      | Time         | Allocated |
|:-------------------------------------------|:-------------|:----------|
| Spring, `pgjdbc` defaults                  | 27.25 ± 0.51 | 2 521 B   |
| JDBI, `pgjdbc` defaults                    | 38.36 ± 5.42 | 14 014 B  |
| Spring, `pgjdbc` with `prepareThreshold=0` | 41.77 ± 0.89 | 2 865 B   |
| Octavius client                            | 43.65 ± 3.82 | 2 058 B   |

**The gap to Spring is server-side prepared statements.** The two Spring rows differ by one connection property, a
factor of 1.5, and Octavius lands on the unprepared one, inside its interval.
[The driver's page](../driver/performance.md#what-not-preparing-costs) measures the feature between the drivers alone,
and says why this is its high case.

**JDBI is the one library comparison**, on the same `pgjdbc` and pool as Spring: 11.1 µs slower and **5.6× the
allocation**. It is called through `withHandle` per operation, its idiom for short work; a long-lived `Handle` or an
`SqlObject` would be a different measurement.

Microseconds for 10 000 rows mapped onto a data class:

| Stack           | Time        | Allocated |
|:----------------|:------------|:----------|
| Octavius client | 4 323 ± 102 | 3.93 MB   |
| JDBI            | 6 845 ± 109 | 11.48 MB  |
| Spring          | 8 783 ± 115 | 10.35 MB  |

Octavius is **1.58×** ahead of JDBI and **2.03×** ahead of Spring, every interval clear, at under 40% of either's
allocation. This margin belongs to the layer: it is the statement and data class that
[`SimpleDataBenchmark`](../driver/performance.md#reading) uses, where the two drivers tie in the same run.

## A composite or a `dynamic_dto`

`dynamic_dto` is itself a composite, `(type_name text, data_payload jsonb)`, so this compares typed attributes with a
discriminator and a JSON document. In the `nested` shape three of the five fields sit in a structure of their own, in
`flat` all five sit side by side. The plain `jsonb` row carries the same document with no envelope; it is written for
this page, to separate the price of the format from the price of the flexibility.

Milliseconds per 10 000 rows, lower is better, the fastest in each column in bold:

| Encoding                           | Write (nested)   | Write (flat)     | Read (nested)   | Read (flat)     | Filter (nested) | Filter (flat)   |
|:-----------------------------------|:-----------------|:-----------------|:----------------|:----------------|:----------------|:----------------|
| Composite, hand-written converter  | **12.62 ± 0.35** | **10.70 ± 1.09** | **4.08 ± 0.11** | **3.28 ± 0.03** | —               | —               |
| Composite, `registerAutoComposite` | 19.19 ± 0.82     | 15.79 ± 0.57     | 6.20 ± 0.29     | 4.42 ± 0.29     | **0.85 ± 0.01** | **0.70 ± 0.02** |
| Plain `jsonb`                      | 32.44 ± 3.64     | 31.37 ± 3.43     | 5.77 ± 0.24     | 5.15 ± 0.13     | 1.39 ± 0.02     | 1.24 ± 0.02     |
| `dynamic_dto`                      | 39.77 ± 3.23     | 37.05 ± 3.55     | 7.07 ± 0.21     | 7.16 ± 0.39     | 1.55 ± 0.02     | 1.40 ± 0.03     |

The filter columns scan all 10 000 rows, with no index, for the 4% that match, so they time the server reaching into
the structure rather than decoding a result.

**A composite is ahead of `dynamic_dto` in every column**: with a hand-written converter by at least 3.2× writing and
1.7× reading, and by 1.8× filtering through reflection. On the nested shape the envelope adds about 23% to plain
`jsonb`'s client-side cost, and 0.17 ms to its filter.

Reflection allocates 1.67× the hand-written converter's garbage reading nested and 1.49× flat, and the same writing.
Its time depends on the class, and this pair is the widest it gets: the hand-written converter reads the nested
structure in one pass, where `registerAutoComposite` goes back through the converter chain at every attribute.

## Summary

* **The client's own cost is 60 B a call**; the rest is assembling a query (582 B) over the pool borrow (320 B). On
  the clock it does not resolve.
* **Against Spring, the whole primary-key gap is server-side prepare**, a factor of 1.5.
* **On 10 000 mapped rows this stack is 1.58× ahead of JDBI and 2.03× ahead of Spring**, where the drivers tie.
* **Use a composite where the shape is fixed**: against `dynamic_dto`, at least 3.2× on writes, 1.7× on reads and 1.8×
  on filters.
