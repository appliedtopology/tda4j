# Scala 3's bytecode optimizer (`-opt`, `-opt-inline`): measured, not adopted (2026-10-13)

Question (project lead): "Are there Scala compiler optimizer flags we should be using but aren't?"

**Answer: one family exists and we do not use it; on this codebase it buys nothing safe, so it stays off.**

## What exists

Scala 3.8.3 shipped a port of the Scala 2.12/2.13 JVM bytecode optimizer (Scala Center, "Porting the Scala 2
optimizer to Scala 3", scala-lang.org blog, 2026-03-23). Checked against the compiler's own `-help`/`-Y`/`-W`
output for both 3.9.0 (the build) and 3.8.4 (the docs build): both accept the flags.

- `-opt`: method-local rewrites only (unreachable code, jump simplification, copy propagation, box/unbox
  elimination within one method, nullness tracking, closure-invocation rewriting).
- `-opt-inline:<patterns>`: the inliner, which is where closure elimination comes from. Patterns as in Scala 2
  (`**`, `a.**`, `!a.b.**`, `<sources>`); `-Yopt-inline-heuristics`, `-Yopt-log-inline`, `-Wopt` alongside.
- The Scala 2 rules carry over unchanged (docs.scala-lang.org, "The Scala 2.12 / 2.13 Inliner and Optimizer"):
  a PUBLISHED library must not inline from its classpath (inlined dependency bytecode breaks when a user's build
  resolves a newer version, and never picks up its fixes), so the only library-safe setting is our own package;
  and the optimizer breaks incremental compilation (zinc does not recompile a caller when an inlined callee's BODY
  changes), so it belongs in CI/release builds only, never the dev loop.

Nothing else in the 3.9.0 option list is a runtime-performance lever. `-java-output-version` (default 17) changes
which JDKs can load the jar, not speed (and raising it would drop JDK 17 users and reopen the MATLAB JVM question in
`WORKLOG-matlab-api.md`); `-Ybackend-parallelism` is compile time only; `-Ylegacy-lazy-vals` reverts to the pre-3.3
lazy vals; `-Yno-patmat-opt` turns an optimization off.

## Probe: what the inliner can reach

A three-line object with `for i <- 0 until a.length do s += sq(a(i))` and `a.map(_ + 1).sum`, compiled four ways
and read back with `javap -c`:

- `-opt` alone: no visible change.
- `-opt -opt-inline:T*` (own code only): inlines `sq` into the loop's closure, but the `Range.foreach` call and its
  closure stay.
- `-opt -opt-inline:**`: `Range.foreach` and `ArrayOps.map` are inlined and the closures eliminated (the `for`
  becomes a plain loop).

So the transformation the optimizer is known for needs inlining from `scala.**`, which a published library cannot
do. Our-package-only inlining can only inline our own final/static methods, which HotSpot's C2 already does.

## Measurement

Three fat jars from fresh compiles of `ba72bf06` (`sbt 'set scalacOptions ++= Seq(...)' clean assembly`):

- `A2`: baseline (current flags). The first baseline, `A`, was an sbt 2 disk-cache hit after `clean` and is NOT
  byte-identical to a fresh compile: 21 classes differ, by private context-bound evidence fields
  (`org$appliedtopology$tda4j$Filterable$$FiltrationT` and similar) present in the cached jar only. Cause not
  investigated; it is why `A2` (a fresh compile, cache key busted with a no-op `-encoding UTF-8`) is the baseline.
  Compile A/Bs must compare fresh compiles made the same way.
- `B`: `-opt -opt-inline:org.appliedtopology.**` (the library-safe setting).
- `C`: `-opt -opt-inline:**` (everything, stdlib included: legitimate only for an application such as the fat jar).

Clean `assembly` wall time (sbt startup included, 4-core sandbox): A2 60 s, B 74 s (+23%), C 98 s (+63%).

Runs: the `tda4j` CLI from each jar, one JVM per run, `-Xms3g -Xmx3g -XX:+UseParallelGC`, jar order rotated each
trial, whole-process wall time (JVM start, input parsing and output included, as a CLI user sees it), median of 5
(9 for the alpha1500 rerun). Seeded inputs: a noisy torus in R^3 (800/1500/5000 points), 60 points on S^2, a
48^3 uniform random image. Ranges overlap within every row unless noted.

| workload | A2 (base) | B (own pkg) | C (all) |
|---|---|---|---|
| VR + ripser, torus1500, maxDim 2, max filtration 1.0 | 13.77 s | 14.71 s | 13.98 s |
| VR + cohomology, sphere60, maxDim 2 | 13.51 s | 13.92 s | 13.91 s |
| fast-cubical, 48^3 image | 5.03 s | 5.23 s | 5.12 s |
| alpha (default engine), torus1500, 1st run | 4.01 s | 4.05 s | 3.62 s |
| alpha, torus1500, rerun, median of 9 | 3.86 s | 4.00 s | 3.63 s (8 of 9 runs below A2's median) |
| alpha, torus5000 | 7.91 s | 8.00 s | 7.56 s |
| VR + chunks, sphere60, maxDim 2, max filtration 1.0 | 4.91 s | 4.91 s | 4.74 s |

Every output file was byte-identical across the three jars.

## Reading

- **B (the only setting we could ship in the library jar): no gain on any workload.** It is 0-7% SLOWER on every
  row, but each row is within noise; the consistent sign is suggestive only (a plausible mechanism is that bigger
  post-inlining methods use up HotSpot's own size-based inlining budgets). Unconfirmed.
- **C: 4-10% on alpha (6% in the 9-trial rerun; 0.23-0.39 s), nothing elsewhere.** The absolute gain does not grow
  from 1500 to 5000 points while the run time doubles, which points at less time in the interpreter and C1 before C2 takes over, not faster steady-state code.
  The alpha code has the most range-`for` loops and collection HOFs of any area (40 and 180 by grep, against 18 and
  154 in all of `homology/`, which is mostly `while` loops).
- Why so little: the hot loops were already rewritten by hand to `while` loops over primitives
  (`WORKLOG-ripser-profiling.md` findings #2, #5, #9), and the remaining known costs (boxing through the `Field`
  instance, `SortedMap`/`HashMap` machinery) are calls on runtime values that a bytecode inliner cannot resolve.

## Decision

`build.sbt` unchanged. Revisit only if (a) a profile shows closure or `Range.foreach` overhead in a hot path that a
hand-written loop or a language-level `inline def` cannot fix more locally (both are incremental-compile-safe and
already used in the packed and fast engines), or (b) the fat jar gets its own compile, where `-opt-inline:**` is legitimate;
for 4-10% on one construction that separate compile is not worth its build complexity today.
