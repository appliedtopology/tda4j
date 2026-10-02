---
layout: main
title: Architecture: from algebra to persistence
---


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
- root (`org.appliedtopology.tda4j` itself) — `package.scala` (`TDAlab`), the pylab-style user-facing Scala entry point.

**Load-bearing import rule**: every file that reaches across a subpackage boundary does it via
`import org.appliedtopology.tda4j.<pkg>.{given, *}` — the `given` matters. A plain `import pkg.*` does
**not** bring `given` instances into scope in Scala 3, and this codebase's `Ordering`/`RingModule`/`Field`
instances are all `given`s. Forgetting `given` compiles cleanly and fails at a summon site with a
confusing "no given instance" error far from the missing import.

## Scala entry point: `TDAlab`

```scala 3
import language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val tdalab = TDAlab(0)          // 0 = Double coefficients; a prime p = Z/p
import tdalab.{*, given}
val chain = Fp(1) ⊠ ∆(1, 2) - ∆(2, 3)       // a Chain
```

`TDAlab(characteristic, precision = 1e-9)` (root `package.scala`) is the pylab-style entry point: instantiate it
once, then `import tdalab.{*, given}`. That brings into scope `Fp(...)` coefficients of the chosen field, the
chain-arithmetic operators (`+`, `-`, `⊠`, ...), `∆`/`Simplex`/`Cube` literals, an implicit
`Simplex -> Chain` conversion so `∆(1,2) - ∆(2,3)` works directly, and `cats` `Show` syntax (`.show`). Vertices are
fixed to `Int`. It deliberately does **not** extend or wrap an engine: engines are constructed explicitly
(e.g. `SimplicialHomologyEngine[Int, Double, Double]()`, see [Persistence engines](persistence-engines.md)). It
is the basis for the [Tutorials](../tutorials/index.md) and the [User's Guide](../user-guide/index.md)'s Scala
quick-start.

## The algebraic core

### `RingModule` and `Field`

`algebra/RingModule.scala` defines what it means for a type `Self` to be a module over a ring-like type `R`:

```scala sc:nocompile
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
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

given Double is Field = Field.DoubleApproximated(1e-9)
```

`Field.DoubleApproximated(epsilon)` treats two doubles as equal within `epsilon` — necessary because exact
floating-point equality is rarely what you want when an `isZero` check decides whether a chain entry
survives a reduction step. `FiniteField.scala` gives exact, non-approximate coefficients instead:
`FiniteField(p)`'s `Fp` is an opaque type *per instance* (so `Fp` from a mod-5 field and a mod-7 field are
distinct, incompatible types), with exact arithmetic via a precomputed inverse table.

### `Cell`, `OrderedCell`, and what `boundary` returns

```scala sc:nocompile
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
combinatorially-possible one. `RipserCohomologyEngine`/`PackedRipserCohomologyEngine` compute coboundaries
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
on the stream itself. `CubicalHomologyEngine` is a one-line `Cube`-specialized wrapper around
`CellularHomologyEngine` — cubical complexes needed no new engine code, only a new `OrderedCell` instance.

#### Fast cubical homology

`FastCubicalHomologyEngine` computes `H_0` directly with union-find and uses
Alexander duality to compute `H_{d-1}` from a dual graph. In dimensions
`d >= 3`, intermediate homology dimensions fall back to
`CellularPersistenceInChunksEngine`.

This avoids general `Chain` reduction in the largest dimensions of a cubical
complex.

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

Because Cech is not a flag complex, VR-specific packed/coface optimizations do
not apply. It is consumed by the generic homology engines.

### Witness complexes

`streams/WitnessStream.scala` implements both lazy and general witness complexes.

- **`LazyWitnessSimplexStream`** reifies the landmark graph as a
  `FiniteMetricSpace[Int]` and delegates to `RipserCofaceSimplexStream`.
  Because it is a flag complex, it is also compatible with the packed Ripser engine.
- **`WitnessCofaceSimplexStream`** uses a dimension-dependent witness filtration
  and supplies its own recursive filtration-value override. Because it is not a
  flag complex, it uses the generic persistence engines.

Both implementations share `WitnessGeometry` and landmark selection machinery.

### Dowker complexes

`DowkerCofaceSimplexStream` constructs a filtered Dowker complex from an
arbitrary relation `R: L × W -> [0, +Infinity]`. Its filtration value is

`f(σ) = min_w max_{x ∈ σ} R(x,w)`.

Like the general witness and Cech streams, it reuses
`RipserCofaceSimplexStream` for coface enumeration but supplies its own
filtration function. Because the construction is not a flag complex, it uses
the generic persistence engines rather than the packed Ripser engine.

`DowkerGeometry.dual` constructs the complex for the transposed relation.

### Sheehy's sparse Vietoris-Rips filtration

`SheehyRipsSimplexStream` constructs a sparse approximation to the
Vietoris-Rips filtration using a greedy permutation and insertion radii.

It reuses the coface-generation machinery of `RipserCofaceSimplexStream` but
supplies a custom filtration function. Because that filtration is not simply
the maximum ambient pairwise distance, the packed Ripser engine is not
applicable; the generic engines consume it instead.

### Flag-complex edge collapse

`EdgeCollapse.collapse` simplifies the weighted graph underlying a
Vietoris-Rips filtration while preserving persistent homology.

The resulting `EdgeCollapsedMetricSpace` implements `FiniteMetricSpace[Int]`,
so existing Vietoris-Rips streams and persistence engines can consume the
collapsed graph without special handling.

### Metric spaces

`FiniteMetricSpace.scala` abstracts "distance + finite point set": `ExplicitMetricSpace` (raw distance
matrix), `EuclideanMetricSpace` (coordinate array, on-demand Euclidean distance, VP-tree-backed `neighbors`
query), `IntMetricSpace` (reindexes to contiguous `0 until size`), `SparseMetricSpace` (reports `+Infinity`
beyond a fixed diameter cutoff, bounding Vietoris-Rips construction to a finite neighborhood per point).

> **Warning.** `SparseMetricSpace` reports `+Infinity` past its cutoff rather than excluding those pairs — it is not a
> thresholded neighbor oracle. Code that queries it expecting "unreachable" to mean "absent" will instead get
> back a real (if unusable) `Double` value; check `.isFinite` explicitly rather than assuming exclusion.

### Opt-in parallelism

A few of the more expensive per-cell computations can run on the common `ForkJoinPool`, opt-in via a
constructor flag defaulting to `false`, with output that is deterministic either way:
`AlphaDQPSettings.parallel` (the per-vertex QP solve), `CubicalGridStream.parallelFiltrationValue`, and
`CechCofaceSimplexStream.parallelFiltrationValue`. These are worth reaching for on large inputs where the
per-cell cost is real (a QP solve, a Miniball radius.)

## `Barcode.scala`: representing the output

`org.appliedtopology.tda4j.barcode` defines `BarcodeEndpoint` (`PositiveInfinity`/`NegativeInfinity`/
`OpenEndpoint`/`ClosedEndpoint`, with a total order that correctly interleaves finite and infinite
endpoints) and `PersistenceBar[FiltrationT, AnnotationT]` (dimension, lower/upper endpoint, an optional
annotation — in practice always the representative `Chain`). `Barcode` additionally implements algebra on
finitely-presented persistence modules: `image`/`kernel`/`cokernel` of a map between two barcodes
represented as a matrix — useful for interleaving distances or persistence-module morphisms, not needed for
ordinary persistent-homology computation.

`BarcodeDistance` and `Vectorization` provide downstream operations on
computed persistence diagrams, including diagram distances, landscapes, and
persistence images.

### Circular coordinates

`CircularCoordinates` constructs circle-valued coordinates from persistent
H¹ classes. It uses persistent cohomology to obtain a cocycle and harmonic
smoothing to produce coordinates on the relevant connected component.

### Toroidal coordinates

`CircularCoordinates.computeToroidal` extends circular coordinates to several
simultaneously alive H¹ classes. `LatticeReduction` applies an integer change
of basis to decorrelate the resulting torus-valued coordinates.


