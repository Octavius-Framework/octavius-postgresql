# Performance

*The aediles kept the official weights in the temple and checked the market's scales against them. A pan that sat a
grain low was not yet fraud — the scales themselves were not that fine, and everyone in the market knew it. Every figure
below is reported with its tolerance for the same reason, and where two drivers sit closer together than that tolerance,
this page says so instead of declaring a winner.*

JMH benchmarks comparing Octavius against the official PostgreSQL JDBC driver (`pgjdbc`). Every figure carries JMH's ±
confidence interval, and **a difference smaller than the intervals it sits between is not a result**; several rows here
are ties for that reason.

> [!NOTE]
> **Environment.** One developer laptop, JDK 25.0.2, Kotlin 2.4.20 with `kotlin-reflect`, JMH 1.37, PostgreSQL 18.4
> over a local connection; 3 warmup and 5 measurement iterations in each of three forks. This page and
> [the client's](../client/performance.md) come from one run of the whole suite. Absolute figures will not reproduce on
> your hardware, and each benchmark fixes one row shape: wider rows, longer strings and nested structures move every
> figure, the ratios included.

## What is measured

| Benchmark                  | Mode         | Work per operation                                                                                             |
|:---------------------------|:-------------|:---------------------------------------------------------------------------------------------------------------|
| `SimpleTypeBenchmark`      | Throughput   | Read 10 000 rows of `int4`, `text`, `boolean`, `float8` as raw values.                                         |
| `SimpleDataBenchmark`      | Throughput   | The same 10 000 rows, mapped onto objects.                                                                     |
| `ArrayTypeBenchmark`       | Throughput   | Read 10 000 rows, each with an `int4[]` and a `text[]`.                                                        |
| `InsertBenchmark`          | Average time | Insert 10 000 rows inside one transaction, by several strategies.                                              |
| `CompositeInsertBenchmark` | Average time | Insert 10 000 `(int, text)` rows as an array of composites, reflectively and through a hand-written converter. |
| `PointLookupBenchmark`     | Average time | One row by primary key, three ways: Octavius, and pgjdbc with server-side prepare on and off.                  |

Run them with `./gradlew :benchmarks:jmh`, or one class with `-Pjmh="InsertBenchmark"`.

## Reading

Operations per millisecond, higher is better; allocation per operation from JMH's `gc` profiler.

| Benchmark              | Octavius      | pgjdbc        | Verdict                     | Allocated, Octavius / pgjdbc |
|:-----------------------|:--------------|:--------------|:----------------------------|:-----------------------------|
| Mapped to objects      | 0.216 ± 0.006 | 0.220 ± 0.005 | Tie — the intervals overlap | 2.57 / 2.33 MB               |
| Raw values, no mapping | 0.189 ± 0.005 | 0.220 ± 0.005 | pgjdbc ~16% ahead           | 2.25 / 2.33 MB               |
| Arrays                 | 0.082 ± 0.005 | 0.103 ± 0.003 | pgjdbc ~26% ahead           | 11.62 / 8.89 MB              |

Mapping onto objects, the path most applications live on, is a tie. The other two gaps are real. Raw values are ~16%
slower while allocating less than pgjdbc, so the time is not going to garbage. Arrays are slower by construction: every
element goes through the conversion machinery that also maps composites, ranges and nested arrays, which is what makes
a `List<Tribute>` of composites work, and it costs 1.3× the allocation too. Closing that gap would take a fast path for
primitive element types beside the general one.

## What not preparing costs

Octavius never promotes a statement to a named server-side one, which is
[a trade rather than an omission](octavius-vs-jdbc.md#nothing-is-prepared-server-side). Microseconds per primary-key
lookup, lower is better:

| Path                                                     | Time       |
|:---------------------------------------------------------|:-----------|
| pgjdbc, server-prepared (`prepareThreshold=5`)           | 25.8 ± 0.4 |
| pgjdbc, re-parsed every execution (`prepareThreshold=0`) | 40.2 ± 0.7 |
| Octavius                                                 | 41.0 ± 0.8 |

The two pgjdbc rows differ by one connection property, so their **14.4 µs** (×1.56) is what a server-side prepared
statement is worth with everything else held still. Octavius lands on the unprepared row, inside its interval.

This is the high case. A primary-key lookup on a warm table is about the cheapest statement PostgreSQL runs, so parsing
and planning take a share of it they rarely reach elsewhere. The saving is a fixed cost per statement: under 1% of a
query doing 2 ms of work, under 3% with the database one network hop away. Planning that is expensive in itself, many
joins or a heavily partitioned table, moves it up.

## Writing

Milliseconds per 10 000 rows in one transaction, lower is better.

| Strategy                | Octavius    | pgjdbc       | Allocated, Octavius / pgjdbc |
|:------------------------|:------------|:-------------|:-----------------------------|
| Single inserts          | 297.7 ± 4.2 | 248.8 ± 12.5 | 4.48 / 2.54 MB               |
| `UNNEST` bulk insert    | 7.60 ± 0.42 | 5.52 ± 0.46  | 0.93 / 0.79 MB               |
| JDBC batching           | n/a         | 23.38 ± 0.52 | — / 2.93 MB                  |
| JDBC batching + rewrite | n/a         | 7.05 ± 0.24  | — / 2.44 MB                  |

**Strategy dominates the driver.** `UNNEST` is about 40× faster than inserting row at a time, in either driver.
Octavius's `UNNEST` ties pgjdbc's `reWriteBatchedInserts=true`, nominally 8% behind with the intervals overlapping, and
is **3.1× faster** than plain JDBC batching. Against pgjdbc's own `UNNEST` it is ~38% behind, and row at a time ~20%
behind with 1.8× the allocation.

`reWriteBatchedInserts` rewrites only `INSERT`; bulk `UPDATE` and `DELETE` fall back to ordinary batching, while
`UNNEST` applies to all three. Beyond this scale, use [`COPY`](copy.md).

## Reflection or a hand-written converter

Both directions can go through reflection, `registerAutoComposite` and the reflective row mapper, or through a
`ResultConverter` / `ParameterConverter` you write. The rows here are narrow: an `int` and a short `text` written, those
two with a `boolean` and a `float8` read.

Writing 10 000 rows through `UNNEST`, milliseconds per operation:

| Building the parameter                           | Time        | Allocated |
|:-------------------------------------------------|:------------|:----------|
| Two parallel scalar arrays (no composite at all) | 7.43 ± 0.35 | 0.92 MB   |
| One `composite[]`, hand-written converter        | 8.29 ± 0.74 | 2.05 MB   |
| One `composite[]`, `registerAutoComposite`       | 9.16 ± 0.33 | 2.05 MB   |

Reading the same 10 000 rows onto a data class, operations per millisecond:

| Mapping the row        | Throughput    | Allocated |
|:-----------------------|:--------------|:----------|
| Hand-written converter | 0.216 ± 0.006 | 2.57 MB   |
| Reflective row mapper  | 0.218 ± 0.012 | 3.77 MB   |

A composite costs no time this run can see against two parallel scalar arrays, for 2.2× the allocation; if a composite
is the shape your data has, use it.

Reflection's allocation is the figure to rely on: 1.5× the hand-written converter's reading, the same writing, and the
reflective read's 3.77 MB repeats to within 400 bytes across forks. Its time depends on the class: on these rows it is
a tie in both directions, on [the client's five-field composites](../client/performance.md#a-composite-or-a-dynamic_dto)
it is clearly behind. Write the converter by hand on the paths that move the most rows, and measure it there.

## Summary

* **Object mapping ties with `pgjdbc`.**
* **Not preparing costs ~14 µs a statement** in its high case, under 1% of a statement doing 2 ms of work.
* **`UNNEST` is 3.1× faster than JDBC batching** and ties pgjdbc's rewrite, which does not cover `UPDATE` and `DELETE`.
* **Arrays cost ~26% for being general**, and raw values ~16% without allocating more.
* **Reflection always costs allocation, and time as well on a wider class.** Replace it on the hottest path only.
