# Architecture: from algebra to a filtration stream

This page walks the two lower layers of the library: the algebraic core (what a "chain" and a
"coefficient" actually are) and complex construction (how a sequence of cells in filtration order gets
produced in the first place). @ref:[Persistence engines](persistence-engines.md) covers what consumes the
stream this layer produces.

If a piece of Scala 3 syntax below looks unfamiliar, see the @ref:[Scala 3 primer](scala3-primer.md) first —
this page assumes you've read it.

## The algebraic core

### `RingModule` and `Field`

`RingModule.scala` defines what it means for a type `Self` to be a module over a ring-like type `R`:

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
    def ⊠(t: Self): Self = this.scale(r, t) // unicode ⊠, "boxed times" -- scalar action
```

A minimal instance only needs to implement `zero`, `plus`, `scale`, and one of `minus`/`negate`; the rest
come for free. `⊠` (type it as `Alt+J` doesn't apply here — it's typed via a Unicode input method or
copy-paste; the character is U+22A0) is the scalar-multiplication operator you'll see everywhere:
`2.0 ⊠ chain` scales `chain` by `2.0`.

`Field.scala` is a separate, self-contained typeclass (not built on `RingModule`) for coefficient types
themselves — `plus`/`minus`/`times`/`divide`/`negate`/`invert`/`zero`/`one`, with `+`/`-`/`*`/`/`/`eql`
extension operators. **There is no default `given Double is Field` anywhere in `src/main`.** You must
bring one into scope explicitly wherever you use `Double` coefficients:

```scala 3
given Double is Field = Field.DoubleApproximated(1e-9)
```

`Field.DoubleApproximated(epsilon)` wraps `Fractional[Double]` and treats two doubles as equal when they're
within `epsilon` — necessary because exact equality on floating point is rarely what you want when
`isZero` checks decide whether a chain entry survives a reduction step. Pick `epsilon` relative to the
scale of your data; every spec in `src/test` uses `1e-25` or `1e-9` (see `ChainSpec.scala`,
`HomologySpec.scala`).

`FiniteField.scala` gives you exact, non-approximate coefficients instead: `FiniteField(p)` is a class
whose `Fp` is an *opaque type per instance* (so `Fp` from a mod-5 field and `Fp` from a mod-7 field are
different, incompatible types, even though both are erased to `Int`), with `given (Fp is Field)` supplying
exact arithmetic via a precomputed inverse table. Usage:

```scala 3
val fp17 = FiniteField(17)
import fp17.{given, *}
Fp(1) ⊠ ∆(1, 2) - Fp(2) ⊠ ∆(1, 3)
```

### `Cell`, `OrderedCell`, and what `boundary` actually returns

From `Chain.scala` (see the @ref:[primer](scala3-primer.md)
for the `is`-syntax mechanics):

```scala 3
trait Cell extends HasDimension:
  type Self
  extension (self: Self) def boundary[CoefficientT: Field]: Seq[(Self, CoefficientT)]

trait OrderedCell extends Cell:
  type Self: Ordering as ordering
```

**`boundary` returns a plain `Seq[(Self, CoefficientT)]`, not a `Chain`.** This is easy to get wrong if
you've seen an older draft of this codebase's own docs (an earlier version of this guide showed `boundary`
returning `Chain[Self, CoefficientT]` directly — that was already stale when found; always check current
source, not a doc, when the two disagree). Callers wrap the result in `Chain.from(...)` when they need
actual `Chain` machinery (collapsing, reduction, arithmetic) — see `CellularHomologyContext.advanceOne`'s
`Chain.from(sigma.boundary[CoefficientT])` in `Homology.scala`.

`Simplex[VertexT]` is the library's only current `OrderedCell` instance (`Simplex.scala`): `boundary`
returns each codimension-1 face (drop one vertex) paired with alternating signs `+1, -1, +1, ...`, and
`dim` is `size - 1`. `Cocell`/`OrderedCocell` are the dual traits (`coboundary` instead of `boundary`); no
concrete type implements them yet as of this writing — `RipserCohomologyContext` computes coboundaries
directly against `SimplexIndexing`'s cofacet iterator rather than through a `Cocell` instance (see
@ref:[Persistence engines](persistence-engines.md)).

### `Chain`

`Chain[CellT: Ordering, CoefficientT: Field]` is a formal sum of cells with field coefficients
(`Chain.scala`), backed by a mutable `scala.collection.mutable.PriorityQueue[(CellT, CoefficientT)]`
ordered so the *smallest* cell under the ambient `Ordering[CellT]` sits at the head — cheap to peek, which
matters because "leading term" (the pivot in every reduction algorithm below) is queried constantly.
Key operations:

- `collapseHead()` / `collapseAll()` merge duplicate-cell entries by summing their coefficients (dropping
  exact zeros). `collapseHead` only touches entries tied with the current head; `collapseAll` rebuilds the
  whole queue. Naive `+`/`-`/`⊠` on `Chain` (via its `RingModule` instance, `Chain.scala:176`) only
  lazily collapse the head — repeated raw arithmetic in a loop accumulates an ever-growing backlog of
  un-collapsed duplicates. **Never build a reduction loop out of raw `Chain` arithmetic** — see
  @ref:[Hard-won invariants](gotchas.md).
- `Chain.reduceBy` / `Chain.reduceByUntil` (`Chain.scala:160-174`) are the actual matrix-reduction
  primitives every persistence engine builds on: given a chain `z`, a `basis: mutable.Map[CellT,
  Chain[CellT,CoefficientT]]` of already-recorded pivot columns, and an optional `stop` predicate, they
  repeatedly subtract off the appropriate multiple of `basis(pivot)` from `z` until `z`'s leading cell is no
  longer a key in `basis` (or `stop` fires), returning both the reduced chain and a "reduction log" chain
  recording which pivots were used and with what coefficient — the log is what lets a caller reconstruct a
  V-column (see @ref:[Persistence engines](persistence-engines.md)). Internally these go through an
  `immutable.SortedMap`, not the `PriorityQueue`-backed `Chain` type, specifically so repeated updates stay
  cheap (map insertion collapses duplicates automatically) — this is the actually-efficient path raw `Chain`
  arithmetic is not.
- `given [CellT: Ordering, CoefficientT: Field] => (Chain[CellT, CoefficientT] is RingModule {type R =
  CoefficientT})` (`Chain.scala:176`) is what makes `+`, `-`, `⊠`, `unary_-` work on `Chain` values. This is
  the `given` whose summon *timing* matters — see the primer and @ref:[Hard-won invariants](gotchas.md).

## Complex construction: streams

A `SimplexStream` is the abstract interface every persistence engine consumes: an iterator over cells in
filtration order, plus a `filtrationValue: PartialFunction[CellT, FiltrationT]` and a `Filterable`
(smallest/largest sentinel values — `±Infinity` for `Double`, via `Filterable.scala`'s givens in
`SimplexStream.scala`). The trait hierarchy, most-general to least:

```
Filtration[CellT, FiltrationT]         -- has a filtrationValue partial function
CellStream[CellT, FiltrationT]         -- Filtration + IterableOnce[CellT] + filtrationOrdering
SimplexStream[VertexT, FiltrationT]    -- CellStream specialized to Simplex[VertexT]
StratifiedCellStream[CellT, FiltrationT] -- adds iterateDimension: dimension-by-dimension access
```

`filtrationOrdering` deserves special attention: `SimplexStream`'s default implementation
(`FilteredSimplexOrdering`, `SimplexStream.scala`) orders cells by filtration value **reversed** (so the
*smallest*-under-this-ordering cell is the *youngest*), tie-broken by dimension then lexicographic vertex
order. This reversal is deliberate and load-bearing: `Chain`'s `leadingCell` is always the minimum under
whatever `Ordering` backs it, and the persistence algorithms need `leadingCell` to mean "youngest" for the
boundary-matrix reduction to select the correct pivot. Several concrete streams override
`filtrationOrdering` with their own tie-break instead of the generic lexicographic one — see
@ref:[Hard-won invariants](gotchas.md)
for why, and #2 for why a stream's *iteration* order and its `filtrationOrdering` (*pivot* order) must
agree.

### The several VR-stream implementations

These are **alternate engines with the same output contract, not layers on top of each other** — don't
assume fixing one fixes the others, and check which `iterateDimension`/`iterator` implementation is
actually in play before reasoning about a bug:

- `EnumeratingCofaceSimplexStream` (`SimplexStream.scala`) — generates each dimension explicitly via
  `SimplexIndexing`'s combinatorial-number-system enumeration (see below), filtered by an optional
  `keepCriterion`, sorted by `filtrationOrdering.reverse`.
- `RipserCofaceSimplexStream` (`SimplexStream.scala`) — a coface-generation variant that caches the
  previous dimension's simplices and only expands cofaces reachable from them, rather than enumerating an
  entire dimension from scratch every time.
- `InorderCofaceSimplexStream` (`SimplexStream.scala`) — generates cofaces "in order" directly from the
  metric-space structure (no post-hoc sort needed for its own generation, though it still relies on
  `filtrationOrdering` being consistent with it — see the gotcha above).
- `RipserStream` / `RipserStreamBase` (`RipserStream.scala`) — a Ripser-style binomial-indexed stream:
  every simplex is addressed by an integer index via `SimplexIndexing`'s combinatorial number system
  (`apply(n, d)` decodes index `n` to the `n`th `d`-subset; `apply(simplex)` encodes the reverse direction),
  letting the stream enumerate `0 until binomial(N, d+1)` directly rather than building simplices
  incrementally. `RipserStreamSparse` additionally does zero-persistence-pair detection
  (`zeroApparentCofacet`/`zeroApparentFacet`) as a generation-time filter — see
  @ref:[Persistence engines](persistence-engines.md) for how this relates to (and differs from) apparent pairs
  as an optimization inside `RipserCohomologyContext`.
- `RecursiveStackVietorisRipsSimplexStream` (`VietorisRips.scala`) — a recursive-stack coface enumerator
  built on a `TopCofacetEnumerator`/spatial-query combination, a third independent generation strategy.

`Cofacets.scala`'s `CofacetIterator` is a separate, lower-level lazy coboundary generator over a
`SparseMetricSpace` (only edges within a diameter cutoff are visible — see `FiniteMetricSpace.scala`'s
`SparseMetricSpace`), used where lazy per-cell coface generation matters more than whole-dimension
enumeration.

### `SimplexIndexing` and the combinatorial number system

`SimplexIndexing.scala` (inside `RipserStream.scala`) is the piece every Ripser-flavored part of this
codebase depends on: it encodes/decodes a `d`-subset of `{0, ..., vertexCount-1}` to/from a single integer
index via binomial-coefficient lookups, giving `O(d)` conversion in both directions without ever
materializing all subsets. `cofacetIterator`/`facetIterator` walk cofacets/facets of a simplex purely
combinatorially over the *complete* `vertexCount`-point abstract simplex — **they have no notion of
`maxDimension` truncation at all**. See
@ref:[Hard-won invariants](gotchas.md)
before building anything new directly on these iterators.

### Metric spaces

`FiniteMetricSpace.scala` abstracts "distance + finite point set": `ExplicitMetricSpace` (raw distance
matrix), `EuclideanMetricSpace` (coordinate array, on-demand Euclidean distance, with a VP-tree-backed
`neighbors` query via the `jvptree` library), `IntMetricSpace` (reindexes any metric space to contiguous
`0 until size` integer indices), and `SparseMetricSpace` (wraps another metric space, reporting `+Infinity`
beyond a fixed diameter cutoff — used to bound Vietoris-Rips construction to a finite neighborhood per
point). `JVPTree`/`BruteForce` (implementing `SpatialQuery`) are the two neighbor-query backends;
`SparseMetricSpace` uses `JVPTree` by default.

### Symmetry-aware construction

`SymmetryGroup.scala` lets construction/computation work on canonical orbit representatives only, when the
point cloud has a known vertex symmetry group. `SymmetryGroup[KeyT, VertexT]` provides `orbit`/
`representative`/`isRepresentative`; `SymmetricRipserStream`/`SymmetricRipserCliqueFinder` build a
`RipserStream` variant that only retains orbit representatives and expands the rest of the orbit lazily.
`HyperCubeSymmetry`/`HyperCubeSymmetryGenerators` are the worked example (hypercube vertices under bit-
position permutation), with a "pseudo-minimum against generators, then check the full orbit only if
needed" optimization in `HyperCubeSymmetryGenerators.isRepresentative` worth reading if you're building a
new symmetry group.

## `Barcode.scala`: representing the output

`org.appliedtopology.tda4j.barcode` (note: this is a genuinely separate package, not just a file) defines
`BarcodeEndpoint` (`PositiveInfinity`/`NegativeInfinity`/`OpenEndpoint`/`ClosedEndpoint`, with a
total-order `given Ordering[BarcodeEndpoint[FiltrationT]]` that correctly interleaves finite and infinite
endpoints) and `PersistenceBar[FiltrationT, AnnotationT]` (dimension, lower/upper endpoint, an optional
`annotation` — in practice always `Chain[CellT, CoefficientT]`, the representative cycle/cocycle). `Barcode`
additionally implements algebra on finitely-presented persistence modules: `image`/`kernel`/`cokernel` of a
map between two barcodes represented as a matrix, following the standard "reduce the induced matrix, read
off pivots" approach — useful if you're implementing interleaving distances or persistence-module
morphisms, not needed for ordinary persistent-homology computation.

## `package.scala`: `TDAContext`

```scala 3
class TDAContext[VertexT: Ordering, CoefficientT: Field, FiltrationT: Ordering]
    extends SimplicialHomologyContext[VertexT, CoefficientT, FiltrationT]():
  val chainIsRingModule = summon[Chain[Simplex[VertexT], CoefficientT] is RingModule {type R = CoefficientT}]
  export chainIsRingModule.*
  given [T: Ordering] => Conversion[Simplex[T], Chain[Simplex[T], CoefficientT]] = Chain.apply
```

`TDAContext` takes **three** type parameters (`VertexT`, `CoefficientT`, `FiltrationT`) — an earlier draft
of this guide, and the code sample it was copied from, used only two and is wrong; always check
`package.scala` directly. `TDAContext` *is* a `SimplicialHomologyContext` (the naive, reference-grade
persistence engine — see @ref:[Persistence engines](persistence-engines.md)), plus it exports chain-arithmetic
operators (`+`, `-`, `⊠`, ...) into your namespace and provides an implicit `Simplex -> Chain` conversion so
you can write `∆(1,2) - ∆(2,3)` directly. See `src/test/scala/.../APISpec.scala` for the actual, currently
working usage pattern (also the basis for the @ref:[User's Guide](../user-guide/index.md)'s Scala quickstart).
`APISpec.scala`'s own doc comment calls itself "developing the non-Scala facing API functionality" — it's
the closest thing that exists today to a seed for a future Matlab/Java-friendly entry point (see the
User's Guide's Matlab section for what that would need to add).
