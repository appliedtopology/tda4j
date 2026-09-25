# Architecture: from algebra to a filtration stream

This page walks the two lower layers of the library: the algebraic core (what a "chain" and a
"coefficient" actually are) and complex construction (how a sequence of cells in filtration order gets
produced). [Persistence engines](persistence-engines.md) covers what consumes the stream this layer
produces.

If a piece of Scala 3 syntax below looks unfamiliar, see the [Scala 3 primer](scala3-primer.md) first.

## Package layout

`org.appliedtopology.tda4j` is split into subpackages, each a layer:

- **`algebra`** — `RingModule`, `Field`, `FiniteField`, `Chain` (including the `Cell`/
  `OrderedCell`/`OrderedBasis` contracts), `SSetElement` (the degeneracy-word algebra underlying simplicial
  sets). The typeclasses and formal-sum machinery everything else builds on.
- **`cells`** — `Simplex`, `Cube`, `FiniteSimplicialSet` — the three concrete `OrderedCell` instances — plus
  `FiniteSimplicialSet`'s companion (`product`/`coproduct`/`quotient`/`identify`), which draws on shared
  ordering helpers kept in the separate `SimplicialSetConstructions.scala`.
- **`streams`** — everything that produces cells in filtration order: `SimplexStream`/`CellStream`, the
  Vietoris-Rips family, `FiniteMetricSpace`, `CubicalStream`/`CubicalImage`, `SimplicialSetStream`/
  `FilteredSimplicialSetStream`, `CechStream`, `WitnessStream`, `UnionFind`.
- **`homology`** — the persistence algorithms (`Homology.scala`, `PackedRipserCohomology.scala`,
  `Cohomology.scala`).
- **`barcode`** — `Barcode`, `PersistenceBar`, `BarcodeEndpoint`.
- **`alpha`** — `AlphaShapes` (`HelixDelaunay`/`AlphaShapeDQP`), `AlphaComplexDQP`.
- **`io`** — file-format adaptors: `CSV`, `Ripser`, `Dipha`, `Gudhi`, `Perseus`.
- **`cli`** — the `tda4j` executable (`TDA4jConf`, `TDA4jCLI`), a thin translator over `matlab.TDA4j`/`io`.
- **`matlab`** — `TDA4j`/`PersistenceResult`/`LandmarkSelectionResult`, the plain-primitives facade for MATLAB
  and other Java callers.
- root (`org.appliedtopology.tda4j` itself) — `package.scala` (`TDAContext`), the user-facing Scala facade.

**Load-bearing import rule**: every file that reaches across a subpackage boundary does it via
`import org.appliedtopology.tda4j.<pkg>.{given, *}` — the `given` matters. A plain `import pkg.*` does
**not** bring `given` instances into scope in Scala 3, and this codebase's `Ordering`/`RingModule`/`Field`
instances are all `given`s. Forgetting `given` compiles cleanly and fails at a summon site with a
confusing "no given instance" error far from the missing import.

## The algebraic core

### `RingModule` and `Field`

`algebra/RingModule.scala` defines what it means for a type `Self` to be a module over a ring-like type `R`:

```scala 3
trait RingModule:
  type Self
  type R
  def zero: Self
  def plus(x: Self, y: Self): Self
  def minus(x: Self, y: Self): Self = plus(x, negate(y))
  def negate(x: Self): Self = minus(zero, x)
  def scale(x: R, y: Self): Self
  extension (t: Self)
    def +(rhs: Self): Self = plus(t, rhs)
    def -(rhs: Self): Self = minus(t, rhs)
    def <*(rhs: R): Self = scale(rhs, t)
    def unary_- : Self = negate(t)
  extension (r: R)
    def |*|(t: Self): Self = this.scale(r, t)
    def ⊠(t: Self): Self = this.scale(r, t) // "boxed times" -- scalar action
```

A minimal instance only needs `zero`, `plus`, `scale`, and one of `minus`/`negate`. `⊠` (U+22A0, typed via
a Unicode input method or copy-paste) is the scalar-multiplication operator used throughout: `2.0 ⊠ chain`.

`algebra/Field.scala` is a separate, self-contained typeclass (not built on `RingModule`) for coefficient
types themselves — `plus`/`minus`/`times`/`divide`/`negate`/`invert`/`zero`/`one`, with `+`/`-`/`*`/`/`/`eql`
extension operators. **There is no default `given Double is Field` anywhere in `src/main`** — bring one in
explicitly:

```scala 3
given Double is Field = Field.DoubleApproximated(1e-9)
```

`Field.DoubleApproximated(epsilon)` treats two doubles as equal within `epsilon` — necessary because exact
floating-point equality is rarely what you want when an `isZero` check decides whether a chain entry
survives a reduction step. `FiniteField.scala` gives exact, non-approximate coefficients instead:
`FiniteField(p)`'s `Fp` is an opaque type *per instance* (so `Fp` from a mod-5 field and a mod-7 field are
distinct, incompatible types), with exact arithmetic via a precomputed inverse table.

### `Cell`, `OrderedCell`, and what `boundary` returns

```scala 3
trait Cell extends HasDimension:
  type Self
  extension (self: Self) def boundary[CoefficientT: Field]: Seq[(Self, CoefficientT)]

trait OrderedCell extends Cell:
  type Self: Ordering as ordering
```

**`boundary` returns a plain `Seq[(Self, CoefficientT)]`, not a `Chain`.** Callers wrap the result in
`Chain.from(...)` when they need actual `Chain` machinery.

`Simplex[VertexT]`, `Cube`, and `FiniteSimplicialSet[G]`'s generators are the library's three concrete
`OrderedCell` instances (`cells/SimplexOrderedCell.scala`, `cells/CubicalOrderedCell.scala`,
`cells/SimplicialSet.scala`): `boundary` returns each codimension-1 face paired with alternating signs.

There is no dual `Cocell`/`OrderedCocell` trait pair — an earlier version of this codebase had one, and it was
removed: coboundary is *extrinsic* to a cell (it depends on which higher-dimensional cells actually exist in
the ambient, possibly-truncated complex), not intrinsic the way `boundary` is, so a per-cell `coboundary`
method with no complex to consult can only be correct when the complex is always the full
combinatorially-possible one. `RipserCohomologyContext`/`PackedRipserCohomologyContext` compute coboundaries
directly against `SimplexIndexing`'s cofacet iterator instead (see
[Persistence engines](persistence-engines.md)).

### `Chain`

`Chain[CellT: Ordering, CoefficientT: Field]` (`algebra/Chain.scala`) is a formal sum of cells with field
coefficients, backed by a mutable `PriorityQueue` ordered so the *smallest* cell under the ambient
`Ordering[CellT]` sits at the head — cheap to peek, since "leading term" (the reduction pivot) is queried
constantly.

- `collapseHead()`/`collapseAll()` merge duplicate-cell entries, dropping exact zeros. Naive `+`/`-`/`⊠`
  only lazily collapse the head, so a hand-rolled reduction loop built out of raw `Chain` arithmetic
  accumulates an ever-growing backlog of uncollapsed duplicates — see
  [Hard-won invariants](gotchas.md).
- `Chain.reduceBy`/`Chain.reduceByUntil` are the actual matrix-reduction primitives every persistence
  engine builds on: given a chain `z` and a `basis: Map[CellT, Chain[CellT, CoefficientT]]` of recorded
  pivot columns, repeatedly subtract the appropriate multiple of `basis(pivot)` until `z`'s leading cell is
  no longer a key in `basis`, returning both the reduced chain and a reduction-log chain (the multipliers
  used) that lets a caller reconstruct a V-column. Internally these go through a `mutable.TreeMap`, not the
  `PriorityQueue`-backed `Chain` type, so repeated updates collapse duplicates automatically.
- `given [CellT: Ordering, CoefficientT: Field] => (Chain[CellT, CoefficientT] is RingModule)` is what makes
  `+`, `-`, `⊠`, `unary_-` work on `Chain` values — the `given` whose summon *timing* matters, see
  [Hard-won invariants](gotchas.md).

## Complex construction: streams

A `CellStream[CellT, FiltrationT]` is the abstract interface every persistence engine consumes: an iterator
over cells in filtration order, plus a `filtrationValue: PartialFunction[CellT, FiltrationT]` and a
`Filterable` (smallest/largest sentinel values, `±Infinity` for `Double`). Trait hierarchy, most-general to
least:

```
Filtration[CellT, FiltrationT]           -- has a filtrationValue partial function
CellStream[CellT, FiltrationT]           -- Filtration + IterableOnce[CellT] + filtrationOrdering
SimplexStream[VertexT, FiltrationT]      -- CellStream specialized to Simplex[VertexT]
StratifiedCellStream[CellT, FiltrationT] -- adds iterateDimension: dimension-by-dimension access
```

**Two rules that hold for every stream in this codebase**, both explained in full in
[Hard-won invariants](gotchas.md):

- `filtrationOrdering` orders cells by filtration value **reversed** (so the *smallest*-under-this-ordering
  cell is the *youngest*), because `Chain`'s pivot is always the ordering's minimum and the reduction
  algorithms need "leading cell" to mean "youngest." A stream's iteration order and its `filtrationOrdering`
  must be the *same* total order (one the consistent `.reverse` of the other), not merely two independently
  valid orderings.
- `StratifiedCellStream.iterateDimension`'s domain must be contiguous from `0` (defined for `0, ..., k` or
  all of ℕ, never with a gap) — the default `.iterator` stops at the first dimension it's undefined for.

### Vietoris-Rips: several independent generation strategies

These are **alternate engines with the same output contract, not layers on one another** — check which
`iterateDimension`/`iterator` implementation is actually in play before reasoning about a bug:

- `EnumeratingCofaceSimplexStream` — generates each dimension explicitly via `SimplexIndexing`'s
  combinatorial-number-system enumeration, filtered by an optional `keepCriterion`.
- `RipserCofaceSimplexStream` — a coface-generation variant that only expands cofaces reachable from the
  previous dimension's survivors rather than re-enumerating a whole dimension from scratch. `CechStream`'s
  `CechCofaceSimplexStream` is built directly on top of this class (see below).
- `InorderCofaceSimplexStream` — generates cofaces "in order" directly from the metric-space structure.
- `IncrementalVietorisRipsSimplexStream` — a reference implementation of Antonio Rieser's New-VR algorithm
  (arXiv:2301.07191), meant as a cross-validation baseline rather than a speed-competitive engine.
- `RecursiveStackVietorisRipsSimplexStream` — a third, independent coface-enumeration strategy built on a
  recursive stack and spatial query.

`EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream`/`InorderCofaceSimplexStream`/
`IncrementalVietorisRipsSimplexStream` all default `maxFiltrationValue` to `metricSpace.minimumEnclosingRadius`,
not `Double.PositiveInfinity` — past that radius every vertex is within range of a common apex, so the
complex is a cone from there on and provably contributes no further homology (real `ripser.cpp` uses the
same default). Pass `maxFiltrationValue = Some(Double.PositiveInfinity)` for the old always-unbounded
behavior. `RecursiveStackVietorisRipsSimplexStream` and the alpha-complex backends keep their own,
always-untruncated default (see [Alpha complex](alpha-complex.md)).

`SimplexIndexing` (inside `SimplexIndexing.scala`) is the piece every Ripser-flavored part of this codebase
depends on: it encodes/decodes a `d`-subset of `{0, ..., vertexCount-1}` to/from a single integer index via
binomial-coefficient lookups, in `O(d)` either direction. Its `cofacetIterator`/`facetIterator` walk the
*complete* `vertexCount`-point abstract simplex with no notion of `maxDimension` truncation at all — see
[Hard-won invariants](gotchas.md) before building anything new directly on them.

### Cubical complexes

`Cube` (`cells/Cubical.scala`) is an elementary cube: a product of `n` (fixed ambient dimension) factors,
each a degenerate point `[a,a]` or a unit interval `[a,a+1]`, via the standard "doubled coordinate"
encoding (axis `k` is `2a` or `2a+1`). `opaque type Cube = Vector[Int]` — `Vector`, not `Array`, so
structural equality works for `Chain`'s pivot tables. Boundary sign alternates over a non-degenerate axis's
*rank among the other non-degenerate axes*, not its raw coordinate position — get this wrong and `d(d(x))`
fails to vanish as soon as a cube has an interleaved degenerate axis, invisibly over a field of
characteristic 2.

`CubicalGridStream` (`streams/CubicalStream.scala`) is a dense cubical complex over a full rectangular grid
(the standard T-construction): a caller-supplied `topCellValue` gives every top-dimensional cube its own
value, and every lower-dimensional cube's value is the `min` over the top cells containing it — computed
directly via the cartesian product over the cube's degenerate axes rather than a recursive coface walk.
`CubicalImage` builds these from images/voxel grids (`fromFlatArray`/`fromBufferedImage`/`fromFile`/
`fromVoxelGrid3D`); sublevel vs. superlevel filtration is handled by negating values on load, not as a flag
on the stream itself. `CubicalHomologyContext` is a one-line `Cube`-specialized wrapper around
`CellularHomologyContext` — cubical complexes needed no new engine code, only a new `OrderedCell` instance.

### Simplicial sets

`SSetElement[G](word, generator)` (`algebra/SSetElement.scala`) plus `FiniteSimplicialSet[G]`
(`cells/SimplicialSet.scala`) implement finite simplicial sets in the classical Eilenberg-Zilber
presentation: a finite set of non-degenerate generators per dimension, plus primitive face data
`faces: G => IndexedSeq[SSetElement[G]]`. `word` is the degeneracy indices in normal form — **strictly
decreasing**, not increasing, a direct consequence of the simplicial identity `s_i s_j = s_{j+1} s_i`
(`i <= j`). `insertOuter`/`faceOf` implement the full operator algebra (`s_i`/`d_i` on arbitrary, possibly
degenerate elements), which is what lets `FiniteSimplicialSet.validate()` check that hand-supplied face data
actually satisfies the simplicial identities.

`FiniteSimplicialSet`'s companion object (`cells/SimplicialSet.scala`; the ordering helpers it uses live in
`cells/SimplicialSetConstructions.scala`) builds new simplicial sets from old: `product`/`coproduct`
(categorical product/coproduct — a product's non-degenerate simplices
are pairs `(a, b)` with *disjoint* degeneracy words, not Eilenberg-Zilber shuffles, and its top dimension is
`maxDim(x) + maxDim(y)`), and `quotient`/`identify` (attaching maps — `quotient` takes `G => SSetElement[G]`
rather than `G => G` specifically so a cell can collapse down a dimension onto a degenerate point, the
Δ-complex model of ℝP² needs exactly this for one of a triangle's three edges).

`streams/SimplicialSetStream.scala` adapts a `FiniteSimplicialSet[G]` to `CellStream` for ordinary,
unfiltered homology (every generator at filtration value `0`); `fromStream` builds a `FiniteSimplicialSet`
from any simplex stream. `streams/FilteredSimplicialSetStream.scala` is the genuine
`StratifiedCellStream[G, Double]`, with a caller-supplied `filtrationValue` and
`validateMonotoneFiltration` to check the one precondition every engine needs (a face's value never exceeds
its coface's).

### Cech complexes

`streams/CechStream.scala` (`CechCofaceSimplexStream`/`CechFiltration`) builds Cech complexes over
`Simplex[Int]`, reusing `RipserCofaceSimplexStream`'s generic coface-generation loop via its
`filtrationValueOverride` hook rather than a from-scratch algorithm. The geometric primitive is
minimum-enclosing-ball radius (`com.dreizak:miniball`), not the Delaunay/QP machinery alpha shapes use —
Cech membership depends only on a simplex's own vertices, not on every other point in the cloud.
`CechFiltration` caches each simplex's radius and clamps it to the max of its own facets' cached radii,
since a raw Miniball call can violate monotonicity by a floating-point ULP on near-degenerate input.

New-VR's Table-Lookup optimization and the packed Ripser engine's optimizations do **not** carry over to
Cech: both are proven specifically for flag complexes / the max-pairwise-distance functional, and Cech is
neither a flag complex nor governed by that functional. Cech complexes work with either generic engine
(`CellularHomologyContext`/`CellularPersistenceInChunksContext`), cross-validated against each other; the
packed and reference Ripser engines (specialized to the Vietoris-Rips functional) are not offered for it.

### Witness complexes

`streams/WitnessStream.scala` implements De Silva & Carlsson's witness complex (2004), checked directly
against JavaPlex's own `LazyWitnessStream`/`WitnessStream` Java source (not just the paper's prose).
`LandmarkSelector.maxmin`/`.random` pick a landmark subset of a `FiniteMetricSpace[Int]`; `WitnessGeometry`
precomputes the landmark-to-witness distance matrix `D` and each witness's sorted landmark-distance row
(`mDim(k, witness)`, the `(k+1)`-th nearest landmark — the one primitive both variants below are built from).

Two independent implementations, both reusing `RipserCofaceSimplexStream`'s generic coface-generation loop
(the same reuse `CechCofaceSimplexStream` makes) rather than a from-scratch enumeration:

- **`LazyWitnessSimplexStream`** (JavaPlex's `LazyWitnessStream`): the lazy witness complex IS, by
  definition, the flag/clique complex of a weighted graph, so `WitnessMetricSpace` reifies that graph as an
  ordinary `FiniteMetricSpace[Int]` (`distance(a,b)` = the edge witness value at a fixed `nu` in `{0,1,2}`)
  and hands it to `RipserCofaceSimplexStream` **unmodified** — no `filtrationValueOverride` at all; the
  inherited "max pairwise distance" flag extension is exactly what a lazy witness complex wants. Because it
  really is a flag complex, `PackedRipserCohomologyContext` — proven only for genuine VR diameters — is
  *also* valid here (any `FiniteMetricSpace[Int]`'s own diameter, not something VR-specific despite the
  class's name), cross-validated directly in `WitnessStreamSpec` rather than assumed.
- **`WitnessCofaceSimplexStream`** (JavaPlex's plain `WitnessStream`): NOT a flag complex — a `k`-dimensional
  simplex's own witness value uses a dimension-specific threshold (`m_k`, JavaPlex's own per-dimension array)
  that need not be monotone facet-to-coface on its own. `filtrationValueOverride` here is a genuinely
  recursive function (`max(own_k(sigma), max over sigma's own facets)`, `TrieMap`-memoized, since the base
  class doesn't memoize a caller-supplied override) — that recursive max is what makes "the complex at
  threshold R" automatically downward-closed for every R, the same way VR's max-pairwise-distance does, and
  it's also what makes JavaPlex's separate `containsElement(face)` gate redundant here (proof and empirical
  check both in `WitnessStreamSpec`). `maxFiltrationValue` defaults to `+Infinity`, not the metric space's
  enclosing radius — that shortcut is only valid for flag complexes.

A fact used to cross-validate the two constructions against each other (`WitnessStreamSpec`): the general
complex's own 1-skeleton is identical to the lazy complex's at `nu = 2` — both use the 2nd-nearest-landmark
threshold for edges, just reached via different code paths.

### Sheehy's sparse/approximate Vietoris-Rips filtration

`streams/SheehyRipsStream.scala` (`SheehyRipsSimplexStream`) implements Cavanna, Jahanseir & Sheehy's greedy-
permutation reformulation (arXiv:1506.03797) of D.R. Sheehy's original net-tree construction (arXiv:1203.6786,
DCG 49(4) 2013) — the two papers' own `epsilon` parameters are not comparable. Given a sparsity parameter
`epsilon in (0,1)` and a full greedy permutation of the point set (`LandmarkSelector.maxmin` run to
`numLandmarks = metricSpace.size`, extended to also expose each point's own insertion radius `lambda`), the
resulting persistence barcode is a `(1+epsilon)`-multiplicative approximation to plain Vietoris-Rips's own
barcode, from a complex whose size is linear in `n` for point sets of bounded doubling dimension — fewer
simplices to *reduce* at the same scale range, in exchange for a controlled, quantified loss of precision.

Like Cech/Witness above, this reuses `RipserCofaceSimplexStream`'s generic coface-generation loop rather than
a from-scratch enumeration, but via a different shape: the ambient metric space is passed through UNMODIFIED
(used only for combinatorial enumeration, never its own `.distance`), and one `filtrationValueOverride`
computes every dimension `>= 1` directly from the greedy permutation's own `lambda` values — not a reified
weighted `FiniteMetricSpace` the way `WitnessMetricSpace`/`DtmRipsSimplexStream`'s own reified space are. The
reason is a genuine exclusion rule with no pairwise-only expression: a `k`-simplex's value is the `max` of its
own edges' births, but ONLY if that value doesn't exceed the smallest of its OWN VERTICES' "vanish" times
(`lambda_p * (1+epsilon)^2 / epsilon`) — a condition that has to see every vertex of the simplex at once, not
just a pair, so a plain "flag complex over a weighted pairwise distance" shape can't carry it.

A documented, verified gap in the source paper's own published algorithm: its `EdgeBirthTime` (Algorithm 3)
computes an edge's birth from its two endpoints alone, with no check against either one's own vanish time —
but the paper's own general `SimplexBirthTime` rule (Section 5.3) requires exactly that check, and an edge is
simply that rule's `k=1` case, not a special one. Verified with two counterexamples, the second checked
directly against the paper's own restricted neighbor-search bound (not just this implementation's own all-pairs
enumeration) — see `.claude/WORKLOG-sheehy-rips.md` for both. This implementation applies the check uniformly
from dimension 1 up.

This construction is deliberately `O(n^2)` (every pairwise edge birth materialized directly), not the paper's
own `O(n log n)` neighbor-search algorithm (Section 5, Algorithms 1-4, not implemented) — the payoff is a
smaller complex to reduce, not a faster one to build, the same honest framing as Cech/Witness/alpha above.
Refuses `engine="ripser"`: a simplex's value here is not simply the maximum ambient pairwise distance among
its vertices (some pairs are sparsified to a smaller value, others excluded outright), so
`PackedRipserCohomologyContext`'s `insertionDiameter`/apparent-pairs optimizations — proven specifically for
that functional — do not apply. `naive`/`chunks`/`cohomology` all consume it like any other
`CofaceSimplexStream[Int, Double]`; `chunks` is cross-validated fresh against `naive`
(`SheehyRipsStreamSpec`), not assumed to carry over from any other construction — see
[Persistence engines](persistence-engines.md)'s own streams-vs-engines table for the full picture across every
construction, not just this one.

### Flag-complex edge collapse

`streams/EdgeCollapseStream.scala` (`EdgeCollapse.collapse`, `.claude/WORKLOG-edge-collapse.md`) implements
Boissonnat-Pritam's edge collapse (SoCG 2020) and Glisse-Pritam's own refinement (SoCG 2022): reduces a
Vietoris-Rips filtration's 1-skeleton to a smaller weighted graph whose flag complex has the SAME persistent
homology at every filtration level, using only the graph itself — no higher simplices are ever built to decide
what to remove. An edge is **dominated** by a vertex `w` (not one of its own endpoints) iff every vertex
adjacent to both endpoints is also adjacent to `w` — for a flag complex this depends only on the graph, verified
directly against `GUDHI`'s own edge-collapse module (`Flag_complex_edge_collapser.h`, co-authored by Pritam and
Glisse themselves; the actual papers were unreachable from this session's network policy — see the worklog).
Across a whole filtration, a dominated edge's own entry time is pushed forward to the largest time it remains
dominated (by, in general, a succession of different dominators as new common neighbors arrive), or removed
outright if that domination never breaks. Reified as `EdgeCollapsedMetricSpace`, a `FiniteMetricSpace[Int]` over
the same vertex ids — exactly the pattern `WitnessMetricSpace` already established for a non-metric,
construction-derived weighted graph — so it slots directly into `EnumeratingCofaceSimplexStream`/
`RipserCofaceSimplexStream` unmodified. Vertices are never removed (only GENUINELY correcting the originating
worklog's own first-draft phrasing, "dominated-vertex removal" — that is a *different* construction, strong
collapse, `arXiv:1809.10945`); `+Infinity` marks a collapsed-away pair, matching `SparseMetricSpace`'s own
"+Infinity past the cutoff" convention; `minimumEnclosingRadius` is overridden to the bound the collapse itself
used, not computed from the (now partly-infinite) collapsed graph — the same enclosing-radius hazard
`SheehyRipsSimplexStream`'s own truncation clamp already had to close, for an unrelated reason.

**Representatives transfer through inclusion, not a separate lifting step**: at every filtration level, the
collapsed complex is a literal subcomplex of the original (a collapse only ever removes cells or defers their
entry, never adds or identifies anything), so a cycle/cocycle representative computed on the smaller complex is
automatically a valid representative of the same class in the bigger one — contrast the MST-based simplicial-set
collapse the originating worklog also considered and declined, where the analogous complex is a *quotient*, and
lifting a representative back is a genuine extra step.

**This implementation is a faithful port of the reference algorithm's own single-pass, descending-filtration-
value, live-mutating-state structure — not an independently-designed alternative**, after an independently-
designed "iterate a definition-driven resolution to a whole-graph fixed point" first draft was tried and proven
wrong by this class's own cross-validation (two distinct over-collapsing bugs, each one silently turning a real,
finite bar essential — see the worklog for both). The processing order is load-bearing, not an implementation
convenience: reconsider it directly from the reference source before ever touching this file's core loop, don't
re-derive a substitute. Cross-validated by barcode agreement against plain, uncollapsed VR (property-tested
random point clouds, a tie-heavy grid, hand-built fixtures pinning both the shift and the outright-removal
outcome) — the real oracle throughout, not agreement with any external tool.

**Enumeration cost is not reduced uniformly** (checked from source, not assumed): `EnumeratingCofaceSimplexStream`
and `PackedRipserCohomologyContext`'s own internal `CofacetCursor`-based enumeration both scan a fixed
combinatorial range regardless of graph sparsity (vertices are never removed), though `EnumeratingCofaceSimplexStream`'s
own downstream sort-and-cache pass over the *surviving* candidates does shrink; `RipserCofaceSimplexStream`'s
own enumeration (built from the previous dimension's own survivors) benefits directly and proportionally.
**Reduction cost benefits substantially regardless of engine** — measured 73-76% of edges removed and a 43-47x
reduction-phase speedup on random point clouds (`EdgeCollapseBenchmarkSpec`, gated the same way
`ApparentPairsBenchmarkSpec` is), against a far more modest 1.45-1.74x construction-phase speedup — see the
worklog for the full table and the source-level reasoning behind the split.

### Metric spaces

`FiniteMetricSpace.scala` abstracts "distance + finite point set": `ExplicitMetricSpace` (raw distance
matrix), `EuclideanMetricSpace` (coordinate array, on-demand Euclidean distance, VP-tree-backed `neighbors`
query), `IntMetricSpace` (reindexes to contiguous `0 until size`), `SparseMetricSpace` (reports `+Infinity`
beyond a fixed diameter cutoff, bounding Vietoris-Rips construction to a finite neighborhood per point).

### Opt-in parallelism

A few of the more expensive per-cell computations can run on the common `ForkJoinPool`, opt-in via a
constructor flag defaulting to `false`, with output that is deterministic either way:
`AlphaDQPSettings.parallel` (the per-vertex QP solve), `CubicalGridStream.parallelFiltrationValue`, and
`CechCofaceSimplexStream.parallelFiltrationValue`. These are worth reaching for on large inputs where the
per-cell cost is real (a QP solve, a Miniball radius) but shouldn't be assumed to give a large win — measured
speedups range from a few percent (cubical, Cech — the per-cell cost there is small enough that scheduling
overhead eats most of the gain) up to roughly 2-4x for the alpha-complex QP solve at a few hundred points and
above, where each per-vertex solve is genuinely expensive.

## `Barcode.scala`: representing the output

`org.appliedtopology.tda4j.barcode` defines `BarcodeEndpoint` (`PositiveInfinity`/`NegativeInfinity`/
`OpenEndpoint`/`ClosedEndpoint`, with a total order that correctly interleaves finite and infinite
endpoints) and `PersistenceBar[FiltrationT, AnnotationT]` (dimension, lower/upper endpoint, an optional
annotation — in practice always the representative `Chain`). `Barcode` additionally implements algebra on
finitely-presented persistence modules: `image`/`kernel`/`cokernel` of a map between two barcodes
represented as a matrix — useful for interleaving distances or persistence-module morphisms, not needed for
ordinary persistent-homology computation.

Two more objects in the same package compare/summarize already-computed diagrams rather than computing one
(`.claude/WORKLOG-mainstream-feature-gap-analysis.md` items 4/8) — both specialized to `PersistenceBar[Double,
_]` (unlike `Barcode`'s own `FiltrationT: Ordering` genericity: every real engine in this codebase already
produces `Double` filtration values, and a metric distance needs real arithmetic, not just an `Ordering`):

- **`BarcodeDistance`**: bottleneck and Wasserstein distance between two single-dimension diagrams
  (`bottleneckDistance`/`wassersteinDistance`, plus `...ByDimension` convenience wrappers that group a
  multi-dimensional barcode first). Ground metric and aggregation-order convention cross-checked against
  Hera/GUDHI's own (`GroundNorm.LInfinity`/`LP(p)` is their `internal_p`, the `order` parameter is their
  `order`/`wasserstein_power`). Essential (never-dying) bars are matched only to each other, by sorted birth
  value; a mismatched essential-bar count between the two diagrams reports `Double.PositiveInfinity`, not an
  exception — a real, meaningful answer ("no finite matching exists"), not a failure. Built on two
  package-private combinatorial primitives in `BipartiteMatching.scala` (`HopcroftKarp` for the bottleneck
  binary search, `Hungarian` for Wasserstein's assignment problem) — both independently unit-tested against
  brute-force permutation search, not just exercised indirectly through `BarcodeDistance` itself.
- **`Vectorization`**: persistence landscapes (Bubenik 2013) and persistence images (Adams et al. 2017),
  turning a diagram into a fixed-size `Array[Array[Double]]` for downstream (e.g. ML) use. The two handle
  essential bars differently, deliberately: a landscape's tent function `max(0, min(t - birth, death - t))`
  degrades to the meaningful, finite ramp `t - birth` exactly at `death = Infinity`, so essential bars are
  included with no special-casing; a persistence image's Gaussian bump is centered at `(birth, Infinity)` in
  birth-persistence coordinates, which has no overlap with any finite pixel grid, so essential bars are
  dropped outright rather than left to silently underflow to zero. Persistence images integrate each pixel's
  weighted Gaussian mass *exactly* (a product of 1D normal-CDF differences, since an isotropic Gaussian's mass
  over a rectangle factors along both axes), not by sampling the surface at the pixel center.

`matlab.PersistenceResult` exposes both as instance methods (`bottleneckDistance`/`wassersteinDistance`
against another `PersistenceResult`, `landscape`/`persistenceImage` on itself) — see that class's own doc.
`cli.TDA4jCLI`'s `--distance-to` mirrors `BarcodeDistance` only (reading a second diagram via
`io.{CSV,Gudhi,Dipha}.readPersistenceDiagram`); the vectorizations are deliberately not mirrored in the CLI,
since they produce a matrix rather than a diagram, which does not fit this CLI's existing single-diagram
output model — see `TDA4jConf.distanceTo`'s own doc.

`matlab.PersistenceResult` also exports the **boundary matrix** of the full complex it was computed from
(`.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 1) — `numCells`/`boundaryRows`/`boundaryCols`/
`boundaryValues`/`columnDimension`/`columnVertices`/`columnFiltrationValue`, rebuildable MATLAB-side as
`sparse(rows()+1, cols()+1, values(), n, n)`. Built lazily (a caller who never asks never pays for it) by
`TDA4j.buildBoundaryMatrix`, called once per `complex` branch in `computeGeneric`/`computeWitnessFromLandmarks`/
`computeCubicalGeneric` from the SAME stream/metric-space construction `engine=naive` already consumes for that
complex — **regardless of which engine actually computed this result's own bars**, since the boundary matrix is
a property of the complex, not of which reduction algorithm ran over it (confirmed directly, not just designed
that way: `naive`/`ripser`/`chunks`/`cohomology` all export byte-for-byte identical matrices for the same
input). Like the vectorizations above, deliberately not mirrored on the CLI — a sparse matrix doesn't fit the
CLI's diagram-in-diagram-out shape any better than a landscape/image array does.

## `homology.CircularCoordinates` (`CircularCoordinates.scala`)

`org.appliedtopology.tda4j.homology` also holds a standalone construction rather than a fifth persistence
engine: circular coordinates (de Silva-Morozov-Vejdemo-Johansson 2011,
`.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 2), which turns one persistent H¹ class of a
Vietoris-Rips complex into a map from (a connected subset of) the point cloud to the circle `R/Z`. `h1Bars`
lists every persistent H¹ class's `(birth, death)`, sorted by persistence descending — the only way a caller
can pick a meaningful threshold `r` for `compute` below, so it's the intended first call, not a diagnostic.

**The reframing that makes this tractable** (the originating worklog's own contribution, not just a
literature port): rather than asking whether a *finite* bar's already-computed representative happens to
restrict to a nonzero cocycle on some sub-level complex — an open question about an existing artifact —
`compute` fixes `r` inside the target bar's `[birth, death)` up front, builds the *static* truncated complex
`K_r` (`maxFiltrationValue = Some(r)`, the same knob enclosing-radius truncation already uses), and computes
`CellularCohomologyContext`'s persistent cohomology of that fixed complex directly. The target class is
essential at `K_r` *by construction* — nothing survives past `r` in a view that stops at `r` — so the
verification question dissolves rather than needing an answer. Matching one of possibly several
simultaneously-alive `K_r`-essential classes back to the specific full-filtration bar `h1Bars` reported turns
out to need only a birth-value comparison: truncating the *end* of a filtration cannot change how early
something is born, so `K_r`'s persistent cohomology (fed the same filtration values, just cut off at `r`)
assigns every bar the same birth it has in the full computation.

The chosen cocycle is computed over an odd prime field (`prime`, default `47` — not this library's usual `2`
default, since an RP²-type class exists over `F_2` with no real/integer lift at all, making a mod-2 "cocycle"
a mirage for coordinatization specifically), lifted to an integer cochain, and checked EXACTLY (not just mod
`prime`, which the field computation already guarantees trivially) against every triangle of `K_r` —
`NoIntegerCocycleException` (a `RuntimeException`, crossing the MATLAB bridge the same way
`IllegalArgumentException` already does) if some triangle's integer boundary doesn't sum to zero, naming the
offending triangle. Verified, not assumed: a class that fails this check is either genuinely torsion or needs
a larger prime; `compute` does not silently coordinatize against a mod-`prime` mirage either way.

The verified integer cocycle is then harmonically smoothed: `min_g ||z - d0 g||^2` for a real vertex function
`g` (`d0` the 0-coboundary map), via the normal equations `d0^T d0 g = d0^T z` — a sparse SPD least-squares
solve, not "optimization" in the LP/QP sense that `.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 3
(optimal cycles, deprioritized) actually needs. Solved matrix-free with the already-vendored
`org.apache.commons.math3.linear.ConjugateGradient` against a `RealLinearOperator` built directly from
`Simplex.boundary[Double]` — no dense matrix materialized, no new dependency — restricted to the connected
component of `K_r`'s 1-skeleton containing the cocycle's own support (a class is only meaningful where a path
exists to integrate it along; other components get no coordinate at all, not a sentinel), with one arbitrary
vertex in that component anchored at `g = 0` to make the reduced system genuinely positive *definite* (the
unreduced graph Laplacian is singular on constants, one null dimension per connected component). The output
coordinate is then, directly, `theta(v) = frac(g(v))` — no separate path-integration step, confirmed against a
real reference implementation (`scikit-tda/DREiMac`'s `toroidalcoords.py`, fetched and read directly) rather
than derived from the paper's more abstract statement alone.

`matlab.TDA4j.h1Bars`/`circularCoordinates` (returning `CircularCoordinatesResult`) mirror `h1Bars`/`compute`
for a MATLAB caller — a genuinely different result *shape* (a per-point angle, `Double.NaN` for a point
outside the relevant component) from `PersistenceResult`'s barcode, hence its own small entry points rather
than a new `complex=circular` value on `computeFromPoints`. Like the vectorizations and boundary-matrix export
above, deliberately not mirrored on the CLI: the natural output is a per-point angle array, not a diagram, and
picking a meaningful `r` is an inherently interactive, data-dependent choice (`h1Bars` then `compute`) that
doesn't reduce to a single flag the way `--distance-to` does for `BarcodeDistance`.

```scala 3
class TDAContext[VertexT: Ordering, CoefficientT: Field, FiltrationT: Ordering]
    extends SimplicialHomologyContext[VertexT, CoefficientT, FiltrationT]():
  val chainIsRingModule = summon[Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT }]
  export chainIsRingModule.*
  given [T: Ordering] => Conversion[Simplex[T], Chain[Simplex[T], CoefficientT]] = Chain.apply
```

`TDAContext` takes **three** type parameters (`VertexT`, `CoefficientT`, `FiltrationT`). It *is* a
`SimplicialHomologyContext` (see [Persistence engines](persistence-engines.md)), plus it exports
chain-arithmetic operators (`+`, `-`, `⊠`, ...) into your namespace and provides an implicit
`Simplex -> Chain` conversion so you can write `∆(1,2) - ∆(2,3)` directly — the basis for the
[User's Guide](../user-guide/README.md)'s Scala quick-start.
