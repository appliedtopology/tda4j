# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**How this file works.** Each entry is a current rule, invariant, or known limitation, plus a pointer to the
`.claude/WORKLOG-*.md`/`DESIGN-*.md` that holds its derivation (what was tried, measurements, repros). Derivations
go in the worklog, not here. This file was condensed on 2026-09-22 from a ~190k-char version; that full text
(every narrative, number, and repro) is preserved at commit `06a55dd` — `git show 06a55dd:.claude/CLAUDE.md`.

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

**Docs site is Laika (Paradox fully removed)**, sources at `src/docs/`, Markdown with a `@:directive` syntax (not
Paradox's `@@`/`@ref:`). `Markdown.GitHubFlavor` and `laika.config.SyntaxHighlighting` are both required
`laikaExtensions` — Laika's base parser doesn't fence code blocks or highlight them without these, and un-fenced
code silently gets parsed as prose (any `[...]` in an example becomes a dangling link reference and fails the
build). `project/SnipDirective.scala` implements `@:snip(path, tag)`, the `@@snip` replacement: extracts the region
between two `// #tag` marker lines from a real source file at build time, same convention as before. Each
directory needing a non-alphabetical left-nav order needs its own `directory.conf` with `laika.navigationOrder`.
`src/docs/default.template.html` overrides Helium's default template to add `@:breadcrumb` (not on by default).
Full derivation: `WORKLOG-laika-migration.md`.

**Never run two `sbt` invocations against this checkout at once** (e.g. a background `test` run plus a foreground
`compile`): the incremental compiler's own class-file writes from one process can be read mid-update by the other,
producing a `NoClassDefFoundError` at test-run time that looks like a real regression but disappears on a clean,
sequential rerun. Wait for one `sbt` command to finish before starting another.

**Benchmark specs** (`ProfilingSpec`, `ApparentPairsBenchmarkSpec`, `CubicalBenchmarkSpec`, `SparseRipsBenchmarkSpec`,
`DimensionCeilingBenchmarkSpec`, `EngineComparisonBenchmarkSpec`, `RipserPaperBenchmarkSpec`, all in `homology`)
print timing tables rather than assert; only an exception counts as a failure. All seven `skipAll` unless
`-DrunBenchmarks=true` (a JVM system property passed to `sbt` before the task, not specs2 `--` syntax); scope with
`testOnly` (`EngineComparisonBenchmarkSpec` can take 15+ min; `RipserPaperBenchmarkSpec` also needs `-DdataDir`, and
optionally `-DripserBin=<path>` to time a real `ripser` binary — see `.claude/scripts/run-ripser-paper-benchmark.sh`).
Each spec's own `-D` args control size once enabled. `SingleEngineProfileDriver`/`CubicalProfileDriver`/
`VRLowDimProfileDriver` are kept one-engine-per-JVM profiling drivers.

**`HomologySpec`'s `BarcodeRegressionSpec` is `skipAll`'d unconditionally and NOT on this flag**: it's a correctness
spec that stalls/OOMs because chunks x `AlphaShapeDQP` on its own generator range (up to dim 10, 150 points)
produces enormous complexes (40 points/dim 4 → 102,090 simplices). One lucky fast run was once mistaken for "fixed"
— don't un-skip without bounding the scale problem (`WORKLOG-benchmark-and-chunks-bug.md`).

## Scala style used throughout

Uses Scala 3.7+'s newest context-abstraction syntax — don't "correct" it to older idioms:

- `Type is TypeClass` for context bounds/givens (`Chain[CellT, CoefficientT] is RingModule`).
- `type Self: Ordering as ordering` — named context-bound aliasing inside trait bodies.
- Unicode algebra operators: `⊠` (scalar action), `∆(...)` (simplex literal), `<*`, `|*|` (`RingModule.scala`/
  `Field.scala`).
- `opaque type Simplex[VertexT] = SortedSet[VertexT]` / `opaque type Cube = Vector[Int]` — no runtime wrapper; API
  is extension methods.
- Prefer `Option` over sentinel values (e.g. `maxFiltrationValue: Option[Double] = None`, formerly a `NaN`
  sentinel). A default can't reference an earlier parameter in the *same* list (`-source:future`), and curried
  parameter lists would force `()` at every call site — `None` + `.getOrElse(...)` inside is the pattern.
- A method's own `[T: Ordering, C: Field]`-style context bounds desugar to a `using` clause appended AFTER every
  explicit parameter list — so a default value earlier in that same signature (e.g. `reductionLog: Chain[T, C] =
  Chain.empty`) cannot reference the `Ordering`/`Field` given that default itself needs; it isn't in scope yet at
  that point in elaboration. No workaround short of every caller passing the value explicitly, or restructuring
  the signature so the context bound is a `using` clause of its own, ahead of that parameter (`Chain.reduceByUntil`).

**Opaque-type extension methods** (`WORKLOG-extension-companion-objects.md`): extensions whose receiver is the
opaque type live in its companion (`object Simplex`/`object Cube`), so different opaque types can reuse names. Two
hazards before copying this pattern: (1) opaque transparency is file-scoped, so same-file code calling the type's
extensions by dot-syntax breaks or silently hits the underlying type's member — hence `simplexIsOrderedCell`/
`cubeIsOrderedCell` live in separate files; (2) a companion extension can lose to a same-named stdlib extension
from a wildcard import (`math.Ordering.Implicits.*`'s `min`/`max`) — so `min`/`max` stay top-level.
`asSimplex`/`asCube` are top-level because their receiver is the raw `SortedSet`/`Vector`. `encoded`/`describe`
were deliberately not renamed back to `underlying`/`show`.

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
   Build it with `FiltrationOrdering.canonical(filtrationValue, dim, tieBreak)` (`SimplexStream.scala`) — filtration
   value reversed, then dimension, then `tieBreak` (colex via `simplexIndexing` for VR, matching Ripser's Def 3.2) —
   rather than hand-rolling the comparator: every stream in this codebase (VR, cubical, alpha, simplicial-set) now
   goes through this one combinator, after several independent hand-built copies each dropped or misordered a key
   at some point (`WORKLOG-code-critique.md`). Reverse *only* the primary key — `.reverse` on a whole ascending
   ordering also flips the dimension tie-break. No tie-break at all = tied cells collide as one `SortedMap` key.
2. **`iterateDimension` bucket order must be `.sorted(using filtrationOrdering.reverse)`** — the *same* `Ordering`
   object, never an independently-built comparator (`sortBy(filtrationValue)`, string tie-breaks, DFS order).
   Two individually-valid orders disagreeing on ties breaks Algorithm 1's shared-order precondition.
3. **Monotone**: `fv(face) <= fv(coface)`, exactly (see Cech's ULP clamp below).
4. **`iterateDimension`'s domain is contiguous from 0 and bounded** (`isDefinedAt` false past the top). `.iterator`
   is `Iterator.from(0).takeWhile(isDefinedAt).flatMap(iterateDimension)`; an always-true domain never terminates.
   VR streams guard with `d < metricSpace.size`; `LimitedCofaceSimplexStream` with `d >= 0 && d <= maxDim` (the
   `d >= 0` closes an `Int`-wraparound path); `AlphaShapeDQP` with `k < sizeByDimension.length`.
5. **Fixed**: calling `iterateDimension(1)` directly on a never-touched `RipserCofaceSimplexStream` used to
   silently return empty, not an error — `EnumeratingCofaceSimplexStream.currentDimension` defaulted to `0`,
   indistinguishable from "dimension 0 was genuinely computed and cached," so the dimension-1 branch's own
   `currentDimension != d - 1` freshness check read a fresh instance as "cache is fine" (`0 != 0` is false)
   instead of "never populated" (dimension `d >= 2` was never affected: `0 != d-1` is true there, forcing a
   correct rebuild regardless). `currentDimension` now defaults to `-1`, a value no legitimate `d - 1` can ever
   equal, so every dimension self-heals on first access whether reached via `.iterator` or a direct,
   out-of-order call. Regression-pinned in `CofaceSimplexStreamSpec` (`WORKLOG-sheehy-rips.md`); still prefer
   `.iterator` for driving a stream, this fix just removes the silent-wrong-answer failure mode.

Checks for a new/changed stream: cross-validate against an independent stream/engine *cell-for-cell* and use
tie-heavy fixtures; the `totalBarsAccountForAllCells` invariant alone is weaker. Filtration values consulted by
`Chain` comparisons must be cheap: `EnumeratingCofaceSimplexStream` (default VR diameter only, not a
`filtrationValueOverride`) and `CubicalGridStream` memoize them — both engines materialize all cells anyway, so the
cache adds no memory concern. Uncached, these were the dominant costs (`WORKLOG-autonomous-session-2026-09-19.md`).

**VR constructions** (all same output contract, alternate engines): `EnumeratingCofaceSimplexStream`,
`RipserCofaceSimplexStream` (+ `SimplexIndexing`), `InorderCofaceSimplexStream`,
`RecursiveStackVietorisRipsSimplexStream`, `IncrementalVietorisRipsSimplexStream` (Rieser's New-VR, arXiv:2301.07191
— "Rieser" the author, not Ripser; a cross-validation baseline, not a fast engine); `CofacetIterator` for lazy
coboundaries. `FiniteMetricSpace` has a VP-tree (`jvptree`) implementation and `SparseMetricSpace` (returns +∞ past
its cutoff rather than excluding — don't use it to build a thresholded oracle).

**`maxFiltrationValue` defaults to `metricSpace.minimumEnclosingRadius`** (Ripser's own `enclosing_radius`) in the
Enumerating/Ripser/Inorder/Incremental streams and both Ripser engines. This is a real semantic change: bars past
that radius are dropped (e.g. `threePointLine` at `maxDim=1` loses an essential H¹ bar — correct Ripser behavior).
Pass `Some(Double.PositiveInfinity)` for untruncated. Oracle: default output == untruncated output restricted to
`[0, radius]`. `RecursiveStackVietorisRipsSimplexStream` and alpha streams deliberately don't get this
(`WORKLOG-mst-and-perf.md`).

### Persistent homology: four independent engines

Independent implementations sharing `Chain` primitives — a fix in one doesn't imply the others need it.
**`maxDim`/`maxDimension` means "top homological degree reported" everywhere it exists** (engines internally build
one dimension higher; `WORKLOG-maxdim-semantics-fix.md`). The naive engine and `CellularCohomologyContext` have no
such parameter: callers truncate the stream (`LimitedCofaceSimplexStream(stream, k + 1)`) and drop `dim == k + 1`
bars. Over a field, cohomology and homology barcodes coincide.

1. **`CellularHomologyContext`/`SimplicialHomologyContext` (naive)** — single-pivot-table boundary reduction, no
   clearing. The reference baseline others are validated against. Incremental (`advanceOne`/`advanceTo`/
   `advanceAll`, `diagramAt`/`barcodeAt`); `barcodeAt` returns real representatives via V-columns.
   **A raw-`UnionFind` fast path for this engine was measured and rejected**: after memoizing VR filtration
   values, what remains is general `Chain` cost, and a port needs risky V-column coefficient bookkeeping in the
   reference oracle (`WORKLOG-autonomous-session-2026-09-19.md`).
2. **`CellularPersistenceInChunksContext[CellT, CoefficientT]` (chunks)**, with `PersistenceInChunksContext` a
   one-line `Simplex` subclass — clear-and-compress chunked algorithm (local reduction per chunk, then global
   `compress`/`globalReduce`). Walks `0..maxDim+1` internally, filters essential bars to `<= maxDim`.
   - Dimensions 0/1 are resolved up front by raw union-find (`unionFindDim01`), safe because a fixed total order
     determines a unique reduced matrix; `advanceAll` starts at delta=2 (`DESIGN-unionfind-in-chunks.md`, `WORKLOG-unionfind-in-chunks.md`).
   - `barcodeAt` gives a real representative for every bar, computed from chunks' **own** state via memoized
     `vcolOf` (derived term-for-term from the naive engine's V-column formula), exactly matching naive-engine
     chains. A delegate design running a second full naive engine was **rejected by the project lead** — don't
     revive it (`WORKLOG-chunks-representatives-incremental.md`; the earlier `-representatives.md` is history).
   - Invariants from fixed bugs: a paired cell must never become a pivot. `compress` runs to a fixpoint via
     `reduceByUntil` (not a snapshot loop); `compress` and `globalReduce` share `eliminationFallback`, whose paired
     branch substitutes the real V-column (`vcolOf`); a reconciliation step between local and global phases
     resolves every "in limbo" cell first; `markActiveEntries` scans all rows (`WORKLOG-benchmark-and-chunks-bug.md`,
     `WORKLOG-chunks-pairing-bug.md`). Pinned by `HomologyFixtures.tetrahedronBoundaryDegenerateCells` (degenerate
     S²) and tie-heavy clique sweeps.
   - A parallel chunks redesign was attempted and invalidated by measurement: rounds 1/2 resolve 0 pairs on real
     inputs (`WORKLOG-parallelization-survey.md`).
3. **`RipserCohomologyContext`** — Bauer's Ripser (arXiv:1908.02518) on `Simplex[Int]` VR, one-shot. **Test/reference
   oracle only** — new call sites use `PackedRipserCohomologyContext`, which is production (MATLAB `engine=ripser`).
   It catches bugs in packed's own representation layer (`DiameterIndex`'s index-only `equals`/`hashCode`,
   index-keyed maps); it is *not* independent of `SimplexIndexing` (the naive engine is the independent oracle).
   Its remaining SortedSet-specific perf costs are deliberately not chased — legibility is its job.
   Both Ripser engines share these facts (`WORKLOG-cohomology.md`, `WORKLOG-lazy-enumeration.md`):
   - **Clearing is required for correctness**, not an optional speedup (without it: spurious essential classes).
   - **Apparent pairs** (Def 3.2/Prop 3.9) with lazy substitution: a mutual apparent pair skips `coboundaryOf(sigma)`
     and writes only `generators(tau)`; a later reduction hitting `tau` recomputes the *full*
     `coboundaryOf(zeroApparentFacet(tau).get)` via `Chain.reduceBy`'s `fallback`, uncached, exactly like
     `ripser.cpp`. The `zero*` helpers use full unrestricted iterators — NOT the restricted, false-negative-prone
     `Cofacets.apparentVertex`. Emergent pairs (Def 3.11): deliberately not
     implemented.
   - Sparse Rips via `maxFiltrationValue`; candidates assembled incrementally from **every** dim-d simplex
     (cleared/apparent-paired included — as `ripser.cpp`'s `assemble_columns_to_reduce` does). `totalSimplexCount`
     replaces `Σ binomial(n, d+1)` for bars-account-for-cells once thresholded.
   - `insertionDiameter` (O(d) incremental cofacet diameter, Ripser's recurrence) avoids a filtration-value cache.
     `memoizeFiltrationValue` defaults **false** on the project lead's instruction: Ripser's goal is memory
     frugality, not raw speed. `zeroPivotFacet` has no incremental formula (removal).
   - `CofacetCursor`/`FacetCursor` (`hasNext`/`vertex`/`index`/`advance()`, zero allocation) back the hot loops;
     `cofacetIteratorWithVertex`/`facetIterator` are thin compat wrappers. `binomialEntry` is a lazily-grown
     `Array[Array[Long]]` (not an eager full table — `WORKLOG-simplexindexing-overflow.md`); `binomialChoose` is a
     separate cache on purpose. `decodeToArray` is verified against `apply` by property test.
   - Perf status (`WORKLOG-ripser-profiling.md`, `WORKLOG-ripser-comparison.md`, `WORKLOG-packed-ripser-engine.md`):
     the "quick win" tier is exhausted; still ~19–64x behind real `ripser.cpp` on `sphere3_*`, gap growing with n.
     `Chain.reduceLoop`'s remaining cost is ~7% (an earlier "48%" was a misattribution); no stdlib single-descent
     upsert exists, so further gains need a real redesign. Compare against **vanilla** `github.com/Ripser/ripser`,
     same machine — not the project lead's modified fork at `~/CLionProjects/ripser`, and not the hardcoded M1 Pro
     `ripserMs` values. Profile with deep stacks (`jfr print --stack-depth 30`); measure A/B, don't infer.
4. **`CellularCohomologyContext`** (`Cohomology.scala`) — persistent cohomology generic over `CellT: OrderedCell`,
   for streams that are fully materialized: builds the coboundary relation by inverting each cell's `boundary`, one
   dimension band at a time. No `maxDim` (see above). No apparent pairs — pointless here, there's no enumeration to
   skip (project lead's call). Only **essential** bars' V-columns are cocycles (`coboundaryOfChain(rep).isZero()`);
   finite bars' V-columns equal their reduced pivot chain. Representatives are **not** expected to match
   `RipserCohomologyContext` term-for-term: tie direction on `fv = 0` vertices differs, legitimately. Bar values do
   match. `require`s enforce chain homogeneity and dimension contiguity. Sign-tested on RP² over `Fp(3)`
   (`WORKLOG-generic-cohomology.md`).

Testing lessons that apply to every engine: F2 hides sign errors (use `Double` or `Fp(3)`) — and signed-field
fixtures must include simplices with **≥5 vertices**: `Set1..Set4` iterate in insertion order, so anything
accidentally routed through an unordered `Set` looks right up to 4 elements and is hash-ordered from 5 on (this hid
a boundary-sign bug until 2026-09-22; `SimplexBoundarySpec`, `SignedFieldBarcodeSpec`,
`WORKLOG-code-critique.md` §1.1). Torsion-free F3-vs-F2 barcode agreement is a cheap sign oracle. Agreement between two
engines isn't proof when both share a truncation or code path (hand-derived fixtures, e.g.
`HomologyFixtures.elderRuleExpected`, are the real oracle); which tied cell dies at a tied time is order-dependent.

`Barcode.scala`: `BarcodeEndpoint` (open/closed/±∞), `PersistenceBar`, algebra on finitely-presented persistence
modules.

**`BarcodeDistance`/`Vectorization`** (same package): bottleneck/Wasserstein distance and persistence
landscapes/images, `PersistenceBar[Double, _]`-specialized (not `Barcode`'s own `FiltrationT: Ordering`
genericity — every real engine here already produces `Double`, and a metric needs real arithmetic). Ground-
norm/aggregation convention matches Hera/GUDHI's own (`internal_p`/`order`); persistence-image construction
(exact per-pixel Gaussian-CDF integration, piecewise-linear weight) matches `scikit-tda/persim`'s. Essential
bars: for distance, matched only to each other by sorted birth (mismatched count → `+Infinity`, not an
exception); for vectorization, included for free by landscapes (the tent function degrades to a meaningful
ramp at `death=Infinity`) but dropped by images (a Gaussian centered at infinite persistence has no finite-grid
overlap to underflow silently instead of dropping outright) — a deliberate, per-method difference, not an
inconsistency. `matlab.PersistenceResult` exposes both; CLI (`--distance-to`) mirrors only the distance, not
the vectorizations (matrix output doesn't fit the CLI's existing diagram-shaped output model — a deliberate
scope boundary, not an oversight). `WORKLOG-bottleneck-wasserstein-vectorizations.md`.

### Cross-engine benchmark

`EngineComparisonBenchmarkSpec` times every (construction x engine) pairing across point count/dimension/`maxDim`,
construction and reduction timed separately, per-cell timeout on daemon threads (prints `"timeout"`). Alpha and VR
bar counts are never compared (circumradius vs diameter).

## Cubical complexes

`WORKLOG-cubical.md`. `Cube` = `opaque type Cube = Vector[Int]` in KMM doubled-coordinate encoding (`2a` degenerate,
`2a+1` = `[a,a+1]`). `Vector`, not an array — structural `equals`/`hashCode` required. Boundary sign alternates by the
axis's **rank among non-degenerate axes**, not raw position (invisible over F2; `CubicalSpec`'s dd=0 runs over F3).

`CubicalGridStream`: dense T-construction (GUDHI/DIPHA/Perseus convention). `topCellValue` per pixel/voxel; lower
cubes take the min over containing top cells (computed directly), which guarantees monotonicity. `shape(i)` = pixel
count; `totalCellCount = prod(2*shape(i)+1)`. `filtrationOrdering` copies `EnumeratingCofaceSimplexStream`'s shape.
`ExplicitCubicalStream` for sparse/hand-built complexes (its `filtrationOrdering` uses the same `FiltrationOrdering.canonical` combinator as `CubicalGridStream`, not an independent copy).
Sublevel/superlevel is handled only in `CubicalImage.scala`'s loaders (negate on load). `CubicalImage`:
`fromFlatArray` (row-major, last axis fastest) is the core; `fromBufferedImage`/`fromFile` via `javax.imageio` with
BT.601 luma; 3D via in-memory arrays. H0 oracle uses Moore (8/26-connected) adjacency, not 4-connected.

Both naive and chunks engines consume cubes. The old "naive scales badly in 3D" finding was an uncached
`filtrationValue`, now fixed — not an engine difference. A grid-exploiting engine (CubicalRipser, Wagner-Chen-Vuçini)
remains a valid future direction (`DESIGN-fast-cubical-engine.md`, `WORKLOG-cubical-chunks-benchmark.md`).

## Simplicial sets

`WORKLOG-simplicial-sets.md`, `WORKLOG-simplicial-set-constructions.md`, `WORKLOG-simplicial-set-filtration.md`. Fresh design, not the old deleted sketch.

- Eilenberg–Zilber presentation: non-degenerate generators per dimension, plus per generator `faces: G =>
  IndexedSeq[SSetElement[G]]` (`d_0..d_n`). `SSetElement(word, target)`: degeneracy word in normal form is
  **strictly decreasing** (`s_0 s_0 = s_1 s_0` → `[1,0]`); `Nil` = bare generator. Dim-0 generators have no faces.
- `insertOuter`/`faceOf` implement the simplicial identities on arbitrary elements; `validate()` checks structure
  (arity, registered targets, normalized words) then `d_i d_j = d_{j-1} d_i`. `validate()` passing is necessary,
  not sufficient — verify intended topology via homology.
- `finiteSimplicialSetIsOrderedCell`: normalized chain complex boundary (only bare faces contribute). The
  instance depends on the set's own `faces`, so thread it explicitly — never an ambient global given.
- **`FiniteSimplicialSet[G]`'s `using Ordering[G]` clause comes AFTER its `generatorsByDim`/`faces` value
  parameters, not before**: putting a `using` clause first (matching the codebase's usual `is`-typeclass-first
  style) broke `new FiniteSimplicialSet(...)` call sites' own type inference for `G` — with no other argument yet
  processed to pin it down, the compiler silently unified `G` with whatever `Ordering` happened to be found first
  in scope, once even landing on an unrelated `Ordering[Cube]`. `using`-first is safe only when the type parameter
  is already fixed some other way (e.g. `finiteSimplicialSetIsOrderedCell`'s own `[G]`, resolved by its caller);
  a constructor that must infer its type parameter from the ordinary arguments needs the `using` clause last.
- `SimplicialSetStream`: constant-0 filtration (ordinary homology); `filtrationOrdering` is dimension ascending
  (unreversed, per `processingOrder`'s comment) then caller's `Ordering[G]`. `FilteredSimplicialSetStream`: real
  `StratifiedCellStream[G, Double]`, same ordering convention as VR; `validateMonotoneFiltration` checks bare faces.
  Discriminating fixtures need non-dimension-aligned filtrations.
- `fromStream` takes `CellStream[Simplex[VertexT], ?]` (VR coface streams are not `SimplexStream`s). Exercises no
  degeneracy machinery.
- `product`: `(X×Y)_n = X_n × Y_n`, pair non-degenerate iff words' index sets are disjoint — **not** EZ shuffles
  (that's the chain map). `elementsAtDim` enumerates all of `X_n`. Top dim = `maxDim(x) + maxDim(y)`. Face maps
  strip the common degeneracy set `J` and **relabel** survivors via rank (`e -> e - |{j∈J: j<e}|`), not delete.
  `coproduct`: `Left`/`Right` tags.
- `quotient(sset, quotientMap: G => SSetElement[G])` — degenerate targets needed (RP² from a triangle collapses an
  edge to `s_0(v)`); must be dimension-consistent and resolve in **one step** to fixed points (`require`d).
  `identify(pairs)` is the union-find ergonomic layer (its own union-find in `cells`; `streams.UnionFind` would be
  a backwards dependency).
- Fixtures (`SimplicialSetFixtures`): `minimalSphere(n)`, `realProjectiveSpace(2|3)` (RP² is the sign
  discriminator over F2 vs F3; RP³ is the only one reaching `faceOf`'s `i > w1+1` branch), `torus`, `triangle`/
  `realProjectiveSpaceViaQuotient`. Not yet attempted: bar construction/classifying spaces. No MATLAB/CLI entry
  for simplicial sets (needs its own encoding design).

## Cech complexes

`streams/CechStream.scala`, `WORKLOG-cech-complex.md`. Over `Simplex[Int]`, built on the VR coface machinery via
`filtrationValueOverride` (valid because Cech is downward-closed). Radius = Miniball (`com.dreizak:miniball`)
minimum enclosing ball. `CechFiltration` caches every radius and **clamps each to the max of its facets'** — Miniball
can be one ULP non-monotone, which reproduced the pivot crash. New-VR's pruning (flag-complex only) and packed
Ripser (proven for diameter only) do **not** carry over; naive/chunks/cohomology only. Future: `Cech_r ⊆ VR_2r`
pre-filter. Fixture discriminators: equilateral radius `s/√3` (not `s/2`), obtuse = half longest side.

## Witness complexes

`streams/WitnessStream.scala`, `WORKLOG-witness-complex.md`. De Silva-Carlsson 2004, checked directly against
JavaPlex's own `LazyWitnessStream`/`WitnessStream` Java source. `LandmarkSelector.maxmin`/`.random` pick a
landmark subset (ambient indices) of a `FiniteMetricSpace[Int]`; `WitnessGeometry` precomputes the landmark
x witness distance matrix and each witness's sorted landmark-distance row. `maxmin` also exposes each chosen
point's own insertion radius (`LandmarkSelection.insertionRadius`, `streams.SheehyRipsSimplexStream`'s own
greedy permutation) and excludes already-chosen points from its own tie-break candidates — a real, if narrow,
pre-existing bug otherwise: an unchosen point that's an exact duplicate of an already-chosen one used to tie
at `minDistToLandmarks = 0` with that already-chosen point and lose the tie (lower ambient index wins), so a
FULL permutation (`numLandmarks = metricSpace.size`) could silently end up with fewer than `metricSpace.size`
distinct entries (`WORKLOG-sheehy-rips.md`).

Two independent variants, both `Simplex[Int]` over LOCAL landmark indices (`0 until landmarks.size` — map back
through `landmarks(i)` for ambient ids), both built on `RipserCofaceSimplexStream` unchanged:
- **`LazyWitnessSimplexStream`**: IS a flag complex by definition, so `WitnessMetricSpace` reifies its edge
  weights as a `FiniteMetricSpace[Int]` (NOT a real metric — can be 0 for distinct landmarks, no triangle
  inequality; never hand it to `JVPTree`/`SparseMetricSpace`/`RecursiveStackVietorisRipsSimplexStream`/`alpha`)
  and needs no `filtrationValueOverride` — the inherited "max pairwise distance" flag extension is exactly
  right. A genuine flag complex, so `PackedRipserCohomologyContext` (proven only for VR diameters) is *also*
  valid here — the one exception to "ripser is VR-only." `nu ∈ {0,1,2}` (JavaPlex's own cap), default 2.
  `maxFiltrationValue` defaults to `minimumEnclosingRadius` — valid (same cone argument as VR/Cech).
- **`WitnessCofaceSimplexStream`** (general): NOT a flag complex — a `k`-simplex's own per-dimension threshold
  `m_k` isn't monotone facet-to-coface alone, so `filtrationValueOverride` computes a **recursive**
  `max(own_k(σ), max over σ's own facets)`, `TrieMap`-memoized (the base class never memoizes a
  caller-supplied override). This recursive max — not JavaPlex's separate `containsElement(face)` gate, which
  it makes redundant — is what makes "the complex at threshold R" automatically downward-closed for every R.
  Refuses `engine=ripser`/`chunks` (MATLAB/CLI); `maxFiltrationValue` defaults to `+Infinity`, NOT the
  enclosing radius (not valid for a non-flag complex). The general complex's own 1-skeleton is provably
  identical to the lazy complex's at `nu=2` (cross-validated, both use the 2nd-nearest-landmark threshold).

## Alpha complex: DQP vs Helix

`WORKLOG-alpha-complex.md`, `HANDOFF-alpha-complex.md`. `AlphaShapes(points, dispatch)`: `"default"` → `"helix"`
(`HelixDelaunay`); `"DQP"` must be explicit. Alpha and VR/Ripser are separate sections with minimal interaction
(project lead's standing call). Never resurrect the ripped-out Miniball-Delaunay backend.

`AlphaComplexDQP` implements Carlsson & Carlsson, Sci. Rep. 14:19824 (2024), with a DAQP-style (Arnström et al.)
dual active-set QP; problem (9) has H = I, so `CholeskyWorkspace` is a hand-rolled incremental Cholesky
update/downdate (O(k²), not Commons Math's O(k³) refactor). Filtration values are squared radii internally;
`radiusOf` takes sqrt so units match Helix. `B_ij = (d²(i,x) + d²(j,x) - d²(i,j))/2` (PSD only for Euclidean-
embeddable metrics); `PowerDistance` works on squared distance, bridging via `toMetricSpace`. `AlphaShapeDQP` is
always untruncated (degenerate configurations produce huge circumradii); use `AlphaComplexDQP.euclidean(points,
maxRadius, ...)` for truncation. Optional parallel construction (`WORKLOG-parallelization-survey.md`).

**Settled numerical decisions in `DualQP.solve` — don't retune:**
- `rankTolerance = 1e-6` (safe range `[1e-7, 1e-5]`; smaller poisons the Cholesky factor and cycles forever,
  `1e-4` gives wrong answers).
- Ratio-test ties broken by **global** constraint index (Bland's rule), not working-set position.
- **Accepted limitation**: small Schur complement with no swappable inequality → candidate treated as infeasible
  (may wrongly exclude a near-degenerate Delaunay simplex). "Commit anyway" was tried and reverted: no fixed
  threshold separates safe from catastrophic. `solveAtVertex` catches per-candidate non-convergence and excludes it;
  logs to stderr only under `AlphaDQPSettings(verbose = true)` (off by default — this is routine, not exceptional).
- Vertex filtration value is `-space.weight(x)` (witness `coordsOf(x)`) **only when `x` lies inside its own
  restricted power cell `V_x`** — not a `0.0` default (breaks weighted monotonicity; `AlphaComplexDQPWeightedSpec`).
  When some Cech-neighbour dominates `x`'s own point (`weight(j) - weight(x) > d²(x,j)`), the correct value is the
  **minimum over `x`'s own incident, already-solved edges** (dimension 1 is solved before dimension 0 for exactly
  this reason), with that edge's own witness, not `x`'s coordinates; a vertex with no incident edges at all is
  genuinely hidden (empty power cell) and is **dropped from the complex entirely**, not assigned a value. Found via
  DTM weights (which trigger this routinely) but not DTM-specific — mild weights never triggered it before.
  `AlphaComplexDQPVertexAttachmentSpec`, `WORKLOG-dtm-filtrations.md`.
- Regressions pinned in `AlphaComplexDQPRegressionSpec`/`AlphaValidationSpec`; property suite uses
  `minTestsOk = 2000` (failure rates were as low as 1/12000). `AlphaComplexDQPSpatialIndexSpec` checks the VP-tree
  `cechNeighbours()` against brute force.

**HelixDelaunay**: bootstrap fixed (greedy affinely-independent subset). **Accepted limitation**: near-cospherical
clusters make the frontier walk order-dependent (~1/170 at ambient dim 4, 20–30 points) — a real fix needs joint
near-tie detection. So Helix is **not reliable ground truth** for dim ≥ 4 fuzzing; `AlphaCrossValidationSpec`'s
comparisons stay as `unsafeCompare`/`unsafeFuzzCompare` diagnostics, not wired into `sbt test` (and not
`pendingUntilFixed`, wrong semantics for probabilistic failures).

**Degeneracy hazard**: in cospherical position the alpha complex is not a Delaunay subcomplex — `k` cospherical
sites give a `(k-1)`-simplex (unit grid in R² → 3-simplices). Truncating at ambient dimension gives the wrong
homotopy type. Correct, not a bug.

Honest framing: the paper's benchmarks are mixed vs Ripser and qhull; the value is high ambient dimension, exact
homology, and small complexes near low-dimensional data — not raw speed.

## DTM-based filtrations

`streams/DistanceToMeasure.scala`, `streams/DtmRipsStream.scala`, `alpha.AlphaComplexDQP.dtm`,
`WORKLOG-dtm-filtrations.md`. `streams.DistanceToMeasure(metricSpace, k, q=2)`: Chazal-Cohen-Steiner-Merigot 2011,
generic over any `FiniteMetricSpace[Int]` (no coordinates needed). `k` is **self-inclusive** (a point counts as
its own nearest neighbour, verified against GUDHI's own docstring and worked examples byte-for-byte) — `k=1` gives
`f=0` everywhere, the degenerate case every DTM consumer here reduces to its unweighted construction at. Defaults
to `streams.BruteForce` for k-NN, not `JVPTree`: VP-tree pruning assumes the triangle inequality, which not every
`FiniteMetricSpace` in this codebase satisfies (`ExplicitMetricSpace` enforces nothing).

**`streams.DtmRipsSimplexStream`** (Anai et al., "DTM-based filtrations," arXiv:1811.04757, Def. 3.1/Prop. 3.5):
doubled units, matching plain VR (GUDHI's own convention too). `p ∈ {1.0, 2.0}` (Def. 3.1's ball-radius exponent,
**not** `DistanceToMeasure`'s own `q`); `p=1` (default) is checked byte-for-byte against GUDHI's own
`DTMRipsComplex`/`WeightedRipsComplex`; `p=2` has no external reference implementation and exists only as the
cross-validation device against `AlphaComplexDQP.dtm` below, not as a recommended default. **The first coface
stream in this codebase with nonzero, distinct vertex filtration values** — every prior VR-flavored stream's
inherited `case 0` (unsorted, unfiltered) was only ever safe because every vertex tied at 0; this class overrides
`case 0` explicitly. `maxFiltrationValue` defaults to the reified (doubled) metric space's own
`minimumEnclosingRadius`, not GUDHI's `+Infinity` — proven safe for both `p` values (`t(f_x,f_y,d) >= max(f_x,f_y)`
by construction), see the worklog for the proof. Refuses `engine=ripser` in `matlab.TDA4j` (Ripser assumes vertex
births at 0 and a diameter-only incremental formula); `naive`/`chunks`/`cohomology` all consume it like any other
flag complex — `chunks` cross-validated against `naive`, not assumed to carry over.

**`alpha.AlphaComplexDQP.dtm`**: `weight(i) = -f(i)²`, the `p=2` ball equation applied through the pre-existing
weighted-alpha/power-distance machinery — not a construction GUDHI implements (no `DTMAlphaComplex` there) or a
literature citation confirmed for this exact combination; derived, not copied. Depends on the vertex-attachment
fix above (DTM weights make a point's own centre fall outside its own cell routinely). Cross-checked against
`DtmRipsSimplexStream(p=2)`'s H0 (persistent nerve lemma: same union of balls ⟹ same component count at every
threshold), NOT bar-for-bar — alpha correctly delays/omits vertices Rips can't, producing zero-length bars on the
Rips side that must be dropped before comparing; see the worklog for the full derivation (it did not match on
the first attempt, and understanding why cost real time — worth reading before touching this code).

## Sheehy's sparse/approximate Vietoris-Rips filtration

`streams/SheehyRipsStream.scala`, `WORKLOG-sheehy-rips.md`. `SheehyRipsSimplexStream` implements
Cavanna-Jahanseir-Sheehy 2015 (arXiv:1506.03797) — the greedy-permutation reformulation of Sheehy's original
net-tree construction (arXiv:1203.6786); the two papers' `epsilon` values are **not** comparable (CJS 2015's
factor is `(1+epsilon)`, Sheehy 2013's is `1/(1-2ε)`). Deliberately `O(n²)` (every pairwise `edgeBirth`
materialized directly), not the paper's own `O(n log n)` neighbor-search algorithm — a smaller complex to
*reduce*, not a faster one to *build*.

Built on `LandmarkSelector.maxmin` run to full size (`numLandmarks = metricSpace.size`) for the greedy
permutation — extended, not duplicated, to also expose each point's own insertion radius
(`LandmarkSelection.insertionRadius`). One memoized `filtrationValueOverride` handles every dimension ≥ 1
uniformly (not a reified weighted metric space plus a separate higher-dimension patch): the `min`-over-
vertices `vanish` exclusion check has to see every vertex of a simplex at once, which a pairwise-distance
flag default cannot express.

**CJS 2015's own Algorithm 3 (`EdgeBirthTime`) omits a check Section 5.3's own `SimplexBirthTime` definition
requires** (a `min`-over-vertices `vanish` clamp) — verified as a real gap that survives even the paper's own
restricted neighbor search, not just an artifact of this class's all-pairs enumeration; whether the paper's
full pipeline compensates elsewhere was not checked. `edgeBirth` here applies that clamp to every edge, not
just higher simplices — pinned by a hand-derived triangle fixture (three individually-finite edges whose own
triangle is still excluded).

Units doubled (diameter convention, matching every other stream here); reduces to plain VR exactly at a
SMALL `epsilon` (not large — the opposite of the first, wrong instinct; see the worklog for the direction).
`maxFiltrationValue` is unconditionally clamped to `maxFiniteFiltrationValue`, even when the caller passes an
explicit `Some(Double.PositiveInfinity)` — `keptByThresholdAndCriterion`'s plain IEEE-754 `<=` would otherwise
treat that threshold as equal to (hence admitting) every excluded pair's own `Double.PositiveInfinity`
filtration value.

Refuses `engine=ripser` (not diameter-only); `naive`/`chunks`/`cohomology` wired through `matlab.TDA4j`
`complex=sheehy-rips` (needs `sheehyEpsilon`, required) and mirrored 1:1 in `cli` (`--sheehy-epsilon`), same as
every other complex — see `persistence-engines.md`'s streams-vs-engines table for the full picture across
every construction, not just this one.

## File I/O

`io`, `WORKLOG-io-module.md`. Every format was verified against its project's primary source; unverified formats
(Perseus simplicial toplex, PHAT, sparse triplet distance matrices) are deliberately not implemented — a plausible
wrong parser is worse than none. Two layers per format: raw loader, then one-line convenience constructor.
- Ripser binary distance matrices are **float32** (`typedef float value_t`), not double.
- DIPHA/Perseus cubical axis order is **first axis fastest** — opposite `fromFlatArray`; readers/writers reverse
  shape. Round trips and homology can't catch transposition; tests use asymmetric all-distinct-value grids.
- Diagram readers rebuild finite bars via `PersistenceBar.apply(dim, lower, upper)` (closed/open) so round trips
  compare equal. Perseus `-1` → `+∞` (missing cell).

## CLI executable

`cli`, `WORKLOG-cli-executable.md`. `sbt assembly` → `java -jar target/scala-3.9.0/TDA4j-<version>-assembly.jar`.
Scallop (zero deps), not Decline (cats-core). Every compute flag mirrors a `matlab.TDA4j` option key 1:1 with **no
Scallop default** — omitted keys let `TDA4j` apply its own defaults (one source of truth). `--input-format`/
`--output-format` map 1:1 onto `io` methods. `--output-format=perseus` is refused for non-integral filtrations
(would silently round to step indices). `TDA4jCLI.run(args, out): Int` is testable in-process, but Scallop's default
`onError` calls `System.exit` on any parse error or `--help`/`--version` — `CLISpec` must never pass malformed flags.
`throwError` was deliberately not used. Scallop `opt[Boolean]` has always-supplied toggle semantics
(`WORKLOG-naming-and-dispatch-expansion.md`). `--distance-to` (+ `--distance-format`/`-order`/`-ground-norm`)
is the one exception to the "every flag mirrors a compute option" framing above — it mirrors
`barcode.BarcodeDistance` instead, comparing the computed diagram against one read from a file via the
existing `io.{CSV,Gudhi,Dipha}.readPersistenceDiagram`; only `--output-format=text` is supported with it (see
"Barcode representation" above for why the vectorizations have no CLI mirror at all).

## MATLAB API

`matlab` (`TDA4j.scala`, `PersistenceResult.scala`), `WORKLOG-matlab-api.md`. Java-facing facade: public methods
take/return only `double`, `int`, `String`, `double[][]`, `String[]` — no `Map`, generics, or Scala types (project
lead rejected a `Map`-based design). Options are a flat key/value `String[]` so new options never change signatures.
The MATLAB-facing option strings still drive dispatch (can't match on types across that boundary), but `dispatch`
parses each one exactly once into a private `ComplexKind`/`EngineKind`/`CoefficientKind` enum before anything else
runs, and dispatches on those enums via `PersistenceEngine.naive`/`.chunks`/`.cohomology` (`homology/
PersistenceEngine.scala`) rather than re-matching the raw string at each branch.
- `computeFromPoints`/`computeFromDistanceMatrix`: `complex` = `vr`/`alpha`/`cech`/`witness`/`dtm-rips`/`dtm-alpha`/
  `sheehy-rips`; `engine` = `ripser`/`naive`/`chunks`/`cohomology` (Alpha and dtm-alpha refuse `ripser`/`chunks`;
  Cech, dtm-rips, sheehy-rips, and witness/general refuse `ripser`, witness/general also refuses `chunks` — see
  "Witness complexes"/"DTM-based filtrations"/"Sheehy's sparse/approximate Vietoris-Rips filtration" above, and
  `persistence-engines.md`'s streams-vs-engines table for the full picture). `dtm-rips`/`dtm-alpha` need `dtmK`
  (required); `sheehy-rips` needs `sheehyEpsilon` (required, strictly in `(0,1)`); `dtm-rips`/`sheehy-rips` alone
  work from `computeFromDistanceMatrix` too (no coordinates needed), `dtm-alpha` needs `computeFromPoints` like
  `alpha`/`cech`. `computeFromCubicalImage`/`computeFromImage` for cubes.
- **Two-step witness recipe** (`WORKLOG-witness-two-step-api.md`), alongside the one-shot path:
  `selectLandmarksFrom{Points,DistanceMatrix}` (→ `LandmarkSelectionResult`) then
  `computeFrom{Points,DistanceMatrix}AndLandmarks` (takes that `int[]`, 0-based ambient indices, directly —
  never re-selects); `coveringRadiusFrom{Points,DistanceMatrix}` queries R for a hand-picked set. Each new pair
  has its OWN strict option allowlist, not the one-shot path's permissive shared one. CLI mirror:
  `--select-landmarks`/`--landmarks-file`.
- `maxDimension` (default 2) = top homological degree. `ripser`/`chunks` pass it straight through; `naive`/
  `cohomology` wrap the stream in `LimitedCofaceSimplexStream(..., k + 1)` and drop `dim == k + 1` bars. Alpha needs
  no +1 (its chain complex terminates on its own). `TDA4jSpec` pins the old un-corrected construction as disagreeing.
- Field: `Z` (prime field, default `prime=2`, the research-literature convention) or `R`
  (`Field.DoubleApproximated`, what internal specs default to) — deliberate divergence.
- `PersistenceResult`: `toArray()` (N×3 dim/birth/death) eagerly; `cycleVertices`/`cycleCoefficients` lazily via
  `fromBars(..., cellVertices: (Int, CellT) => Array[Int], ...)`. `cycleVertices` throws `UnsupportedOperationException`
  if a bar has no recorded representative, but every engine now records one for every bar at every dimension (see
  the representatives design principle above) — this exception path indicates an engine bug, not an expected gap.
  Boundary-matrix export designed, not implemented.
- `PersistenceResult.bottleneckDistance`/`wassersteinDistance` (against another `PersistenceResult` handle —
  fine across MATLAB's Java bridge, unlike a generic/`Map` type) and `.landscape`/`.persistenceImage`: plain
  required positional parameters, not routed through the `String[]` options map (none of these are optional/
  cross-cutting the way that map exists for) — see "Barcode representation" above.
- Unverified: MATLAB's bundled JVM version and actual `double[][]`/`String[]`/`int[]` marshalling.

## Session practices

- **Write a `.claude/WORKLOG-<topic>.md` by default** for any substantial investigation, debugging, or
  profiling arc, without being asked ([[tda4j-worklog-convention]]). Worklogs are point-in-time snapshots, never
  retroactively edited. At the end of the arc, update this file with **only the resulting rule/invariant/limitation
  plus a worklog pointer** — no narrative, measurements, or repros here. Keep this file under ~40k characters.
- Performance claims need isolated A/B measurement (`git stash` A/B, median of trials, one engine per JVM); machine
  noise here often exceeds small effects — report unconfirmed effects as unconfirmed.
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
