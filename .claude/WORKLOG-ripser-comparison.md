# WORKLOG: tda4j vs real ripser.cpp on the Ripser paper's own benchmark data

Date: 2026-09-17. Point-in-time record — not retroactively edited across sessions (see
[[tda4j-worklog-convention]]). Finding #2 below WAS rewritten in place, but within this same investigative
arc/session, after a follow-up measurement corrected the initial read of the raw ratios — the wrong first read
is kept visible in the text rather than silently replaced, which is the intent behind "not retroactively
edited": don't erase a dead end, but also don't ship a conclusion inside one arc that a later measurement in
the same arc already overturned.

## Ask

Project lead asked for a comparison of the current library against real Ripser, specifically on the test cases
Ripser's own paper (Bauer, arXiv:1908.02518, Table 1) used, and flagged that dramatic slowdowns (as opposed to
"some degradation") should trigger a deeper look at why the library is slow.

## Setup

- Hardware: Apple M1 Pro, 32GB RAM, macOS 15.7.1.
- JDK for both `sbt` and the test JVM: OpenJDK 17.0.9 (JetBrains Runtime build), via `$JAVA_HOME`
  (`/Users/mik/.sdkman/candidates/java/current`). Note: CLAUDE.md's own "Commands" section says "Java 21" —
  this session actually ran on 17.0.9, a pre-existing environment discrepancy, not something fixed here.
- Real `ripser.cpp`: fresh `git clone https://github.com/Ripser/ripser.git`, plain `make` (no
  `USE_COEFFICIENTS`, so it computes over Z/2 by default — this codebase's `RipserPaperBenchmarkSpec` uses
  `FiniteField(2)` explicitly to match).
- Data sets and exact CLI flags: reconstructed from the paper's own `ripser-benchmark` GitHub repo's Dockerfile
  (`github.com/Ripser/ripser-benchmark`), not guessed — this is the actual harness that produced the paper's
  Table 1. `sphere3`/`o3_1024`/`o3_4096` are `ripser`'s own bundled examples; `random16`/`dragon`/`fractal-r`
  come from the Otter et al. "PH-roadmap" benchmark the paper cites (`n-otter/PH-roadmap` on GitHub).
- `torus4` (50000 points) deliberately excluded: real `ripser.cpp` itself needs ~8GB for it (Table 1), and
  `RipserCohomologyContext`'s `Simplex[Int]`/`SortedSet[Int]` per-simplex carrier is roughly two orders of
  magnitude heavier than Ripser's packed 64-bit `diameter_index_t` (already flagged as a deliberate deferred
  choice in `DiameterSimplex`'s own doc, `RipserStream.scala`) — extrapolating that ratio puts torus4 well past
  what any single machine reasonably has. Not run; stated instead of spending wall-clock time proving it.
- New spec: `src/test/scala/org/appliedtopology/tda4j/RipserPaperBenchmarkSpec.scala`, `skipAll`'d by default
  (matching `EngineComparisonBenchmarkSpec`'s convention — this asserts nothing, just prints a table, and holds
  sbt's project-wide lock while running). Run via:
  ```
  sbt -J-Xmx16G -DdataDir=<path> -DtimeoutSeconds=240 "testOnly org.appliedtopology.tda4j.RipserPaperBenchmarkSpec"
  ```

## Finding #1: `RipserCohomologyContext`'s `maxDimension` parameter means "top simplex dimension built," not "top
homological degree reported" — the same truncation-artifact class already documented for the MATLAB facade

A first version of this spec passed the requested homological degree directly as `maxDimension`
(`RipserCohomologyContext(ms, 2)` for `--dim 2`). Result: sphere3 (192 points) reported **1,072,740 bars** at
supposed `maxDim=2` — Table 2's entire non-zero-pair count for that exact data set is 18,145, two orders of
magnitude smaller, which is what caught this before it reached a real comparison.

Root cause, confirmed by reading `Homology.scala` directly: `coboundaryOf(sigma)` (`RipserCohomologyContext`,
`Homology.scala`) is

```scala
def coboundaryOf(sigma: Simplex[Int]): Chain[Simplex[Int], CoefficientT] =
  if sigma.dim + 1 > maxDimension then Chain.empty
  else ...
```

so at `sigma.dim == maxDimension`, the coboundary is empty **by construction** — every simplex at the requested
top dimension comes out essential regardless of whether it actually is, because no `(maxDimension+1)`-simplex
ever gets built to potentially kill it. This is exactly the well-known "H_k needs (k+1)-chains" truncation
artifact CLAUDE.md's MATLAB-facade section already documents and fixes for `Tda4j.computeFromPoints`/
`computeFromDistanceMatrix` ("build to `maxDimension + 1` internally, report only `dim <= maxDimension`") — it
had just never been applied to a *direct* caller of `RipserCohomologyContext` before, because every existing
comparison of this engine (`RipserCohomologySpec`'s `cohomologyBars` vs. `naiveBars`) compares it against
`LimitedCofaceSimplexStream(stream, maxDim)`, which truncates *simplices* at the same `maxDim` too — both sides
consistently truncated the same way, so the existing cross-validation suite never exercised "does `maxDim`
actually mean top reported degree," only "do two consistently-truncated computations agree with each other."
Real `ripser --dim p` does not have this problem: it always builds the `(p+1)`-skeleton internally to resolve
dimension-`p` pairs (the paper's own Section 3.2).

Fixed in the spec (not in the engine — this is a benchmark-harness fix, not a library change) by constructing
`RipserCohomologyContext(ms, requestedDim + 1, ...)` and discarding the `dim == requestedDim + 1` bars (all
spuriously essential) before comparing. **This is worth a documentation note in `CLAUDE.md` itself** (added
below in the "What actually shipped" section) since it's a real, non-obvious API footgun for any future direct
caller of `RipserCohomologyContext`, not just a benchmark-harness bug.

## Finding #2: a large but roughly FLAT per-simplex constant-factor tax — not an algorithmic (superlinear)
divergence, despite the raw slowdown ratio appearing to grow sharply

Every case ran with correct output (barcode dimension counts, non-zero-persistence only, matched real ripser's
own printed output exactly) whenever it completed at all. The failure mode throughout is TIME, not WRONG
ANSWERS.

### Size-growth ladder (same shape, same `--dim 2`, same no-threshold default, only `n` varies)

| n   | tda4j (ms) | ripser.cpp (ms), this machine | slowdown | bars match ripser? |
|-----|-----------:|-------------------------------:|---------:|:-------------------:|
| 48  | 2,942      | 10                              | 294x     | yes (d0=48,d1=14,d2=1) |
| 96  | 60,675     | 50                              | 1,213x   | yes (d0=96,d1=22,d2=1) |
| 192 | timeout (>240s) | 660                        | —        | did not complete |

**First read of this table (wrong, corrected below by an advisor-prompted follow-up measurement): "the ratio
more than quadruples from n=48 to n=96, so this must be algorithmic divergence."** That reading doesn't survive
a cheap, direct check — `RipserCohomologyContext.totalSimplexCount` (a public accessor that already exists for
exactly this kind of structural check) at each `n`, measured with a throwaway probe
(`RipserDiagProbe.scala`, deleted after use, not part of the permanent suite):

| n  | tda4j time (ms) | totalSimplexCount | µs/simplex |
|----|----------------:|-------------------:|-----------:|
| 48 | 2,675           | 136,390             | 19.6       |
| 96 | 54,069          | 2,480,471           | 21.8       |

(These probe numbers ran with `-J-Xmx8G`, not the `-J-Xmx16G` the main spec run above used — a different heap
size, not a repeat of the same measurement. Don't read 54,069ms here against 60,675ms in the spec table above
as if only that one JVM difference explains the gap; both are separate single runs, not medians, so ordinary
run-to-run noise on top of the heap difference is the more likely explanation for the ~6.6s gap.)

Simplex count grows **18.2x** from n=48 to n=96 (consistent with the expected `~C(n,4)` combinatorial growth
for a dimension-3 build: `C(96,4)/C(48,4) ≈ 17.0`, close given the enclosing-radius threshold isn't identical
at the two sizes); tda4j's own time grows **20.2x** — almost exactly in step. **Per-simplex cost is flat to
within ~11%, not growing.** So the sharply-growing *slowdown ratio* against ripser is NOT evidence that
tda4j's own algorithm is diverging with `n` — it is real ripser.cpp's own 10ms/50ms numbers being dominated by
process-startup/OS overhead at this tiny scale (10ms is close to `/usr/bin/time`'s own resolution floor and
includes process launch, not just the actual VR computation), not a clean measurement of ripser's real
per-simplex cost at n=48. This is exactly the caveat flagged before the ladder was run and confirmed after the
fact by this follow-up measurement, not assumed away.

**The honest, measured finding is a large constant-factor tax, not a growing divergence**: tda4j pays
**measured, directly**: roughly **~20 microseconds of wall-clock time per simplex** assembled and reduced by
`RipserCohomologyContext` (`elapsed / totalSimplexCount`), flat to within ~11% across an 18x range of complex
sizes. Real `ripser.cpp`'s own per-simplex cost is NOT directly measured this session — `ripser.cpp` doesn't
print a simplex/pair count by default, and the only wall-clock numbers available (10ms/50ms at n=48/96) are
exactly the startup-dominated floor already flagged as unreliable above. What IS solid: `ripser.cpp` solves
Table-2-scale complexes (hundreds of thousands to tens of millions of simplices) in 1-31 seconds flat on this
machine (the "Full Table-1 data sets" numbers below), which bounds its per-simplex cost well below tda4j's
~20µs without pinning down by exactly how much — stated as a bound, not a measured ratio, to hold this session
to the same "measure, don't infer" standard its own numbers are held to. Either way, this constant factor, not
a blowup that gets worse with `n`, is what turns "some degradation" into "6 of 8 Table-1-derived cases don't
finish in 240 seconds": the complexes involved reach millions of simplices well within the paper's own test
cases, and 20µs x millions is minutes, regardless of whether the growth curve itself is well-behaved.

### Full Table-1 data sets (informational — most did not complete, consistent with Finding #2's scale argument)

| case      | tda4j (ms) | ripser.cpp (ms) | slowdown | status |
|-----------|-----------:|-----------------:|---------:|--------|
| sphere3 (n=192, dim2) | — | 660 | — | timeout (240s) |
| dragon (n=2000, dim1) | — | 1,150 | — | timeout |
| o3_1024 (n=1024, dim3, t=1.8) | — | 1,570 | — | timeout |
| fractal-r (n=512, dim2) | — | 3,030 | — | timeout |
| random16 (n=50, dim7) | — | 3,440 | — | timeout |
| o3_4096 (n=4096, dim3, t=1.4) | — | 30,790 | — | timeout |

**Honest headline: tda4j's `RipserCohomologyContext` completed 2 of 8 Table-1-derived rows inside a 240-second
per-case budget. On the two that completed, it paid a roughly constant ~20µs/simplex — genuinely large (an
order of magnitude or more versus real `ripser.cpp`) but NOT growing with problem size.** This is squarely the
"dramatic slowdown, worth searching more carefully" case the project lead flagged in advance — just a constant-
factor one, not a superlinear one; the distinction matters because it points at a fixable representation/
overhead cost rather than a reduction-order or correctness bug.

Total wall-clock for the whole spec run: ~25 minutes (mostly spent inside the six 240s timeouts). GC pressure
during the run climbed to 72-78% of wall time in several 10-second windows as heap usage climbed toward the
16GB cap passed via `-J-Xmx16G` — consistent with an allocation-heavy hot path.

## Bounded diagnostic: where does the ~20µs/simplex actually go?

`SparseRipsBenchmarkSpec` already exercises this same engine at n=40-80 with `maxDim=2` on 2-D random clouds,
fast enough to run a 3-trial sweep as part of routine benchmarking — nothing resembling 60 seconds at n=96. The
two things that differ here: this spec's `maxDimension` is `requestedDim + 1 = 3` (per Finding #1), not 2, and
sphere3's ambient dimension is 3, not 2. A direct `maxDimension=2` vs. `maxDimension=3` run on sphere3_96 (same
throwaway probe) isolates which level the cost is concentrated at:

| maxDimension | time (ms) | totalSimplices |
|---|---:|---:|
| 2 | 2,209 | 124,628 |
| 3 | 53,641 | 2,480,471 |

Simplex count ratio (19.9x) and time ratio (24.3x) are close — consistent with Finding #2's "roughly flat
per-simplex cost" conclusion, not a separate dim-3-specific regression. A `jstack` sample taken mid-run during
the `maxDimension=3` case (same method `WORKLOG-mst-and-perf.md` used to catch an earlier comparator-cost bug)
caught the worker thread inside:

```
RipserCohomologyContext#cohomologyOrdering.compare (Homology.scala:796-797)
  -> FiniteMetricSpace.MaximumDistanceFiltrationValue.apply (FiniteMetricSpace.scala:69-73)
  <- scala.collection.immutable.RedBlackTree.lookup
  <- Chain.updateMap (Chain.scala:126)
  <- Chain.reduceLoop / reduceByUntil / reduceBy (Chain.scala:163-188)
  <- RipserCohomologyContext.persistentCohomology (Homology.scala:1108)
```

i.e., a live sample landed inside the *unmemoized* filtration-value recompute (`memoizeFiltrationValue`
defaults to `false`, CLAUDE.md's documented memory-frugality tradeoff) that `cohomologyOrdering.compare` does
on every `TreeMap`/`RedBlackTree` comparison inside `Chain.reduceBy`'s reduction. **This looked, on first catch,
like it might be the dominant cost** — but a direct `memoizeFiltrationValue=true` vs. `false` A/B on the exact
same sphere3_96/maxDim=3 case settles how much of the ~20µs/simplex it actually accounts for:

| maxDimension | memoize | time (ms) |
|---|---|---:|
| 2 | false | 2,209 |
| 2 | true  | 1,638 |
| 3 | false | 53,641 |
| 3 | true  | 41,039 |

A ~24-26% speedup at both dimensions — real, and already exactly what CLAUDE.md's own prior measurement
documented for this flag ("a real but modest 5%-25% slowdown... shrinking as n grows," `SparseRipsBenchmarkSpec`/
`ApparentPairsBenchmarkSpec`). **This confirms existing knowledge rather than surfacing something new**: the
`jstack` frame was real and on the hot path, but it is not the dominant contributor to the ~20µs/simplex figure
— roughly three-quarters of it comes from somewhere else. The next place to look, not yet measured this
session (a genuine follow-up, not done here — see "Scope" below): `DiameterSimplex`/`Simplex[Int]` carrying a
full `SortedSet[Int]` through `Chain.reduceBy`'s `TreeMap`-based reduction instead of Ripser's packed 64-bit
`diameter_index_t` — already flagged as a deliberate, memory-favoring deferred choice in `DiameterSimplex`'s own
doc (`RipserStream.scala`), and the most likely remaining source of a large, allocation-heavy per-simplex
constant given the observed GC pressure (Finding #2) and the fact that memoization only accounts for a quarter
of the gap.

## Scope note

Per standing project practice on this exact engine (the reverted DQP "commit anyway" fix, the premature
`BarcodeRegressionSpec` un-skip — both in CLAUDE.md), **this session characterizes the cost and stops short of
fixing it.** `RipserCohomologyContext`'s reduction path is the reference every other cross-validation in this
codebase either directly or indirectly touches; changing its representation is a deliberate, dedicated pass of
its own, not a tail-end addition to a benchmarking session. The concrete next-step candidates this session's
measurements point at, in likely-impact order: (1) a packed/lighter-weight simplex carrier through
`Chain.reduceBy`'s reduction (the `DiameterSimplex` doc's own flagged option), (2) further isolating the
remaining ~75% of the ~20µs/simplex cost (this session only ruled out the `filtrationValue`-memoization share
of it) via a proper allocation profiler rather than another jstack sample, and (3) revisiting whether
`memoizeFiltrationValue=true` should be the default for this specific access pattern (comparator-driven, not
cofacet-enumeration-driven) even if the codebase keeps memory-frugality as its default philosophy elsewhere.

## Housekeeping

- `RipserPaperBenchmarkSpec.scala` is a new, permanent, `skipAll`'d spec (see "Setup" above for how to run it).
- `RipserDiagProbe.scala` was a throwaway diagnostic (`@main`, not a spec) and has been deleted after use.
- Note for whoever resumes this: the working tree had concurrent, unrelated edits to
  `matlab/Tda4j.scala`/`matlab/PersistenceResult.scala`/`matlab/Tda4jSpec.scala`/`DimensionCeilingBenchmarkSpec.scala`
  present for this entire session, made outside it (an IntelliJ Scala compile server was running throughout) —
  not touched, not staged, not reviewed as part of this investigation.
