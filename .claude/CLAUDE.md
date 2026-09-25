# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**How this file works.** Each entry is a current rule, invariant, or known limitation, plus a pointer to the
`.claude/WORKLOG-*.md`/`DESIGN-*.md` that holds its derivation (what was tried, measurements, repros). Derivations
go in the worklog, not here. This file was condensed on 2026-09-22 from a ~190k-char version (preserved at commit
`06a55dd`) and again on 2026-09-25 from a ~75k-char version (preserved at commit `b8739a8`) — `git show
<commit>:.claude/CLAUDE.md` for either full text.

## What this is

TDA4j is a Scala 3 library for persistent homology and topological data analysis (a spiritual successor to
JavaPlex/Ripser, from the Stanford Computational Topology workgroup lineage). Single sbt module, root package
`org.appliedtopology.tda4j`, pre-1.0 (`0.1.3-SNAPSHOT`), actively evolving API.

## Package layout

Source/test directories mirror package names; file names mostly carry over from the old flat layout
(`WORKLOG-package-reorg.md`), with a few later renames/moves to fix a file's content drifting from its name
(`RipserStream.scala` → `SimplexIndexing.scala`; `CubicalHomologyContext` moved from `streams` to `homology`).

- `algebra` — `RingModule`, `Field`, `FiniteField`, `Chain` (plus the `Cell`/`OrderedCell`/`OrderedBasis`
  contracts), `SSetElement` (degeneracy words + `insertOuter`/`faceOf`).
- `cells` — `Simplex`/`SimplexOps`/`SimplexOrderedCell`, `Cubical`/`CubicalOrderedCell`, `SimplicialSet`
  (`FiniteSimplicialSet`, with `.product`/`.coproduct`/`.quotient`/`.identify` on its companion object, also in
  `SimplicialSet.scala`), `SimplicialSetConstructions` (shared ordering helpers those draw on).
- `streams` — `SimplexStream`, `FiniteMetricSpace`, `VietorisRips`, `Cofacets`, `SimplexIndexing`, `CubicalStream`,
  `CubicalImage`, `UnionFind` (also defines `Kruskal`, which is metric-space-specific — hence
  here, and why no `util` package exists), `SimplicialSetStream`, `FilteredSimplicialSetStream`, `CechStream`.
- `homology` — `Homology` (four engines, including `CubicalHomologyContext`), `PackedRipserCohomology`, `Cohomology`
  (`CellularCohomologyContext`). The package graph is acyclic: `streams` never depends on `homology`.
- `barcode` — `Barcode`. `alpha` — `AlphaShapes`, `AlphaComplexDQP`. `unicode` — `PrintingHelper` (unused).
- `matlab` — MATLAB facade. `io` — `CSV`, `Ripser`, `Dipha`, `Gudhi`, `Perseus` (leaf package).
  `cli` — `TDA4jConf`, `TDA4jCLI` (thin translator over `matlab.TDA4j`/`io`).
- root — `package.scala` (`TDAContext`, a top-level class, thin user facade); test side `APISpec.scala`, kept flat
  as a cross-cutting test.

Cross-package references use `import org.appliedtopology.tda4j.<pkg>.{given, *}` — **the `given` matters**: a plain
`import pkg.*` does NOT import `given` instances in Scala 3, and this codebase's `Ordering`/`RingModule`/`Field`
instances are all givens. Broad wildcard imports are deliberate (mirroring the old same-package visibility).

## Commands

Java 21, Scala 3.9.0 LTS (`WORKLOG-scala-3.9-lts-upgrade.md`). `.jvmopts` sets `-Xmx2G` — the old test-suite OOMs
were sbt's own 1024MB default, not code (`WORKLOG-test-suite-memory-and-benchmark-gating.md`).

```
sbt clean test                  # full test suite (what CI runs)
sbt "testOnly *SimplexSpec"     # single specs2 spec (glob ok)
sbt scalafmtAll                 # format everything — run before committing
sbt scalafmtCheck scalafmtSbtCheck   # what CI's lint job checks (check only, no autofix)
sbt mimaReportBinaryIssues      # binary compat (CI test job)
sbt laikaSite                   # docs site (src/docs) -> target/docs/site, linked scaladoc included
sbt assembly                    # fat jar for CLI/MATLAB
sbt -DrunBenchmarks=true test   # also run benchmark/profiling specs — NOT what CI runs
```

No linter beyond scalafmt. Tests are specs2 (`org.specs2.mutable.Specification`). CI: `test.yml` (test + mima),
`lint.yml` (scalafmt), `docs.yml` (Laika → GitHub Pages, push to `scala` only). `build.sbt` permanently enables
`-feature -deprecation -unchecked` etc.; the ~319 `-Wunused:all` warnings (mostly unused wildcard imports) are
deliberately left alone (`WORKLOG-compiler-warnings.md`).

**`sbt scalafmtSbt`/`scalafmtSbtCheck` cover `project/*.scala` (sbt's own Scala 2.12 meta-build), not this
project's Scala 3.9** — `.scalafmt.conf`'s global `runner.dialect = scala3` also reaches these files by default
and will rewrite valid Scala 2 syntax into forms the meta-build compiler can't parse, breaking `sbt` itself.
`project/SnipDirective.scala` carries a `// format: off` guard against exactly this (a `fileOverride` glob was
tried first and did not take effect — don't re-attempt without confirming it works); see that file's own header
comment for the full story.

**Docs site is Laika (Paradox fully removed)**, sources at `src/docs/`, Markdown with a `@:directive` syntax (not
Paradox's `@@`/`@ref:`). `Markdown.GitHubFlavor` and `laika.config.SyntaxHighlighting` are both required
`laikaExtensions` — without them un-fenced code silently parses as prose (any `[...]` becomes a dangling link
reference and fails the build). `project/SnipDirective.scala` implements `@:snip(path, tag)` (extracts the region
between two `// #tag` marker lines from a real source file at build time). Each directory needing a non-
alphabetical left-nav order needs its own `directory.conf` with `laika.navigationOrder`.
`src/docs/default.template.html` overrides Helium's default template to add `@:breadcrumb`. Full derivation:
`WORKLOG-laika-migration.md`.

**Never run two `sbt` invocations against this checkout at once** — the incremental compiler's own class-file
writes from one process can be read mid-update by the other, producing a `NoClassDefFoundError` that looks like a
real regression but disappears on a clean, sequential rerun.

**Benchmark specs** (`ProfilingSpec`, `ApparentPairsBenchmarkSpec`, `CubicalBenchmarkSpec`, `SparseRipsBenchmarkSpec`,
`DimensionCeilingBenchmarkSpec`, `EngineComparisonBenchmarkSpec`, `RipserPaperBenchmarkSpec`, all in `homology`)
print timing tables rather than assert; only an exception counts as a failure. All seven `skipAll` unless
`-DrunBenchmarks=true` (a JVM system property, not specs2 `--` syntax); scope with `testOnly`
(`EngineComparisonBenchmarkSpec` can take 15+ min; `RipserPaperBenchmarkSpec` also needs `-DdataDir`, optionally
`-DripserBin=<path>` — see `.claude/scripts/run-ripser-paper-benchmark.sh`). `SingleEngineProfileDriver`/
`CubicalProfileDriver`/`VRLowDimProfileDriver` are kept one-engine-per-JVM profiling drivers.

**`HomologySpec`'s `BarcodeRegressionSpec` is `skipAll`'d unconditionally and NOT on this flag**: chunks x
`AlphaShapeDQP` on its own generator range produces enormous complexes (40 points/dim 4 → 102,090 simplices) that
stall/OOM. Don't un-skip without bounding the scale problem (`WORKLOG-benchmark-and-chunks-bug.md`).

## Scala style used throughout

Uses Scala 3.7+'s newest context-abstraction syntax — don't "correct" it to older idioms:

- `Type is TypeClass` for context bounds/givens (`Chain[CellT, CoefficientT] is RingModule`).
- `type Self: Ordering as ordering` — named context-bound aliasing inside trait bodies.
- Unicode algebra operators: `⊠` (scalar action), `∆(...)` (simplex literal), `<*`, `|*|` (`RingModule.scala`/
  `Field.scala`).
- `opaque type Simplex[VertexT] = SortedSet[VertexT]` / `opaque type Cube = Vector[Int]` — no runtime wrapper; API
  is extension methods.
- Prefer `Option` over sentinel values (e.g. `maxFiltrationValue: Option[Double] = None`). A default can't
  reference an earlier parameter in the *same* list (`-source:future`), and curried parameter lists would force
  `()` at every call site — `None` + `.getOrElse(...)` inside is the pattern.
- A method's own `[T: Ordering, C: Field]`-style context bounds desugar to a `using` clause appended AFTER every
  explicit parameter list — so a default value earlier in that same signature cannot reference the given that
  default itself needs. No workaround short of every caller passing the value explicitly, or restructuring the
  signature so the context bound is a `using` clause of its own, ahead of that parameter (`Chain.reduceByUntil`).

**Opaque-type extension methods** (`WORKLOG-extension-companion-objects.md`): extensions whose receiver is the
opaque type live in its companion (`object Simplex`/`object Cube`), so different opaque types can reuse names. Two
hazards: (1) opaque transparency is file-scoped, so same-file code calling the type's extensions by dot-syntax
breaks or silently hits the underlying type's member — hence `simplexIsOrderedCell`/`cubeIsOrderedCell` live in
separate files; (2) a companion extension can lose to a same-named stdlib extension from a wildcard import
(`math.Ordering.Implicits.*`'s `min`/`max`) — so `min`/`max` stay top-level. `asSimplex`/`asCube` are top-level
because their receiver is the raw `SortedSet`/`Vector`.

**specs2 gotcha**: in a class mixing `ScalaCheck`, give a `Seq[Simplex[_]]` an explicit type ascription before
`.forall` — otherwise it can resolve to specs2's `ValueCheck` extension with confusing errors.

## Naming convention: `tda4j`/`TDA4j`, never `Tda4j`

`tda4j` (lowercase) for package, artifact, executable, repo, prose; `TDA4j` for Scala identifiers and headings
(`TDA4j`, `TDA4jConf`, `TDA4jCLI`). `Tda4j` is never correct — a real, repeated drift; watch for it on any new class.
Same rule for every acronym identifier: `CSV`, `CLI` (not `Csv`, `Cli`). `Gudhi`/`Dipha`/`Ripser`/`Perseus` are
proper nouns (external projects' own spellings), correctly titlecased.

## Architecture

**Design principle (project lead, foundational — hold every engine/optimization against it, not just speed)**:
every homology implementation should (a) be generic over `Field` coefficients and (b) return representatives (a
real chain witnessing each bar). An optimization that abandons representatives is probably not worth it. Every
public interface (MATLAB facade included) should expose representatives; anywhere that doesn't is incomplete.
**Current gaps**: none known — every engine, including `PackedRipserCohomologyContext`'s apparent-pairs shortcut,
now records a representative for every bar.

### Algebraic core

- `RingModule`/`Field`: minimal typeclasses built with `is` syntax; everything downstream is generic over `Field`.
  `FiniteField`: `Fp` opaque type per prime `p`.
- `Chain[CellT, CoefficientT]`: formal sum backed by a `PriorityQueue` ordered by cell (leading term = cheap peek).
  Defines `reduceBy`/`reduceByUntil` (with an optional `fallback` for pivots needing on-the-fly substitution) and
  a `RingModule` instance. `reduceLoop` uses a `mutable.TreeMap` accumulator (use `z.head`, not `headOption`, which
  allocates an iterator on `mutable.TreeMap`). Known footgun, not fixed: `Chain` overrides `equals` without a
  matching `hashCode` — don't put `Chain`s in a `Set`/`Map` key.
- `Cell`/`OrderedCell`/`OrderedBasis`: anything with a `boundary` and a total order plugs into homology. Concrete
  instances: `Simplex`, `Cube`, `FiniteSimplicialSet` generators.
- `Cocell`/`OrderedCocell` were **removed on purpose**: coboundary is extrinsic (depends on the ambient complex),
  so a per-cell `coboundary` is the wrong shape. Don't reintroduce (`DESIGN-generic-cohomology.md`).
- **Generic-`given` capture gotcha**: a `given` like `chainRM` resolves its implicit `Ordering[CellT]` once, where
  it's summoned. Summoned at class scope (before the stream's filtration ordering exists) it silently pivots on
  lexicographic order. Summon it where the per-stream ordering is in scope (`WORKLOG-naive-homology.md`).
  `TDAContext`'s class-scope `chainIsRingModule` is user-arithmetic convenience only, never used by an engine.

### Streams: the ordering contract (the #1 historical bug source)

`SimplexStream.scala` defines `CellStream`/`SimplexStream`/`Filtration`/`StratifiedCellStream`: cells in
filtration order, a `filtrationValue` partial function, a `Filterable` (±∞ sentinels), and (stratified)
`iterateDimension`. Rules every stream must satisfy — violations have caused the "reduction pivot ... was not a
recorded open class" `IllegalStateException` at least five times, or worse, a silently different barcode:

1. **`filtrationOrdering` is a total order with the primary key reversed**: smaller-under-the-ordering = younger.
   Build it with `FiltrationOrdering.canonical(filtrationValue, dim, tieBreak)` — filtration value reversed, then
   dimension, then `tieBreak` (colex via `simplexIndexing` for VR, matching Ripser's Def 3.2) — rather than
   hand-rolling the comparator; every stream in this codebase goes through this one combinator
   (`WORKLOG-code-critique.md`). Reverse *only* the primary key — `.reverse` on a whole ascending ordering also
   flips the dimension tie-break. No tie-break at all = tied cells collide as one `SortedMap` key.
2. **`iterateDimension` bucket order must be `.sorted(using filtrationOrdering.reverse)`** — the *same* `Ordering`
   object, never an independently-built comparator. Two individually-valid orders disagreeing on ties breaks
   Algorithm 1's shared-order precondition.
3. **Monotone**: `fv(face) <= fv(coface)`, exactly (see Cech's ULP clamp below).
4. **`iterateDimension`'s domain is contiguous from 0 and bounded** (`isDefinedAt` false past the top). `.iterator`
   is `Iterator.from(0).takeWhile(isDefinedAt).flatMap(iterateDimension)`; an always-true domain never terminates.
5. **`EnumeratingCofaceSimplexStream.currentDimension` defaults to `-1`, not `0`** — `0` was indistinguishable
   from "dimension 0 was genuinely computed and cached," so a direct out-of-order `iterateDimension(d)` call
   could silently read stale/empty cache instead of rebuilding. Regression-pinned in `CofaceSimplexStreamSpec`
   (`WORKLOG-sheehy-rips.md`); still prefer `.iterator` for driving a stream.

Checks for a new/changed stream: cross-validate against an independent stream/engine *cell-for-cell* and use
tie-heavy fixtures; the `totalBarsAccountForAllCells` invariant alone is weaker. Filtration values consulted by
`Chain` comparisons must be cheap: `EnumeratingCofaceSimplexStream` and `CubicalGridStream` memoize them
(`WORKLOG-autonomous-session-2026-09-19.md`).

**VR constructions** (all same output contract, alternate engines): `EnumeratingCofaceSimplexStream`,
`RipserCofaceSimplexStream` (+ `SimplexIndexing`), `InorderCofaceSimplexStream`,
`RecursiveStackVietorisRipsSimplexStream`, `IncrementalVietorisRipsSimplexStream` (Rieser's New-VR, arXiv:2301.07191
— cross-validation baseline, not a fast engine); `CofacetIterator` for lazy coboundaries. `FiniteMetricSpace` has a
VP-tree (`jvptree`) implementation and `SparseMetricSpace` (returns +∞ past its cutoff rather than excluding —
don't use it to build a thresholded oracle).

**`maxFiltrationValue` defaults to `metricSpace.minimumEnclosingRadius`** (Ripser's own `enclosing_radius`) in the
Enumerating/Ripser/Inorder/Incremental streams and both Ripser engines — bars past that radius are dropped. Pass
`Some(Double.PositiveInfinity)` for untruncated. `RecursiveStackVietorisRipsSimplexStream` and alpha streams
deliberately don't get this default (`WORKLOG-mst-and-perf.md`).

### Persistent homology: four independent engines

Independent implementations sharing `Chain` primitives — a fix in one doesn't imply the others need it.
**`maxDim`/`maxDimension` means "top homological degree reported" everywhere it exists** (engines internally build
one dimension higher; `WORKLOG-maxdim-semantics-fix.md`). The naive engine and `CellularCohomologyContext` have no
such parameter: callers truncate the stream (`LimitedCofaceSimplexStream(stream, k + 1)`) and drop `dim == k + 1`
bars. Over a field, cohomology and homology barcodes coincide.

1. **`CellularHomologyContext`/`SimplicialHomologyContext` (naive)** — single-pivot-table boundary reduction, no
   clearing. The reference baseline others are validated against. Incremental (`advanceOne`/`advanceTo`/
   `advanceAll`, `diagramAt`/`barcodeAt`); `barcodeAt` returns real representatives via V-columns. A raw-`UnionFind`
   fast path was measured and rejected — after memoizing VR filtration values, what remains is general `Chain`
   cost (`WORKLOG-autonomous-session-2026-09-19.md`).
2. **`CellularPersistenceInChunksContext[CellT, CoefficientT]` (chunks)** — clear-and-compress chunked algorithm.
   Walks `0..maxDim+1` internally, filters essential bars to `<= maxDim`. Dimensions 0/1 resolved up front by raw
   union-find (`unionFindDim01`, safe because a fixed total order determines a unique reduced matrix,
   `DESIGN-unionfind-in-chunks.md`). `barcodeAt` gives a real representative via memoized `vcolOf` (derived
   term-for-term from the naive engine's V-column formula) — a delegate design running a second full naive
   engine was **rejected by the project lead**, don't revive it (`WORKLOG-chunks-representatives-incremental.md`).
   Invariants from fixed bugs: a paired cell must never become a pivot; `compress` runs to a fixpoint via
   `reduceByUntil`; `compress`/`globalReduce` share `eliminationFallback`; a reconciliation step resolves every
   "in limbo" cell first (`WORKLOG-benchmark-and-chunks-bug.md`, `WORKLOG-chunks-pairing-bug.md`). A parallel
   redesign was attempted and invalidated by measurement (`WORKLOG-parallelization-survey.md`).
3. **`RipserCohomologyContext`** — Bauer's Ripser (arXiv:1908.02518) on `Simplex[Int]` VR, one-shot. **Test/reference
   oracle only** — production call sites use `PackedRipserCohomologyContext` (MATLAB `engine=ripser`); not
   independent of `SimplexIndexing` (the naive engine is the independent oracle). Both share
   (`WORKLOG-cohomology.md`, `WORKLOG-lazy-enumeration.md`): clearing is required for correctness, not an
   optional speedup; apparent pairs (Def 3.2/Prop 3.9) with lazy substitution (a mutual apparent pair skips
   `coboundaryOf(sigma)`; a later reduction hitting `tau` recomputes the full coboundary via `Chain.reduceBy`'s
   `fallback`, uncached, exactly like `ripser.cpp` — the `zero*` helpers use full unrestricted iterators, NOT the
   restricted, false-negative-prone `Cofacets.apparentVertex`; emergent pairs, Def 3.11, not implemented);
   `insertionDiameter` (O(d) incremental cofacet diameter) avoids a filtration-value cache
   (`memoizeFiltrationValue` defaults **false** on the project lead's instruction, memory frugality over speed).
   Perf: the "quick win" tier is exhausted; still ~19–64x behind real `ripser.cpp` on `sphere3_*`, gap growing
   with n — compare against **vanilla** `github.com/Ripser/ripser` only (`WORKLOG-ripser-profiling.md`,
   `WORKLOG-ripser-comparison.md`, `WORKLOG-packed-ripser-engine.md`).
4. **`CellularCohomologyContext`** (`Cohomology.scala`) — persistent cohomology generic over `CellT: OrderedCell`,
   for fully-materialized streams: builds the coboundary relation by inverting each cell's `boundary`. No `maxDim`,
   no apparent pairs. Only **essential** bars' V-columns are cocycles (`coboundaryOfChain(rep).isZero()`); finite
   bars' V-columns equal their reduced pivot chain. Representatives are **not** expected to match
   `RipserCohomologyContext` term-for-term (tie direction on `fv=0` differs) but bar values do match. Sign-tested
   on RP² over `Fp(3)` (`WORKLOG-generic-cohomology.md`).

Testing lessons that apply to every engine: F2 hides sign errors (use `Double` or `Fp(3)`) — and signed-field
fixtures must include simplices with **≥5 vertices**: `Set1..Set4` iterate in insertion order, so anything
accidentally routed through an unordered `Set` looks right up to 4 elements and is hash-ordered from 5 on
(`SimplexBoundarySpec`, `SignedFieldBarcodeSpec`, `WORKLOG-code-critique.md` §1.1). Torsion-free F3-vs-F2 barcode
agreement is a cheap sign oracle. Agreement between two engines isn't proof when both share a truncation or code
path — hand-derived fixtures (e.g. `HomologyFixtures.elderRuleExpected`) are the real oracle.

`Barcode.scala`: `BarcodeEndpoint` (open/closed/±∞), `PersistenceBar`, algebra on finitely-presented persistence
modules.

**`BarcodeDistance`/`Vectorization`** (same package): bottleneck/Wasserstein distance and persistence
landscapes/images, `PersistenceBar[Double, _]`-specialized (every real engine here produces `Double`; a metric
needs real arithmetic). Ground-norm/aggregation convention matches Hera/GUDHI (`internal_p`/`order`);
persistence-image construction matches `scikit-tda/persim`. Essential bars: for distance, matched only to each
other by sorted birth (mismatched count → `+Infinity`); for vectorization, included by landscapes but dropped by
images — a deliberate per-method difference. `matlab.PersistenceResult` exposes both; CLI (`--distance-to`)
mirrors only the distance (vectorizations don't fit the CLI's diagram-shaped output model).
`WORKLOG-bottleneck-wasserstein-vectorizations.md`.

**`homology.CircularCoordinates`** (de Silva-Morozov-Vejdemo-Johansson 2011): `h1Bars` lists persistent H¹
`(birth,death)` by persistence descending (required first call to pick `r`); `compute(metricSpace, r,
cocycleIndex, prime=47, ...)` fixes `r` and computes cohomology of the *static* truncated complex `K_r` directly
(essential there by construction). A `K_r`-essential class is matched back to its full-filtration bar by birth
value alone. Cohomology runs over an odd prime field (never `p=2`); the integer lift is checked **exactly**
against every triangle, throwing `NoIntegerCocycleException` rather than silently coordinatizing a mirage.
Harmonic smoothing solves matrix-free via `commons-math3` `ConjugateGradient`, restricted to the cocycle's own
connected component, one vertex anchored at `g=0`. Output is directly `theta(v) = frac(g(v))` — no
path-integration step. MATLAB facade mirrors this; deliberately no CLI mirror (picking `r` is inherently
two-step and data-dependent). `WORKLOG-circular-coordinates.md`.

### Cross-engine benchmark

`EngineComparisonBenchmarkSpec` times every (construction x engine) pairing across point count/dimension/`maxDim`,
construction and reduction timed separately, per-cell timeout on daemon threads. Alpha and VR bar counts are
never compared (circumradius vs diameter).

## Cubical complexes

`WORKLOG-cubical.md`. `Cube` = `opaque type Cube = Vector[Int]` in KMM doubled-coordinate encoding (`2a`
degenerate, `2a+1` = `[a,a+1]`). `Vector`, not an array — structural `equals`/`hashCode` required. Boundary sign
alternates by the axis's **rank among non-degenerate axes**, not raw position (invisible over F2;
`CubicalSpec`'s dd=0 runs over F3).

`CubicalGridStream`: dense T-construction (GUDHI/DIPHA/Perseus convention). `topCellValue` per pixel/voxel; lower
cubes take the min over containing top cells, guaranteeing monotonicity. `filtrationOrdering` copies
`EnumeratingCofaceSimplexStream`'s shape. `ExplicitCubicalStream` for sparse/hand-built complexes (same
`FiltrationOrdering.canonical` combinator, not an independent copy). Sublevel/superlevel handled only in
`CubicalImage.scala`'s loaders (negate on load). `CubicalImage.fromFlatArray` (row-major, last axis fastest) is
the core; H0 oracle uses Moore (8/26-connected) adjacency, not 4-connected.

Both naive and chunks engines consume cubes. A grid-exploiting engine dedicated to **3D** (CubicalRipser,
Wagner-Chen-Vuçini) remains a valid future direction (`DESIGN-fast-cubical-engine.md`,
`WORKLOG-cubical-chunks-benchmark.md`); every dimension `>= 2` now has a fast(er) option — see below.

**`FastCubicalHomologyContext` (`homology/FastCubicalHomology.scala`, `engine="fast-cubical"`)** — Flash Cubical
(Le Breton-Szustakowski-Piraud, arXiv:2606.04801), an original derivation (no reference implementation to port).
**Valid at any ambient dimension `>= 2`**. Top cells become vertices of a DUAL graph, codimension-1 cells become
dual edges (`∞` sentinel for the grid's outer boundary), and primal `H_{d-1}` of the sublevel filtration is
ordinary `H_0` of that dual graph's own SUPERLEVEL filtration (Alexander duality) via the same elder-rule
union-find `unionFindDim01` uses, run in descending primal order with endpoints swapped; combined with a primal
`H_0` union-find, this covers a 2D grid completely (`H_2 ≡ 0` for any planar subset) with no `Chain` reduction.
**`∞` must be the unconditional elder of any merge it takes part in — checked explicitly, not inferred from
`birthOf(∞)` being largest**: a real top cell can also carry `topValue = +Infinity` and tie against it — see the
worklog before touching the young/old decision in `computeDualTopDimension`. Representatives: a running signed
sum of top cells per active dual component, oriented via each merge's connecting facet's own `±1` boundary
coefficients. `WORKLOG-fast-cubical-engine.md`.

**At ambient dimension `>= 3`, a hybrid with `chunks` handles the residual "middle" dimensions**
(`.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`): `H_0`/`H_{d-1}` stay the two union-finds above, and
dimensions `1..d-2` (no duality shortcut exists) go to `CellularPersistenceInChunksContext` run on a
`LimitedCubicalGridStream` view that hides the real top-dimensional cells entirely. `chunks`'s own pre-existing
`maxDim = d-2` semantics already discards the incomplete bars a truncation would otherwise wrongly leave open.
Cross-validated against the naive engine at `d=3` plus one `d=4` smoke test; **not** validated at `d >= 5`, and
the win shrinks with `d` by design.

## Simplicial sets

`WORKLOG-simplicial-sets.md`, `WORKLOG-simplicial-set-constructions.md`, `WORKLOG-simplicial-set-filtration.md`.

- Eilenberg–Zilber presentation: non-degenerate generators per dimension, plus per generator `faces: G =>
  IndexedSeq[SSetElement[G]]`. `SSetElement(word, target)`: degeneracy word in normal form is **strictly
  decreasing** (`s_0 s_0 = s_1 s_0` → `[1,0]`); `Nil` = bare generator.
- `insertOuter`/`faceOf` implement the simplicial identities on arbitrary elements; `validate()` checks structure
  then `d_i d_j = d_{j-1} d_i` — necessary, not sufficient; verify intended topology via homology.
- `finiteSimplicialSetIsOrderedCell`: normalized chain complex boundary (only bare faces contribute), depends on
  the set's own `faces` — thread it explicitly, never an ambient global given.
- **`FiniteSimplicialSet[G]`'s `using Ordering[G]` clause comes AFTER its value parameters, not before**:
  `using`-first broke constructor call sites' own type inference for `G` (the compiler silently unified `G` with
  whatever `Ordering` was found first in scope). `using`-first is safe only when the type parameter is already
  fixed some other way.
- `SimplicialSetStream`: constant-0 filtration; `filtrationOrdering` is dimension ascending then `Ordering[G]`.
  `FilteredSimplicialSetStream`: real `StratifiedCellStream[G, Double]`, same ordering convention as VR.
- `fromStream` takes `CellStream[Simplex[VertexT], ?]` (VR coface streams are not `SimplexStream`s).
- `product`: `(X×Y)_n = X_n × Y_n`, pair non-degenerate iff words' index sets are disjoint — **not** EZ shuffles.
  Face maps strip the common degeneracy set `J` and **relabel** survivors via rank, not delete. `coproduct`:
  `Left`/`Right` tags.
- `quotient(sset, quotientMap: G => SSetElement[G])` — degenerate targets needed (RP² from a triangle collapses
  an edge to `s_0(v)`); must resolve in **one step** to fixed points (`require`d). `identify(pairs)` is the
  union-find ergonomic layer (own union-find in `cells`; `streams.UnionFind` would be a backwards dependency).
- Fixtures (`SimplicialSetFixtures`): `minimalSphere(n)`, `realProjectiveSpace(2|3)` (RP² is the sign
  discriminator over F2 vs F3), `torus`, `triangle`/`realProjectiveSpaceViaQuotient`. No MATLAB/CLI entry for
  simplicial sets (needs its own encoding design).

## Cech complexes

`streams/CechStream.scala`, `WORKLOG-cech-complex.md`. Over `Simplex[Int]`, built on the VR coface machinery via
`filtrationValueOverride` (valid because Cech is downward-closed). Radius = Miniball minimum enclosing ball.
`CechFiltration` caches every radius and **clamps each to the max of its facets'** — Miniball can be one ULP
non-monotone. New-VR's pruning and packed Ripser (proven for diameter only) do **not** carry over; naive/chunks/
cohomology only.

## Witness complexes

`streams/WitnessStream.scala`, `WORKLOG-witness-complex.md`. De Silva-Carlsson 2004, checked directly against
JavaPlex's own Java source. `LandmarkSelector.maxmin`/`.random` pick a landmark subset of a
`FiniteMetricSpace[Int]`; `maxmin` also exposes each chosen point's own insertion radius
(`LandmarkSelection.insertionRadius`) and excludes already-chosen points from its own tie-break candidates
(`WORKLOG-sheehy-rips.md`).

Two independent variants, both `Simplex[Int]` over LOCAL landmark indices, both built on
`RipserCofaceSimplexStream` unchanged:
- **`LazyWitnessSimplexStream`**: IS a flag complex by definition, so `WitnessMetricSpace` reifies its edge
  weights as a `FiniteMetricSpace[Int]` (NOT a real metric — never hand it to `JVPTree`/`SparseMetricSpace`/
  `alpha`). `PackedRipserCohomologyContext` (proven only for VR diameters) is *also* valid here — the one
  exception. `nu ∈ {0,1,2}`, default 2. `maxFiltrationValue` defaults to `minimumEnclosingRadius`.
- **`WitnessCofaceSimplexStream`** (general): NOT a flag complex — `filtrationValueOverride` computes a
  **recursive** `max(own_k(σ), max over σ's own facets)`, `TrieMap`-memoized, making "the complex at threshold R"
  automatically downward-closed. Refuses `engine=ripser`/`chunks`; `maxFiltrationValue` defaults to `+Infinity`.
  The general complex's own 1-skeleton is provably identical to the lazy complex's at `nu=2`.

## Dowker complexes

`streams/DowkerStream.scala`, `WORKLOG-dowker-complex.md`. `DowkerGeometry(relation: Array[Array[Double]])`: a fully
general `R: L x W -> [0, Infinity]`, not derived from any metric (generalizes witness's `nu=0` case, which is exactly
this formula with `R` = the landmark-to-witness distance matrix — not implemented by delegating to `WitnessGeometry`,
independently re-derived instead, since that class's shape doesn't fit a relation with no shared ambient space).
`filtrationValue(sigma) = min_w max_{x in sigma} R(x,w)` is automatically monotone (proved directly from the formula,
no recursive facet clamp needed, unlike witness's per-dimension `m_k`); NOT a flag complex in general, so built on
`RipserCofaceSimplexStream`'s generic coface loop like Cech/general-witness, not the flag-specific machinery.
`.fromBoolean` lifts a classical (unfiltered) relation (`true`→`0.0`, `false`→`+Infinity`).

**`keptByThresholdAndCriterion`'s `<=` admits `+Infinity <= +Infinity`** — every other stream's `maxFiltrationValue
= +Infinity` default is safe only because none of them ever compute a genuinely infinite filtration value; Dowker's
boolean encoding does, on purpose, to mean "never witnessed." `DowkerCofaceSimplexStream` overrides
`keptByThresholdAndCriterion` to additionally require `.isFinite` — without it, an untruncated stream silently
collapses to the complete simplex on every vertex (confirmed empirically, `WORKLOG-dowker-complex.md`).

**Duality is the point** (`.dual`, via `DowkerGeometry.dual` — the transpose relation): the functorial Dowker
duality theorem (Chowdhury & Mémoli 2018) gives the X-side and Y-side persistence modules as naturally isomorphic,
so their barcodes agree exactly — **but only after dropping zero-persistence (birth == death) bars from both**: a
simplicial filtration records exactly one `H_0` birth per vertex, so when `numLeft != numWitnesses` the raw
barcodes can't match bar-for-bar even in principle; the "extra" births are always zero-persistence
(`DowkerStreamSpec.dropZeroPersistence`, confirmed on a hand-worked rectangular relation, not just asserted from
the theorem).

**Not yet wired into `matlab.TDA4j`/`cli`/docs** — this is a streams-layer-only capability so far; see
`WORKLOG-dowker-complex.md`'s own "explicitly not done" section before assuming a `complex=dowker` option exists.

## Alpha complex: DQP vs Helix

`WORKLOG-alpha-complex.md`, `HANDOFF-alpha-complex.md`. `AlphaShapes(points, dispatch)`: `"default"` → `"helix"`
(`HelixDelaunay`); `"DQP"` must be explicit. Alpha and VR/Ripser are separate sections with minimal interaction
(project lead's standing call). Never resurrect the ripped-out Miniball-Delaunay backend.

`AlphaComplexDQP` implements Carlsson & Carlsson, Sci. Rep. 14:19824 (2024), with a DAQP-style dual active-set QP;
`CholeskyWorkspace` is a hand-rolled incremental Cholesky update/downdate. Filtration values are squared radii
internally; `radiusOf` takes sqrt so units match Helix. `AlphaShapeDQP` is always untruncated; use
`AlphaComplexDQP.euclidean(points, maxRadius, ...)` for truncation.

**Settled numerical decisions in `DualQP.solve` — don't retune:**
- `rankTolerance = 1e-6` (safe range `[1e-7, 1e-5]`; smaller poisons the Cholesky factor and cycles forever,
  `1e-4` gives wrong answers).
- Ratio-test ties broken by **global** constraint index (Bland's rule), not working-set position.
- **Accepted limitation**: small Schur complement with no swappable inequality → candidate treated as infeasible.
  "Commit anyway" was tried and reverted: no fixed threshold separates safe from catastrophic.
- Vertex filtration value is `-space.weight(x)` **only when `x` lies inside its own restricted power cell `V_x`**
  — not a `0.0` default. When some Cech-neighbour dominates `x`, the correct value is the **minimum over `x`'s
  own incident, already-solved edges** (dimension 1 solved before 0 for exactly this reason); a vertex with no
  incident edges is genuinely hidden and **dropped from the complex entirely**. Found via DTM weights.
  `AlphaComplexDQPVertexAttachmentSpec`, `WORKLOG-dtm-filtrations.md`.
- Regressions pinned in `AlphaComplexDQPRegressionSpec`/`AlphaValidationSpec`; property suite uses
  `minTestsOk = 2000`.

**HelixDelaunay's bootstrap crash is fixed** (`assert(validated.nonEmpty)`, `.claude/WORKLOG-helix-bootstrap-fix.md`)
— three independent, additive fixes: (1) the hull-supporting-hyperplane refinement now retries EVERY
affinely-independent candidate subset it can form (ordered by smallest total pairwise span), not just the first
found greedily; (2) the "find a hull-supporting hyperplane" loop rejects an affinely-DEGENERATE candidate simplex
outright rather than handing it to `Hyperplane.from` (whose SVD-based normal extraction is under-determined for
such input); (3) a globally coplanar point cloud (own affine rank < declared ambient dimension) is transparently
projected onto an orthonormal basis of its own true affine span before construction — exact, not approximate, a
no-op for already-full-rank input. All three route rank computation through one shared `rankAtEpsilon` helper
tied to `epsilon.epsilon` (`1e-5`) — `SingularValueDecomposition.getRank`'s default tolerance is far tighter and
missed genuinely-degenerate-at-this-tolerance input.

**One root mechanism (near-cospherical clusters making the frontier walk's own facet-pivot choices
order-dependent — `HelixDelaunayBuilder`'s own class doc, "Accepted limitation") produces two DIFFERENT outcomes,
one WONTFIX and one fixed** — do not conflate them; a naive `dqpSet -- helixSet` diff alone cannot tell them
apart (`.claude/WORKLOG-helix-bootstrap-fix.md`):
1. **Order-dependent disagreement with DQP, still WONTFIX** (project lead: "I'm okay with C persisting as a
   WONTFIX issue"): when a facet has more than one legitimately-empty-circumsphere candidate coface,
   `visitedFacets` locks in whichever the walk finds first and never reconsiders — but the discarded side is
   reachable some other way too, so Helix's own output, though different from DQP's, is still a complete,
   internally-consistent triangulation. Helix is **not reliable ground truth** for dim ≥ 4 fuzzing;
   `AlphaCrossValidationSpec`'s comparisons stay as `unsafeCompare`/`unsafeFuzzCompare` diagnostics, not wired
   into `sbt test`.
2. **Genuine incomplete triangulation (a real topological hole), fixed.** Same lock, but here the discarded side
   is reachable NO OTHER WAY, so a whole local neighborhood is permanently lost — a real nonzero
   `H_{ambientDim-1}` on the full unfiltered complex. Self-consistency (not a diff against DQP) is the
   discriminator — a naive-diff check alone conflates the two (measured 2/8 genuine holes in one sweep). Two
   compounding bugs: (a) a structural exclusion bug in `handleCosphericalPoints`'s own facet-queue construction
   made the originating facet itself unreachable from its own local search (fixed — iterate every vertex of the
   new simplex, not just the triggering facet's own); (b) `handleCosphericalPoints`'s greedy point-pull has no
   empty-circumsphere check and no joint near-tie consideration (genuinely NOT fixed — same out-of-scope
   symbolic-perturbation redesign as case 1). Worked around, not root-fixed, at the repair layer:
   `requireValidTriangulation`'s jitter-and-recompute repair (see below) now also triggers on a genuine void
   detected on the RAW, unrepaired output — `HelixDelaunay.interiorVoidVertices` returns the essential
   representative's own vertex support as a targeted jitter seed.

**`FastAlphaHomologyContext` (`homology/FastAlphaHomology.scala`)** — `FastCubicalHomologyContext`'s own dual
union-find, ported to `HelixDelaunay`'s top simplices; **valid at any ambient dimension `>= 2`**, `HelixDelaunay`
only (never `AlphaShapeDQP`, which builds no adjacency structure so can't supply the dual graph's "every facet
has ≤2 cofaces" precondition). That precondition is NOT guaranteed by construction (unlike a cubical grid) —
measured noticeably more likely at higher ambient dimension and with more points — validated explicitly, throwing
the named `FastAlphaTriangulationException` rather than building a silently-wrong dual graph. A facet's own
dual-edge value must come from `HelixDelaunay.filtrationValue` directly, never recomputed as `min` over containing
top simplices (unlike cubical, these can genuinely differ). At ambient dimension `>= 3`, the same
hybrid-with-`chunks` extension as `FastCubicalHomologyContext` handles middle dimensions `1 <= k <= d-2` via
`PersistenceInChunksContext[Int, C]` on a new `alpha.LimitedAlphaShapesStream` view — cross-validated at `d=3`
against the naive engine (the facet-multiplicity risk needed its own fresh `d=3` measurement, materially higher
than `d=2`, not assumed to carry over). `WORKLOG-alpha-dual-unionfind.md`, `DESIGN-alpha-dual-unionfind.md`,
`.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`.

**Wired into `matlab.TDA4j`/`cli` as `engine="fast-alpha"`/`--engine fast-alpha`** (valid only for
`complex=alpha` with `alphaBackend=helix`, any ambient dimension `>= 2`) — project lead signed off on shipping
the measured exception rate as a production option, given the clear exception message.

**`HelixDelaunay(pts, seed, requireValidTriangulation = true)` repairs a real facet-multiplicity violation**
(`.claude/DESIGN-helix-triangulation-repair.md`) — off by default, zero behavior change to the unflagged
constructor. Two earlier designs were tried and rejected: coning the conflicting region from an arbitrary apex
(fails — the discarded region's own boundary isn't a simple cycle); pruning each over-claimed facet down to its
two smallest-circumradius claimants, discard-without-replacement (empirically shown wrong — can punch a genuine
interior hole through the mesh). The shipped design nudges only the near-tied vertices by a small random
perturbation and re-runs `HelixDelaunayBuilder` — the same already-tested global algorithm — on the full (mostly
unperturbed) point set, then recomputes every resulting simplex's circumsphere from the ORIGINAL coordinates
("simulation of simplicity"). **Its own first version had the identical failure mode as the rejected pruning
design** (a real ~10.5% barcode-disagreement rate at `d=3`) — the "no facet has `>2` claimants" self-check alone
was necessary but not sufficient, because the builder, re-run on jittered coordinates, could silently fail to
place a tetrahedron's second coface, leaving a facet that looks like an ordinary hull facet but is actually a
gap. Fixed with a second, independent self-check, `HelixDelaunay.interiorVoidVertices` (`Option[Set[Int]]` —
returns the essential `H_{d-1}` representative's own vertex support when a void exists, a far more targeted
retry seed than a facet-count heuristic): a genuine Delaunay triangulation's convex hull is convex, hence
contractible, so the full unfiltered complex's own `H_{d-1}` must be trivial. **Later extended to trigger on
this same void check on the RAW, unrepaired input too, not just after a facet-multiplicity-driven retry** —
fixes the genuine-incompleteness case above. Not attempted at `d>=4` (HelixDelaunay already documented above as
unreliable there for unrelated reasons). A third avenue (recognizing violations as textbook Delaunay diagonal
flips / Radon-partition bistellar flips) was investigated and found promising but not implemented — a possible
future alternative.

**Degeneracy hazard**: in cospherical position the alpha complex is not a Delaunay subcomplex — `k` cospherical
sites give a `(k-1)`-simplex (unit grid in R² → 3-simplices). Truncating at ambient dimension gives the wrong
homotopy type. Correct, not a bug.

Honest framing: the paper's benchmarks are mixed vs Ripser and qhull; the value is high ambient dimension, exact
homology, and small complexes near low-dimensional data — not raw speed.

## DTM-based filtrations

`streams/DistanceToMeasure.scala`, `streams/DtmRipsStream.scala`, `alpha.AlphaComplexDQP.dtm`,
`WORKLOG-dtm-filtrations.md`. `streams.DistanceToMeasure(metricSpace, k, q=2)`: Chazal-Cohen-Steiner-Merigot 2011,
generic over any `FiniteMetricSpace[Int]`. `k` is **self-inclusive** (verified against GUDHI byte-for-byte) —
`k=1` gives `f=0` everywhere. Defaults to `streams.BruteForce` for k-NN, not `JVPTree` (VP-tree pruning assumes
the triangle inequality, which not every `FiniteMetricSpace` here satisfies).

**`streams.DtmRipsSimplexStream`** (Anai et al., arXiv:1811.04757, Def. 3.1/Prop. 3.5): doubled units. `p ∈
{1.0, 2.0}`; `p=1` (default) checked byte-for-byte against GUDHI's `DTMRipsComplex`; `p=2` exists only as the
cross-validation device against `AlphaComplexDQP.dtm`. **The first coface stream in this codebase with nonzero,
distinct vertex filtration values** — overrides `case 0` explicitly. `maxFiltrationValue` defaults to the
reified metric space's own `minimumEnclosingRadius`, proven safe for both `p` values. Refuses `engine=ripser` in
`matlab.TDA4j`.

**`alpha.AlphaComplexDQP.dtm`**: `weight(i) = -f(i)²`, the `p=2` ball equation through the pre-existing
weighted-alpha/power-distance machinery — derived, not copied from any external source. Cross-checked against
`DtmRipsSimplexStream(p=2)`'s H0 via the persistent nerve lemma, NOT bar-for-bar (alpha correctly delays/omits
vertices Rips can't; see the worklog for the full derivation).

## Sheehy's sparse/approximate Vietoris-Rips filtration

`streams/SheehyRipsStream.scala`, `WORKLOG-sheehy-rips.md`. `SheehyRipsSimplexStream` implements
Cavanna-Jahanseir-Sheehy 2015 (arXiv:1506.03797) — the two papers' `epsilon` values are **not** comparable.
Deliberately `O(n²)` (every pairwise `edgeBirth` materialized directly), not the paper's own `O(n log n)`
neighbor-search — a smaller complex to *reduce*, not a faster one to *build*. Built on `LandmarkSelector.maxmin`
run to full size, extended to also expose each point's own insertion radius. One memoized
`filtrationValueOverride` handles every dimension ≥ 1 uniformly (the `min`-over-vertices `vanish` exclusion check
needs every vertex of a simplex at once).

**CJS 2015's own Algorithm 3 omits a check Section 5.3's own definition requires** (a `min`-over-vertices
`vanish` clamp) — `edgeBirth` here applies that clamp to every edge, pinned by a hand-derived triangle fixture.

Units doubled; reduces to plain VR exactly at a SMALL `epsilon` (not large). `maxFiltrationValue` is
unconditionally clamped to `maxFiniteFiltrationValue` even when the caller passes `Some(Double.PositiveInfinity)`
— plain IEEE-754 `<=` would otherwise admit every excluded pair's own `+Infinity`. Refuses `engine=ripser`;
`naive`/`chunks`/`cohomology` wired through `matlab.TDA4j complex=sheehy-rips` (needs `sheehyEpsilon`) and `cli`
`--sheehy-epsilon`.

## Flag-complex edge collapse

`streams/EdgeCollapseStream.scala`, `WORKLOG-edge-collapse.md`. `EdgeCollapse.collapse` implements
Boissonnat-Pritam (SoCG 2020) + Glisse-Pritam (SoCG 2022): reduces a VR filtration's 1-skeleton to a smaller
weighted graph with the SAME persistent homology at every level. An edge `{u,v}` is dominated by `w` iff every
common neighbor of `u,v` is also adjacent to `w`, verified directly against GUDHI's own
`Flag_complex_edge_collapser.h`. **Removes dominated EDGES, never vertices** — vertex domination is strong
collapse (`arXiv:1809.10945`), a different construction. A dominated edge's entry is pushed forward to the
largest time it stays dominated, or removed if that never breaks. Reified as `EdgeCollapsedMetricSpace` — drop-in
for `EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream`. Representatives transfer through inclusion for
free. `minimumEnclosingRadius` is overridden to the bound the collapse itself used; the initial edge-admission
check is `d.isFinite && d <= bound`, not just `d <= bound`.

**This is a faithful port of the reference algorithm's own single-pass, descending-filtration-value,
live-mutating-state structure — not an independent redesign.** An independently-designed "fixed-point iteration"
first draft was tried and PROVEN WRONG by barcode cross-validation (two distinct bugs, each silently turning a
real bar essential on a 5-point counterexample). The processing order is load-bearing — re-derive nothing here
without rereading the reference source first.

Vertices are never removed, so enumeration cost does NOT drop uniformly; reduction cost benefits substantially
regardless of engine (measured 73–76% of edges removed, 43–47x reduction-phase speedup on random clouds,
`EdgeCollapseBenchmarkSpec`). Wired through `matlab.TDA4j`'s `edgeCollapse` option (`complex=vr` only) and `cli`
`--edge-collapse`.

## File I/O

`io`, `WORKLOG-io-module.md`. Every format was verified against its project's primary source; unverified formats
(Perseus simplicial toplex, PHAT, sparse triplet distance matrices) are deliberately not implemented.
- Ripser binary distance matrices are **float32**, not double.
- DIPHA/Perseus cubical axis order is **first axis fastest** — opposite `fromFlatArray`; readers/writers reverse
  shape.
- Diagram readers rebuild finite bars via `PersistenceBar.apply(dim, lower, upper)` so round trips compare equal.
  Perseus `-1` → `+∞` (missing cell).

## CLI executable

`cli`, `WORKLOG-cli-executable.md`. `sbt assembly` → `java -jar target/scala-3.9.0/TDA4j-<version>-assembly.jar`.
Scallop (zero deps). Every compute flag mirrors a `matlab.TDA4j` option key 1:1 with **no Scallop default** —
omitted keys let `TDA4j` apply its own defaults (one source of truth). `--output-format=perseus` is refused for
non-integral filtrations. `TDA4jCLI.run(args, out): Int` is testable in-process, but Scallop's default `onError`
calls `System.exit` on any parse error or `--help`/`--version` — `CLISpec` must never pass malformed flags.
Scallop `opt[Boolean]` has always-supplied toggle semantics (`WORKLOG-naming-and-dispatch-expansion.md`).
`--distance-to` (+ `--distance-format`/`-order`/`-ground-norm`) is the one exception to the "every flag mirrors a
compute option" framing — it mirrors `barcode.BarcodeDistance` instead; only `--output-format=text` is supported
with it.

## MATLAB API

`matlab` (`TDA4j.scala`, `PersistenceResult.scala`), `WORKLOG-matlab-api.md`. Java-facing facade: public methods
take/return only `double`, `int`, `String`, `double[][]`, `String[]` — no `Map`, generics, or Scala types
(project lead rejected a `Map`-based design). Options are a flat key/value `String[]`. `dispatch` parses each
option exactly once into a private `ComplexKind`/`EngineKind`/`CoefficientKind` enum before anything else runs,
and dispatches via `PersistenceEngine.naive`/`.chunks`/`.cohomology` rather than re-matching the raw string at
each branch.
- `computeFromPoints`/`computeFromDistanceMatrix`: `complex` = `vr`/`alpha`/`cech`/`witness`/`dtm-rips`/`dtm-alpha`/
  `sheehy-rips`; `engine` = `ripser`/`naive`/`chunks`/`cohomology` (Alpha and dtm-alpha refuse `ripser`/`chunks`;
  Cech, dtm-rips, sheehy-rips, and witness/general refuse `ripser`, witness/general also refuses `chunks` — see
  `persistence-engines.md`'s streams-vs-engines table for the full picture). `dtm-rips`/`dtm-alpha` need `dtmK`;
  `sheehy-rips` needs `sheehyEpsilon` (strictly in `(0,1)`); `dtm-rips`/`sheehy-rips` alone work from
  `computeFromDistanceMatrix` too. A sixth `engine`, `fast-alpha`, is valid ONLY for `complex=alpha` with
  `alphaBackend=helix`, any ambient dimension `>= 2`. `computeFromCubicalImage`/`computeFromImage` — same four
  base `engine` values plus `fast-cubical`, refused only for a degenerate 1-axis image.
- **Two-step witness recipe** (`WORKLOG-witness-two-step-api.md`): `selectLandmarksFrom{Points,DistanceMatrix}` →
  `LandmarkSelectionResult`, then `computeFrom{Points,DistanceMatrix}AndLandmarks` (takes that `int[]` directly,
  never re-selects); `coveringRadiusFrom{Points,DistanceMatrix}` queries R for a hand-picked set. CLI mirror:
  `--select-landmarks`/`--landmarks-file`.
- `maxDimension` (default 2) = top homological degree. `ripser`/`chunks` pass it straight through; `naive`/
  `cohomology` wrap the stream in `LimitedCofaceSimplexStream(..., k + 1)` and drop `dim == k + 1` bars. Alpha
  needs no +1.
- Field: `Z` (prime field, default `prime=2`) or `R` (`Field.DoubleApproximated`, what internal specs default
  to) — deliberate divergence.
- `PersistenceResult`: `toArray()` eagerly; `cycleVertices`/`cycleCoefficients` lazily. `cycleVertices` throws
  `UnsupportedOperationException` if a bar has no recorded representative — every engine now records one for
  every bar, so this indicates an engine bug, not an expected gap.
- **Boundary-matrix export**: `numCells`/`boundaryRows`/`boundaryCols`/`boundaryValues`/`columnDimension`/
  `columnVertices`/`columnFiltrationValue`, lazy (one shared thunk per `complex` branch, reused across every
  `engine` — confirmed byte-identical across engines).
- **Circular coordinates**: `h1Bars(points)`/`circularCoordinates(points, r[, cocycleIndex, prime])` →
  `CircularCoordinatesResult`, own small entry points rather than a `complex=` value. No CLI mirror.
- Unverified: MATLAB's bundled JVM version and actual `double[][]`/`String[]`/`int[]` marshalling.

## Session practices

- **Write a `.claude/WORKLOG-<topic>.md` by default** for any substantial investigation, debugging, or
  profiling arc, without being asked. Worklogs are point-in-time snapshots, never retroactively edited. At the
  end of the arc, update this file with **only the resulting rule/invariant/limitation plus a worklog pointer**
  — no narrative, measurements, or repros here. Keep this file under ~40k characters; when it drifts past that,
  condense it the same way (strip narrative to worklog pointers) and note the new condensing date/commit at top.
- Performance claims need isolated A/B measurement (`git stash` A/B, median of trials, one engine per JVM);
  machine noise here often exceeds small effects — report unconfirmed effects as unconfirmed.
- **Finalizing a user-visible capability** (new complex, engine, or option) means checking four surfaces each
  session that lands a chunk of it: (1) `matlab.TDA4j` dispatch, (2) `cli.TDA4jCLI`/`TDA4jConf` (1:1 mirror),
  (3) `src/docs/developers-guide/` (`persistence-engines.md`, `architecture.md`, `class-diagrams.md`),
  (4) `src/docs/user-guide/README.md`. Internal refactors and bug fixes with no new surface are exempt.
- The project lead commits their own work; don't commit unasked.

## Collaboration preferences

The project lead values intellectual honesty and direct pushback over agreement — say plainly when an approach is
a dead end, when benchmarks are mixed, or when a deliverable is unverified, rather than softening it. This has been
well received repeatedly (mixed paper benchmarks, uncompiled deliverables, drifted oracles, bugs in existing code,
"fixes" that had to be reverted) — don't reflexively hedge findings like these.
