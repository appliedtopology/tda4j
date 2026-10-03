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
`DowkerCofaceSimplexStream` overrides it to additionally require `.isFinite` — without it, an untruncated stream
silently collapses to the complete simplex on every vertex (confirmed empirically).

**Duality is the point** (`.dual` — the transpose relation): the functorial Dowker duality theorem (Chowdhury &
Mémoli 2018) gives X-side/Y-side barcodes agreeing exactly **only after dropping zero-persistence bars from
both** (a simplicial filtration records exactly one `H_0` birth per vertex, so `numLeft != numWitnesses` can't
match bar-for-bar otherwise — confirmed on a hand-worked fixture, not just asserted from the theorem).

Own MATLAB/CLI entry point (`computeFromRelation`/`--input-format csv-relation`), not a `complex=` value — a
relation doesn't fit the point-cloud/distance-matrix dispatch. `engine` defaults `naive`, refuses `ripser`/
`chunks`; `dual`/`--dual` computes the W-side directly.

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
`matlab.TDA4j complex=sparse-rips` (needs `sparseEpsilon`) and `cli --sparse-epsilon`.

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
