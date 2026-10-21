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

For memory, compare peak RSS or a heap histogram
(`jcmd <pid> GC.class_histogram`), or find the smallest `-Xmx` at which each
variant still finishes. An `OutOfMemoryError` at a heap size where the other
variant runs is a clearer result than a timing.

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

## Against other libraries: `bench/`

`bench/` times TDA4j against the other persistent-homology libraries on the
same inputs on one machine:
- for Vietoris-Rips: ripser.cpp, ripser.py, giotto-ph, GUDHI, Ripserer.jl and
  JavaPlex;
- for cubical complexes: GUDHI and CubicalRipser;
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
engines across sizes; it is how the `Auto` default for images is chosen.
`bench/run.sh sweep-threads` times the multithreaded Vietoris-Rips tools,
the two block-parallel experiments among them, at 1, 2, 4, ... threads, each
run pinned to as many CPUs, and checks that the barcode does not change with
the thread count.
