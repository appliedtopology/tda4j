# WORKLOG: the thread count on one processor (#339)

2026-11-13, cloud session, on PR #340 beside the demo's web page, which found it
(`WORKLOG-web-playground-build.md`, point 1). The project lead: "Let's fix #339. I'm okay with it sharing a PR."

## What was wrong

1. **`Parallel.threads` was never below 2.** It was `ForkJoinPool.commonPool().getParallelism + 1` ("the pool's workers
   and the caller"), and the pool never reports less than 1. So on a one-processor machine, and with the pool's size
   set to 0, `Engine.Auto` chose `ParallelRipser`, `Parallel.byDefault` was `true`, and every step split its work.
2. **A one-thread run did not stay on its thread** (found while reading the callers). `Parallel.ranges` cut its range
   in `min(threads * 4, count / grain)` pieces and gave them to a parallel stream whenever that was more than one: with
   `threads == 1`, up to four pieces on the common pool. `Parallel.sequentially`, `withThreads(1)`,
   `Persistence(threads = 1)`, `--threads 1` and the benchmark drivers' `sequential=true` all go through it. Callers
   of `ranges`: the alpha complexes' face tables and values (`AlphaShapes`, `AlphaComplexDQP`), the cohomology engine's
   coboundary blocks (`Cohomology.scala`), the scan of `ParallelRipserCycles`. `ParallelPackedCohomology` guarded its
   own call and was not affected.

## What the JVM does

Measured on Java 21.0.12 with a class that runs a parallel stream of 16 sleeping items and prints the threads that ran
them (`-XX:ActiveProcessorCount=P`, and `N` for `-Djava.util.concurrent.ForkJoinPool.common.parallelism=N`):

| started with | `availableProcessors` | `getParallelism` | threads that ran the items |
|---|---|---|---|
| P=1 | 1 | 1 | 2 (one worker, main) |
| P=2 | 2 | 1 | 2 |
| P=4 | 4 | 3 | 4 |
| P=4, N=0 | 4 | 1 | 1 (main alone) |
| P=4, N=1 | 4 | 1 | 2 |
| P=1, N=3 | 1 | 3 | 4 |
| P=4, N=-2 | 4 | 1 | 1 (read as 0) |
| P=4, N=x | 4 | 3 | 4 (ignored) |

Two facts follow. Left alone, the pool has `max(1, processors - 1)` workers: a real worker on one processor, where it
and the caller take turns. And `getParallelism` cannot tell a pool of 0 from a pool of 1; only the property can.
`ForkJoinPool.getCommonPoolParallelism` reports the same numbers.

## The rule

`Parallel.commonThreads(reported, preset, processors)`, with `preset = Parallel.presetWorkers(the property)`:

- the property sized the pool: `reported + 1`, and 1 for a pool of 0. Taken at its word whatever the machine has.
- nobody sized it: `min(reported + 1, processors)`, at least 1.

Every row of the table above then counts the threads that really run, except P=1 with no property, which counts 1
where two threads share one processor: nothing is gained by splitting there, and the page's runtime hangs on it.

**The choice in it**: #339 proposed `min(parallelism + 1, availableProcessors)` for every case. That would also
overrule a pool somebody sized on purpose (`N=3` on one processor: 1 thread), which is how one exercises the shared
code paths on a small machine. An explicit size wins here, as `withThreads(n)` and `threads = n` already do. The
property and the processors are read once, when `Parallel` loads, as the pool reads both once; the pool's own report
is read on every call (`ForkJoinPool.setParallelism` can change it).

`ranges` makes one call when `threads == 1`.

## The spec (`ParallelThreadsSpec`, JVM)

- The rule as a table (`commonThreads` over what the JVM would report).
- The count itself in four JVMs that the spec starts, because neither the processors nor the pool's size can change in
  a running JVM, and a spec must not set process-wide state: P=1 gives 1, P=4 gives 4, P=4 N=0 gives 1, P=1 N=3 gives
  4. The class path for them comes from the spec's class loaders (`URLClassLoader.getURLs` up the parent chain; sbt 2
  runs specs inside its own JVM, where `java.class.path` is sbt's) plus `java.class.path` for other runners. Started
  together; the spec takes 3 s alone and 5 s beside three others.
- Inside `sequentially`, every kind of step on the calling thread and `ranges` in one piece. Each piece sleeps 20 ms:
  with trivial pieces the calling thread ran all four before a worker woke, and only the piece count failed.

Both new examples failed on the old code: `'threads=2' != 'threads=1'`, and `List(ranges) is not empty` (pieces on
other threads inside `sequentially`).

Also run after the fix, all passing: `ParallelRipserEngineSpec`, `ParallelRipserSpec`, `ParallelPackedCohomologySpec`,
`BlockParallelReductionSpec`, `ParallelAlphaSpec`, `PackedSimplexCohomologySpec`, `EulerCharacteristicCurveSpec`,
`LongestDistanceWithinSpec`, `ZigzagPersistenceSpec`, `RationalPersistenceSpec` (101 examples with the spec above).

## Consequences

- **One processor**: `Engine.Auto` is `Ripser`, not `ParallelRipser`. Same bars; representatives may differ in ties
  (`rules/engines.md`). The same for the pool's size set to 0, which the docs already called the sequential run.
- **Timings taken with one thread before this** (`threads = 1`, `sequential=true` in `PaperBenchmarkDriver`, so the
  single-thread rows of `bench/`) had help from up to three other threads in the `ranges` steps: alpha cases most,
  the `Cohomology` engine's block build on layers of 4,096 cells or more, DQP's table. How much was not measured. The
  Vietoris-Rips rows through `Ripser` do not call `ranges`. Rows measured before the fix are not single-thread numbers
  for those cases.
- `bench.py` passes the pool's size as `threads`, one more thread than it names where the common pool is used. The
  drivers run the parallel engine in a pool of their own (`withThreads`), so this does not reach the measured rows;
  left as it is.

## The demo's web page

The page hung because of defect 1: CheerpJ has one processor, the count was 2, `Auto` chose `ParallelRipser`, and
fork-join threads never finish in that runtime (3 of 10 sessions came back). The page's own guard,
`Parallel.sequentially` around every input, fixed it there (`WORKLOG-web-playground-build.md`).

With this library fix and the guard taken out of `WebRepl` (a build for this check only): 8 of 8 fresh sessions came
back from `val diagram = Persistence(points)`, 4.9 to 5.3 s. So the count was the cause, and the fix alone is enough
for the page. The guard stays in: it holds whatever the runtime reports about its processors, and before this fix it
did not cover `ranges` (defect 2), which the page's examples happen not to reach.

The final page (fix and guard) passes `check-web-playground.mjs`: 21 checks in 85 s, on a machine that ran every
line about a tenth slower than the day's earlier runs (`Persistence(points)` 5.0 s).
