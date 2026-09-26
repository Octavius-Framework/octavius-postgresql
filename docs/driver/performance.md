# Performance

*The aediles kept the official weights in the temple and checked the market's scales against them. A pan that sat a
grain low was not yet fraud — the scales themselves were not that fine, and everyone in the market knew it. Every figure
below is reported with its tolerance for the same reason, and where two drivers sit closer together than that tolerance,
this page says so instead of declaring a winner.*

JMH benchmarks comparing Octavius against the official PostgreSQL JDBC driver (`pgjdbc`).

Every figure below carries JMH's ± confidence interval over 15 measurement iterations, and that number is the point of
this page: **a difference smaller than the intervals it sits between is not a result.** Several rows here are ties for
exactly that reason, and saying so is more useful than reporting a 2% win.

> [!NOTE]
> **Environment.** One developer laptop, JDK 25, Kotlin 2.4.20 with `kotlin-reflect` on the classpath, JMH 1.37,
> PostgreSQL 18.4 over a local connection, 3 warmup and 5
> measurement iterations in each of three forks — 15 samples behind every figure — declared on the benchmark classes
> themselves. Both drivers do identical work in the same JVM, and every figure on this page and on
> [the client's](../client/performance.md) comes from one run of the whole suite, which is what makes them
> comparable to each other.
> Absolute throughput will not reproduce on your hardware — the ratios between the two columns are what travels.
>
> Every benchmark also fixes one row shape, named in the table below, and both drivers carry that identical shape. That
> is what makes the two columns comparable, and what stops any single figure from being a per-row constant: wider rows,
> longer strings and nested structures move all of these numbers, the ratios included.

## What is measured

| Benchmark                  | Mode         | Work per operation                                                                                             |
|:---------------------------|:-------------|:---------------------------------------------------------------------------------------------------------------|
| `SimpleTypeBenchmark`      | Throughput   | Read 10 000 rows of `int4`, `text`, `boolean`, `float8` as raw values.                                         |
| `SimpleDataBenchmark`      | Throughput   | The same 10 000 rows, mapped onto objects.                                                                     |
| `ArrayTypeBenchmark`       | Throughput   | Read 10 000 rows, each with an `int4[]` and a `text[]`.                                                        |
| `InsertBenchmark`          | Average time | Insert 10 000 rows inside one transaction, by several strategies.                                              |
| `CompositeInsertBenchmark` | Average time | Insert 10 000 `(int, text)` rows as an array of composites, reflectively and through a hand-written converter. |
| `PointLookupBenchmark`     | Average time | One row by primary key, three ways: Octavius, and pgjdbc with server-side prepare on and off.                  |

Run them yourself with `./gradlew :benchmarks:jmh`, or narrow to one class with `-Pjmh="InsertBenchmark"`. The
benchmarks that compare the client layer rather than the two drivers are on
[the client's performance page](../client/performance.md).

## Reading

Operations per millisecond — higher is better.

| Benchmark                  | Octavius      | pgjdbc        | Verdict                     |
|:---------------------------|:--------------|:--------------|:----------------------------|
| **Mapped to objects**      | 0.222 ± 0.006 | 0.220 ± 0.006 | Tie — the intervals overlap |
| **Raw values, no mapping** | 0.188 ± 0.003 | 0.219 ± 0.006 | pgjdbc ~16% ahead           |
| **Arrays**                 | 0.088 ± 0.003 | 0.106 ± 0.003 | pgjdbc ~20% ahead           |

The first row is the one most applications live on, and it is a genuine dead heat: Octavius is nominally 1% ahead, and each driver's own interval is six times that wide.

The other two rows are real — the intervals do not come close to touching. Both are worth understanding rather than just noting, because they are not the same kind of gap.

Arrays are slower by construction. Octavius decodes every element through the same conversion machinery that maps composites, ranges and nested arrays, which is precisely what makes a `List<Tribute>` of composites work at all. A driver that treats `int4[]` as a special case can be quicker at `int4[]`; the price of that is having no answer for the general one. This is a trade rather than a defect, and closing it would mean adding a specialized fast path for primitive element types alongside the general one — not fixing something broken.

## What not preparing costs

Octavius never promotes a statement to a named server-side one: every execution is a `Parse` into the unnamed statement, which is [a trade rather than an omission](octavius-vs-jdbc.md#nothing-is-prepared-server-side). `PointLookupBenchmark` puts a number on it, on the workload where that number is largest.

Microseconds per single-row primary-key lookup — lower is better.

| Path                                                     | Time       |
|:---------------------------------------------------------|:-----------|
| pgjdbc, server-prepared (`prepareThreshold=5`)           | 25.6 ± 0.7 |
| pgjdbc, re-parsed every execution (`prepareThreshold=0`) | 37.7 ± 0.5 |
| **Octavius**                                             | 40.6 ± 1.1 |

The two pgjdbc rows are the controlled half of it: one driver, one table, one method, and a single connection property between them. The distance between those two — **12.1 µs, a factor of 1.47** — is what a server-side prepared statement is worth with everything else held still.

Octavius lands 2.9 µs above the unprepared row, and the intervals do not touch; running this class again on its own put it 2.0 µs above, the intervals again just apart. This benchmark cannot say what those microseconds belong to. The same lookup through the same driver, in [the client's `ClientOverheadBenchmark`](../client/performance.md#what-the-client-costs), came in at 38.4 ± 0.7 in the same run — overlapping the unprepared row, while also parsing named parameters on every call. Of the 15 µs between Octavius and the prepared row, 12 are the feature, and the rest is no larger than what separates two benchmarks of one path.

**It is a ceiling rather than a typical case.** A primary-key lookup against a warm 10 000-row table is close to the cheapest statement PostgreSQL can be asked to run, so parsing and planning take the largest share of it they are ever going to take. What the feature saves is a fixed cost per statement, not a proportion of one: against a query doing 2 ms of real work it is under 1%, and against a database one network hop away — half a millisecond gone before the server has read anything — about 2%. What moves it back up is planning that is expensive in itself, many joins or a heavily partitioned table, where the planner's work grows and the executor's need not.

Reproduce with `./gradlew :benchmarks:jmh -Pjmh="PointLookupBenchmark"`. The same pair, measured through a whole
data-access stack rather than through the drivers alone, comes out at
[a factor of 1.6](../client/performance.md#against-other-stacks).

## Writing

Milliseconds per operation, each operation being 10 000 rows in one transaction — lower is better.

| Strategy                    | Octavius     | pgjdbc       |
|:----------------------------|:-------------|:-------------|
| **Single inserts**          | 296.0 ± 8.5  | 230.2 ± 7.8  |
| **`UNNEST` bulk insert**    | 7.13 ± 0.35  | 5.47 ± 0.40  |
| **JDBC batching**           | n/a          | 26.27 ± 0.33 |
| **JDBC batching + rewrite** | n/a          | 7.09 ± 0.45  |

**Strategy dominates the driver.** Row-at-a-time insertion costs ~296 ms against ~230 ms, but the same 10 000 rows go in **42× faster** through `UNNEST` in either driver. If you take one thing from this page, take that one.

**Octavius's `UNNEST` matches pgjdbc's fastest batching.** 7.13 ± 0.35 against `reWriteBatchedInserts=true` at 7.09 ± 0.45 — the intervals overlap almost entirely, so a tie — and **3.7× faster** than plain JDBC batching at 26.27 ms. That last gap is far outside the noise.

**Against pgjdbc doing `UNNEST` too, Octavius is behind by ~30%**: 7.13 ± 0.35 against 5.47 ± 0.40, intervals clear of each other. It points where the read benchmarks point — per-value serialization, the same machinery that costs the array row above.

Worth knowing about `reWriteBatchedInserts`: it only rewrites `INSERT`, so bulk `UPDATE` and `DELETE` fall back to ordinary batching and its worse figures, while `UNNEST` applies unchanged to all three. For loads beyond this scale, neither column is the answer — use [`COPY`](copy.md).

## Reflection or a hand-written converter

Both directions of the type system can be driven two ways: reflectively, through `registerAutoComposite` and the reflective row mapper, or through a `ResultConverter` / `ParameterConverter` you write yourself. The reflective path is what makes the driver pleasant; this is what it costs.

**Writing** — 10 000 rows through `UNNEST`, milliseconds per operation and bytes allocated:

| Building the parameter                           | Time        | Allocated |
|:-------------------------------------------------|:------------|:----------|
| Two parallel scalar arrays (no composite at all) | 6.58 ± 0.20 | 0.93 MB   |
| One `composite[]`, hand-written converter        | 7.36 ± 0.77 | 2.05 MB   |
| One `composite[]`, `registerAutoComposite`       | 8.19 ± 0.49 | 4.93 MB   |

**Reading** — the same 10 000 rows mapped onto a data class, operations per millisecond:

| Mapping the row        | Throughput    | Allocated |
|:-----------------------|:--------------|:----------|
| Hand-written converter | 0.222 ± 0.006 | 2.57 MB   |
| Reflective row mapper  | 0.217 ± 0.004 | 6.97 MB   |

Three things come out of this.

**Composites themselves cost little on the clock.** One array of composites against two parallel scalar arrays is 7.36
against 6.58 — the intervals only just overlap, so this run cannot tell them apart — for 2.2× the allocation. If a
composite type is the shape your data already has, use it.

**This run cannot put reflection on the clock in either direction.** Writing, 8.19 against 7.36 is 11% nominally, and
the hand-written converter's ±0.77 reaches past the lower edge of the reflective figure; reading, 0.217 against 0.222 is
2%, the intervals overlapping. Reflection costs something — it allocates 2.4× as much writing and 2.7× reading, and that
part is not in doubt — but the clock cannot say how much.

**The allocation difference is the sturdy one — against the clock, not against your schema.** Reflection allocated 2.4×
the hand-written converter when writing and 2.7× when reading, and unlike the timings those ratios came back to within a
fraction of a percent on every run and on either power profile. What they do *not* survive is a change of shape.
Everything measured here is a narrow row — an `int` and a short `text` written, those two with a `boolean` and a
`float8` read — and a class with fifteen fields, nested composites or `numeric` columns will land somewhere else
entirely, in both columns of the comparison. Take the direction from this and measure the size of it on your own data.

**What a nested composite does to that, measured.** [The client's performance page](../client/performance.md#a-composite-or-a-dynamic_dto)
runs the same two-way comparison on a five-field row in two shapes: `(id, name, stats)`, with three of the fields inside
`stats`, and the same five side by side. The allocation ratio keeps its shape and grows with the extra level —
reflection took 2.8× the hand-written converter reading a flat row and **3.4× reading a nested one**, 2.2× and 2.7× on
the corresponding writes. A second level therefore costs the discovery more than it costs the composite, which is what
the mechanism predicts: attributes are matched to constructor parameters once per composite, and a nested one is two
composites.

One caution about what the pair is comparing. A hand-written converter for a nested type can read the inner structure
itself in a single pass, or send it back through the converter chain so that whatever is registered for that type is
honoured; the benchmark's does the former. So this pair sets `registerAutoComposite`, which re-enters the chain at every
attribute and every level, against a converter that leaves the chain after one call. That is the widest the two styles
can be apart rather than the ordinary distance between them, and it is why the allocation ratio is the figure to take
from it.

Where the time goes is visible in JMH's `stack` profiler, and it explains why the timings are the softer measurement
here. On the composite insert, ~40% of RUNNABLE samples sit in `sun.nio.ch.Net.poll` — waiting for the server — while
the reflective path adds ~2% in `TypeCatalog.findParameterConverter`, ~1% in
`ReflectionCompositeParameterConverter.convert` and ~0.7% in `invokeExact`. The work reflection adds is real, but it is
competing with a socket, which is what buries it in the noise on a local connection and would bury it further on a real
network.

So: reach for a hand-written converter on the paths that move the most rows, and let reflection handle everything else.
Rewriting a mapping that runs once per request buys nothing worth the code.

## Memory

Bytes allocated per operation, from JMH's `gc` profiler.

| Benchmark                       | Octavius | pgjdbc          |
|:--------------------------------|:---------|:----------------|
| Mapped to objects               | 2.57 MB  | 2.33 MB         |
| Mapped to objects, reflectively | 6.97 MB  | n/a             |
| Raw values, no mapping          | 2.25 MB  | 2.33 MB         |
| Arrays                          | 11.62 MB | 7.77 or 8.89 MB |
| Single inserts                  | 5.43 MB  | 2.58 MB         |
| `UNNEST` bulk insert            | 0.93 MB  | 0.79 MB         |
| JDBC batching                   | n/a      | 2.94 MB         |
| JDBC batching + rewrite         | n/a      | 2.44 MB         |

One row here is worth pausing on. Reading raw values is ~16% slower in Octavius while allocating **less** than pgjdbc (
2.25 MB against 2.33 MB), so whatever costs the time on that path, it is not garbage — and looking for it among
allocations would be looking in the wrong place.

Arrays allocate materially more, which is the per-element conversion described above doing its work; the generality has
a memory cost as well as a time one. Octavius's 11.62 MB sits still, ±0.3 KB across the three forks. pgjdbc's does
not: two forks allocated 7.77 MB and the third 8.89 MB, each steady within itself, so the ratio is 1.5× or 1.3×
depending on the fork.

Single-row inserts allocate 2.1× more — 5.43 MB against 2.58 MB — a wider gap than the 29% they lose on the clock.

## Summary

* **Object mapping ties with `pgjdbc`** — the path most applications spend their time on.
* **Not preparing statements costs ~12 µs a statement** — measured with pgjdbc against itself, server-side prepare
  switched on and off, on a primary-key lookup chosen so that the figure would be the largest share of a query it can
  be. Under 1% of a statement that does 2 ms of work.
* **`UNNEST` bulk writes are 3.7× faster than classic JDBC batching** and tie with pgjdbc's rewrite optimization, while
  remaining usable for `UPDATE` and `DELETE`, where that optimization does not apply.
* **Array decoding costs ~20% for being general** — every element goes through the machinery that also maps composites
  and nested structures. Expect it to stay that way; it is what buys you `List<YourDataClass>`.
* **Raw value decoding is ~16% slower without allocating more** — a genuinely different problem from the array one, and
  not one to look for among allocations.
* **Reflection cost ~2.5× the allocation of a hand-written converter** on a narrow row, in both directions, and less
  time than this run can resolve — rising to 3.4× the allocation where the composite has another inside it. The
  direction generalizes; the multiplier is an example from one shape, not a constant. Worth replacing on your hottest
  path, not everywhere.
