# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

TDA4j is a Scala 3 library for persistent homology and topological data analysis (a spiritual successor to
JavaPlex/Ripser, from the Stanford Computational Topology workgroup lineage). Single sbt module, root package
`org.appliedtopology.tda4j`, split into subpackages (see "Package layout" below). Currently pre-1.0
(`0.1.3-SNAPSHOT`), actively evolving API.

## Package layout

The codebase used to be one flat package; it's now split into subpackages under `org.appliedtopology.tda4j`,
matching this file's own section structure below. Source and test directories mirror the package names
(`src/main/scala/.../algebra/`, `src/test/scala/.../algebra/`, etc.) — file names are unchanged, only their
directory and package declaration moved, so any file path in this doc below should be read as living under its
package's subdirectory.

- `algebra` — `RingModule`, `Field`, `FiniteField`, `Chain` (including the `Cell`/`Cocell`/`OrderedCell`/
  `OrderedBasis` trait contracts), `SSetElement` (the degeneracy-word element type + `insertOuter`/`faceOf`
  operator algebra underlying finite simplicial sets — see "Simplicial sets" below). The coefficient/module
  typeclasses plus the formal-sum machinery everything else builds on.
- `cells` — `Simplex`/`SimplexOps`/`SimplexOrderedCell`, `Cubical`/`CubicalOrderedCell`, `SimplicialSet`
  (`FiniteSimplicialSet`, a third concrete `OrderedCell` instance — see "Simplicial sets" below),
  `SimplicialSetConstructions` (`product`/`coproduct`). Concrete `OrderedCell` instances.
- `streams` — `SimplexStream`, `FiniteMetricSpace`, `VietorisRips`, `Cofacets`, `RipserStream`, `CubicalStream`,
  `CubicalImage`, `SymmetryGroup`, `UnionFind` (which also defines `Kruskal` — MST/cycle-basis over a
  `FiniteMetricSpace`, not a generic utility, which is why it lives here and not in some separate `util`
  package that never ended up existing — see below), `SimplicialSetStream` (the `CellStream` adapter for
  `FiniteSimplicialSet`, plus the `fromStream` builder), `FilteredSimplicialSetStream` (a real, non-constant
  `StratifiedCellStream[G, Double]` for `FiniteSimplicialSet` — see "Simplicial sets" below). Filtration/complex
  construction.
- `homology` — `Homology` (all four persistence engines), `PackedRipserCohomology`. Note `CubicalHomologyContext`
  is defined inside `streams/CubicalStream.scala`, not here — a real, pre-existing `streams -> homology`
  dependency for that one wrapper class, not a layering violation introduced by the split.
- `barcode` — `Barcode`. Already its own package before this split (nested `package barcode` inside the file);
  only the physical file location changed, to match.
- `alpha` — `AlphaShapes`, `AlphaComplexDQP`.
- `unicode` — `PrintingHelper`. Already its own package before this split, same as `barcode`; currently unused
  (only a dead commented-out import references it).
- `matlab` — unchanged, already its own package.
- root (`org.appliedtopology.tda4j` itself) — just `package.scala` (`TDAContext`), the thin user-facing facade,
  plus `APISpec.scala`/`SimplicialSetSpec.scala` (dead) on the test side, which stay flat as cross-cutting
  integration tests rather than belonging to any one subpackage.

There is no dedicated `util` package — `UnionFind.scala` was the only real util-shaped candidate, and turned
out to be mixed-concern (see `streams` above), so the bucket was dropped rather than forced.

Every file that references a symbol from another subpackage does so via an explicit
`import org.appliedtopology.tda4j.<pkg>.{given, *}` — note the `given`: a plain `import pkg.*` does NOT bring
`given` instances into scope in Scala 3 (a real, easy-to-hit gotcha, distinct from the companion-object/opaque-
type hazards below), and this codebase's `Ordering[CellT]`/`RingModule`/`Field` instances are all `given`s. These
imports are broad wildcard imports by design (mirroring what same-package visibility already gave every file
before the split) rather than narrow per-symbol imports — full derivation of the split, including the two
Scala-3-specific gotchas hit along the way, is in `WORKLOG-package-reorg.md`.

## Commands

Build and test with sbt (Java 21, Scala 3.8.4):

```
sbt clean test                  # full test suite (what CI runs)
sbt "testOnly *SimplexSpec"     # run a single specs2 spec by class name (glob supported)
sbt "testOnly org.appliedtopology.tda4j.homology.HomologySpec"
sbt scalafmtAll                 # format the whole codebase
sbt scalafmtCheck scalafmtSbtCheck   # what CI's lint job checks (formatting only, no autofix)
sbt mimaReportBinaryIssues      # binary-compatibility check (also run in CI's test job)
sbt makeSite                    # build the Paradox docs site (src/main/paradox) — needs Graphviz for diagrams
```

There is no linter beyond scalafmt — formatting is enforced in CI (`lint.yml`) as a check, not autofix, so run
`scalafmtAll` before committing. Tests use specs2 (`org.specs2.mutable.Specification`); `ProfilingSpec` is a
benchmark-style spec that reads `-Dbitlength=`/`-DmaxFVal=`/`-DmaxDim=`-style specs2 command-line args rather than
asserting behavior — don't treat its failures like normal test failures.

CI is three independent GitHub Actions workflows on push/PR to `scala`: `test.yml` (test + mima),
`lint.yml` (scalafmt check), `docs.yml` (build+publish Paradox site to GitHub Pages, push-to-`scala` only).

## Scala style used throughout

The codebase leans heavily on Scala 3.7+'s newest context-abstraction syntax — don't "correct" this to Scala 2
style or older Scala 3 idioms:

- **`Type is TypeClass`** as the spelling for a context bound/given instance (e.g. `Chain[CellT, CoefficientT] is
  RingModule`, `Simplex[VertexT] is OrderedCell`), instead of `given TypeClass[Type]`.
- **`type Self: Ordering as ordering`** — named context-bound aliasing (`as ordering`) inside trait bodies, so the
  bound's evidence can be referred to by name without a separate `summon`.
- Custom operators/unicode for algebra: `⊠` (boxed times, scalar action), `∆(...)` (simplex literal, typed
  `Alt+J` on Mac GB layout), `<*`, `|*|` for the `RingModule`/`Field` typeclasses in `RingModule.scala` /
  `Field.scala`.
- `opaque type Simplex[VertexT] = SortedSet[VertexT]` — `Simplex` has no runtime wrapper; its API is entirely
  extension methods (see `SimplexOps.scala` for the delegated `SortedSet`-like surface, `Simplex.scala` for the
  `OrderedCell` instance).

## Architecture

### Algebraic core (typeclass layer)

- `RingModule.scala` / `Field.scala`: minimal typeclasses for "module/vector space over a ring/field" and "field",
  built via the `is` syntax above. Everything downstream (chains, homology) is generic over the coefficient
  `Field` — not hardcoded to `Double` or `Z/pZ`.
- `FiniteField.scala`: `Fp` as an opaque type per prime `p`, with a `Field` instance.
- `Chain.scala`: `Chain[CellT, CoefficientT]` is a formal sum of cells with field coefficients, backed by a mutable
  `PriorityQueue` ordered by cell (so the "leading term" — used pervasively in the reduction algorithms below — is
  always a cheap peek). Defines the generic matrix-reduction primitives (`reduceBy`, `reduceByUntil`) that the
  homology algorithms build on, plus a `RingModule` instance so chains support `+`, `-`, `⊠` directly.
- `Chain.scala` also defines the `Cell`/`Cocell`/`OrderedCell`/`OrderedBasis` traits: anything with a `boundary`
  (given a coefficient field) and a total order over instances can plug into the homology machinery — not just
  simplices. `Simplex.scala` is the (currently only) concrete `OrderedCell` instance.

### Complex construction (producing a filtered stream of cells)

- `SimplexStream.scala`: `SimplexStream`/`CellStream`/`Filtration`/`StratifiedCellStream` — the abstract interface
  a persistent-homology computation consumes: an iterator over cells in filtration order, plus a
  `filtrationValue` partial function and a `Filterable` (smallest/largest sentinel values, e.g. ±∞ for `Double`).
  `StratifiedCellStream` additionally exposes `iterateDimension` for dimension-by-dimension algorithms.
  **`iterateDimension`'s domain must be contiguous from 0** (defined for `0, 1, ..., k` for some `k`, or all of
  ℕ, never with a gap) — `.iterator`'s default implementation stops at the first dimension it's undefined for,
  so a gap silently truncates iteration rather than skipping past it. Every implementation here already
  satisfies this for a structural reason, not by convention alone: a simplicial complex can't have a
  `d`-simplex without its `(d-1)`-dimensional faces, so "no cells at `d`" implies "no cells at any dimension
  beyond `d`" too.
- `FiniteMetricSpace.scala`: abstracts "distance + finite point set", with a VP-tree (`jvptree`)-backed
  implementation for nearest-neighbor queries and a `SparseMetricSpace` wrapper that only exposes edges below a
  diameter cutoff (used to bound Vietoris–Rips construction).
- `VietorisRips.scala` / `Cofacets.scala` / `RipserStream.scala`: several independent strategies for enumerating a
  VR filtration — an explicit coface-enumeration stream (`EnumeratingCofaceSimplexStream`), a Ripser-style
  binomial-indexed stream (`RipserCofaceSimplexStream`, `SimplexIndexing`), an in-order variant
  (`InorderCofaceSimplexStream`), a recursive-stack variant (`RecursiveStackVietorisRipsSimplexStream`), a
  straightforward non-optimized reference implementation of Antonio Rieser's New-VR algorithm (arXiv:2301.07191v3,
  *A New Construction of the Vietoris-Rips Complex* — an explicit refinement of Zomorodian's own Incremental-VR;
  "Rieser" the author, not "Ripser" the software — `IncrementalVietorisRipsSimplexStream` in `SimplexStream.scala`,
  meant as a cross-validation baseline for the other, more experimental engines rather than a speed-competitive
  one), and `CofacetIterator` for lazy coboundary generation. These are alternate engines with the same output
  contract, not layers on top of each other — check `iterateDimension`/`iterator` in whichever is in play.

**`EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream`/`InorderCofaceSimplexStream`/
`IncrementalVietorisRipsSimplexStream` all now default `maxFiltrationValue` to `metricSpace.minimumEnclosingRadius`
instead of `Double.PositiveInfinity`** (`RecursiveStackVietorisRipsSimplexStream` was deliberately left alone —
see below). Past that radius every vertex is within range of some common apex, so the (unboundedly-many-dimensions)
complex is a cone from that point on and contributes no further homology (Ripser paper, p. 412) — real `ripser.cpp`
uses this exact quantity (`enclosing_radius`) as its own default threshold, routinely alongside a bounded `dim_max`,
so this is how Ripser has always worked, not a special unbounded-dimension case. `EnumeratingCofaceSimplexStream`
and its two direct subclasses had NO threshold mechanism at all before this — `maxFiltrationValue` is a genuinely
new parameter there, folded into a combined `keptByThresholdAndCriterion` predicate alongside the existing
`keepCriterion` (one place deciding "is this cell kept," not two independent filters); `RipserCohomologyContext`
and `IncrementalVietorisRipsSimplexStream` already had the parameter (used for genuine sparse-Rips truncation) and
only had their *default* changed. All five constructors use a `Double.NaN` sentinel resolved internally to
`metricSpace.minimumEnclosingRadius`, not a literal default referencing `metricSpace` directly, because Scala 3
only allows a default value to reference an earlier *parameter list*, not an earlier parameter in the same list —
confirmed directly (`class Foo(val x: Int, val y: Int = x + 1)` fails to compile with "Not found: x" under this
project's `-source:future` setting), and splitting into a curried parameter list instead was rejected because Scala
requires an explicit trailing `()` at every call site once a parameter list exists, even one where every parameter
has a default — that would have broken every existing call, not just the ones setting the threshold. Pass
`maxFiltrationValue = Double.PositiveInfinity` explicitly for the old always-unbounded behavior.

**This is a real semantic change, not a free performance win layered on unchanged output** — confirmed by a first
implementation attempt that broke ~10 existing tests across `RipserCohomologySpec`/`IncrementalVietorisRipsSpec`/
`PersistenceInChunksSpec`/`SimplexStreamSpec`, all of which had (understandably, since no other option existed
before this session) pinned specific behavior at the untruncated default — e.g. `threePointLine` (3 colinear points
at distances 1, 2, 3) has `minimumEnclosingRadius = 2.0` from its middle point, less than its own longest edge
(3.0), so a `maxDim = 1` computation (no triangle available to kill anything) genuinely loses the essential H¹ bar
that edge would otherwise contribute — this is Ripser's actual, intended behavior (a real bar dropped, not a
truncation artifact), not a bug, but every test that wanted the old always-unbounded semantics needed
`maxFiltrationValue = Double.PositiveInfinity` added explicitly. **The load-bearing verification, present now in
both `RipserCohomologySpec` and `IncrementalVietorisRipsSpec`**: the default-thresholded barcode is exactly the
untruncated barcode restricted to `[0, minimumEnclosingRadius]` — the identical `restrictToThreshold` oracle every
other explicit threshold value already had to satisfy, confirmed over 200 random Vietoris-Rips point clouds each,
not just reasoned through. Full derivation, including the advisor exchange that corrected an initial "this looks
unsafe, revert it" instinct once the restriction-oracle check actually ran, is in `WORKLOG-mst-and-perf.md`.

`RecursiveStackVietorisRipsSimplexStream` (`VietorisRips.scala`) was deliberately NOT given this treatment in the
same pass — it has its own `edges`/coface-enumeration logic independent of `EnumeratingCofaceSimplexStream`'s, is
explicitly documented as a cross-validation baseline rather than a speed-competitive engine, and extending it
needs its own dedicated pass rather than being folded into this one. `AlphaShapeDQP`/`HelixDelaunay` were also
NOT touched — alpha shapes and Ripser reproduction remain different sections of the library with minimal
interaction (an explicit, standing call from the project lead), and `minimumEnclosingRadius` in Euclidean-distance
terms is not obviously the right quantity for a circumradius-based filtration in the first place.

**`StratifiedCellStream.iterator`'s default implementation used to hang or crash for every coface-style stream,
fixed**: it was `Iterator.from(0).filter(iterateDimension.isDefinedAt).map(...).fold(Iterator.empty)((x,y) => x ++
y)` — two compounding bugs. `Iterator.filter` on an infinite source can never prove "no more matches ahead", so
once past the last dimension `iterateDimension` is defined for, it spins forever searching for a `d` that will
never come; `.fold` compounds this by being a strict terminal op that can't yield anything until the
(already-hanging) source is exhausted. Worse: for a guard shaped like `d <= someBound` (true for negative `d`
too, which `LimitedCofaceSimplexStream`'s `case d: Int if d <= maxDim` was), `Int` silently wrapping from
`Int.MaxValue` to `Int.MinValue` after ~2^31 iterations makes the guard spuriously true again, so instead of
hanging forever the loop can eventually resume and feed a huge negative `d` straight to the wrapped stream's
`iterateDimension` — surfaced as `BinomialCoefficient` throwing `CombinatoricsException: Number -2147483647 is
out of range [0, n]`, not as an obvious hang, which is what `APISpec`'s "A full persistent homology computation"
test did after ~1m20s before this fix. `HomologyFixtures.flattenToCellStream` (a test-only helper that
pre-flattened `iterateDimension` into a finite `Vector` up front) and a matching workaround in
`PersistenceInChunksContext` (`Homology.scala`) both existed specifically to route around this — `flattenToCellStream`
is gone now, its callers pass their coface streams straight to `persistentHomology` (exactly what `APISpec` already
did, which is what exposed the bug), and `PersistenceInChunksContext`'s explicit-walk is kept only because it
separately enforces a caller-supplied `maxDim` that can be tighter than a stream's own natural bound, not because
`.iterator` is unsafe anymore.

Fixed by replacing the whole thing with `Iterator.from(0).takeWhile(iterateDimension.isDefinedAt).flatMap
(iterateDimension)` — `.takeWhile` stops at the first `d` the domain doesn't cover and never asks about any `d`
beyond it, which is exactly the contiguous-domain contract documented on `iterateDimension` above. This alone
fixes any stream whose domain is already properly bounded (`LimitedCofaceSimplexStream`,
`IncrementalVietorisRipsSimplexStream`), but `EnumeratingCofaceSimplexStream`, `RipserCofaceSimplexStream`,
`InorderCofaceSimplexStream`, and `RecursiveStackVietorisRipsSimplexStream` all used to declare their top
dimension via a catch-all `case d => ...` with no upper bound at all (`isDefinedAt` always `true`) — genuinely
unbounded, so `.takeWhile` alone wouldn't have terminated on any of them either. Fixed at the source too: each
now guards its catch-all case with `d < metricSpace.size` (a real bound, not a defensive one — a `d`-simplex
needs `d + 1` distinct vertices, and `BinomialCoefficient.value(metricSpace.size, d + 1)` throws outright past
that point anyway) and `LimitedCofaceSimplexStream`'s own guard picked up an explicit `d >= 0` alongside its
existing `d <= maxDim`, closing the wraparound path at the source rather than relying on `.takeWhile` alone to
never reach it. `HelixDelaunay` (`AlphaShapes.scala`) was already safe under the old buggy `.iterator` *or* the
new one — its `iterateDimension` domain (`simplicesSortedMap.contains(d)`) is contiguous by construction
(`Map.from((0 to ambientDimension).map(...))`), so it satisfies the contract without needing this fix at all.
**`AlphaShapeDQP`'s `iterateDimension` (`AlphaComplexDQP.scala`) was left alone in that same session, then fixed in
a later one** once `EngineComparisonBenchmarkSpec` (below) became a real caller of `.iterator()` on it. It used to
be `alphaComplexDQP.cellsOfDimension(_).iterator`, an eta-expanded total function assigned to a `PartialFunction`,
which makes `isDefinedAt` always `true` — the same unbounded shape the coface streams had. Fixed by bounding it to
`k >= 0 && k < alphaComplexDQP.sizeByDimension.length` (the public mirror of the private `cellsByDim.length`,
`== maxDimension + 1`) — this changes nothing for any `k` already in range, since `cellsOfDimension`'s own internal
`k >= cellsByDim.length` check already silently returned empty there; it only turns the never-reached tail from
"silently empty forever" into "undefined," matching every other stream. Confirmed safe against the exact concern
that held this back the first time (`AlphaComplexSpec`/`AlphaValidationSpec`/`AlphaCrossValidationSpec` calling
`iterateDimension(d)` directly up to the point cloud's own ambient dimension, and the degeneracy-hazard note above
meaning a cosphericity-driven simplex can legitimately exceed ambient dimension): `maxDimension` is set to the
ambient dimension at construction, so `cellsByDim.length = ambientDimension + 1` and every existing call site's `d`
is already `< cellsByDim.length` by construction — verified by running all four alpha specs after the change, not
just reasoned through.
- `AlphaShapes.scala` / `AlphaComplexDQP.scala`: alpha complex construction — see "Alpha complex: DQP vs Helix"
  below for full context, including known, accepted limitations in both backends.
  `Alpha(points, dispatch)` chooses a backend: `"helix"` (`HelixDelaunay`, an actual Delaunay triangulation) or
  `"DQP"` (`AlphaShapeDQP`/`AlphaComplexDQP.scala`) — a from-scratch dual active-set QP method that never builds
  the Delaunay complex at all, instead answering per-simplex feasibility queries via a Cholesky-updated active-set
  QP. As of now `dispatch = "default"` always resolves to `"helix"` regardless of point-cloud shape — `"DQP"` must
  be requested explicitly. An earlier Miniball-based Delaunay backend was ripped out entirely as broken (see git
  history); don't resurrect it without checking why.
- `SymmetryGroup.scala`: for complexes with a known vertex symmetry group (e.g. `HyperCubeSymmetry`), lets
  construction/computation work on canonical orbit representatives only.

### Cross-engine benchmark, and a bug it found on first run

`EngineComparisonBenchmarkSpec.scala` times every (complex construction x homology engine) pairing this codebase
actually supports — the 5 VR streams, both alpha backends, `SimplicialHomologyContext` and
`PersistenceInChunksContext` as decomposable (stream, engine) pairs, plus `RipserCohomologyContext` as its own
bundled row (it takes a `FiniteMetricSpace[Int]` directly, not a stream, and can't touch alpha complexes at all)
— across a sweep of point count, ambient dimension, and max homology dimension. Follows the established
`ProfilingSpec`/`ApparentPairsBenchmarkSpec`/`SparseRipsBenchmarkSpec` convention (`Arguments`-driven config,
median-of-trials, printed table, small CI-safe defaults); see its own doc comment for the full rationale,
including why construction and reduction are timed as separate phases and why alpha/VR bar counts are never
compared against each other (different quantities — circumradius vs. diameter). Each cell runs under a
per-cell timeout on a daemon-thread executor (no engine here supports cooperative cancellation), so a stall
prints `"timeout"` rather than hanging the run — `PersistenceInChunksContext` x alpha specifically is a known
stall/scale risk: `HomologySpec.scala`'s `BarcodeRegressionSpec` is `skipAll`'d, "currently stalls out," for
exactly that combination, predating this benchmark.

**A first pass this session tried un-skipping `BarcodeRegressionSpec`, on the strength of one fast run — wrong,
and worth recording as a caught mistake, not quietly fixed**: a single `sbt testOnly` invocation happened to
sample a small point cloud from the spec's own `matrixGen(..., Gen.chooseNum(2, 10), Gen.chooseNum(25, 150))`
generator (no fixed seed) and finished in under 20 seconds, which was wrongly generalized to "the stall is
fixed." A later run — same un-skipped test, different random sample — hit `OutOfMemoryError` after nearly 3
minutes with up to 515% GC time (multiple threads all in GC simultaneously) on a 1GB heap, and left the JVM
degraded enough to cascade into an unrelated spec's failure later in the same `sbt test` run. Re-skipped.
**Measured, not inferred, before re-closing this**: instrumented `PersistenceInChunksContext.advanceAll` to
print `R`'s total and max per-chain term count after each dimension's global step on a 40-point, ambient-
dimension-4 `AlphaShapeDQP` complex (well inside this spec's own generator range) — the per-chain maximum
stayed at 8-10 terms throughout, no growth pattern consistent with this session's `compress`/`globalReduce`
fix compounding chain sizes across the sweep. The actual cause: that same 40-point/dimension-4 input alone
produced **102,090 simplices** from `AlphaShapeDQP`'s always-untruncated construction (see "Alpha complex: DQP
vs Helix" below) — this spec's generator range (dimension up to 10, up to 150 points) can produce alpha
complexes far larger still. This is a pre-existing combinatorial-scale limitation of `PersistenceInChunksContext`
on complexes this size (the exact "stalls out... speed issues" the skip already documented, likely long
predating this session), not a regression from anything fixed here — but that's a measured conclusion from one
diagnostic run, not an exhaustive proof, and un-skipping this again without first fixing or bounding the scale
problem would repeat the same mistake.

**First run immediately found a real, previously-unknown reduction bug**, not a benchmark artifact: at
`maxDim >= 2`, `SimplicialHomologyContext` (the "Naive" engine) threw `IllegalStateException: reduction pivot
... was not a recorded open class` for exactly three constructions -- `RecursiveStackVietorisRipsSimplexStream`,
`HelixDelaunay`, and `AlphaShapeDQP` -- while every other construction, and `PersistenceInChunksContext` on these
same three, ran clean. **Now fixed, in two parts, both required:**

1. **Direction.** All three defined `filtrationOrdering` as a plain ascending `Ordering.by(filtrationValue)`
   (`VietorisRips.scala`, `AlphaShapes.scala`, and `AlphaComplexDQP.scala`'s `FilteredSimplexOrdering[Int,
   Double](this)` called without supplying its own `filtrationOrdering` using-parameter, silently defaulting to
   ascending `Ordering[Double]`) -- never reversed. This violates the exact convention
   `EnumeratingCofaceSimplexStream.filtrationOrdering` documents (see the "Bug found while cross-validating (4)
   against (1)" section below): `CellularHomologyContext.HomologyState` bakes `stream.filtrationOrdering`
   directly into `Chain`'s pivot-selection machinery, which requires "smaller under this ordering" to mean
   "younger," not "older." Fixed by reversing the primary key only (`Ordering.by(filtrationValue).reverse.orElse(tiebreak)`,
   matching `EnumeratingCofaceSimplexStream`'s own pattern) in all three. This alone made the crash go away.
2. **Tie-break consistency.** Fixing (1) alone left a *second*, distinct bug exposed rather than fixed: each
   stream's `iterateDimension` bucket order didn't match `filtrationOrdering.reverse` on cells that tie exactly
   -- `RecursiveStackVietorisRipsSimplexStream`'s dim-1 `edges` sorted ascending+ascending-tiebreak (not
   ascending+descending, what `filtrationOrdering.reverse` actually is once the primary key alone is reversed),
   its dim>=2 DFS walk had no sort at all (order came from `SortedSet[Int]` neighbor traversal, i.e. ascending
   vertex id, unrelated to filtration order); `HelixDelaunay`'s `simplicesSortedMap` used bare
   `sortBy(filtrationValue)`, no explicit tie-break at all; `AlphaShapeDQP`'s `byDim(k).sortInPlaceBy` tie-broke
   on `c.show` (a string), not `simplexOrdering[Int]`. This is the *same bug class* the "Bug found while
   cross-validating" section documents for `EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream`/
   `InorderCofaceSimplexStream`, just not yet applied to these three. No crash resulted (the pivot-table
   `IllegalStateException` only fires on values, not orders among ties), so it surfaced instead as
   `SimplicialHomologyContext` producing a *different, still self-consistent* barcode than before -- caught only
   by adding real regression tests (`VietorisRipsSpec`'s new test, `AlphaFiltrationOrderingRegressionSpec`) that
   compared against an independent oracle rather than just checking "does it still crash." Fixed the same way as
   the original fix: every ad hoc/independent bucket sort replaced with `.sorted(using filtrationOrdering.reverse)`
   -- the same `Ordering` object, not a separately reconstructed comparator (`AlphaShapeDQP`'s fix builds an
   explicit `Ordering.by(weight).orElse(simplexOrdering[Int].reverse)` at the point in `AlphaComplexDQP.scala`'s
   `compute()` where `filtrationOrdering` itself isn't yet constructible, but is written to match it exactly).

**Verified**, not just asserted: `SimplicialHomologyContext` on the now-fixed `RecursiveStackVietorisRipsSimplexStream`
matches `SimplicialHomologyContext` on `EnumeratingCofaceSimplexStream` (an independently cross-validated stream)
*exactly*, cell-for-cell, on every generated point cloud tried -- the strongest evidence available that this
stream's ordering is now fully correct, not merely "passes its own structural invariant." No independent oracle
stream exists for alpha complexes (`RipserCohomologyContext` can't consume one), so `HelixDelaunay`/`AlphaShapeDQP`
are verified only via `SimplicialHomologyContext`'s own structural invariant (`totalBarsAccountForAllCells`:
every cell opens or closes exactly one bar) holding on every trial, which is weaker but was never even close before
this fix.

**A second, separate bug was found in the process, and is now fixed too**: `PersistenceInChunksContext` itself --
independent of anything above -- failed its own `totalBarsAccountForAllCells` structural invariant (and disagreed
with the now-verified-correct `SimplicialHomologyContext`) on all three of these streams, specifically when
`maxDim` was bounded and there were multiple/tied essential classes at the top retained dimension: it dropped one
or more genuine essential bars (e.g. `(2, x, Infinity)`) and reported a spurious extra finite bar instead (e.g. an
extra `(1, y, x)`), which is topologically impossible for a bounded complex whose top-dimension cells were never
paired against anything higher. This is *not* the same bug as the direction/tie-break issue above: it reproduced
even when `SimplicialHomologyContext` was fully cross-validated and self-consistent (confirmed via a hand-verified
minimal repro -- 4 coincident points bounded at `maxDim=2`, the full 2-skeleton of a tetrahedron, i.e. the
boundary of the 3-simplex, topologically S², whose correct barcode is verifiable by hand: one essential H₀ class,
one essential H₂ class), and reproduced even on `EnumeratingCofaceSimplexStream`, the well-established stream --
so it isn't a stream-ordering issue at all, and existing `PersistenceInChunksSpec` coverage never exercised the
multiple-tied-essential-classes-at-a-bounded-maxDim case that triggers it.

Root cause, two distinct defects in `PersistenceInChunksContext.HomologyState` (`Homology.scala`), both now
fixed: (1) `compress` (Algorithm 4) took a static snapshot of `Rk.items` before its elimination loop started,
then mutated `Rk` inside the loop body -- any term newly introduced by a substitution (compressing away one
cleared entry can pull in an unrelated *paired* cell never in the original snapshot) was silently never itself
eliminated, letting an already-paired cell (which must never become anyone's pivot) survive as `Rk`'s final
leading term and corrupt the cleared/paired invariant. Fixed by rewriting `compress` on top of
`Chain.reduceByUntil`'s existing fixpoint loop (the same primitive `processCell`/`globalReduce` already use)
instead of a hand-rolled single pass. (2) Fixing that alone wasn't sufficient: `globalReduce`'s own separate
reduction (against `boundaries`, cleared pivots only) could *also* expose a newly-introduced paired cell
partway through, and `globalReduce` had no elimination rule for paired cells at all -- only `compress` did, and
by then `compress` had already finished running for that cell. Fixed by extracting the elimination rule into one
shared `eliminationFallback` method and passing it to both `compress` and `globalReduce`. Also fixed in passing,
defensibly if not proven load-bearing for the specific repro: `markActiveEntries`'s row scan used
`takeWhile(_ => !isActive)`, stopping at the first row that makes a column active and leaving every later row in
the same chain unclassified in `activeRows` even though `compress`/`eliminationFallback` look each one up
independently later -- changed to a full scan. Full derivation, including the hand-traced repro's exact
call-by-call sequence, in `WORKLOG-benchmark-and-chunks-bug.md`.

Verified against `PersistenceInChunksContext` directly, not merely against agreement with `SimplicialHomologyContext`
(agreement was exactly what this bug defeated for a while, so it can't be the only oracle): the S² repro above is
pinned as `PersistenceInChunksSpec`'s own `HomologyFixtures.tetrahedronBoundaryDegenerateCells` fixture, alongside
new `PersistenceInChunksSpec` cases reusing `RipserCohomologySpec`'s hand-verified 3-cycle-graph and
filled-triangle fixtures and `HomologyFixtures.elderRuleExpected`, all checked against a hand-derived barcode
directly. Full `sbt test`: 146 total, 143 passed, 0 failed, 3 skipped, 1 pending (`HomologySpec.scala`'s
`BarcodeRegressionSpec` stays `skipAll`'d, accounting for the 3 skips -- see the
cross-engine benchmark section above for why un-skipping it turned out to be premature) -- this touched shared
machinery (`Chain.reduceByUntil`'s calling convention at two call sites; every stream's own bucket order), so a
clean full run mattered more here than for a narrowly-scoped change.

### Persistent homology (consuming a stream)

`Homology.scala` contains **four independently-implemented** persistence algorithms sharing the `Chain` reduction
primitives but with different tradeoffs — they are not variants of one shared engine, so a fix in one does not
imply the others need it:

1. `CellularHomologyContext` / `SimplicialHomologyContext`: the naive single-pivot-table boundary-reduction
   algorithm (no clearing, no chunking, no cohomology/twist optimization) — the reference-grade baseline the
   other two algorithms below get cross-validated against. Incremental (`advanceOne`/`advanceTo`/`advanceAll`) so
   you can query `diagramAt`/`barcodeAt` a filtration value without finishing the whole stream; `barcodeAt`
   annotates each `PersistenceBar` with an actual representative cycle, reconstructed via a V-column alongside the
   ordinary boundary-matrix reduction (see the class doc in `Homology.scala` for the derivation). **Rewritten from
   a "twist"-with-cohomology-bookkeeping variant that was silently wrong**: the old code summoned its
   `Chain[CellT,CoefficientT] is RingModule` instance at `CellularHomologyContext` class scope, before any stream
   (hence before the stream-specific filtration `Ordering[CellT]`) existed, so it permanently captured the
   generic, filtration-blind `OrderedCell`-derived ordering instead — chain arithmetic silently pivoted on
   lexicographic vertex order rather than filtration order whenever a multi-term chain got built through it. Fixed
   by summoning `chainRM` inside `HomologyState` (where the correct per-stream ordering is in scope); the general
   lesson — a generic `given` resolves its own implicit parameters once, at construction, not per later call to its
   methods — applies to any future `chainRM`-style pattern in this codebase (see WORKLOG-naive-homology.md for the
   confirmed repro). **Audited against `PersistenceInChunksContext` and `SimplicialHomologyByDimensionContext`**
   (`WORKLOG-cohomology.md`'s "a different ordering bug" section has the full audit): `PersistenceInChunksContext`
   also summons `chainRM` at class scope but is confirmed correct anyway — every pivot-relevant reduction goes
   through `Chain.reduceByUntil`, a `def` that resolves `Ordering[CellT]` fresh per call site, not through the
   stale `chainRM` closure — verified empirically against the same elder-rule discriminator fixture used below, not
   just reasoned through (the capture mechanism is subtle enough that a read-through alone isn't trustworthy here).
   `SimplicialHomologyByDimensionContext` was **not** auditable the same way: its `HomologyState` constructor
   throws `NoSuchElementException` unconditionally for any complex with an MST edge (an unrelated, pre-existing
   `barcode(0)`-read-before-init bug, `Homology.scala:427`/`490`), so it has never successfully run and the
   ordering question is moot until that's fixed — see item 3 below. `package.scala`'s `TDAContext` was checked too
   and left alone: its class-scope `chainIsRingModule` is exported purely for user-facing chain-arithmetic
   convenience (`+`/`⊠`/an implicit `Simplex -> Chain` conversion), never consumed by any engine's own reduction
   path, so a stale ordering there affects only how a user's own hand-built chain arithmetic displays/collides,
   not any persistence computation. **Phase 2 (persistent cohomology + clearing + apparent pairs) is mostly done**:
   persistent cohomology and clearing are implemented as `RipserCohomologyContext` (item 4 below); apparent pairs is
   implemented too, but only partially — see item 4's own note for exactly what's landed vs. what's still open (the
   full lazy optimization). `WORKLOG-naive-homology.md`'s "Phase 2 plan" section has the original plan;
   `WORKLOG-cohomology.md` has what actually happened, including a correction to that plan (clearing turned out to be
   required for correctness, not a later-stage optimization) and the apparent-pairs resolution (its "Apparent pairs:
   resolved" section, after the earlier "negative result" section that stays as historical record) — read the latter
   before continuing phase 2 work.
2. `PersistenceInChunksContext`: the parallelizable "clear-and-compress" chunked algorithm (local reduction per
   chunk, then global column compression/reduction), for larger complexes where cross-chunk work can be batched.
   **`maxDim` means "top homological degree reported," not "top simplex dimension built" -- fixed at the source,
   same fix and same reason as `RipserCohomologyContext`'s own `maxDimension` below (see
   `.claude/WORKLOG-maxdim-semantics-fix.md`)**: this is homology, so the mirror-image fact holds -- a class
   BORN at dimension `maxDim` can only be correctly resolved as finite-or-essential by considering real
   `(maxDim + 1)`-dimensional cells' own boundaries (a `(maxDim+1)`-simplex's boundary reduces to a
   dimension-`maxDim` pivot exactly when it kills that class). Before the fix, `allCells` and both `advanceAll`
   loops walked only `0.to(maxDim)`, so no cell that could possibly kill a `maxDim`-born class was ever
   considered -- every such class came out essential regardless of whether it actually was. Fixed by walking
   `0.to(maxDim + 1)` internally (a private `internalMaxDim`) and filtering `diagramAt`'s essential-bar output
   back down to `sigma.dim <= maxDim` (finite bars need no equivalent filter: `recordPair`'s `barDim =
   pivot.dim` is always `<= maxDim` already, since a pivot is one dimension below its killer). Any external
   caller previously passing `maxDim + 1` and filtering out `dim == maxDim + 1` bars itself (the MATLAB facade
   did, for `engine="chunks"`) should now pass the real requested degree directly.

   **Genericized in a later session, to `CellularPersistenceInChunksContext[CellT: OrderedCell, CoefficientT:
   Field]`, with `PersistenceInChunksContext[VertexT: Ordering, CoefficientT: Field]` now a one-line `Simplex`-
   specific subclass** — mirroring `SimplicialHomologyContext`'s relationship to `CellularHomologyContext`
   exactly. This class had zero actual `Simplex`-specific behavior anywhere in its body (every operation went
   through the generic `OrderedCell` interface), so the change was a pure type-annotation rename, not a
   behavior change — confirmed by every one of the ~9 existing `PersistenceInChunksContext[Int, Double]` call
   sites continuing to work unchanged (the subclass, not a rename), and a clean full-suite run. See "Simplicial
   sets" below for what this unlocked and `.claude/WORKLOG-simplicial-set-filtration.md` for the full
   derivation.
3. `SimplicialHomologyByDimensionContext`: dimension-0 and the births of dimension-1 classes read off directly via
   Kruskal's algorithm/union-find over the stream's own dimension-0/1 cells (elder rule: a tree edge kills the
   younger of the two components it joins; a non-tree edge births a new 1-cycle), higher dimensions via the same
   reduction approach as (1) but processed strictly dimension by dimension. **Fixed and cross-validated this
   session** (`WORKLOG-mst-and-perf.md` has the full account) — it was previously non-functional, and turned out
   to have five distinct bugs, not the two originally suspected: (i) an unguarded `barcode` map read
   (`NoSuchElementException` on any complex with an MST edge), (ii) a missing `given Ordering[Simplex[VertexT]] =
   stream.filtrationOrdering` (chain arithmetic silently falling back to lexicographic order), (iii) the
   dimension-0/1 setup itself took an edge's own two raw endpoints as "the vertex that dies" instead of reducing
   against `boundaries` first, which breaks as soon as a cascade touches an already-killed vertex twice in the
   same pass -- fixed by dropping `Kruskal`'s own eager, complete-graph-assuming `mstIterator`/`cyclesIterator`
   entirely and instead reducing every dimension-1 cell (in the stream's own already-correct filtration order)
   through the exact same `Chain.reduceBy` primitive `advanceOne` uses for every other dimension, (iv)
   `advanceTo`'s loop condition gated on `currentIterator.hasNext`, which starts `false` (`Iterator.empty.buffered`)
   and is only ever refilled by a call this same condition was blocking -- no dimension >= 2 cell was ever
   processed before this fix, for any input, (v) finite bars above dimension 0 were filed under the *killing*
   cell's own dimension instead of the *killed class*'s dimension (one lower). `SimplicialHomologyByDimensionSpec`
   is this class's first-ever regression suite: hand-verified fixtures (including one fully-degenerate,
   all-cells-tied-at-one-value case), the bars-account-for-cells structural invariant, and cross-validation
   against `SimplicialHomologyContext` on 100+ random Vietoris-Rips point clouds, all passing.
   **Deliberately not (yet) wired into `CellularHomologyContext`/`PersistenceInChunksContext` as an actual
   performance fast path** -- see `WORKLOG-mst-and-perf.md`'s "Decision" section: the `Chain.reduceBy`-based
   version validated here is provably correct but pays the same `filtrationOrdering` comparator cost as general
   reduction, so it isn't obviously faster; a genuine speedup would need raw `UnionFind` instead, which needs its
   own dedicated validation before touching the reference oracle every other engine is checked against.
4. `RipserCohomologyContext`: persistent *co*homology via Ulrich Bauer's Ripser algorithm
   (arXiv:1908.02518), specialized to `Simplex[Int]` Vietoris-Rips/clique complexes via `SimplexIndexing`'s
   combinatorial number system (a deliberate narrowing from (1)'s generic `CellT: OrderedCell`, agreed with the
   project lead) and one-shot (no incremental `advanceTo`-style querying, also agreed scope). Cross-validated
   against (1) — see `WORKLOG-cohomology.md` for the full pivot-orientation/birth-death-dimension derivation
   (re-derived directly from the paper, not from memory) and the validation strategy.
   **`maxDimension` means "top simplex dimension built," not "top homological degree reported" — a real API
   footgun for any direct caller, confirmed while building a same-hardware benchmark against real
   `ripser.cpp`** (`WORKLOG-ripser-comparison.md`): `coboundaryOf(sigma)` is empty by construction at
   `sigma.dim == maxDimension` (`Homology.scala`), so every simplex at the requested top dimension comes out
   essential regardless of whether it actually is — the same "H_k needs (k+1)-chains" truncation artifact
   already fixed for the MATLAB facade (`Tda4j.computeFromPoints`/`computeFromDistanceMatrix`: "build to
   `maxDimension + 1` internally, report only `dim <= maxDimension`"), just never applied to a *direct* caller
   of this class before. `RipserCohomologySpec`'s own cross-validation never caught this because its oracle
   (`LimitedCofaceSimplexStream(stream, maxDim)`) truncates simplices at the same `maxDim` too — both sides
   consistently truncated the same way, so agreement between them never implied "maxDim means top *reported*
   degree." Any caller wanting correct dimension-`k` bars must construct with `maxDimension = k + 1` and drop
   the reported `dim == k + 1` bars itself; this class does not do that internally.
   **Clearing is included and is load-bearing for correctness, not an optional speedup layered on an
   already-correct baseline** — an early draft without it passed every hand-built fixture but reported spurious
   essential cohomology classes on real inputs (confirmed by hand-deriving H¹ of a plain 3-cycle graph: 3
   reported vs. the correct 1), because Proposition 3.1's essential-index definition requires excluding any
   simplex already claimed as a pivot one dimension down, not just checking its own column reduces to zero. See
   `WORKLOG-cohomology.md`'s "clearing is required for correctness" section before touching this. **Apparent pairs
   (Ripser's other major optimization, Definition 3.2/Proposition 3.9) is implemented, including the on-the-fly
   lazy substitution** — `zeroPivotCofacet`/`zeroPivotFacet`/`zeroApparentCofacet`/`zeroApparentFacet`
   (reimplemented from scratch against `SimplexIndexing`'s full unrestricted iterators, confirmed term-for-term
   against Ripser's own `get_zero_pivot_facet`/`get_zero_pivot_cofacet`/`get_zero_apparent_facet`/
   `get_zero_apparent_cofacet` in `ripser.cpp`, NOT reusing `RipserStreamBase`'s `zero*` helpers or
   `Cofacets.scala`'s `apparentVertex` — both flagged as using a restricted, false-negative-prone cofacet
   iterator) gate `persistentCohomology`'s main loop: when a genuine mutual apparent pair `(sigma, tau)` is found,
   `coboundaryOf(sigma)` is NOT computed at all and `basis(tau)` is never written — only `generators(tau)` (a
   trivial single-term V-column) and the bar itself. If some OTHER column's own reduction later reaches `tau` as
   an unresolved pivot, `Chain.reduceBy`'s new `fallback` parameter (`Chain.scala`) recomputes
   `coboundaryOf(zeroApparentFacet(tau).get)` fresh, right there, and folds it in — this is Ripser's own
   `compute_pairs` on-the-fly substitution, confirmed against `ripser.cpp` directly to do no caching anywhere
   (recomputes every time a pivot is hit, even by a different column later), so this codebase deliberately
   doesn't cache it either. Correctness of recomputing via `zeroApparentFacet` rather than consulting a map
   built up during the sweep: the mutual apparent-pair condition is a pure fact about filtration values and
   combinatorial indices, so it doesn't depend on *when* or *how* `tau` was reached — unlike a map keyed by
   "who claimed this pair first," which would need its own soundness argument for the mid-cascade case (see
   `zeroApparentFacet`'s doc). See `WORKLOG-cohomology.md`'s "Apparent pairs: resolved" section for the
   apparent-pairs derivation itself, the Ripser source line citations for the mutual-pair check, and a real bug
   caught along the way (storing only `{tau -> sign}` in `basis(tau)` instead of sigma's complete coboundary —
   passed every hand-built fixture, broke on a fuzzed 12-point counterexample, now a pinned regression test in
   `RipserCohomologySpec`, and the reason this engine recomputes the FULL coboundary on substitution too, not
   a shortcut of it).

   **Sparse Rips (`maxFiltrationValue`, default `metricSpace.minimumEnclosingRadius` as of a later session —
   see below) and optional memoization (`memoizeFiltrationValue`, default `false`) are both implemented** — a
   second, later session, deliberately
   NOT matching `AlphaShapeDQP`'s always-untruncated convention ("alpha shapes and Ripser reproduction are
   different sections of the library with minimal interaction," an explicit call from the project lead).
   `filtrationValue` is memoized only when `memoizeFiltrationValue = true` (a `mutable.HashMap` wrapper,
   unchanged from the first attempt at this); **it defaults to `false`, on an explicit correction from the
   project lead**: Ripser's own historical design goal was memory frugality (the classic bottleneck for
   persistent homology implementations), not raw speed — a global cache of every filtration value ever
   touched runs directly against that on large complexes, so it isn't the default. The actual replacement is
   `insertionDiameter`, an O(d) incremental cofacet-diameter formula (`max(parent's own diameter, max distance
   from the parent's vertices to the newly inserted vertex)`, Ripser's own `simplex_coboundary_enumerator`
   recurrence, confirmed against `ripser.cpp` directly) threaded through a new `DiameterSimplex` carrier —
   this ELIMINATES `MaximumDistanceFiltrationValue`'s O(d²) full-pairwise-recompute for cofacet enumeration
   entirely, rather than paying for it once and caching the answer, which is strictly better on every axis
   that matters here (no growing memory footprint, no hashing, no first-computation cost to amortize).
   `persistentCohomology`'s per-dimension candidate list is now assembled incrementally dimension-by-dimension
   (`sparseCofacets`, the "insert a vertex strictly above the simplex's own maximum" canonical-facet
   convention `SimplexIndexing.topCofacetIterator` already uses) instead of `(0 until binomial(n, d+1))`
   direct indexing — critically, assembled from EVERY dimension-d simplex, cleared/apparent-paired ones
   included, not just the ones independently reduced (confirmed from `ripser.cpp`'s own
   `assemble_columns_to_reduce`: its `next_simplices.push_back(...)` runs unconditionally, BEFORE the
   exclusion checks that shrink `columns_to_reduce` — clearing controls what gets independently reduced,
   never what's a valid source for the next dimension's cofacets). `coboundaryOf`/`zeroPivotCofacet` also use
   `insertionDiameter` instead of `filtrationValue(tau)` per candidate; `zeroPivotFacet` does NOT (removing a
   vertex has no equally cheap incremental formula — a real, named scope boundary, not an oversight).
   Emergent pairs (Definition 3.11, Ripser's OTHER apparent-pairs fast path) is deliberately NOT implemented —
   its guard interacts with `basis` occupancy and apparent-pair exclusion in a way two prior designs in this
   file's history already died on, so it's pure speed layered on a now-verified base, left for later rather
   than risked in the same pass. `totalSimplexCount` (a new public accessor, reset per `persistentCohomology()`
   call) replaces `Σ binomial(n, d+1)` for the bars-account-for-cells structural invariant once a threshold is
   in play — the binomial formula assumes every combinatorially-possible subset exists, true only at
   `maxFiltrationValue = +Infinity`.

   **Correctness oracle for the threshold**: NOT thresholding the naive engine's own input stream (a real trap
   — `SparseMetricSpace.distance` returns `+Infinity` past its cutoff rather than excluding the simplex, so
   `EnumeratingCofaceSimplexStream` over it would emit simplices at `fv = +Infinity` the sparse engine excludes
   outright, for reasons that have nothing to do with a bug). Instead: a thresholded Vietoris-Rips filtration's
   persistence is EXACTLY the untruncated filtration's own persistence restricted to `[0, t]` (a bar born after
   `t` never existed at all; a bar straddling `t` truncates to essential at `t`) — both sides come from the
   SAME engine, so tie-breaks agree and the comparison covers the full bar list, zero-length bars included, on
   200 random Vietoris-Rips clouds (`RipserCohomologySpec`). Also pinned: `maxFiltrationValue = +Infinity`
   (explicit or default) reproduces the pre-threshold engine's output bit-for-bit on every existing fixture,
   and `memoizeFiltrationValue` toggled true/false changes nothing about the computed barcode (200 random
   clouds).

   **Measured performance, both real and honestly attributed** (`SparseRipsBenchmarkSpec`,
   `ApparentPairsBenchmarkSpec` with `-Dmemoize`): a threshold scaled as `~2.5/sqrt(n)` (keeping the expected
   neighborhood size roughly constant as `n` grows, rather than the complex getting denser) measured 3.85x–
   15.1x wall-clock speedup at n=40–80, GROWING with n as expected for an untruncated complex's `O(n^{d+1})`
   growth — but the same runs' `totalSimplexCount` shows the sparse/dense SIZE ratio (10.5x–32.5x) exceeds the
   time ratio at every n, meaning the speedup is dominated by, and actually runs somewhat below, the reduction
   in complex size — not an artifact of extra per-simplex overhead in the new enumeration mechanism, which is
   the honest framing, not "the threshold makes the algorithm smarter." Separately, `memoizeFiltrationValue
   = false`'s own cost (previously unmeasured — flagged and fixed within the same session after a second
   advisor pass) is a real but modest 5%–25% slowdown on the dense (untruncated) path at n=40–80, shrinking as
   n grows — a defensible tradeoff for the stated memory-frugality goal, not a silent regression.

   **Same-hardware comparison against real `ripser.cpp` on the Ripser paper's own Table 1 data sets**
   (`RipserPaperBenchmarkSpec`, `skipAll`'d like the other benchmark specs — see its own doc for exact data-set
   URLs and invocation) found a large but roughly FLAT per-simplex constant-factor tax, not a growing
   algorithmic divergence: ~20µs/simplex in `RipserCohomologyContext`, confirmed flat to within ~11% across an
   18x range of complex sizes (`totalSimplexCount`) — real ripser's own per-simplex cost wasn't directly
   measured (it prints no simplex count), but it solves Table-2-scale complexes in 1-31s flat on the same
   machine, bounding it well below tda4j's ~20µs without pinning an exact ratio. Large enough either way that
   6 of 8 Table-1-derived cases didn't finish in a 240s budget purely from complex-size scale, not from any
   per-case blowup. A `memoizeFiltrationValue=true`/`false` A/B on the slow case confirmed
   the already-documented 5%-25% comparator-recompute cost accounts for only about a quarter of that ~20µs —
   the remaining majority is unexplained by anything measured so far and is the natural next thing to profile
   (allocation profiler, not another `jstack` sample) before touching this class's representation. See
   `WORKLOG-ripser-comparison.md` for the full derivation, including the `maxDimension`-semantics footgun noted
   above (discovered while building this same benchmark) and why this session characterized the cost and
   deliberately stopped short of fixing it.

   **Deferred, on purpose, not by oversight**: Ripser's own compact `(Double, Int)` `diameter_index_t`
   representation — `DiameterSimplex` carries a full `Simplex[Int]`/`SortedSet[Int]` instead, a
   speed/simplicity choice made AGAINST the project's stated memory goal, flagged in `DiameterSimplex`'s own
   doc as a live option for a future session, not something to silently "fix." **This is now the leading
   suspect for the ~20µs/simplex constant-factor tax measured above**, not just a theoretical memory-vs-speed
   tradeoff. See `WORKLOG-lazy-enumeration.md`'s "Session 2" section for the full derivation, the advisor
   corrections that shaped it (recompute `zeroApparentFacet` rather than cache a claim-order map; measure the
   memoization cost
   rather than infer it; separate size-reduction from mechanism-efficiency in the benchmark), and the API
   changes a distance threshold would need if a from-scratch reader wants to extend this further (the note was
   already there before this session started implementing).

   **A real profiling pass, not the `DiameterSimplex` redesign above, found and fixed three separate sources of
   accidentally-sunk compute shared by `RipserCohomologyContext` AND `PackedRipserCohomologyContext`** (both go
   through the same `SimplexIndexing` class) — see `.claude/WORKLOG-ripser-profiling.md` for the full derivation,
   including a negative result worth remembering (naive `HashMap`-based memoization of the first bug below measured
   as making zero difference, because the cost was in `BigInt`'s own arithmetic, not in redundant recomputation of
   it — the fix had to change the ALGORITHM, not add a cache around it). (1) `SimplexIndexing.cofacetIteratorWithVertex`/
   `facetIterator`/the encode direction of `apply(simplex)` called the free-standing `binomial(n, k)` function
   directly, UNCACHED, despite `SimplexIndexing`'s own class doc explaining exactly why `binomialEntry`/
   `binomialCache` needed to memoize this same computation for its OWN (smaller) usage — `O(vertexCount)` uncached
   `BigInt`-based recomputations per simplex whose coboundary is enumerated, not `O(1)`. Fixed by switching
   `binomial`'s own implementation from `BigInt` to `org.apache.commons.numbers.combinatorics.BinomialCoefficient.value`
   (a `long`-only, non-allocating-for-realistic-sizes algorithm already a dependency of this file), not by caching the
   old `BigInt` version — caching it was the first attempt, and measured to do nothing (see the worklog). (2)
   `binomialEntry`/`binomialCache` itself was a `mutable.Map[(Int, Int), Long]`, boxing a `Tuple2$mcII$sp` key on
   EVERY lookup including cache hits — allocation profiling found this was the single largest allocation source in
   BOTH engines (16.3%/19.1% of main-thread allocation weight). Fixed with a lazily-grown `Array[Array[Long]]`
   instead (rows indexed by `d`, always small at every real call site; NOT a reintroduction of the eagerly-computed-
   full-table overflow bug `WORKLOG-simplexindexing-overflow.md` fixed — entries are still computed lazily, on first
   access, just into array cells instead of hashmap buckets). (3) `cofacetIteratorWithVertex`/`facetIterator` were
   built on `Iterator.unfold(...).filter(...).map(...)`, allocating a fresh `Tuple5`/`Tuple4` state tuple, an
   `Option` wrapper, AND a step closure on every candidate vertex considered — not just once per cofacet/facet
   actually found. Rewritten as hand-rolled `Iterator` subclasses with plain mutable fields, same exact per-step
   arithmetic (verified line-for-line against the original, including a subtle detail in `facetIterator` — it yields
   using the OLD `iA` before that step's own update, not the freshly-computed one — kept byte-for-byte, not
   "corrected"). A FOURTH bug, found only in `RipserCohomologyContext` specifically (the packed engine never had it,
   for a reason already documented on `cofacetIteratorWithVertex`'s own doc comment): `coboundaryOf`/
   `zeroPivotCofacet` used `(tau.underlying diff sigma.underlying).head` to find the one vertex a cofacet has that its
   facet doesn't — invoking `TreeSet`'s general persistent-tree set-difference algorithm
   (`RedBlackTree.split`/`._difference`, itself allocating `Tuple4`s and tree nodes) to answer a question with
   exactly one right answer by construction. Fixed with a plain linear scan,
   `tau.underlying.find(v => !sigma.underlying.contains(v)).get`. **Measured, not inferred, on a machine under real
   external load for the whole session** (a documented, checked-first noise floor of ~13% run-to-run at these sizes):
   all four fixes together gave a reproducible ~36% wall-clock improvement on BOTH engines, and a 37.7%–45.2%
   reduction in total allocated bytes, with the full `sbt test` suite (235 examples) passing identically after every
   individual fix. **What's now the largest remaining identified cost, characterized but deliberately NOT touched
   this session**: `Chain.reduceLoop`'s `SortedMap`-based elimination accumulator (`Chain.scala`) — shared by every
   engine in this file via `Chain.reduceByUntil`, so a change here needs its own dedicated, carefully-validated
   session rather than being folded into this one; see the worklog's own "What's still open" section for a concrete,
   bounded next step (`mutable.TreeMap` instead of the current immutable, persistent `SortedMap`) and why it wasn't
   attempted here (this class is the reference oracle every other engine is cross-validated against, and the packed
   engine was built on an explicit standing instruction to go through `Chain.reduceBy` unchanged).

   **Separately: a compute-server timing table given for this same profiling pass turned out to compare
   tda4j-on-the-compute-server against a HARDCODED `ripser.cpp` reference value measured on a completely different
   machine** (`RipserPaperBenchmarkSpec.scala`'s `DataCase.ripserMs` field, dated 2026-09-17, Apple M1 Pro — see the
   class's own doc comment) — every `x`/gap-to-ripser ratio in that table is not a same-hardware comparison and
   shouldn't be read as one; only the `SortedSet`-vs-`packed` ratio within that table (both freshly measured on the
   same machine) is trustworthy as given. A real same-hardware comparison would need `ripser.cpp` actually built and
   timed on the compute server itself, not inferred from the M1 Pro's numbers.

   **Follow-up session (2026-09-19), three more things, all in `.claude/WORKLOG-ripser-profiling.md`'s own
   "Follow-up session" heading**: (a) a real same-day, same-machine re-measurement against a FRESH vanilla
   `ripser.cpp` build (`github.com/Ripser/ripser`, not the project lead's own separate, independently-modified
   fork at `~/CLionProjects/ripser` — using that instead would have silently changed what "ripser.cpp" means here)
   confirmed the fixes above are real on actual paper data, not just synthetic clouds: the packed engine's own
   wall-clock time on `sphere3_48`/`96`/`192` dropped by 60.5%/48.9%/44.9% against the true pre-session baseline,
   larger than the ~36% measured on synthetic clouds — real point clouds exercise the `O(vertexCount)`-scaling
   fixes harder. The gap to real `ripser.cpp` on these three cases is now 18.8x/38.6x/64.0x (GROWING with `n`, not
   closing — consistent with the still-unfixed `Chain.reduceLoop`/boxing costs below scaling with total simplex
   count same as ripser's own work). (b) `RipserPaperBenchmarkSpec` now supports `-DripserBin=<path>`: when set, it
   shells out to a real `ripser` binary directly, times it (median of `-DripserTrials`, default 5 — a SINGLE
   subprocess timing at these small sizes is dominated by process-launch noise, confirmed directly: one single-
   trial run of `sphere3_96` gave 106.6ms, a 5-trial median of the identical binary/data/machine gave 45.7ms), and
   parses bar counts fresh from its own stdout — replacing the hardcoded snapshot for any run that sets this flag,
   with the old hardcoded-snapshot behavior kept as the default when it isn't. A companion script,
   `.claude/scripts/run-ripser-paper-benchmark.sh`, builds/downloads everything `-DripserBin` needs and is meant to
   be copied onto and run directly on a machine (e.g. a compute server) that doesn't already have either, with the
   `skipAll` toggle it needs restored via an `EXIT` trap even on a failed run. (c) A fifth fix, found by a
   dedicated profiling pass aimed specifically at answering "are there more clear time sinks in the packed path":
   `insertionDiameter` (byte-for-byte identical in BOTH engines) computed `math.max(sigmaFv,
   sigma.underlying.iterator.map(u => metricSpace.distance(u, v)).max)` — a closure allocated fresh on every single
   call (this method is itself called `O(vertexCount)` times per simplex, same shape as every other fix in this
   arc) plus `Double` boxing from `.map(...).max`; measured as the packed engine's own single largest allocation
   source after the four fixes above (7.5% of allocation weight on real `sphere3_96` data). Fixed with a plain
   `while` loop over primitive `double`, no closure, no boxing — measured as a further 26.7% wall-clock reduction
   on top of the four fixes already described, the single largest individual improvement in either session, and
   confirmed via re-profiling: the closure category disappeared entirely and `Double`'s own allocation share
   dropped from 21.7% to 1.2%. **After this fifth fix, a fresh profile of the packed engine shows the "quick win"
   tier exhausted**: every remaining cost is either genuinely necessary computation (the same `insertionDiameter`
   loop's own distance math, `SimplexIndexing.searchRow`'s decode, `BinomialCoefficient.value`'s own arithmetic) or
   the SAME two already-characterized structural costs named just above (`Chain.reduceLoop`'s `SortedMap`, now
   ~48% of allocation weight with the noise on top of it removed; generic `Long`/`DiameterIndex`/`Tuple2` boxing,
   ~34%) — nothing new in that category, just the same bigger, already-scoped job with the smaller stuff cleared
   away from around it.

**Bug found while cross-validating (4) against (1), fixed**: `EnumeratingCofaceSimplexStream.filtrationOrdering`
(`SimplexStream.scala`) used to be `Ordering.by(filtrationValue)` — no secondary tie-break — so it wasn't a
total order: it treated any two *different* simplices tied at the same filtration value as equal, which
happens by construction on every Vietoris-Rips complex with `maxDimension >= 2` (a triangle always ties with
its own longest edge). `CellularHomologyContext` bakes `stream.filtrationOrdering` into `Chain.reduceBy`'s
`SortedMap`, so two tied cells collided as one map key and the reduction silently garbled pairings.

Fixed at the source, on explicit instruction: `filtrationOrdering` is now filtration value (reversed, so
smaller-under-this-ordering = younger), then dimension, then **colexicographic** order via `simplexIndexing`'s
own combinatorial-number-system index — colex specifically to match Ripser's own Definition 3.2/Proposition 3.9
"lexicographically refined" tie-break, not `FilteredSimplexOrdering`'s plain lex.

Fixing that tie-break alone then *exposed* a second, distinct bug: `iterateDimension` in
`EnumeratingCofaceSimplexStream`, `RipserCofaceSimplexStream`, and `InorderCofaceSimplexStream` sorted each
dimension's bucket via its own independent `.sortBy(filtrationValue)` (or, in one `RipserCofaceSimplexStream`
call, `.sorted(using filtrationOrdering)` with no `.reverse` — silently correct only because the *old*,
non-reversed `filtrationOrdering` happened to already sort oldest-first) — a *different* total order from
`filtrationOrdering` on exactly the cells that tie. Algorithm 1 requires processing order (columns) and pivot
order (rows) to be indexed by one shared total order; two independently-tie-broken orders that disagree,
even though each is individually a valid total order, violates that precondition. This produced a crash
(`IllegalStateException: reduction pivot ... was not a recorded open class`) on a 4-point unit-square fixture
once (1) alone was fixed. **General lesson**: a stream's `iterateDimension` order and its `filtrationOrdering`
must be the *same* total order, one the consistent `.reverse` of the other — not merely "each independently
valid." Fixed by replacing every `.sortBy(filtrationValue)` (and the one un-reversed `.sorted(using
filtrationOrdering)`) with `.sorted(using filtrationOrdering.reverse)` — `.reverse` on the *same* `Ordering`
object, not a second, independently-built comparator (an earlier attempt at "oldest-first" via
`FilteredSimplexOrdering` broke exactly this way: its dimension tie-break didn't reverse consistently with a
separately-reversed primary key). `InorderCofaceSimplexStream`'s own coface generation doesn't sort at all
(order comes from the metric-space structure directly); checked independently and found to already agree with
the fixed `filtrationOrdering` on the same square fixture. Verified: square-fixture crash gone, resulting
barcode independently correct by hand count, full targeted regression (`HomologySpec`,
`PersistenceInChunksSpec`, `RipserCohomologySpec`, `SimplexStreamSpec`, `SimplexIndexingSpec`,
`RipserStreamSpec`, `VietorisRipsSpec`, `CofaceSimplexStreamSpec`) clean. Full derivation in
`WORKLOG-cohomology.md`. (`SimplicialHomologyByDimensionContext`, the third live persistence engine, was not
exercisable by this regression at all — see item 3 above, it's non-functional independent of this bug.)

`Barcode.scala` (package `org.appliedtopology.tda4j.barcode`) defines the persistence-diagram representation:
`BarcodeEndpoint` (open/closed/±∞) and `PersistenceBar`, plus algebra on finitely-presented persistence modules.

### Dead/experimental code, kept intentionally

- The bottom third of `Homology.scala` (below `SimplicialHomologyByDimensionContext`) is commented-out prior art
  (`RipserHomology`, `computePersistentHomology`) kept for reference while the three live contexts above were
  developed.
- Root test sources' `APISpec.scala`/`SimplicialSetSpec.scala`: the latter is entirely commented out (a much
  earlier, from-scratch sketch of a simplicial-set representation — `SimplicialSetElement`, a `Product`
  construction, a `sphere(n)` builder — predating and unrelated to the real `FiniteSimplicialSet` now in
  `cells`/"Simplicial sets" below; kept as historical record of that earlier attempt, not wired into anything).

`SimplicialSet.scala`'s old commented-out sketch and `Deferred.scala` (an AST-based deferred-arithmetic
experiment) were both deleted outright in the session that preceded "Simplicial sets" below, on the project
lead's own initiative — no longer present, not merely dead code kept around.

## Cubical complexes and persistence

`Cubical.scala`/`CubicalStream.scala`/`CubicalImage.scala` add cubical complexes as a second concrete `OrderedCell`
instance alongside `Simplex[VertexT]` — built in one overnight session; see `.claude/WORKLOG-cubical.md` for the
full derivation, including the advisor consult that shaped the design up front and three separate instances of a
same-named-top-level-extension-method collision hit and fixed along the way (worked around at the time by picking
distinct names, `encoded`/`describe` instead of `underlying`/`show`).

**That collision's root cause is now fixed for real, in a later session** — every `Simplex[VertexT]`/`Cube`
extension whose *receiver* is the opaque type itself now lives inside that type's own companion object
(`object Simplex`/`object Cube`), not as a top-level `extension` clause; extension-method resolution checks a
receiver type's companion object by nominal type, so two unrelated opaque types can now safely reuse the same
method name (confirmed with `underlying`/`show` reused across a standalone `scala-cli` repro before this was
applied to real code). `encoded`/`describe` were deliberately **not** renamed back to `underlying`/`show` — the
fix removes the *need* for distinct names, but renaming touches call sites and is left as an independent decision.
This is **not** a fix to reach for reflexively on every future opaque type without re-checking two hazards
found in the process: (1) opaque-type transparency is scoped to the *whole file* the `opaque type` is declared
in, so any code in that same file calling the type's own extensions by dot-syntax breaks (hard error, or worse,
silently resolves to a same-named member of the underlying representation type instead) — `Simplex_is_OrderedCell`/
`Cube_is_OrderedCell` had to move into their own files (`SimplexOrderedCell.scala`/`CubicalOrderedCell.scala`) for
exactly this reason; (2) a companion-object extension can lose to a same-named stdlib extension reachable via a
wildcard import at some call site (`Simplex[VertexT]`'s `min`/`max` vs. `scala.math.Ordering.Implicits.
infixOrderingOps.{min,max}`, imported via `import math.Ordering.Implicits.*` in `FiniteMetricSpace.scala`/
`SimplexStream.scala`) — `min`/`max` deliberately stay top-level extensions for this reason, the sole exception.
`asSimplex`/`asCube` were never moved either, for a third, simpler reason: their receiver is the *raw*
`SortedSet[VertexT]`/`Vector[Int]`, not the opaque type, so companion-object lookup on `Simplex`/`Cube` could never
find them regardless. Full derivation, including every `scala-cli` repro that pinned down each hazard one at a
time, in `.claude/WORKLOG-extension-companion-objects.md`.

**`Cube` (`Cubical.scala`)**: an elementary cube is a product of `n` (the ambient/embedding dimension, fixed per
complex — not per cube) factors, each either a degenerate interval `[a,a]` or a unit interval `[a,a+1]`; the
cube's own dimension is the count of non-degenerate factors. `opaque type Cube = Vector[Int]`, the standard
"doubled coordinate" encoding from the cubical-homology literature (Kaczynski-Mischaikow-Mrozek, *Computational
Homology*): axis `k` is `2*a` (degenerate, point `a`) or `2*a+1` (non-degenerate, `[a,a+1]`). `Vector`, not
`Array`/`IArray` — structural `equals`/`hashCode` is required for `Chain`'s pivot tables to collide two
structurally-identical cubes; an array-backed opaque type would silently use reference equality instead.
Boundary is the standard KMM formula, sign alternating over a non-degenerate axis's RANK AMONG the other
non-degenerate axes (not its raw position in the coordinate vector) — using raw position instead is a real,
easy-to-make sign bug that breaks d(d(x))=0 as soon as a cube has a degenerate axis interleaved among its
non-degenerate ones, and is invisible over F2 (where -1=1) — `CubicalSpec`'s dd=0 property test is therefore run
over a signed field (F3) as well as Double, exhaustively for small ambient dimensions. `Cube_is_OrderedCell`
mirrors `Simplex.scala`'s parameterized-given pattern exactly (a `setOrdering` parameter defaulting to a
canonical lexicographic order on the encoding, so a stream can inject its own filtration-aware ordering).

`CellularHomologyContext` (the naive, generic reduction engine — see "Persistent homology" above) needed
**nothing new** to consume `Cube`: it was already fully generic over `CellT: OrderedCell`. `CubicalHomologyContext`
is a one-line wrapper, exactly mirroring `SimplicialHomologyContext`'s relationship to the same engine.
`PersistenceInChunksContext`/`SimplicialHomologyByDimensionContext` remain hardcoded to `Simplex[VertexT]` and
were NOT generalized — out of scope for this session, matching what "slot in cleanly with [the cellular
homology algorithm]" actually meant (the naive engine specifically, confirmed by reading `Homology.scala`
before writing any code).

**`CubicalGridStream` (`CubicalStream.scala`)**: a dense cubical complex over a full rectangular grid — the
T-construction, GUDHI/DIPHA/Perseus's standard convention for image persistence. A caller-supplied
`topCellValue: IndexedSeq[Int] => Double` gives every top-dimensional cube (pixel/voxel) its own value directly;
every lower-dimensional cube's value is the `min` over every top cell that contains it, computed DIRECTLY
(cartesian product over the cube's degenerate axes' `±1` choices) rather than via a recursive immediate-cofaces
walk — mathematically equivalent (every top cell containing an immediate coface of `c` also contains `c`) and
cheaper. Monotonicity (`fv(face) <= fv(coface)`, which `CellularHomologyContext.processingOrder`'s ascending
sort requires — get it backwards and it's the exact "reduction pivot ... was not a recorded open class" crash
this codebase has hit three other times, see the "Bug found while cross-validating" section above) falls
directly out of this construction: `{top cells containing a coface}` is always a subset of `{top cells
containing its face}`, so a min over fewer things is never smaller. `filtrationOrdering` copies
`EnumeratingCofaceSimplexStream`'s exact shape (explicit negated-fv comparison, then dimension, then a canonical
tie-break) — NOT `.reverse` of an ascending-built ordering, which flips the dimension tie-break too. `shape(i)`
is the pixel/voxel COUNT along axis `i` (not lattice points); `totalCellCount = prod_i (2*shape(i)+1)` — a real
256x256 image is 263,169 cells, not 65,536. `ExplicitCubicalStream` (sparse/arbitrary cube sets with explicit
filtration values, mirroring `ExplicitStream`) also exists for hand-built fixtures and genuinely non-grid
cubical complexes.

Sublevel (ascending intensity, the default and GUDHI's own convention) vs. superlevel is handled ENTIRELY in
`CubicalImage.scala`'s loaders (`sublevel: Boolean` parameter, negating values on load — the standard "sublevel
of `-f` is superlevel of `f`, reparametrized" trick) rather than as a direction flag on `CubicalGridStream`
itself — keeps the stream's "min over cofaces, always" logic free of a second code path to verify.

**`CubicalImage.scala`** converts greyscale images/voxel grids into `CubicalGridStream`s: `fromFlatArray(shape,
flatValues, sublevel)` is the one real implementation everything else reduces to (row-major strides).
`fromBufferedImage`/`fromFile` use `javax.imageio` (JDK-builtin, no new dependency) for 2D image files, standard
ITU-R BT.601 luma for grayscale conversion (applied unconditionally — harmless identity on an already-grayscale
image). 3D+ voxel grids (`fromVoxelGrid3D`, or `fromFlatArray` directly) take a plain in-memory array — there is
no single standard JDK-readable volumetric format, so a caller with a specific one (NRRD, NIfTI, a raw slice
stack) loads it upstream with whatever library that needs.

**Validation strategy, deliberately reordered from a first instinct** (see WORKLOG-cubical.md's advisor-consult
section): NOT led with a cubical-to-simplicial triangulation cross-check — an unvalidated oracle can make a real
cubical bug look like a triangulation bug or vice versa, the same trap this file's Helix/DQP history already
documents. Led instead with, in order: dd=0 over a signed field; a monotonicity property test; the
`totalBarsAccountForAllCells` structural invariant (`HomologyFixtures`, already used elsewhere in this
codebase); three hand-derived fixtures deliberately chosen TIE-HEAVY (few distinct filtration values — exactly
the regime this codebase's `filtrationOrdering` bugs have historically hidden in), whose EXACT bar-count
breakdown (not just presence of the topologically meaningful bars) was pinned via a general planar-graph
argument (spanning-tree/cycle-rank: a grid's full 1-skeleton opens `V` classes at dimension 0, `V-1` "tree"
edges kill all but one, and by Euler's formula the remaining `E-(V-1)` "extra" edges exactly equal the pixel
count, so every pixel is guaranteed to kill exactly one dimension-1 class); and an independent H0 cross-check
via union-find over PRESENT PIXELS using Moore/Chebyshev adjacency (8-connected in 2D, 26-connected in 3D) — NOT
4-connected, which would be a WRONG oracle here: two pixels touching only at a shared corner vertex are
genuinely in the same path component of the cubical complex whenever that vertex is present. All of the above
pass: `CubicalSpec` (9 examples), `CubicalStreamSpec` (8 examples, 331 expectations, including a dedicated
`ExplicitCubicalStream`-vs-`CubicalGridStream` cross-check — the former's own `filtrationOrdering` is an
independently-written duplicate, not shared code, so this also confirms the two never silently drifted apart),
`CubicalImageSpec` (12
examples, including a real PNG file round-trip through `ImageIO.write`/`fromFile` and an end-to-end image-to-
barcode test reproducing the hand-derived fixture through the actual `BufferedImage`/luma path).

**Naive-engine scaling, measured** (`CubicalBenchmarkSpec.scala`, matching `SparseRipsBenchmarkSpec`'s
convention — small defaults, `Arguments`-driven overrides for a real run): 2D is roughly FLAT per-cell cost
(~60-95us/cell) across a 900x range of complex sizes, so a real 256x256 image (263,169 cells) processes in
~25 seconds on the fully generic, unoptimized naive engine. **3D is a materially different, worse shape**:
per-cell cost GROWS with `n` (190 -> 340 -> 850 us/cell from n=8 to n=32) rather than staying flat, so a modest
32-cubed voxel grid (274,625 cells — barely more than the 2D 256x256 case) takes ~3.9 MINUTES, not ~25 seconds.
Not yet root-caused (a genuine next-session profiling target — allocation profiler, not another timing table,
same lesson `RipserCohomologyContext`'s own constant-factor tax learned the hard way).

A specialized, grid-structure-exploiting fast cubical persistence algorithm — **CubicalRipser** (reproducing
Ripser's clearing/apparent-pairs optimizations for cubical complexes) or the **Wagner-Chen-Vuçini** "Efficient
Computation of Persistent Homology for Cubical Data" approach (union-find for dimension 0, discrete-Morse-style
reduction for higher dimensions) — is a real, flagged-on-purpose future direction, NOT attempted this session:
tonight's scope was deliberately limited to slotting `Cube` into the existing generic naive engine. The measured
3D numbers above are the concrete case for it, not a theoretical one — profile first (root-cause the growing
per-cell cost) before deciding whether a targeted fix or a genuinely specialized engine is warranted, mirroring
how `RipserCohomologyContext` itself was only built after the naive engine's own limits were understood, not
before.

## Simplicial sets

`algebra/SSetElement.scala` + `cells/SimplicialSet.scala` + `streams/SimplicialSetStream.scala` add finite
simplicial sets as a third concrete `OrderedCell` instance alongside `Simplex[VertexT]` and `Cube` — a genuinely
independent design from the earlier, entirely-deleted `SimplicialSet.scala` sketch (see "Dead/experimental code"
above), built fresh per an explicit ask not to reuse the old approach. See `.claude/WORKLOG-simplicial-sets.md`
for the full derivation, including three `advisor()`-driven correction passes before any code was written.

**Representation**: the classical Eilenberg–Zilber presentation (as used by Kenzo/EAT for effective homology).
A finite simplicial set is a finite set of non-degenerate *generators* per dimension, plus, per generator `g`
of dimension `n`, primitive user-supplied face data `faces: G => IndexedSeq[SSetElement[G]]` — the `n+1` values
`d_0(g), ..., d_n(g)`, each itself an `SSetElement[G](word, target)`: `word` is the degeneracy indices in
Eilenberg–Zilber normal form (**strictly decreasing**, not increasing — derived directly from `s_i s_j =
s_{j+1} s_i` for `i <= j`: `s_0 s_0 (v) = s_1 s_0 (v)`, so the unique normal form for "apply `s_0` twice" is
`[1,0]`, never `[0,1]`), `word = Nil` meaning the face is itself a bare (non-degenerate) generator. This
primitive data is enough to infer everything else. Dimension-0 generators have zero faces (face maps target
dimension `n-1`, which doesn't exist below 0 — the same convention `Simplex`/`Cube` already use).

**Operator algebra, not just a homology-only boundary rule**: `insertOuter` (composes a new outermost
degeneracy into a normalized word) and `faceOf` (`d_i` on an *arbitrary* element — not just generators — via
the simplicial identities: `i < w1` shrinks the target index and recurses, `i ∈ {w1, w1+1}` cancels the
degeneracy outright, `i > w1+1` shifts and recurses) together let `FiniteSimplicialSet.validate()` check that
hand-supplied face data actually satisfies `d_i d_j = d_{j-1} d_i` (`i<j`) — impossible with only a
generators-only boundary rule, since checking it requires `d_i` on the frequently-*degenerate* `d_j(g)`.
`validate()` also checks structural well-formedness first (arity, registered targets, a genuinely normalized
`word`) since those are the data-entry mistakes a hand-written presentation is actually likely to make.

**The `OrderedCell` instance lives on the generators themselves** (`FiniteSimplicialSet_is_OrderedCell`,
mirroring `Simplex_is_OrderedCell`'s injectable-ordering pattern): `boundary` is the normalized-chain-complex
differential — only faces that are themselves bare generators (`word.isEmpty`) contribute, alternating sign;
a degenerate face contributes nothing, since the normalized chain complex is quasi-isomorphic to the full one.
This is the *only* place degeneracy matters for homology; no recursive `faceOf` is needed there at all.

**Feeding the existing engines needed a real structural finding, not an assumption**: `CellularHomologyContext`
(`Homology.scala:39`) takes a `stream: CellStream[CellT, FiltrationT]`, not a bare `OrderedCell` — there is no
engine entry point that skips the stream interface. `SimplicialSetStream[G]` is a trivial adapter (every
generator at filtration value `0` — ordinary, unfiltered homology of one fixed simplicial set, not real
persistence) whose `filtrationOrdering` (`Ordering.by(dimOf)` ascending, then the caller's own `Ordering[G]` as
tiebreak) was derived by tracing `Homology.scala:80-94`'s own `processingOrder` comment rather than guessed —
that comment documents exactly why `filtrationOrdering`'s dimension component must be ascending and unreversed.
`G is OrderedCell` is threaded explicitly through the adapter's companion `apply`, not resolved as an ambient
global given: unlike `Simplex`/`Cube`, a `FiniteSimplicialSet`'s `OrderedCell` instance depends on that one
instance's own `faces` data, not on `G` alone, so it can never be a single global instance for a given `G`.

**`fromStream[VertexT](stream: CellStream[Simplex[VertexT], ?])`** builds a `FiniteSimplicialSet` from any
stream of simplices: faces of a genuinely-ordered simplex (strictly increasing vertex tuple) are always
non-degenerate, so every generator's own face data is `word = Nil` throughout — exercising *zero* of the
degeneracy machinery, a plumbing check only (cross-validated against `SimplicialHomologyContext` run directly
on the same stream), not evidence `faceOf`/`insertOuter` themselves are correct. Deliberately typed against
`CellStream[Simplex[VertexT], ?]`, not the narrower `SimplexStream[VertexT, ?]` the first draft used: the real
Vietoris-Rips streams in this codebase (`EnumeratingCofaceSimplexStream` and relatives) are
`CofaceSimplexStream`/`StratifiedCellStream`, a *sibling* of `SimplexStream` under `CellStream`, not a subtype
of it — caught by trying `fromStream` against a real VR stream while writing its cross-validation spec, not by
re-reading the type hierarchy in the abstract.

**Fixtures** (`cells/SimplicialSetFixtures.scala`, test sources), each hand-derived and cross-checked against
the real engine, not just asserted: `minimalSphere(n)` (S¹, S², S³ from one generic builder — 1 vertex, 1 top
`n`-cell, all faces the same maximally-degenerate `(n-1)`-simplex over the vertex for `n >= 2`, no degeneracy
at all for `n=1`); `realProjectiveSpace(topDim)` (RP², RP³ from one generic builder — the reduced-bar-
construction model of `B(Z/2)`, one non-degenerate generator per dimension); `torus` (Hatcher's minimal
Δ-complex model, *Algebraic Topology* Example 2.4 — 1 vertex, 3 loop-edges, 2 triangles, no degeneracy at all;
its value is being a genuine Δ-complex `fromStream` can never produce from an actual `Simplex[VertexT]`, which
forbids repeated vertices). **RP² is the sign-discriminating fixture** (`H_1=H_2=F2` over F2, both `0` over F3
— a sign error in the alternating boundary is invisible over F2 and only shows up over F3); **RP³ is the only
fixture that reaches `faceOf`'s `i > w1+1` branch** (none of the others do — a real coverage gap found and
closed by extending RP² to RP³, not assumed covered) and pins a genuinely essential bar above dimension 0
(`H_3=F` over every field, since RP³ is a closed orientable 3-manifold).

**`product`/`coproduct` (`cells/SimplicialSetConstructions.scala`), added in a later session** — see
`.claude/WORKLOG-simplicial-set-constructions.md` for the full derivation, including a real design correction
and a real implementation bug, both caught rather than assumed away.

**The first design attempt was wrong, caught by `advisor()` before any code was written**: indexing the
product's generators by Eilenberg–Zilber *shuffles* (pairs of non-degenerate simplices of dimension `p`,`q`
with `p+q=n`) is the classical EZ *chain map* between `C_*(X) tensor C_*(Y)` and `C_*(X x Y)`, not an
enumeration of `X x Y`'s own non-degenerate simplices — concrete counterexample: for `X=Y=minimalSphere(1)`,
`(e_X, e_Y)` (both non-degenerate, dimension 1 each) is a real non-degenerate 1-simplex of `X x Y` that no
`(p,q)`-shuffle with `p+q=1` could ever produce, since here `p=q=1`. The actual definition needs no shuffles:
`(X x Y)_n = X_n x Y_n` degreewise, and a pair `(a,b)` is non-degenerate in the product iff no single `j`
degenerates both sides at once (`s_j` on the product is literally the diagonal `(s_j, s_j)`) — operationally,
`a` is `s_j`-degenerate exactly when `j` appears anywhere in `a.word` (not just the outermost entry), so
non-degeneracy of the pair reduces to `a.word.toSet.intersect(b.word.toSet).isEmpty`, cross-checked in
`SimplicialSetConstructionsSpec` against the actual definition (`sOp(j, dOp(j, a)) == a`) exhaustively, not
just spot-checked. `elementsAtDim[G](sset, n)` enumerates ALL of `X_n`, not just its generators (every
generator of dimension `p<=n` paired with every size-`(n-p)` subset of `{0,...,n-1}` as its word) — needed
because `product` must consider every element of `X_n x Y_n` before filtering to non-degenerate pairs.
`ProductGenerator[GX,GY](x, y)` (a word-disjoint pair) is the product's generator type.

**Top dimension is `maxDim(x) + maxDim(y)`, proved during implementation, not merely assumed by analogy to
CW-complex dimension**: a non-degenerate pair at dimension `n` needs disjoint word-subsets of an `n`-element
set, each of size `>= n - maxDim` of its own factor, forcing `n <= maxDim(x)+maxDim(y)` by a direct counting
argument — beyond that bound no pair can possibly be non-degenerate, permanently.

**A real bug, caught by `validate()`'s `d_i d_j = d_{j-1} d_i` check, not by inspection**: face maps reuse
`faceOf` on each side independently, but the result can come back sharing common word entries even when the
input pair didn't — handled by stripping the common degeneracy set `J` from both sides. The first attempt just
deleted `J`'s entries from each word, leaving the remaining entries' absolute values unchanged — wrong, and
caught immediately as an `IndexOutOfBoundsException` deep in `faceOf`'s recursion on
`product(minimalSphere(1), minimalSphere(2))` (the first test case big enough to exercise the stripping path at
all — the torus-matching `product(minimalSphere(1), minimalSphere(1))` never hits it, by luck of that
example's dimensions). Root cause: the remaining (private) word entries live in the *same shared* gap-position
domain as the stripped-out ones, so removing `J` shrinks that domain and the survivors must be *relabeled* via
the rank function (`e -> e - |{j in J : j < e}|`, the standard order isomorphism onto the smaller domain), not
left at their original absolute values, which can land outside the smaller domain's valid range entirely — the
*outer* wrapping word (`J` itself) needs no such relabeling, since it's already expressed in the final,
larger-dimensional domain's own coordinates.

**Verification**: `product(minimalSphere(1), minimalSphere(1))` generator counts by dimension `(1, 3, 2)`,
hand-derived before writing any code and confirmed by the implementation — NOT the minimal 2-edge Δ-complex
torus (3 edges, not 2; the raw categorical product is a strictly larger, non-minimal simplicial set, only
homotopy equivalent to the hand-built `torus` fixture), but matching its Betti numbers `(1,2,1)` exactly, via a
completely different construction. `product(minimalSphere(1), minimalSphere(2))` matches Künneth's theorem for
`S^1 x S^2` (Betti `(1,1,1,1)`) — the same case that exercises the stripping/relabeling bug above, so it
doubles as the strongest correctness evidence available (an independent classical theorem) and the regression
pin for the fix. `coproduct(minimalSphere(1), minimalSphere(2))`: Betti `(2,1,1)` — unreduced `H_0` adds
directly across a disjoint union of connected spaces (this engine computes unreduced homology throughout; it's
*reduced* `H_0` that would need a "-1" adjustment, not this case). `coproduct` itself needs no degeneracy
machinery at all — generators tagged `Left`/`Right`, faces delegate within whichever side a generator came
from.

**Real filtration + a chunks engine, added in a later session** (`streams/FilteredSimplicialSetStream.scala`) —
see `.claude/WORKLOG-simplicial-set-filtration.md` for the full derivation. `SimplicialSetStream` stays the
constant-`0`, ordinary-homology-only adapter; `FilteredSimplicialSetStream[G]` is a genuine
`StratifiedCellStream[G, Double]` with a caller-supplied `filtrationValue: PartialFunction[G, Double]`, defined
only on generators (never on degenerate `SSetElement`s, since no engine here ever queries a stream's
`filtrationValue` on anything else). `filtrationOrdering` (`simplicialSetFiltrationOrdering`) reuses
`EnumeratingCofaceSimplexStream`'s exact, twice-debugged convention rather than re-deriving it: filtration value
compared reversed (smaller-under-this-ordering = younger), then dimension ascending, then a caller-supplied
tie-break. `validateMonotoneFiltration(sset, filtrationValue)` checks the one precondition every engine needs —
a face's value never exceeds its coface's — against only BARE (`word = Nil`) direct faces, the only ones
`FiniteSimplicialSet_is_OrderedCell.boundary` ever looks at; lives in this adapter file, not on
`FiniteSimplicialSet` itself, per the architecture principle already stated above.

This is also what motivated genericizing `PersistenceInChunksContext` into `CellularPersistenceInChunksContext`
(see "Persistent homology" above) — investigation before writing anything found the class had zero actual
`Simplex`-specific behavior in its body, so `FiniteSimplicialSet` generators slot in directly once a real
`StratifiedCellStream[G, Double]` exists. Cross-validated against `CellularHomologyContext` on a deliberately
non-dimension-aligned `torus` filtration (edges of the SAME dimension given different values, so filtration
order and dimension order genuinely disagree — a dimension-aligned filtration couldn't have caught a reversed
`filtrationOrdering` primary key) — the two engines agree exactly, matching a hand-derived expected structure
(one finite H_1 bar dying at the older triangle's filtration value; the younger, identical-face-data triangle
survives as the essential H_2 class) — plus a randomized dimension-band-plus-jitter fuzz across every existing
fixture.

**Deliberately deferred, still not attempted**: quotients/attaching maps (identifying generators, or generators
across a coproduct, under a gluing relation respecting face compatibility — probably the single most useful
remaining construction for hand-building models directly, and a prerequisite for the item below); the bar
construction / classifying spaces (would lean on `product` and quotients once quotients exist).
`SimplicialHomologyByDimensionContext` remains hardcoded to `Simplex[VertexT]` and was not generalized — a
separate, unrelated algorithm (union-find-based dimension-0/1 handling) from the two engines touched so far.

## Alpha complex: DQP vs Helix

`AlphaComplexDQP.scala` implements Erik Carlsson & John Carlsson, *Computing the alpha complex using dual active
set quadratic programming*, Scientific Reports 14:19824 (2024), https://doi.org/10.1038/s41598-024-63971-3. The
QP solver follows DAQP (Arnström, Bemporad & Axehill, IEEE TAC 67(8):4362–4369, 2022,
https://github.com/darnstrom/daqp — the paper's own reference [27]); the paper's problem (9) is already in DAQP's
canonical inner form (H = I, a least-distance problem), so DAQP's H-factorisation is unnecessary and its recursive
LDL^T updates collapse to Cholesky update/downdate of `B_W` (`CholeskyWorkspace` in the source, a hand-rolled
incremental Cholesky — deliberately not Apache Commons Math's `CholeskyDecomposition`, which has no update/downdate
API and would force an O(k³) full refactorisation per active-set step instead of O(k²)).

Math cheat sheet (so it doesn't need re-deriving from the paper): base vertex `x`, neighbours `x_i`, power weights
`p`. **Filtration values are squared radii (powers)** per the paper's Definition 10 — `radiusOf` takes the sqrt
(and `AlphaShapeDQP.filtrationValue` goes through `radiusOf`, not the raw squared value, specifically so it
matches `HelixDelaunay.filtrationValue`'s units under the shared `AlphaShapes` contract — the two are meant to be
dispatch-interchangeable). The dual objective only needs *squared distances*, not the dot products the paper
frames it with: `B_ij = (d²(i,x) + d²(j,x) - d²(i,j))/2` — so `PowerDistance` sits on squared distance rather than
extending `FiniteMetricSpace` directly (which is unsquared); it bridges via `PowerDistance.toMetricSpace` where
interop is actually needed (e.g. `cechNeighbours()`'s VP-tree spatial index, `JVPTree` from
`FiniteMetricSpace.scala`), not by inheritance. Caveat: `B` is PSD only for Euclidean-embeddable metrics.

`AlphaShapeDQP` (what `Alpha(pts, "DQP")` actually constructs) always computes the complete, **untruncated** alpha
complex (`maxRadius = Double.PositiveInfinity`), specifically to match `HelixDelaunay`'s always-untruncated
behaviour — `metricSpace.minimumEnclosingRadius` was tried as the default first and rejected, because degenerate
configurations produce simplices with arbitrarily large circumradius (see the degeneracy hazard below) that a
finite bound silently excludes. Callers who want an actually radius-truncated alpha complex should call
`AlphaComplexDQP.euclidean(points, maxRadius, maxDimension, settings)` directly.

**Extensive numerical-robustness work has gone into `DualQP.solve`** (see `WORKLOG-alpha-complex.md` in `.claude/`
for the full derivation of each, including concrete counterexample point clouds) — treat these as settled, verified
design decisions, not things to casually retune:
- `rankTolerance` default is `1e-6`, not the more "obvious" `1e-12`: a Schur-complement ratio as large as `~1e-8`
  has been observed to poison the Cholesky factor (multipliers blowing up to `~1e14`) and cause genuine
  non-terminating active-set cycling (confirmed non-terminating at 100,000 iterations, not just slow) —
  `[1e-7, 1e-5]` is the empirically-verified safe range; `1e-4` starts rejecting genuinely non-degenerate
  directions and silently gives a wrong answer instead.
- The ratio tests in the singular-step handling break ties by the constraint's **global** index, not its position
  in the working set (Bland's-rule anti-cycling) — working-set position isn't a stable ordering, so breaking ties
  by it lets the same pair of global indices swap forever without progress.
- **Known, accepted limitation** (do not "fix" this without re-reading `WORKLOG.md` first): when the entering
  variable's Schur complement is small and no active inequality can be swapped out to compensate, `DualQP.solve`
  conservatively treats the candidate as infeasible rather than committing the small pivot directly. This *can*
  wrongly exclude a genuinely-Delaunay simplex near certain near-degenerate configurations. A mathematically
  "more correct" fix (commit anyway when `s > 0`, since the dual objective has a genuine finite maximum at
  `t* = grad_j / s`) was implemented and reverted: two counterexamples with near-identical Schur-complement ratios
  (`~3.4e-7` legitimate, `~3.0e-7` catastrophic) required opposite handling, proving no fixed numerical threshold
  can safely distinguish "safe to commit" from "will poison the factor" — it depends on the rest of the working
  set's conditioning, not that one ratio in isolation. `solveAtVertex` also has a defense-in-depth per-candidate
  catch so a not-yet-characterised non-convergence case excludes just that one candidate (logged to stderr)
  rather than aborting the whole complex.
- **Vertex filtration values must go through `space.weight(x)`, not a bare `0.0` default.** `weights`/`witnesses`
  (the `HashMap`s backing `AlphaComplexDQP.filtrationValue`/`.witness`) are only ever populated for `k>=1`
  candidates inside `compute()`'s main loop — vertices are added to `byDim(0)` separately and need their own
  entries set explicitly (`weights(f) = -space.weight(x)`, per Definition 10 evaluated at a vertex where the
  unconstrained minimiser is trivially `y*=x`; `witnesses(f) = coordsOf(x)`). Defaulting to `0.0`/`null` is only
  correct in the unweighted case — with nonzero weights it silently breaks the monotonicity invariant (a vertex
  can report a *larger* filtration value than an edge through it). Caught by `AlphaComplexDQPWeightedSpec`, which
  is the only place weighted complexes get exercised end-to-end at all (`HelixDelaunay` is plain-Euclidean-only
  and can't serve as ground truth for the weighted case, so this spec checks structural invariants only, the same
  way the unweighted `AlphaComplexSpec` does, not a full correctness proof).
- `AlphaComplexDQPRegressionSpec` (in `AlphaComplexSpec.scala`) pins two hand-verified adversarial point clouds
  (a facet-closure counterexample and the cycling counterexample above) as permanent regression tests, alongside
  the original 3x3-grid repro in `AlphaValidationSpec` and a broader `AlphaComplexSpec` property suite
  (`minTestsOk = 2000`, not the scalacheck default of 100 — the bugs above had verified failure rates as low as
  1-in-12000, so 100 samples gives weak protection). `AlphaComplexDQPSpatialIndexSpec` separately cross-checks
  `cechNeighbours()`'s VP-tree-based implementation (used when `maxPower` is finite; the default unbounded mode
  takes a separate "everyone is everyone's neighbour" path where a spatial index buys nothing) against a
  preserved brute-force reimplementation — correctness never depends on the index, only performance does, and the
  win is real but regime-dependent (scales with N in sparse/local neighbourhoods, the normal alpha-complex case;
  no benefit, even a small regression, in dense near-complete-graph neighbourhoods).
- Writing more specs2 code with `Seq[Simplex[_]]` in this file: give it an explicit type ascription
  (`val xs : IndexedSeq[Simplex[Int]] = ...`) before calling `.forall`/similar on it. Without one, in a class
  mixing specs2's `ScalaCheck` trait, `.forall` can resolve to a specs2 `ValueCheck`-based extension instead of
  the standard-library one, breaking type inference inside the lambda with confusing "value X is not a member of
  ValueCheck[Simplex[Int]]" errors. Not fully root-caused; the explicit ascription reliably fixes it.

**`HelixDelaunay` had two of its own independent robustness bugs**, found incidentally while using it as DQP's
cross-validation ground truth (see `WORKLOG.md` Part 3 for full repro/root-cause detail):
1. **Fixed and verified.** `assert(validated.nonEmpty)` at the initial-simplex bootstrap used to fail on ordinary
   random input at roughly a 1-in-600 rate: when more than `ambientDimension` points lay on the discovered
   hull-supporting hyperplane (common for grid-like/degenerate clouds), the code collapsed `startingSimplex` down
   to just 2 points regardless of `ambientDimension`, starving the subsequent bootstrap of a well-posed
   circumsphere. Fixed by greedily growing an affinely-independent subset of exactly `ambientDimension` points
   (rank-checked via `SingularValueDecomposition`) instead. 20,000-trial fuzz re-run: zero failures.
2. **Partially fixed; residual behavior is a known, accepted limitation, not a bug left open by oversight.**
   `addFrontierCase`'s facet-deduplication check compared a `d`-vertex facet against a `(d+1)`-vertex full
   simplex (always `false`, dead code) instead of the facet actually derived from it — fixed, but this alone
   didn't meaningfully change the failure rate. The dominant cause: on point clouds containing a near-cospherical
   local cluster (circumradii of multiple candidate top-dimensional simplices agreeing to ~4-5 significant
   figures — closer than `HelixDelaunay`'s own `handleCosphericalPoints` tiling logic detects, since that logic
   only checks how many points lie near *one already-chosen* candidate's own circumsphere, not near-ties *across*
   competing candidates), the frontier walk's greedy first-empty-candidate search becomes order-dependent: a seed
   sweep of one adversarial example found the DQP-matching (correct) triangulation only 8% of the time across 300
   reconstructions, an incomplete-but-locally-consistent one 25% of the time, and an entirely disjoint, wrong
   triangulation the remaining 66% of the time. Quantified prevalence: **zero failures in 20,000-trial fuzz sweeps
   at ambient dimension 2 and 5** (generic, not adversarially-minimized, point clouds) but **~1-in-170 at ambient
   dimension 4 with 20-30 points** — ordinary-looking inputs, not just contrived minimal counterexamples. A real
   fix needs joint near-tie detection across all competing candidates before committing to one, using a tie-break
   convention consistent with DQP's own (Bland's-rule) one — a genuine algorithm change, out of scope for a
   bounded bug fix. This mirrors DQP's own already-accepted "conservative exclusion near thin Schur complements"
   limitation in spirit (see the reverted "commit anyway" fix above) — same category of problem, same reason not
   to chase a fragile threshold-based patch.
   This means **Helix is not a fully reliable ground truth for automated cross-validation fuzzing on point clouds
   with ambient dimension ≥ 4 and no assurance against near-cospherical local structure** —
   `AlphaCrossValidationSpec`'s broad `forAll`-based DQP-vs-Helix comparisons are deliberately *not* wired into
   `sbt test` (kept as `unsafeCompare`/`unsafeFuzzCompare` diagnostic methods instead — a real Helix bug would
   otherwise masquerade as a DQP regression or vice versa). `specs2`'s `pendingUntilFixed` was tried for this and
   rejected: it's for a deterministically-known-failing example, and flags an unexpected *pass* as itself a
   failure — wrong semantics for a bug that only triggers probabilistically.

**Degeneracy hazard worth knowing before "fixing" complex sizes that look too big:** in degenerate (cospherical)
position the alpha complex is *not* a Delaunay subcomplex. `k` cospherical sites sharing a Voronoi vertex
contribute a `(k-1)`-simplex — e.g. a unit grid in the plane produces 3-simplices (one per unit square), a unit
grid in R³ produces 7-simplices (one per unit cube). Truncating at the ambient dimension gives the *wrong homotopy
type*, not merely a truncated one. CGAL/GUDHI users will not expect this; it is correct, not a bug. The
`AlphaValidationSpec` grid test is a mild version of exactly this: two near-collinear rows produce two
"sliver" simplices with circumradius ~40-60x the point cloud's diameter, which both backends correctly include.

Honest framing to keep when discussing this work: the paper's own benchmarks are mixed against Ripser (loses on
2 of 4 persistence examples) and against qhull-based Delaunay on some inputs. The genuine value proposition is
high ambient dimension (where Delaunay is infeasible), exact homology rather than persistence diagrams, and much
smaller complexes than Vietoris–Rips when data sits near a low-dimensional subspace — not raw speed.

## MATLAB API

`org.appliedtopology.tda4j.matlab` (`Tda4j.scala`, `PersistenceResult.scala`) is a Java-facing facade for calling
this library from MATLAB via MATLAB's built-in Java interface (`javaaddpath` + the `sbt assembly` fat jar). Every
public method takes/returns only `double`, `int`, `String`, `double[][]`, or `String[]` — no `java.util.Map`, no
generics, nothing Scala-specific — on explicit instruction from the project lead, who rejected an initial
`Map<String,Object>`-based design as unusable from MATLAB. Options are a flat alternating key/value `String[]`
(`{"engine","naive","maxDimension","3"}`) rather than fixed parameters, specifically so new options never change a
method's call signature. `Tda4j.computeFromPoints`/`computeFromDistanceMatrix` dispatch across `complex`
(`vr`/`alpha`), `engine` (`ripser`/`naive`/`chunks`, with `alpha` refusing `ripser` and `chunks` — the latter
because `complex=alpha` + `engine=chunks` is the exact combination `HomologySpec`'s `BarcodeRegressionSpec` stays
`skipAll`'d for), and coefficient field (`Z` — a prime finite field, default `prime=2`, matching the TDA research
literature's own convention — or `R`, `Field.DoubleApproximated`, which is what this codebase's *own* existing
cross-validation specs default to instead; a deliberate, known divergence, not an oversight). `PersistenceResult`
is non-generic on purpose: it eagerly converts to plain `int`/`double` arrays for the barcode itself
(`toArray()`, N-by-3: dimension/birth/death) and lazily, via a captured closure, for representative-chain access
(`cycleVertices`/`cycleCoefficients`), throwing `UnsupportedOperationException` rather than returning something
empty when an engine genuinely has no chain to report (`engine=chunks` always; `engine=ripser` for a bar resolved
via the apparent-pairs shortcut). Boundary-matrix export is designed for (a second such closure) but not yet
implemented.

**Two real bugs were found and fixed via this facade's own cross-validation, not inherited from either engine.**
First: the `engine=naive` VR path originally built `EnumeratingCofaceSimplexStream` directly, which has no
dimension cap of its own (only a filtration-value one — CLAUDE.md's `.iterator` notes above cover why its
`iterateDimension` is merely bounded by `d < metricSpace.size`) — so despite `maxDimension` defaulting to 2 and
being threaded correctly to `engine=ripser`/`chunks`, the naive path silently computed through higher dimensions
than requested. Caught by `Tda4jSpec` checking that `engine=ripser` and `engine=naive` agree through the facade on
a fixed point cloud (they didn't). Fixing this alone — wrapping the stream in
`LimitedCofaceSimplexStream(rawStream, maxDimension)`, the same wrapping `RipserCohomologySpec`'s own `naiveBars`
helper already uses — made the two engines *agree*, but not *correct*: **computing H_k correctly requires
(k+1)-dimensional chains** (H_k = ker(∂_k)/im(∂_{k+1}); with no (k+1)-chains at all there's no way to tell a
genuine k-cycle from one a not-yet-built (k+1)-simplex would have killed), so building only to `maxDimension` and
reporting its own top dimension makes every top-dimension class look essential *by construction* regardless of
whether it actually is — a well-known truncation artifact, not engine-specific behavior, per the project lead
after reviewing the first fix. Corrected by building to `maxDimension + 1` internally for `complex=vr` (across all
three VR engines) and dropping bars at the requested-and-beyond dimension from what's reported, keeping
`"maxDimension"` as the public option name (still the intuitive "give me H_0..H_k" reading) while its internal
meaning shifted from "highest simplex dimension to build" to "highest homological degree to report."
`complex=alpha` needed no equivalent change — an alpha complex's chain complex terminates on its own (see the
degeneracy-hazard note above), so it's never artificially cut short by this option to begin with, and its own top
dimension is genuine information rather than scaffolding. `Tda4jSpec` pins the fix as a discriminating regression
(the old, un-corrected construction is asserted to actually disagree with the corrected one on the same cloud, not
just re-checked for self-consistency). See `WORKLOG-matlab-api.md` for the full account, including the MATLAB-side
spikes attempted (fat-jar build succeeded; confirming MATLAB's own bundled JVM version and its actual
`double[][]`/`String[]` marshalling behavior could not be completed from this environment and remain open, not
silently assumed to work).

## Session practices

**Any session that does a substantial investigation or debugging arc (tracking down a root cause, fixing more
than one related bug, a profiling/benchmarking pass) should write a `WORKLOG-<topic>.md` in `.claude/`**
(alongside this file), even without an explicit ask to keep one going — this has been asked for repeatedly and
should be the default, not something the project lead has to remember to request each time. See
[[tda4j-worklog-convention]] / the existing `.claude/WORKLOG-*.md` files for the expected shape: a point-in-time
record of what was tried, what the root cause turned out to be, and what's still open, kept separate from
`CLAUDE.md` (which reflects only the final shipped state, updated at the end of the arc). Worklogs are not
retroactively edited later.

## Collaboration preferences

The project lead values intellectual honesty and direct pushback over agreement — say plainly when an approach is
a dead end, when benchmarks are mixed, or when a deliverable is unverified, rather than softening it. This has
been well received repeatedly on exactly this alpha-complex work (mixed paper benchmarks, an uncompiled
deliverable, a drifted reference oracle, bugs in existing code, a "fix" that turned out to be numerically unsafe
and had to be reverted) — don't reflexively hedge findings like these.
