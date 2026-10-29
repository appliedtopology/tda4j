---
paths:
  - "src/**/*Cech*.scala"
  - "src/**/*Witness*.scala"
  - "src/**/*Dowker*.scala"
  - "src/**/*Dtm*.scala"
  - "src/**/DistanceToMeasure*.scala"
  - "src/**/*Sheehy*.scala"
  - "src/**/*EdgeCollapse*.scala"
  - "src/**/Complexes*.scala"
---

# Cech, witness, Dowker, DTM, sparse (Sheehy) Rips and edge-collapse complexes

Loads when you work on one of these complexes. Project-wide rules are in `.claude/CLAUDE.md`; the stream ordering contract they all satisfy is in `streams.md`.

## The coface loop they share (`RipserCofaceSimplexStream`; `WORKLOG-rare-complex-performance.md`, #181)

Cech, witness, Dowker, DTM and sparse Rips all generate cells in `RipserCofaceSimplexStream`, and **generating the cells,
not reducing them, was about 90 % of their time** (the reduction ~10 %; Cech's ball only 6 % of Cech's). Invariants:

- Candidates of dimension `d >= 2` are `spx + i` for `i` below `spx.min` and adjacent, in the table of KEPT edges
  (`keptEdgeNeighbours`, bitset rows, built from dimension 1 or on first use), to every vertex of `spx`. A `keepCriterion`
  or filtration that keeps a simplex but not one of its edges is outside the stream contract (and now yields fewer cells).
- On the `Simplex` path keep per-cell work to ONE hash lookup (a `TreeSet`'s own `hashCode`/`equals` allocate):
  `FiltrationCache` hashes by traversal; `MemoizedFiltration` answers a hit before it checks the domain;
  `FiltrationOrdering.canonical` calls `applyOrElse` once per cell. No `isDefinedAt`-then-`apply` pairs or `Simplex`-keyed
  `HashMap`s. A hash linear in the vertices collides for every equal vertex sum (sparse Rips ran 7+ minutes): mix each
  element (MurmurHash3's finalizer).
- `Persistence` takes the largest value from `CellularCohomologyEngine.largestFiltrationValue`; iterating again rebuilds.
- Against GUDHI's Delaunay-Cech, Cech stays orders of magnitude slower (every `dim + 2`-subset is a cell); `AlphaShapes`
  gives the same barcode of a Euclidean point cloud.
- **Flat layers (#206, `WORKLOG-rare-complex-performance.md`)**: a stream whose `flatFiltration` is defined builds each
  dimension on arrays (`FlatLayerStream.Layer`: ascending vertex tuples, values, cohomology order = value ascending then
  colex ascending) and the cohomology engine reads them without a `Simplex`, a `cohomologyOrder` pass or a second read.
  The default Vietoris-Rips value (`filtrationIsDefault`, so also the lazy witness complex), DTM-Rips, Dowker, sparse
  Rips and Cech have an array-level `FlatFiltration`; the general witness complex wraps its `Simplex` function
  (`FlatFiltration.ofSimplices`). A `FlatFiltration` must equal the stream's `filtrationValue` bit for bit; `FlatLayersSpec`
  compares every complex with itself built from cells (turn the hook off
  by overriding `flatFiltration` to `None`), so a new hook goes into its `pairs`. A `keepCriterion` other than
  `CofaceSimplexStream.keepAll` is asked about a `Simplex` per candidate, so the shared constant is the default of every
  coface stream: a literal `{ case _ => true }` default silently takes the slow path. A subclass that changes which
  values are kept overrides `keptValue`, not only `keptByThresholdAndCriterion` (Dowker: finite values only).
- **Generator-backed layers (#190, `WORKLOG-flag-generator.md`)**: a coface stream that is the flag complex of a weighted
  graph overrides `flatGenerator` (DTM-Rips, sparse Rips, Rips over an `EdgeCollapsedMetricSpace` by default): layers from
  dimension 1 come from `LayeredInorderSimplexStream.FlagGenerator` in order, nothing sorted (2.2 to 7.5 times faster).
  It must list the cells and values of `flatFiltration`. Sheehy's vanishing points are per-vertex deadlines, checked where
  a vertex is added; `FlatLayersSpec` needs an input where they matter (`cloud(12, 2, 2L)`).
- A flag-like hook gets the parent's value (`parentValue`) and only adds the new vertex's edges; an edge ignores it.
- **State kept across a run of candidates** (`FlatFiltration.perRun`, `WORKLOG-flat-values.md`, #206): the candidates of one
  parent come together, the parent's vertices after the new one. Dowker keeps the per-witness maxima over the parent's
  rows (a candidate costs one row, 2.4 to 4.2 times faster layers). A hook that keeps state returns a fresh object from
  `perRun` (the stream calls it once per task) and its `value` stays stateless.
- **Cech's value cache is a `VertexSetValues`** (vertex array to value, 64 locked shards; never a `Simplex`-keyed map).
  `CechFiltration.Values` serves the `Simplex` function and the array hook from ONE cache, because a value depends on
  which facets are cached when it is first asked (the floor, the interior vertex reusing its facet's radius): two caches
  would give ULP-different values. `CechValuesSpec` feeds the earlier `Simplex`-keyed formula the same calls, with faces
  left out, and compares bits. About 4 times faster layers.
- Left (#206): the general witness complex on arrays; Scala.js timings and the witness complexes' speed are unmeasured.

## Cech complexes

`streams/CechStream.scala`, `WORKLOG-cech-complex.md`. Over `Simplex[Int]`, built on the VR coface machinery via
`filtrationValueOverride` (valid because Cech is downward-closed). Radius = `MinimumEnclosingBall` (our own
active-set solver, `WORKLOG-scalajs-numerics-spatial.md`). `CechFiltration.Values` caches every radius and **clamps each to
the max of its facets'** — a computed ball can be one ULP non-monotone. New-VR's pruning and packed Ripser (proven for diameter only) do **not** carry over; naive/chunks/
cohomology only.

## Witness complexes

`streams/WitnessStream.scala`, `WORKLOG-witness-complex.md`. De Silva-Carlsson 2004, checked against JavaPlex's
own Java source. `LandmarkSelector.maxmin`/`.random` pick a landmark subset of a `FiniteMetricSpace[Int]`;
`maxmin` also exposes each point's own insertion radius (`LandmarkSelection.insertionRadius`), excluding
already-chosen points from its own tie-break candidates (`WORKLOG-sheehy-rips.md`).

Two independent variants, both `Simplex[Int]` over LOCAL landmark indices, both on `RipserCofaceSimplexStream`
unchanged: **`LazyWitnessSimplexStream`** IS a flag complex, so `WitnessMetricSpace` reifies edge weights as a
`FiniteMetricSpace[Int]` (NOT a real metric — never hand to `VantagePointQuery`/`SparseMetricSpace`/`alpha`);
`PackedRipserCohomologyEngine` is *also* valid here (the one exception); `nu ∈ {0,1,2}`, default 2.
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
`DowkerCofaceSimplexStream` overrides it to also require `.isFinite`; without it an untruncated stream silently becomes
the complete simplex.

**Duality is the point** (`.dual`, the transpose relation): X-side and Y-side barcodes agree exactly **without zero-length
bars** (the default; with `includeZeroLength = true` they differ, as each vertex records one `H_0` birth and the sides
have different vertex counts; Chowdhury and Mémoli 2018, confirmed on a hand-worked fixture).

Own MATLAB/CLI entry point (`computeFromRelation`/`--input-format csv-relation`), not a `complex=` value — a
relation doesn't fit the point-cloud/distance-matrix dispatch. `engine` defaults `cohomology`, refuses `ripser`/
`chunks`; `dual`/`--dual` computes the W-side directly.

## DTM-based filtrations

`streams/DistanceToMeasure.scala`, `streams/DtmRipsStream.scala`, `AlphaComplexDQP.dtm`,
`WORKLOG-dtm-filtrations.md`. `DistanceToMeasure(metricSpace, k, q=2)`: Chazal-Cohen-Steiner-Merigot
2011, generic over any `FiniteMetricSpace[Int]`. `k` is **self-inclusive** (verified vs GUDHI byte-for-byte) —
`k=1` gives `f=0` everywhere. Defaults to `BruteForce` for k-NN, not `VantagePointQuery` (triangle-inequality assumption
not universal here).

**`DtmRipsSimplexStream`** (Anai et al., arXiv:1811.04757): doubled units, `p∈{1.0,2.0}`, p=1 (default)
checked byte-for-byte vs GUDHI's `DTMRipsComplex`, p=2 only for cross-validating `AlphaComplexDQP.dtm`. First
coface stream with nonzero distinct vertex filtration values (overrides `case 0` explicitly).
`maxFiltrationValue` defaults to `minimumEnclosingRadius`. Refuses `engine=ripser`.

**`AlphaComplexDQP.dtm`**: `weight(i)=-f(i)²`, derived not copied. Cross-checked against
`DtmRipsSimplexStream(p=2)`'s H0 via the persistent nerve lemma, not bar-for-bar (alpha delays/omits vertices Rips can't).

## Sheehy's sparse/approximate Vietoris-Rips filtration

`streams/SheehyRipsStream.scala`, `WORKLOG-sheehy-rips.md`. `SheehyRipsSimplexStream` implements
Cavanna-Jahanseir-Sheehy 2015 (arXiv:1506.03797) — the two papers' `epsilon` values are **not** comparable.
Deliberately `O(n²)` (every pairwise `edgeBirth` materialized), not the paper's `O(n log n)` neighbor search: a smaller
complex to *reduce*, not a faster one to *build*. Built on `LandmarkSelector.maxmin` run to full size. One memoized
`filtrationValueOverride` handles every dimension ≥ 1 (the `vanish` check needs every vertex at once).

**CJS 2015's own Algorithm 3 omits a check Section 5.3's own definition requires** (a `min`-over-vertices
`vanish` clamp) — `edgeBirth` here applies that clamp to every edge, pinned by a hand-derived triangle fixture.

Units doubled; reduces to plain VR exactly at a SMALL `epsilon` (not large). `maxFiltrationValue` is
unconditionally clamped to `maxFiniteFiltrationValue` even when the caller passes `+Infinity` — plain IEEE-754
`<=` would otherwise admit every excluded pair's own `+Infinity`. Refuses `engine=ripser`; wired through
`matlab.TDA4j complex=sparse-rips` (needs `sparseEpsilon`) and `cli --sparse-epsilon`.

## Flag-complex edge collapse

`streams/EdgeCollapseStream.scala`, `WORKLOG-edge-collapse.md`. `EdgeCollapse.collapse` implements
Boissonnat-Pritam (SoCG 2020) + Glisse-Pritam (SoCG 2022): reduces a VR filtration's 1-skeleton to a smaller
weighted graph with the SAME persistent homology at every level. Edge `{u,v}` dominated by `w` iff every common
neighbor of u,v is also adjacent to w (verified vs GUDHI's `Flag_complex_edge_collapser.h`). **Removes dominated
EDGES, never vertices** (vertex domination = strong collapse, arXiv:1809.10945, different construction). Reified
as `EdgeCollapsedMetricSpace` — drop-in for Enumerating/RipserCofaceSimplexStream; representatives transfer
through inclusion free; `minimumEnclosingRadius` overridden to the collapse's own bound.

**A faithful port of the reference's single-pass, descending, live-mutating-state structure, not a redesign.** A
"fixed-point iteration" draft was PROVEN WRONG by barcode cross-validation (two bugs, each silently turning a real bar
essential on a 5-point counterexample). Processing order is load-bearing: reread the reference before touching this.

Measured 73-76% edges removed, 43-47x reduction-phase speedup (`EdgeCollapseBenchmarkSpec`). Up to 4,096 points the
collapse runs on an `n x n` matrix and bit sets, above on sorted maps (the reference: `EdgeCollapseDenseSpec` holds the
two equal bit for bit); cost ~ edges x common neighbours (`WORKLOG-edge-collapse-dense.md`, #256). Wired through
`matlab.TDA4j`'s `edgeCollapse` option (`complex=vr` only) and `cli --edge-collapse`.
