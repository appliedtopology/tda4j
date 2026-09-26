# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**How this file works.** Each entry is a current rule, invariant, or known limitation, plus a pointer to the
`.claude/WORKLOG-*.md`/`DESIGN-*.md` that holds its derivation (what was tried, measurements, repros). Derivations
go in the worklog, not here. This file was condensed on 2026-09-22 from a ~190k-char version (commit `06a55dd`),
2026-09-25 from a ~75k-char version (commit `b8739a8`), and 2026-09-26 from a ~56k-char version (commit
`e5e86ec`) — `git show <commit>:.claude/CLAUDE.md` for any of those full texts.

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

If `sbt` isn't on `PATH` in this environment, see `.claude/scripts/install-sbt.sh` (bootstraps the launcher and
paces around Maven Central's cold-cache rate limiting — `.claude/WORKLOG-toroidal-coordinates.md`'s own
environment note has the story).

No linter beyond scalafmt. Tests are specs2 (`org.specs2.mutable.Specification`). CI: `test.yml` (test + mima),
`lint.yml` (scalafmt), `docs.yml` (Laika → GitHub Pages, push to `scala` only). The ~319 `-Wunused:all` warnings
(mostly unused wildcard imports) are deliberately left alone (`WORKLOG-compiler-warnings.md`).

**`sbt scalafmtSbt`/`scalafmtSbtCheck` cover `project/*.scala` (sbt's own Scala 2.12 meta-build), not this
project's Scala 3.9** — `.scalafmt.conf`'s global `runner.dialect = scala3` also reaches these files and will
rewrite valid Scala 2 syntax into forms the meta-build compiler can't parse, breaking `sbt` itself.
`project/SnipDirective.scala` carries a `// format: off` guard against this (a `fileOverride` glob was tried
first and did not take effect — don't re-attempt without confirming it works).

**Docs site is Laika (Paradox fully removed)**, sources at `src/docs/`, Markdown with a `@:directive` syntax (not
Paradox's `@@`/`@ref:`). `Markdown.GitHubFlavor` and `laika.config.SyntaxHighlighting` are both required
`laikaExtensions` — without them un-fenced code silently parses as prose (any `[...]` becomes a dangling link
reference and fails the build). `project/SnipDirective.scala` implements `@:snip(path, tag)` (extracts the region
between two `// #tag` marker lines from a real source file). Each directory needing a non-alphabetical left-nav
order needs its own `directory.conf` with `laika.navigationOrder`. `WORKLOG-laika-migration.md`.

**Never run two `sbt` invocations against this checkout at once** — the incremental compiler's own class-file
writes from one process can be read mid-update by the other, producing a `NoClassDefFoundError` that looks like a
real regression but disappears on a clean, sequential rerun.

**Benchmark specs** (`ProfilingSpec`, `ApparentPairsBenchmarkSpec`, `CubicalBenchmarkSpec`, `SparseRipsBenchmarkSpec`,
`DimensionCeilingBenchmarkSpec`, `EngineComparisonBenchmarkSpec`, `RipserPaperBenchmarkSpec`, all in `homology`)
print timing tables rather than assert; only an exception counts as a failure. All seven `skipAll` unless
`-DrunBenchmarks=true` (a JVM system property, not specs2 `--` syntax); scope with `testOnly`
(`EngineComparisonBenchmarkSpec` can take 15+ min; `RipserPaperBenchmarkSpec` also needs `-DdataDir`, optionally
`-DripserBin=<path>` — see `.claude/scripts/run-ripser-paper-benchmark.sh`).

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

Checks for a new/changed stream: cross-validate against an independent stream/engine *cell-for-cell* with
tie-heavy fixtures; `totalBarsAccountForAllCells` alone is weaker. Filtration values consulted by `Chain`
comparisons must be cheap: `EnumeratingCofaceSimplexStream`/`CubicalGridStream` memoize them
(`WORKLOG-autonomous-session-2026-09-19.md`).

**VR constructions** (same output contract, alternate engines): `EnumeratingCofaceSimplexStream`,
`RipserCofaceSimplexStream` (+ `SimplexIndexing`), `InorderCofaceSimplexStream`,
`RecursiveStackVietorisRipsSimplexStream`, `IncrementalVietorisRipsSimplexStream` (Rieser's New-VR, arXiv:2301.07191
— cross-validation baseline, not a fast engine); `CofacetIterator` for lazy coboundaries. `FiniteMetricSpace` has
a VP-tree (`jvptree`) impl and `SparseMetricSpace` (+∞ past its cutoff rather than excluding — don't use it as a
thresholded oracle).

**`maxFiltrationValue` defaults to `metricSpace.minimumEnclosingRadius`** (Ripser's own `enclosing_radius`) in the
Enumerating/Ripser/Inorder/Incremental streams and both Ripser engines. Pass `Some(Double.PositiveInfinity)` for
untruncated. `RecursiveStackVietorisRipsSimplexStream` and alpha streams don't get this default
(`WORKLOG-mst-and-perf.md`).

### Persistent homology: four independent engines

Independent implementations sharing `Chain` primitives — a fix in one doesn't imply others need it. `maxDim`/
`maxDimension` = top homological degree everywhere (engines build one dimension higher internally;
`WORKLOG-maxdim-semantics-fix.md`). Naive and `CellularCohomologyContext` have no such param: callers truncate
via `LimitedCofaceSimplexStream(stream, k+1)` and drop `dim==k+1` bars. Over a field, cohomology/homology
barcodes coincide.

1. **Naive** (`CellularHomologyContext`/`SimplicialHomologyContext`) — single-pivot-table reduction, no clearing;
   the reference baseline. Incremental (`advanceOne`/`advanceTo`/`advanceAll`, `diagramAt`/`barcodeAt` via
   V-columns). A raw-UnionFind fast path was measured and rejected (`WORKLOG-autonomous-session-2026-09-19.md`).
2. **Chunks** (`CellularPersistenceInChunksContext`) — clear-and-compress chunked algorithm, walks `0..maxDim+1`,
   filters essentials to `<=maxDim`. Dims 0/1 via raw union-find (`unionFindDim01`, `DESIGN-unionfind-in-chunks.md`).
   `barcodeAt` via memoized `vcolOf` (a second full naive engine was **rejected by the project lead**, don't
   revive — `WORKLOG-chunks-representatives-incremental.md`). Invariants: a paired cell never becomes a pivot;
   `compress` runs to a fixpoint; a reconciliation step resolves "in limbo" cells first
   (`WORKLOG-benchmark-and-chunks-bug.md`, `WORKLOG-chunks-pairing-bug.md`). Parallel redesign attempted and
   invalidated by measurement (`WORKLOG-parallelization-survey.md`).
3. **`RipserCohomologyContext`** — Bauer's Ripser (arXiv:1908.02518) on `Simplex[Int]` VR, one-shot. **Test/
   reference oracle only** — production uses `PackedRipserCohomologyContext`. Clearing required for correctness;
   apparent pairs with lazy substitution; emergent pairs (Def 3.11) not implemented; `memoizeFiltrationValue`
   defaults **false** (project lead: memory over speed). ~19-64x behind vanilla `ripser.cpp`, gap growing with n
   (`WORKLOG-ripser-profiling.md`, `WORKLOG-ripser-comparison.md`, `WORKLOG-packed-ripser-engine.md`).
4. **`CellularCohomologyContext`** (`Cohomology.scala`) — generic over `CellT: OrderedCell`, fully-materialized
   streams only, no `maxDim`/apparent pairs. Only essential bars' V-columns are cocycles; finite bars' V-columns
   are their reduced pivot chain. Representatives don't match Ripser term-for-term (tie direction differs) but
   bar values do. Sign-tested on RP² over Fp(3) (`WORKLOG-generic-cohomology.md`).

Testing lessons for every engine: F2 hides sign errors; signed-field fixtures need ≥5 vertices (`Set1..Set4`
hash-order past 4 elements, `SimplexBoundarySpec`/`SignedFieldBarcodeSpec`, `WORKLOG-code-critique.md` §1.1).
F3-vs-F2 agreement is a cheap sign oracle. Two engines agreeing isn't proof if they share a truncation/code path
— hand-derived fixtures are the real oracle.

`Barcode.scala`: `BarcodeEndpoint` (open/closed/±∞), `PersistenceBar`, algebra on finitely-presented persistence
modules.

**`BarcodeDistance`/`Vectorization`**: bottleneck/Wasserstein distance and persistence landscapes/images,
`PersistenceBar[Double,_]`-specialized. Ground-norm/aggregation matches Hera/GUDHI; persistence-image matches
`scikit-tda/persim`. Essential bars: matched by sorted birth for distance (count mismatch → `+Infinity`);
included by landscapes but dropped by images. `matlab.PersistenceResult` exposes both; CLI `--distance-to`
mirrors only distance. `WORKLOG-bottleneck-wasserstein-vectorizations.md`.

**`homology.CircularCoordinates`** (de Silva-Morozov-Vejdemo-Johansson 2011): `h1Bars` lists persistent H¹
`(birth,death)` by persistence descending (pick `r` from this first); `compute(metricSpace, r, cocycleIndex,
prime=47,...)` computes cohomology of the *static* truncated complex `K_r` directly (essential there by
construction, matched to the full-filtration bar by birth value). Odd prime field only (p=2 can hide torsion);
integer lift checked **exactly** per triangle, `NoIntegerCocycleException` otherwise. Harmonic smoothing via
`commons-math3` `ConjugateGradient`, restricted to the cocycle's connected component, anchored at `g=0`;
`theta(v)=frac(g(v))` directly, no path integration. MATLAB mirrors this; no CLI (picking `r` is two-step/
data-dependent). `WORKLOG-circular-coordinates.md`.

**Toroidal coordinates** (`computeToroidal`, Scoccola-Gakhar-Bush-Schonsheck-Rask-Zhou-Perea 2022,
arXiv:2212.07201): combines `k` *simultaneously*-alive H¹ classes (common `r`, same connected component of
`K_r` — checked) into one torus-valued map, via `homology.LatticeReduction` (hand-rolled LLL on the classes'
harmonic-cochain Gram matrix's Cholesky factor, `delta=3/4`), applying the resulting unimodular `U` to the
already-computed per-class `theta`s (linearity of harmonic smoothing). Not a port of `scikit-tda/DREiMac`'s
`toroidalcoords.py`: its `_gram_schmidt` has a real orthogonalization bug (invisible at k=2, non-orthogonal
intermediate result at k≥3), but an end-to-end search found no case degrading `_lll`'s final output — see
`.claude/BUGS-IN-REFERENCES.md`, don't overclaim beyond what's checked there. MATLAB: `toroidalCoordinates`/
`ToroidalCoordinatesResult` (separate class, MiMa); no CLI. `WORKLOG-toroidal-coordinates.md`.

### Cross-engine benchmark

`EngineComparisonBenchmarkSpec` times every (construction x engine) pairing across point count/dimension/`maxDim`,
construction and reduction timed separately, per-cell timeout on daemon threads. Alpha and VR bar counts are
never compared (circumradius vs diameter).

## Cubical complexes

`WORKLOG-cubical.md`. `Cube` = `opaque type Cube = Vector[Int]` in KMM doubled-coordinate encoding (`2a`
degenerate, `2a+1` = `[a,a+1]`). `Vector`, not an array — structural `equals`/`hashCode` required. Boundary sign
alternates by the axis's **rank among non-degenerate axes**, not raw position (invisible over F2;
`CubicalSpec`'s dd=0 runs over F3).

`CubicalGridStream`: dense T-construction (GUDHI/DIPHA/Perseus convention); lower cubes take the min over
containing top cells (monotonicity). `ExplicitCubicalStream` for sparse/hand-built complexes (same
`FiltrationOrdering.canonical` combinator). Sublevel/superlevel handled only in `CubicalImage.scala`'s loaders.
`CubicalImage.fromFlatArray` row-major, last axis fastest; H0 oracle uses Moore (8/26-connected) adjacency.

Both naive and chunks engines consume cubes. A grid-exploiting **3D** engine (CubicalRipser, Wagner-Chen-Vuçini)
remains a valid future direction (`DESIGN-fast-cubical-engine.md`); every dimension `>= 2` now has a faster
option below.

**`FastCubicalHomologyContext`** (`engine="fast-cubical"`) — Flash Cubical (Le Breton-Szustakowski-Piraud,
arXiv:2606.04801), original derivation, valid any ambient dim `>= 2`. Top cells → dual graph vertices, codim-1
cells → dual edges (`∞` sentinel for the outer boundary); primal `H_{d-1}` of the sublevel filtration = ordinary
`H_0` of the dual's own SUPERLEVEL filtration (Alexander duality) via `unionFindDim01` run descending with
endpoints swapped; combined with a primal `H_0` union-find, covers a 2D grid completely with no `Chain`
reduction. **`∞` must be checked explicitly as unconditional elder of any merge, not inferred from `birthOf(∞)`
being largest** — a real top cell can tie against it (see worklog before touching `computeDualTopDimension`).
Representatives: running signed sum of top cells per dual component, oriented via each merge's facet boundary
coefficients. `WORKLOG-fast-cubical-engine.md`.

**At ambient dim `>= 3`**, `chunks` handles residual middle dimensions `1..d-2` (no duality shortcut) via
`CellularPersistenceInChunksContext` on a `LimitedCubicalGridStream` hiding real top cells (`chunks`'s own
`maxDim=d-2` already discards the incomplete bars this would otherwise wrongly leave open). Cross-validated at
d=3 + one d=4 smoke test; not validated d≥5, win shrinks with d by design.
`.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`.

## Simplicial sets

`WORKLOG-simplicial-sets.md`, `WORKLOG-simplicial-set-constructions.md`, `WORKLOG-simplicial-set-filtration.md`.

- Eilenberg–Zilber presentation: non-degenerate generators per dimension, plus per generator `faces: G =>
  IndexedSeq[SSetElement[G]]`. `SSetElement(word, target)`: degeneracy word in normal form is **strictly
  decreasing** (`s_0 s_0 = s_1 s_0` → `[1,0]`); `Nil` = bare generator. `insertOuter`/`faceOf` implement the
  simplicial identities on arbitrary elements; `validate()` checks `d_i d_j = d_{j-1} d_i` — necessary, not
  sufficient; verify intended topology via homology.
- `finiteSimplicialSetIsOrderedCell`: normalized chain complex boundary (bare faces only), depends on the set's
  own `faces` — thread explicitly, never an ambient global given.
- **`FiniteSimplicialSet[G]`'s `using Ordering[G]` clause comes AFTER its value parameters, not before**:
  `using`-first broke constructor call sites' type inference for `G` (silently unified with whatever `Ordering`
  was found first in scope) — safe only when `G` is already fixed some other way.
- `SimplicialSetStream`: constant-0 filtration, dimension-then-`Ordering[G]`. `FilteredSimplicialSetStream`: real
  `StratifiedCellStream[G, Double]`, same VR ordering convention. `fromStream` takes `CellStream[Simplex[VertexT],
  ?]` (VR coface streams aren't `SimplexStream`s).
- `product`: `(X×Y)_n = X_n × Y_n`, pair non-degenerate iff words' index sets are disjoint — **not** EZ shuffles;
  face maps strip the common degeneracy set and **relabel** survivors via rank, not delete. `coproduct`:
  `Left`/`Right` tags.
- `quotient(sset, quotientMap: G => SSetElement[G])` needs degenerate targets (RP² from a triangle collapses an
  edge to `s_0(v)`); must resolve in **one step** to fixed points (`require`d). `identify(pairs)` is the
  union-find ergonomic layer (own union-find in `cells`).
- Fixtures (`SimplicialSetFixtures`): `minimalSphere(n)`, `realProjectiveSpace(2|3)` (sign discriminator F2 vs
  F3), `torus`, `triangle`/`realProjectiveSpaceViaQuotient`. No MATLAB/CLI entry (needs its own encoding design).

## Cech complexes

`streams/CechStream.scala`, `WORKLOG-cech-complex.md`. Over `Simplex[Int]`, built on the VR coface machinery via
`filtrationValueOverride` (valid because Cech is downward-closed). Radius = Miniball minimum enclosing ball.
`CechFiltration` caches every radius and **clamps each to the max of its facets'** — Miniball can be one ULP
non-monotone. New-VR's pruning and packed Ripser (proven for diameter only) do **not** carry over; naive/chunks/
cohomology only.

## Witness complexes

`streams/WitnessStream.scala`, `WORKLOG-witness-complex.md`. De Silva-Carlsson 2004, checked against JavaPlex's
own Java source. `LandmarkSelector.maxmin`/`.random` pick a landmark subset of a `FiniteMetricSpace[Int]`;
`maxmin` also exposes each point's own insertion radius (`LandmarkSelection.insertionRadius`), excluding
already-chosen points from its own tie-break candidates (`WORKLOG-sheehy-rips.md`).

Two independent variants, both `Simplex[Int]` over LOCAL landmark indices, both on `RipserCofaceSimplexStream`
unchanged: **`LazyWitnessSimplexStream`** IS a flag complex, so `WitnessMetricSpace` reifies edge weights as a
`FiniteMetricSpace[Int]` (NOT a real metric — never hand to `JVPTree`/`SparseMetricSpace`/`alpha`);
`PackedRipserCohomologyContext` is *also* valid here (the one exception); `nu ∈ {0,1,2}`, default 2.
**`WitnessCofaceSimplexStream`** (general) is NOT a flag complex — `filtrationValueOverride` computes a
recursive `max(own_k(σ), max over facets)`, `TrieMap`-memoized; refuses `engine=ripser`/`chunks`,
`maxFiltrationValue` defaults `+Infinity`. Its 1-skeleton is provably identical to the lazy complex's at `nu=2`.

## Dowker complexes

`streams/DowkerStream.scala`, `WORKLOG-dowker-complex.md`. `DowkerGeometry(relation: Array[Array[Double]])`: a
fully general `R: L x W -> [0, Infinity]`, not metric-derived (generalizes witness's `nu=0` case, independently
re-derived — doesn't fit `WitnessGeometry`'s shared-ambient-space shape). `filtrationValue(sigma) = min_w
max_{x in sigma} R(x,w)` is automatically monotone; NOT a flag complex, built on `RipserCofaceSimplexStream`'s
generic coface loop. `.fromBoolean` lifts an unfiltered relation (`true`→`0.0`, `false`→`+Infinity`).

**`keptByThresholdAndCriterion`'s `<=` admits `+Infinity <= +Infinity`** — safe elsewhere only because no other
stream computes a genuinely infinite value; Dowker's boolean encoding does, on purpose ("never witnessed").
`DowkerCofaceSimplexStream` overrides it to additionally require `.isFinite` — without it, an untruncated stream
silently collapses to the complete simplex on every vertex (confirmed empirically).

**Duality is the point** (`.dual` — the transpose relation): the functorial Dowker duality theorem (Chowdhury &
Mémoli 2018) gives X-side/Y-side barcodes agreeing exactly **only after dropping zero-persistence bars from
both** (a simplicial filtration records exactly one `H_0` birth per vertex, so `numLeft != numWitnesses` can't
match bar-for-bar otherwise — confirmed on a hand-worked fixture, not just asserted from the theorem).

Own MATLAB/CLI entry point (`computeFromRelation`/`--input-format csv-relation`), not a `complex=` value — a
relation doesn't fit the point-cloud/distance-matrix dispatch. `engine` defaults `naive`, refuses `ripser`/
`chunks`; `dual`/`--dual` computes the W-side directly.

## Alpha complex: DQP vs Helix

`WORKLOG-alpha-complex.md`, `HANDOFF-alpha-complex.md`. `AlphaShapes(points, dispatch)`: `"default"` → `"helix"`
(`HelixDelaunay`); `"DQP"` must be explicit. Alpha and VR/Ripser are separate sections with minimal interaction
(project lead's standing call). Never resurrect the ripped-out Miniball-Delaunay backend.

`AlphaComplexDQP` implements Carlsson & Carlsson (Sci. Rep. 14:19824, 2024), DAQP-style dual active-set QP;
`CholeskyWorkspace` hand-rolled incremental Cholesky. Filtration values are squared radii internally; `radiusOf`
takes sqrt. `AlphaShapeDQP` always untruncated; `AlphaComplexDQP.euclidean(points, maxRadius, ...)` for truncation.

**Settled `DualQP.solve` numerics — don't retune**: `rankTolerance=1e-6` (safe `[1e-7,1e-5]`); ratio-test ties
broken by global constraint index (Bland's rule); small-Schur-complement-no-swappable-inequality → infeasible
(accepted limitation, "commit anyway" tried and reverted). Vertex filtration value is `-space.weight(x)` only
when `x` is inside its own restricted power cell `V_x`, else the min over `x`'s own incident already-solved
edges (dim 1 before 0), and a vertex with no incident edges is dropped entirely
(`AlphaComplexDQPVertexAttachmentSpec`, `WORKLOG-dtm-filtrations.md`). Regressions in
`AlphaComplexDQPRegressionSpec`/`AlphaValidationSpec` (`minTestsOk=2000`).

**HelixDelaunay's bootstrap crash is fixed** (`.claude/WORKLOG-helix-bootstrap-fix.md`) via three additive fixes
routed through one shared `rankAtEpsilon` helper: retry every affinely-independent hyperplane candidate
(smallest-span first); reject affinely-degenerate candidates outright; project a globally-coplanar cloud onto
its true affine span first.

**One root mechanism (near-cospherical clusters, order-dependent facet-pivot choices) produces two DIFFERENT
outcomes — don't conflate, a naive set-diff can't tell them apart**:
1. Order-dependent disagreement with DQP, **WONTFIX** (project lead) — the discarded side is reachable some
   other way too, still a complete triangulation. Helix is not reliable ground truth for dim≥4 fuzzing;
   `AlphaCrossValidationSpec` comparisons stay diagnostic (`unsafeCompare`/`unsafeFuzzCompare`).
2. Genuine incomplete triangulation (real topological hole), **fixed**. Self-consistency (not diff-vs-DQP) is
   the discriminator. Two compounding bugs: (a) `handleCosphericalPoints`'s facet-queue excluded the originating
   facet (fixed — iterate every vertex of the new simplex); (b) its greedy point-pull has no empty-circumsphere
   check (not fixed, out-of-scope symbolic-perturbation redesign). Worked around at the repair layer:
   `requireValidTriangulation`'s jitter-and-recompute also triggers on a genuine void on the RAW output.

**`FastAlphaHomologyContext`** — `FastCubicalHomologyContext`'s dual union-find ported to `HelixDelaunay`'s top
simplices; valid any ambient dim≥2, Helix only (DQP builds no adjacency structure). The "every facet ≤2 cofaces"
precondition is NOT guaranteed by construction (more likely violated at higher dim/more points) — validated
explicitly, throws `FastAlphaTriangulationException` rather than a silently-wrong dual graph. Facet dual-edge
value from `HelixDelaunay.filtrationValue` directly, never recomputed as min over top simplices. At dim≥3, same
chunks-hybrid as cubical on `alpha.LimitedAlphaShapesStream`; cross-validated at d=3. Wired as
`engine="fast-alpha"` (alpha+helix only, project lead signed off on the measured exception rate).
`WORKLOG-alpha-dual-unionfind.md`, `DESIGN-alpha-dual-unionfind.md`.

**`HelixDelaunay(pts, seed, requireValidTriangulation = true)` repairs facet-multiplicity violations** (off by
default) — nudges near-tied vertices, re-runs the same builder, recomputes circumspheres from ORIGINAL
coordinates ("simulation of simplicity"). Two earlier designs (coning from an apex; pruning to
smallest-circumradius claimants) were rejected (wrong boundary cycle / can punch a real hole). **Its own first
version had the identical failure mode as the rejected pruning design** (~10.5% barcode disagreement at d=3) —
facet-count self-check alone insufficient; fixed with a second check, `HelixDelaunay.interiorVoidVertices` (a
genuine Delaunay hull is convex hence contractible, so `H_{d-1}` of the full unfiltered complex must be trivial),
also triggered on RAW unrepaired input. Not attempted d≥4. `.claude/DESIGN-helix-triangulation-repair.md`.

**Degeneracy hazard**: cospherical `k` sites give a `(k-1)`-simplex (unit grid in R² → 3-simplices) — correct,
not a bug; truncating at ambient dimension gives the wrong homotopy type. Honest framing: paper's benchmarks
mixed vs Ripser/qhull; value is high ambient dimension + exact homology + small complexes, not raw speed.

## DTM-based filtrations

`streams/DistanceToMeasure.scala`, `streams/DtmRipsStream.scala`, `alpha.AlphaComplexDQP.dtm`,
`WORKLOG-dtm-filtrations.md`. `streams.DistanceToMeasure(metricSpace, k, q=2)`: Chazal-Cohen-Steiner-Merigot
2011, generic over any `FiniteMetricSpace[Int]`. `k` is **self-inclusive** (verified vs GUDHI byte-for-byte) —
`k=1` gives `f=0` everywhere. Defaults to `BruteForce` for k-NN, not `JVPTree` (triangle-inequality assumption
not universal here).

**`streams.DtmRipsSimplexStream`** (Anai et al., arXiv:1811.04757): doubled units, `p∈{1.0,2.0}`, p=1 (default)
checked byte-for-byte vs GUDHI's `DTMRipsComplex`, p=2 only for cross-validating `AlphaComplexDQP.dtm`. First
coface stream with nonzero distinct vertex filtration values (overrides `case 0` explicitly).
`maxFiltrationValue` defaults to `minimumEnclosingRadius`. Refuses `engine=ripser`.

**`alpha.AlphaComplexDQP.dtm`**: `weight(i)=-f(i)²`, derived not copied. Cross-checked against
`DtmRipsSimplexStream(p=2)`'s H0 via the persistent nerve lemma, not bar-for-bar (alpha correctly delays/omits
vertices Rips can't).

## Sheehy's sparse/approximate Vietoris-Rips filtration

`streams/SheehyRipsStream.scala`, `WORKLOG-sheehy-rips.md`. `SheehyRipsSimplexStream` implements
Cavanna-Jahanseir-Sheehy 2015 (arXiv:1506.03797) — the two papers' `epsilon` values are **not** comparable.
Deliberately `O(n²)` (every pairwise `edgeBirth` materialized directly), not the paper's own `O(n log n)`
neighbor-search — a smaller complex to *reduce*, not a faster one to *build*. Built on `LandmarkSelector.maxmin`
run to full size. One memoized `filtrationValueOverride` handles every dimension ≥ 1 uniformly (the
`min`-over-vertices `vanish` exclusion check needs every vertex at once).

**CJS 2015's own Algorithm 3 omits a check Section 5.3's own definition requires** (a `min`-over-vertices
`vanish` clamp) — `edgeBirth` here applies that clamp to every edge, pinned by a hand-derived triangle fixture.

Units doubled; reduces to plain VR exactly at a SMALL `epsilon` (not large). `maxFiltrationValue` is
unconditionally clamped to `maxFiniteFiltrationValue` even when the caller passes `+Infinity` — plain IEEE-754
`<=` would otherwise admit every excluded pair's own `+Infinity`. Refuses `engine=ripser`; wired through
`matlab.TDA4j complex=sheehy-rips` (needs `sheehyEpsilon`) and `cli --sheehy-epsilon`.

## Flag-complex edge collapse

`streams/EdgeCollapseStream.scala`, `WORKLOG-edge-collapse.md`. `EdgeCollapse.collapse` implements
Boissonnat-Pritam (SoCG 2020) + Glisse-Pritam (SoCG 2022): reduces a VR filtration's 1-skeleton to a smaller
weighted graph with the SAME persistent homology at every level. Edge `{u,v}` dominated by `w` iff every common
neighbor of u,v is also adjacent to w (verified vs GUDHI's `Flag_complex_edge_collapser.h`). **Removes dominated
EDGES, never vertices** (vertex domination = strong collapse, arXiv:1809.10945, different construction). Reified
as `EdgeCollapsedMetricSpace` — drop-in for Enumerating/RipserCofaceSimplexStream; representatives transfer
through inclusion free; `minimumEnclosingRadius` overridden to the collapse's own bound.

**Faithful port of the reference's own single-pass, descending, live-mutating-state structure — not an
independent redesign.** An independent "fixed-point iteration" draft was tried and PROVEN WRONG by barcode
cross-validation (two bugs, each silently turning a real bar essential on a 5-point counterexample). Processing
order is load-bearing — reread the reference before touching this.

Vertices never removed (enumeration cost doesn't drop uniformly); measured 73-76% edges removed, 43-47x
reduction-phase speedup (`EdgeCollapseBenchmarkSpec`). Wired through `matlab.TDA4j`'s `edgeCollapse` option
(`complex=vr` only) and `cli --edge-collapse`.

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
omitted keys let `TDA4j` apply its own defaults (one source of truth). `--output-format=perseus` refused for
non-integral filtrations. `TDA4jCLI.run(args, out): Int` is testable in-process, but Scallop's `onError` calls
`System.exit` on any parse error or `--help`/`--version` — `CLISpec` must never pass malformed flags. Scallop
`opt[Boolean]` has always-supplied toggle semantics (`WORKLOG-naming-and-dispatch-expansion.md`). `--distance-to`
(+`--distance-format`/`-order`/`-ground-norm`) mirrors `barcode.BarcodeDistance` instead; only
`--output-format=text` works with it.

## MATLAB API

`matlab` (`TDA4j.scala`, `PersistenceResult.scala`), `WORKLOG-matlab-api.md`. Java-facing facade: public methods
take/return only `double`, `int`, `String`, `double[][]`, `String[]` — no `Map`, generics, or Scala types
(project lead rejected a `Map`-based design). Options are a flat key/value `String[]`; `dispatch` parses each
once into a private `ComplexKind`/`EngineKind`/`CoefficientKind` enum before anything runs.
- `computeFromPoints`/`computeFromDistanceMatrix`: `complex` = `vr`/`alpha`/`cech`/`witness`/`dtm-rips`/`dtm-alpha`/
  `sheehy-rips`; `engine` = `ripser`/`naive`/`chunks`/`cohomology` (Alpha and dtm-alpha refuse `ripser`/`chunks`;
  Cech, dtm-rips, sheehy-rips, and witness/general refuse `ripser`, witness/general also refuses `chunks` — see
  `persistence-engines.md`'s streams-vs-engines table). `dtm-rips`/`dtm-alpha` need `dtmK`; `sheehy-rips` needs
  `sheehyEpsilon` (strictly `(0,1)`); those two alone also work from `computeFromDistanceMatrix`. A sixth
  `engine`, `fast-alpha`, is valid ONLY for `complex=alpha`+`alphaBackend=helix`. `computeFromCubicalImage`/
  `computeFromImage` — same four base engines plus `fast-cubical`.
- **Two-step witness recipe**: `selectLandmarksFrom{Points,DistanceMatrix}` → `LandmarkSelectionResult`, then
  `computeFrom{Points,DistanceMatrix}AndLandmarks` (takes that `int[]` directly, never re-selects);
  `coveringRadiusFrom{Points,DistanceMatrix}` queries R for a hand-picked set. CLI: `--select-landmarks`/
  `--landmarks-file`. `WORKLOG-witness-two-step-api.md`.
- `maxDimension` (default 2) = top homological degree. `ripser`/`chunks` pass it straight through; `naive`/
  `cohomology` wrap the stream in `LimitedCofaceSimplexStream(..., k+1)`. Alpha needs no +1.
- Field: `Z` (prime, default `prime=2`) or `R` (`Field.DoubleApproximated`, internal specs' own default).
- `PersistenceResult`: `toArray()` eager; `cycleVertices`/`cycleCoefficients` lazy, throwing
  `UnsupportedOperationException` for a bar with no representative (every engine records one now — an engine
  bug, not an expected gap).
- **Boundary-matrix export**: `numCells`/`boundaryRows`/`boundaryCols`/`boundaryValues`/`columnDimension`/
  `columnVertices`/`columnFiltrationValue`, lazy, byte-identical across engines.
- **Circular coordinates**: `h1Bars(points)`/`circularCoordinates(points, r[, cocycleIndex, prime])` →
  `CircularCoordinatesResult`. **Toroidal coordinates**: `toroidalCoordinates(points, r, cocycleIndices[, prime,
  reduce])` → `ToroidalCoordinatesResult` (separate class). Neither has a CLI mirror.
- Unverified: MATLAB's bundled JVM version and actual marshalling.

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
- A cloud session (working on its own `claude/...` branch) may commit and push its own work to that branch at
  will, without asking first — the branch is disposable/session-scoped, not shared history. A local/interactive
  session working directly on a shared branch still waits to be asked; the project lead commits that work.
- **Found a bug in someone else's paper or reference implementation while validating a tda4j feature against
  it?** Log it in `.claude/BUGS-IN-REFERENCES.md` (flat, never condensed away). State precisely what was verified
  — don't extrapolate an isolated bug into an end-to-end correctness claim without a repro that actually shows
  that (a real miss, corrected — `WORKLOG-toroidal-coordinates.md`). Two entries so far: CJS 2015 (Sheehy-Rips)
  and DREiMac's `_gram_schmidt` (toroidal coordinates).
- **This environment may start with no `sbt` and no dependency cache** — `.claude/scripts/install-sbt.sh`
  bootstraps it. Add its contents to the environment's own setup script (cloud environment menu → Edit → Setup
  script) so new sessions don't repeat the ~20-40 minutes this can take cold.

## Collaboration preferences

The project lead values intellectual honesty and direct pushback over agreement — say plainly when an approach is
a dead end, when benchmarks are mixed, or when a deliverable is unverified, rather than softening it. This has been
well received repeatedly (mixed paper benchmarks, uncompiled deliverables, drifted oracles, bugs in existing code,
"fixes" that had to be reverted) — don't reflexively hedge findings like these.
