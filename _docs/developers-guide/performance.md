---
layout: main
title: Measuring performance
---

A speed or memory claim about this library needs an isolated measurement. On
a laptop or a shared machine the noise often exceeds the effect, so this page
describes how to measure, and the tools in the repository for doing it.

## The rule: an A/B in isolation

- **One engine per JVM process.** The JIT, the heap and the caches of one run
  change the next, so a fair comparison runs each variant in its own process.
- **Warm-ups, then a median.** Run a few untimed computations, then several
  timed ones, and report the median. Repeat the process three times or more,
  and look at the spread between processes, not only within one.
- **Interleave the variants.** Run variant A, then variant B, then A again,
  rather than all of A first, so drift in the machine hits both.
- **Build the variants apart.** Compile each variant in its own git worktree
  and copy the jars aside. Never run two `sbt` processes against one checkout:
  one process can read the other's class files mid-write, and the resulting
  `NoClassDefFoundError` looks like a regression.
- **Say when an effect is within the noise.** A difference smaller than the
  spread between processes is not a result.

For memory, see [Memory: measure the live data](#memory-measure-the-live-data) below.

## Benchmark specs

The benchmark specs in `src/test/.../homology` print timing tables. Only an
exception makes one fail. They are skipped unless the JVM property
`runBenchmarks` is set, so CI never runs them:

```
sbt -DrunBenchmarks=true "testOnly *EngineComparisonBenchmarkSpec"
```

The specs are:
- `EngineComparisonBenchmarkSpec` times every pairing of construction and
  engine over point count, dimension and degree, with construction and
  reduction timed apart and a timeout per cell. It runs for 15 minutes or more.
- `ProfilingSpec`, `ApparentPairsBenchmarkSpec`, `CubicalBenchmarkSpec`,
  `ThresholdBenchmarkSpec`, `DimensionCeilingBenchmarkSpec` and
  `EdgeCollapseBenchmarkSpec` each cover one engine or construction.
- `RipserPaperBenchmarkSpec` runs the datasets of the Ripser paper. It needs
  `-DdataDir`, and with `-DripserBin` it also times `ripser.cpp`.

Use them to see where time goes across the library, not for an A/B: they run
everything in one JVM.

## Profile drivers

For an A/B, use a driver: a `main` in the test tree that runs one engine on
one workload and prints a median. The drivers include:
- `PaperBenchmarkDriver`, which runs the `Persistence` verb (or a named
  engine) on Vietoris-Rips (optionally edge-collapsed), cubical, alpha, Čech, sparse
  Rips, DTM-Rips, witness or Dowker input and is what `bench/` calls;
- `SingleEngineProfileDriver` and `VRLowDimProfileDriver` for Vietoris-Rips,
  the first as a single-engine subprocess for comparisons with ripser.cpp;
- `CubicalProfileDriver` and `FastCubicalProfileDriver` for images;
- `UnionFindEdgeDriver` for the chunks engine's union-find;
- the `...ParallelProfileDriver`s for the opt-in parallel filtration values.

Each documents its arguments in its scaladoc. Run one inside sbt with
`sbt "Test/runMain org.appliedtopology.tda4j.CubicalProfileDriver 2 512"`, or
outside sbt, which an A/B across worktrees needs. To run outside sbt, get the
classpath with `sbt "export Test/fullClasspath"`. sbt 2 prints it with
placeholders: replace `${OUT}` with the checkout's `target/out` and
`${CSR_CACHE}` with the coursier cache, and drop the `>sha256-...` suffixes.

For where the time goes inside a run, record it with Java Flight Recorder
(`-XX:StartFlightRecording`). The scripts `run-single-engine-jfr.sh` and
`jfr-summarize.sh` in `.claude/scripts/` wrap a driver run and summarize the
hot methods.

## A worked A/B

To compare a change with the code before it:

1. `git worktree add ../tda4j-a <base>` and `../tda4j-b <change>`. In each, `sbt "export Test/fullClasspath"` (one sbt at a
   time), and note the two classpaths. Each worktree keeps its own `target/`, so neither build disturbs the other.
2. Pick a driver and a workload that exercise the change through the entry point a user calls (below). The workload must
   be large enough that one run takes seconds: below that the JIT's first compilations are most of what you time.
3. Run `java -cp $CP_A ...`, then `$CP_B`, then A, then B, each as a fresh JVM, at least three times each. The driver prints
   the median of its timed trials and one JSON line; compare the medians of the processes, and look at the spread.
4. Add `digest=true` to `PaperBenchmarkDriver`: it prints a SHA-256 of the last diagram's bars with their representatives'
   terms. **Equal digests across the two builds show the change altered no output**, which is what an optimisation claims.
   A digest does not apply to a change that is allowed to change representatives; compare bars there.
5. Report what the numbers show. An effect inside the spread between processes is unconfirmed, and says so.

Mutation-test the new code the same way you would a spec: an A/B on code that is not run proves nothing, and a
wrong answer that is fast shows as an improvement.

## Profile the steady state, through the real entry point

- **Skip the warm-up.** A fresh JVM spends its first few runs in the interpreter and the compilers: runs that take
  3 to 5 seconds at first take 1 second once warm, whatever the collector. A recording of the whole process mostly profiles
  the JIT. Start it after the warm-up (`-XX:StartFlightRecording:delay=22s,...`) and run enough trials (30 or so)
  to have samples to read.
- **Profile what the user calls.** The default `Persistence` run on points asks for cycles, which Ripser derives from its
  cocycles by an extra reduction (`Involution`); a profile of the cocycle-only driver never sees that pass, and half the
  time of the default run once went there. `PaperBenchmarkDriver ... reps=cycles` is the default row.
- **JFR names the hot method, not the cost of removing it.** A frame that shows hot in the profile can cost nothing
  when removed (the sampled allocation sites of a tabulate, the inlining of a sort); check each candidate with an A/B
  before you build on it.
- `jfr-summarize.sh <recording.jfr>` reduces a recording to the top CPU leaf frames and the top allocation sources by
  class and by call site, weighted by bytes, in under a hundred lines. For allocation, `jdk.ObjectAllocationSample`
  grouped by class and by the first TDA4j frame, with its line number, finds the boxing sites in one pass.

## Memory: measure the live data

Peak RSS follows the collector. With `-Xmx` equal to `-Xms` and the parallel collector, a 1 to 3 GB computation never
collects in a 16 GB heap, and RSS is the sum of everything it allocated, including the trials after the first. The
benchmarks therefore use the serial collector with a heap that follows the live data. To find what a computation
needs: the heap histogram at a point where its result is still reachable (`hold=<seconds>` keeps the last diagram alive
for it), the smallest `-Xmx` at which it finishes, or `heap_peak_mb` and `old_peak_mb` of the driver's own pools.

Memory limits what can be computed more than time does, so the project prefers a small slowdown that saves a lot of memory
(hybrid dense and neighbour-list edge ranks in `ParallelRipser`, primitive per-simplex state, bars of 330 bytes rather
than objects of several times that). Show both numbers.

## The same entry point on both platforms

A benchmark of Scala.js against the JVM is only meaningful when both sides run the same code path. The npm package is the
facade (`matlab.TDA4j`), the `Persistence` verb is something else: they differ in the degrees they build, in
zero-length bars and in representatives, and a 500-fold difference between "JS" and "JVM" once came from exactly that.
Compare facade with facade, or verb with verb. See [JVM and Scala.js](jvm-and-js.md).

## Against other libraries: `bench/`

`bench/` times TDA4j against the other persistent-homology libraries on the
same inputs on one machine:
- for Vietoris-Rips: ripser.cpp, ripser.py, giotto-ph, GUDHI, Ripserer.jl and
  JavaPlex;
- for cubical complexes: GUDHI and CubicalRipser, and FlashCubical (2-D and 3-D, Z/2, which reads vertex values: the
  benchmark's `_v` cases give TDA4j the equivalent image);
- alpha complexes as well;
- Čech (GUDHI's Delaunay-Čech), DTM-Rips (GUDHI), witness (GUDHI, JavaPlex),
  sparse Rips (GUDHI's sparse Rips, a different construction, and the exact
  Rips barcode from ripser.cpp as the reference) and Dowker complexes (a
  hand-built GUDHI simplex tree, Blaser and Brun's `dowker_homology`, Yoon's
  Julia package);
- edge collapse: `tda4j-collapse` beside `gudhi-collapse`.

It also checks that every tool returns the same barcode, by bottleneck
distance per degree. `bench/run.sh setup`, `prepare` and `run` install the
tools, generate the data and run the cases; `bench/README.md` has the options.

It reports both:
- **cold**, one computation per process including JVM start-up, which is what
  a command-line or first MATLAB call sees;
- **warm**, the median of computations in one process, which is what a
  long-running process sees.

It records peak RSS for each. `bench/run.sh sweep-cubical` times the image
engines across sizes, with CubicalRipser and GUDHI beside them, and is how the
`Auto` default for images is chosen; its RSS column is the memory comparison.
`bench/run.sh sweep-threads` times the multithreaded Vietoris-Rips tools,
`ParallelRipser` through the verb and the bars-only block-parallel experiment
among them, at 1, 2, 4, ... threads, each run pinned to as many CPUs, and
checks that the barcode does not change with the thread count.

The main tables are single-threaded, as ripser.cpp and GUDHI are: every TDA4j
row runs with `sequential=true`, except the rows named `-mt`. `bench/final_run.sh`
runs the paper's final benchmark in stages on one frozen commit: it measures
TDA4j's own rows and merges them (`bench.py merge`) with the rows of the other
tools from an earlier full run, after anchor rows have shown that the machine
still runs those tools at the same speed.
