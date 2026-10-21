---
layout: main
title: Architecture: from algebra to persistence
---


This page walks the two lower layers of the library: the algebraic core (what a "chain" and a
"coefficient" are) and complex construction (how a sequence of cells in filtration order gets
produced). [Persistence engines](persistence-engines.md) covers what consumes the stream this layer
produces.

If a piece of Scala 3 syntax below looks unfamiliar, see the [Scala 3 primer](scala3-primer.md) first.

## Package layout

The core is **one package**, `org.appliedtopology.tda4j`: `import org.appliedtopology.tda4j.*` brings in all of it.
Its source directories are file organization only (they are not packages), and each is a layer:

- **`algebra/`** — `RingModule`, `Field`, `FiniteField`, `Chain` (including the `Cell`/`OrderedCell`/`OrderedBasis`
  contracts), `LinearAlgebra`. The typeclasses and formal-sum machinery everything else builds on.
- **`cells/`** — `Simplex` and `Cube`, the concrete `OrderedCell` instances, with their default instances in their
  companions.
- **`streams/`** — everything that produces cells in filtration order: `SimplexStream`/`CellStream`, the
  Vietoris-Rips family, `FiniteMetricSpace`, `CubicalStream`/`CubicalImage`, `CechStream`, `WitnessStream`,
  `DowkerStream`, `UnionFind`, and the dispatchers (`VietorisRips`, `Cech`, `Witness`, `Dowker`, ...).
- **`homology/`** — the persistence algorithms (`Homology.scala`, `PackedRipserCohomology.scala`, `Cohomology.scala`,
  the fast cubical/alpha engines) and circular coordinates.
- **`barcode/`** — `Barcode`, `PersistenceBar`, `BarcodeEndpoint`, distances and vectorizations.
- **`alpha/`** — `AlphaShapes` (`BowyerWatsonDelaunay`/`HelixDelaunay`, both `DelaunayAlphaShapes`; `AlphaShapeDQP`), `AlphaComplexDQP`, `DelaunayPredicates`.
- **`io/`** — file-format adaptors: `CSV`, `Ripser`, `Dipha`, `Gudhi`, `Perseus`.
- `package.scala` — the labs (`Lab`, `TDAlab`, `CubicalLab`) for writing chain algebra by hand. The `Persistence`
  verb is in `homology/Persistence.scala`; both are on [The public API](public-api.md).

Outside the core:

- **`sset`** (`org.appliedtopology.tda4j.sset`, an opt-in add-on) — simplicial sets (`SimplicialSet`,
  `FiniteSimplicialSet`, `SSetElement`, `SSetMap`, `CupProduct`, `Steenrod`, `FundamentalGroup`, `BettiNumbers`, the
  simplicial-set streams), nerves of monoids and finite categories (`FiniteMonoid`, `FiniteCategory`, `CategoryNerve`),
  and group classifying spaces (`FiniteGroup`, `ClassifyingSpace`, `CollapsedNerve`; see
  [Nerves and group homology](nerves.md)). It depends on the core;
  the core never uses it (except `TDAlab`, which re-exports it).
- **`cli`** — the `tda4j` executable (`TDA4jConf`, `TDA4jCLI`), a thin translator over `matlab.TDA4j`.
- **`matlab`** — `TDA4j`/`PersistenceResult`/`LandmarkSelectionResult`, the plain-primitives facade for MATLAB and
  other Java callers.
- **`plot`** (`org.appliedtopology.tda4j.plot`, directory `plot/`) — plots of barcodes, diagrams, images, complexes
  and representatives as SVG and 3-D pages, and a live browser viewer. A leaf like `matlab` and `cli`: it uses the
  core's public API only and nothing in the core uses it. See [Plotting](../user-guide/plotting.md) for its use and
  [Plotting internals](plotting.md) for how it is built.

## Two platforms: the JVM and Scala.js

The library is built for the JVM and for JavaScript (Scala.js) from the same sources. sbt 2's project matrix has two
rows, `tda4j` (JVM, artifact `tda4j_3`) and `tda4jJS` (Scala.js, artifact `tda4j_sjs1_3`). Everything under
`src/main/scala` and `src/test/scala` compiles for both; `src/main/scalajvm` and `src/main/scalajs` (and the matching
`src/test/...`) hold what exists on one platform only. Write shared code first: it uses no JVM-only library (the
JVM-only ones are `cask`, the parallel collections, and the Java libraries Scala.js cannot compile) and no
`java.nio.file`, `java.io.File` or threads.

What differs between the platforms is behind a few small seams, each defined once per platform with the same name:

| seam | JVM | Scala.js |
|---|---|---|
| `Parallel.foreach`/`map` (`streams/`) | the common fork-join pool | a loop: JavaScript has one thread |
| `FileAccess` (`io/`) | `java.nio.file` | Node.js' `fs`, found at run time with `process.getBuiltinModule`; a browser gets an error that says it has no file system |
| `plot.Viewer` | a small web server (cask) and a browser tab | the plot appended to the page, or written to a temporary HTML file and opened from Node.js |
| `CubicalImagePlatform` (mixed into `CubicalImage`) | image files, `javax.imageio` | `fromImageData`, the pixels of a canvas; `fromFile` throws |
| `FiltrationCache` (`streams/`) | a `TrieMap` that several threads can fill | a `HashMap` |
| `plot.RootText` | `Locale.ROOT` for lower-casing and `String.format`; `DecimalFormatSymbols` | plain lower-casing and `String.format`; `Intl.NumberFormat` for a locale's separators (`java.util.Locale` does not exist on Scala.js) |
| `cli.ProcessExit` | `sys.exit` | sets `process.exitCode` (no `System.exit` on Scala.js) |

The specs under `src/test/scala` run on both platforms and have one seam of their own, `TestPlatform`. A property that
searches for rare inputs runs `TestPlatform.propertyCases(n)` cases: all `n` on the JVM and a tenth on Scala.js, since
the code under test is the same and the Scala.js run only needs to check what differs between platforms.

The Scala.js build adds `javascript.Exports` (the functions the npm package exports, a thin layer over the
[MATLAB facade](../user-guide/matlab.md)) and the `tda4j` command on Node.js (`cli.TDA4jNodeMain`; the command is
`TDA4jCLI` as on the JVM). The linker writes ES modules: `main.mjs` holds the exports, `cli.mjs` the command. `sbt
tda4jJS/npmPack` packs them with `npm/package.json` and `npm/index.d.ts` into the npm tarball. The user-facing side is
[Using it from JavaScript](../user-guide/javascript.md).

**Load-bearing rule for givens**: the core package has no top-level `given`s. Default instances live in the companion
of the data type they serve (`object Simplex` holds `Simplex[V] is OrderedCell`, the `Ordering` derived from it and
`Show`; likewise `Cube`, `Chain`, `BarcodeEndpoint`, `Fp`). That puts them in the type's *implicit scope*: they are
found without any import, and are consulted only when no given is lexically visible, so a stream's or a user's own
given always wins instead of tying with them. A top-level generic given would be visible everywhere in the flat
package and would even decide type inference (an unconstrained `[V: Ordering]` once inferred `V =
BarcodeEndpoint[Cube]` that way). Never put an instance in a *typeclass's* companion (`Field`, `OrderedCell`): that
companion is searched whenever the instance type is still unknown. Generic code that holds only `CellT: OrderedCell`
and wants the cell's intrinsic order opts in with `import OrderedCell.cellOrdering`.

**Name-binding hazard of the flat package**: a wildcard import of an external library silently beats a same-named
definition from another file of this package. Prefer named imports of external libraries in core files.

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
  def scalarMultiply(x: R, y: Self): Self
  extension (t: Self)
    def +(rhs: Self): Self = plus(t, rhs)
    def -(rhs: Self): Self = minus(t, rhs)
    def <*(rhs: R): Self = scalarMultiply(rhs, t)
    def unary_- : Self = negate(t)
  extension (r: R)
    def |*|(t: Self): Self = this.scalarMultiply(r, t)
    def ⊠(t: Self): Self = this.scalarMultiply(r, t) // "boxed times" -- scalar action
```

A minimal instance only needs `zero`, `plus`, `scalarMultiply`, and one of `minus`/`negate`. `⊠` (U+22A0, typed via
a Unicode input method or copy-paste) is the scalar-multiplication operator used throughout: `2.0 ⊠ chain`.

`algebra/Field.scala` is a separate, self-contained typeclass (not built on `RingModule`) for coefficient
types themselves — `plus`/`minus`/`times`/`divide`/`negate`/`invert`/`zero`/`one`, with `+`/`-`/`*`/`/`/`eql`
extension operators. **There is no default `given Double is Field` anywhere in `src/main`** — bring one in
explicitly:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)
```

`Field.DoubleApproximated(epsilon)` treats two doubles as equal within `epsilon` — necessary because exact
floating-point equality is rarely what you want when an `isZero` check decides whether a chain entry
survives a reduction step. `FiniteField.scala` gives exact, non-approximate coefficients instead:
`FiniteField(p)`'s `Fp` is an opaque type *per instance* (so `Fp` from a mod-5 field and a mod-7 field are
distinct, incompatible types), with exact arithmetic via a precomputed inverse table and products in `Long`,
so large primes stay exact.

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
`sset/SimplicialSet.scala`): `boundary` returns each codimension-1 face paired with alternating signs.

There is no dual `Cocell`/`OrderedCocell` trait pair: coboundary is *extrinsic* to a cell (it depends on which
higher-dimensional cells exist in the ambient, possibly truncated complex), not intrinsic the way `boundary` is, so a per-cell `coboundary`
method with no complex to consult can only be correct when the complex is always the full
combinatorially-possible one. `RipserCohomologyEngine`/`PackedRipserCohomologyEngine` compute coboundaries
directly against `SimplexIndexing`'s cofacet iterator instead (see
[Persistence engines](persistence-engines.md)).

### `Chain`

`Chain[CellT: Ordering, CoefficientT: Field]` (`algebra/Chain.scala`) is a formal sum of cells with field
coefficients, kept in an order on the cells: its leading term (the reduction pivot) is the *smallest* cell. The
chain itself holds its `Ordering` and its `Field`. `Chain` is abstract and open to new storages: a subclass passes
the order and the field to `Chain`'s constructor and implements `entryIterator` (its stored entries, a cell possibly
repeated or with a zero total); every other member (`terms`, `isZero`, `leadingTerm`, `rawEntries`, the collapses) has
a default built on it, which a storage may override for speed, and reductions, arithmetic, `boundary` and equality use
nothing else. The library has two storages (both `final`: the reductions match on them):

- `HeapChain`, what `Chain(...)`, `Chain.from` and all arithmetic make: a mutable `PriorityQueue` ordered so the
  smallest cell sits at the head — cheap to peek, since the leading term is queried constantly.
- `PackedChain`, how the grid engines (`FastCubicalHomologyEngine`, `PackedCubicalCohomologyEngine`) store their
  representatives: the cells as integer keys (doubled-grid indices), distinct and sorted under the chain's order,
  the nonzero coefficients in a parallel array, and a decoder (`GridCubes`) that turns a key into its cube. Reading
  it (`terms`, `cells`, `leadingTerm`, `boundary`, equality) decodes the cells afresh and never changes it;
  arithmetic on it returns a `HeapChain`.

- Equality compares formal sums: the same cells, each with coefficients equal by the field's own `isEqual` (a
  field element can have several representations), whatever the order or the storage.
- `collapseHead()`/`collapseAll()` merge duplicate-cell entries, dropping exact zeros. Naive `+`/`-`/`⊠`
  only lazily collapse the head, so a hand-rolled reduction loop built out of raw `Chain` arithmetic
  accumulates an ever-growing backlog of uncollapsed duplicates — see
  [Hard-won invariants](gotchas.md).
- `Chain.reduceBy`/`Chain.reduceByUntil` are the actual matrix-reduction primitives every persistence
  engine builds on: given a chain `z` and a `basis: Map[CellT, Chain[CellT, CoefficientT]]` of recorded
  pivot columns, repeatedly subtract the appropriate multiple of `basis(pivot)` until `z`'s leading cell is
  no longer a key in `basis`, returning both the reduced chain and a reduction-log chain (the multipliers
  used) that lets a caller reconstruct a V-column. Internally these go through a `mutable.TreeMap`, not the
  `PriorityQueue`-backed `Chain` type, so repeated updates collapse duplicates automatically. Each step clears its
  pivot, so the pivot never moves back; a column whose leading cell is not the key it is stored under makes it move
  back (or stay put), and the reduction throws instead of looping forever.
- `given [CellT: Ordering, CoefficientT: Field] => (Chain[CellT, CoefficientT] is RingModule)` is what makes
  `+`, `-`, `⊠`, `unary_-` work on `Chain` values — the `given` whose summon *timing* matters, see
  [Hard-won invariants](gotchas.md).

## Complex construction: streams

**Public entry points.** Ask for a complex through one object per kind: `VietorisRips`, `Cech`, `Witness`, `Dowker`,
`DtmRips`, `SparseRips` (plus `Truncated` to cut any coface stream off), each taking `maxDimension` as the top
homological degree you want and building one dimension higher internally. The sections below describe the implementation
classes those objects choose between (`EnumeratingCofaceSimplexStream`, `CechCofaceSimplexStream`, ...). They are
public: the objects are the documented entry, and the classes are what to extend when building a new filtered complex
on the same machinery (most of them reuse `RipserCofaceSimplexStream`'s coface loop).

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

### Building a stream by hand

`FiltrationOrdering.canonical(filtrationValue, dim, tieBreak)` builds the
order every stream uses:
- filtration value, reversed;
- then dimension;
- then the tie-break (colex for Vietoris-Rips).

Use it rather than writing a comparator. Reverse only the filtration value:
`.reverse` on a whole ascending ordering also flips the dimension tie-break.
Sort each dimension's bucket with the same object,
`.sorted(using filtrationOrdering.reverse)`.

`ExplicitStreamBuilder` (`streams/SimplexStream.scala`) builds a finite stream
from a list:
- `fromFacets(facets)` puts every cell at `0`;
- `fromFilteredFacets(Seq((value, facet), ...))` closes a list of maximal
  cells under faces, and an unlisted face enters with the earliest cell containing it.

The result, an `ExplicitStream`, is stratified, so the verb and every engine
take it. This is the tool for test fixtures and for complexes that come from
outside the library.

**A truncated stream says so.** `VietorisRips(..., maxDimension = k)`, the
other dispatchers, `Truncated` and `LimitedCofaceSimplexStream` report
`homologyDegreeLimit = Some(k)`. Such a stream holds cells up to dimension
`k + 1`, so its unfilled top cells would look like essential classes of
degree `k + 1`. The verb reads this limit, and complete complexes report
`None`. A new truncating wrapper must override it, taking the minimum with the
stream it wraps.

### Vietoris-Rips: several independent generation strategies

These are **alternative implementations with the same output contract, not layers on one another**. Check which
`iterateDimension`/`iterator` implementation is in play before reasoning about a bug:

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
same default). Pass `maxFiltrationValue = Some(Double.PositiveInfinity)` for the whole complex. `RecursiveStackVietorisRipsSimplexStream` and the alpha-complex backends keep their own,
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
on the stream itself. A NaN pixel is masked: not in the complex, in either direction (NaN has no sign, so
negating a superlevel image keeps it). Every other cube takes the minimum over the unmasked pixels containing it and is
absent when they are all masked, so the complex is the closure of the unmasked pixels. The grid engines rank a masked
pixel `GridRanks.masked`, one past every real rank, and skip cells of that rank. Infinite values are ordinary values. `CubicalHomologyEngine` is a one-line `Cube`-specialized wrapper around
`CellularHomologyEngine` — cubical complexes needed no new engine code, only a new `OrderedCell` instance.

#### Fast cubical homology

`FastCubicalHomologyEngine` computes `H_0` directly with union-find and uses
Alexander duality to compute `H_{d-1}` from a dual graph. In dimensions
`d >= 3`, intermediate homology dimensions go to cohomology on the grid
without its top cells (`PackedCubicalCohomologyEngine`).

This avoids general `Chain` reduction in the largest dimensions of a cubical
complex.


### Simplicial sets (the `sset` add-on)

Everything here is in `org.appliedtopology.tda4j.sset` (directory `sset/`). `SSetElement[G](word, generator)`
(`SSetElement.scala`) plus `FiniteSimplicialSet[G]` (`SimplicialSet.scala`) implement finite simplicial sets in the
classical Eilenberg-Zilber presentation: a finite set of non-degenerate generators per dimension, plus primitive face
data `faces: G => IndexedSeq[SSetElement[G]]`. `word` is the degeneracy indices in normal form — **strictly
decreasing**, not increasing, a direct consequence of the simplicial identity `s_i s_j = s_{j+1} s_i` (`i <= j`).
`insertOuter`/`faceOf` implement the full operator algebra (`s_i`/`d_i` on arbitrary, possibly degenerate elements,
exposed as `x.degeneracy(j, e)`/`x.face(i, e)`), which is what lets `validate()` check that hand-supplied face data
actually satisfies the simplicial identities.

The API has two halves. `object SimplicialSet` (the trait's companion; the catalog itself is in
`SimplicialSetCatalog.scala`) *builds* spaces: `SimplicialSet(generatorsByDim, faces)`, `fromSimplicialComplex`,
`fromStream`, and ready-made `simplex`/`horn`/`sphere`/`torus`/`realProjectiveSpace`/`kleinBottle`/
`complexProjectivePlane`/`presentationComplex`/`hopfMap`/`classifyingSpace`. Methods of `FiniteSimplicialSet` do
everything else: enumeration (`generators(n)`, `allGenerators`, `simplices(n)` with the degenerate ones, `fVector`,
`eulerCharacteristic`, `bettiNumbers(p)`), and constructions (`product`/`coproduct`, `wedge`, `smash`, `join`, `cone`,
`suspension`, `quotient`/`identify`, `subcomplex`), implemented in `Constructions.scala`. A product's non-degenerate
simplices are pairs `(a, b)` with *disjoint* degeneracy words, not Eilenberg-Zilber shuffles, and its top dimension is
`maxDim(x) + maxDim(y)`; `quotient` takes `G => SSetElement[G]` rather than `G => G` specifically so a cell can collapse
down a dimension onto a degenerate point (the Δ-complex model of ℝP² needs exactly this for one of a triangle's three
edges).

`x.stream` (`SimplicialSetStream.scala`) adapts a set to `CellStream` for ordinary, unfiltered homology (every
generator at filtration value `0`); `x.filtered(f)` (`FilteredSimplicialSetStream.scala`) is the genuine
`StratifiedCellStream[G, Double]`, with `validateMonotoneFiltration` checking the one precondition every engine needs
(a face's value never exceeds its coface's). Engines need the set's cell structure in scope: `import x.given`.

`SSetMap.scala` has simplicial maps (with `mappingCone` and the induced rank on homology), `FundamentalGroup.scala` a
presentation of the fundamental group, and `CupProduct.scala` and `Steenrod.scala` the Alexander-Whitney cup product
and the Steenrod squares over F₂. `Nerve`/`ClassifyingSpace` (`ClassifyingSpace.scala`, with `FiniteGroup.scala`) use the
lazy `SimplicialSet` trait for the infinite nerve of a finite group. None of this has a MATLAB or CLI entry point: a
simplicial set needs its own input encoding.

Nerves of monoids and finite categories, persistent group homology and Brown's
collapse are on their own page: [Nerves and group homology](nerves.md).

### Cech complexes

`streams/CechStream.scala` (`CechCofaceSimplexStream`/`CechFiltration`) builds Cech complexes over
`Simplex[Int]`, reusing `RipserCofaceSimplexStream`'s generic coface-generation loop via its
`filtrationValueOverride` hook rather than a from-scratch algorithm. The geometric primitive is
minimum-enclosing-ball radius (`MinimumEnclosingBall`, the library's own, so it also builds for Scala.js), not the Delaunay/QP machinery alpha shapes use —
Cech membership depends only on a simplex's own vertices, not on every other point in the cloud.
`CechFiltration` caches each simplex's radius and clamps it to the max of its own facets' cached radii,
since a raw ball computation can violate monotonicity by a floating-point ULP on near-degenerate input.

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

### DTM filtrations

`DistanceToMeasure` (`streams/DistanceToMeasure.scala`) gives each point a
weight `f(x)`, the `q`-mean of its distances to its `k` nearest neighbours
(`q = 2` by default; as in GUDHI, `x` counts among them). Two complexes use
the weights.

**`DtmRips`** (`DtmRipsSimplexStream`) is the weighted Rips filtration of Anai
et al. (arXiv:1811.04757): a flag complex on weighted pairwise values, with
vertex `x` born at `2 f(x)`. It reifies the weighted distances as a
`FiniteMetricSpace` and reuses `RipserCofaceSimplexStream`'s coface loop.
- **Exponents:** `p = 1` matches GUDHI's `DTMRipsComplex`, and `p = 2` is the
  ball equation of the paper.
- **Vertex order:** the vertices are not born at 0, so the stream sorts
  dimension 0 itself; the inherited case would emit them unsorted.
- **Engines:** for the same reason the packed Ripser engine does not apply, and
  the generic engines consume it.

**DTM alpha** (`AlphaComplexDQP.dtm`) is the weighted alpha complex with
weights `-f(x)^2`, built by the DQP backend, which takes power weights. Its
degree-0 bars agree with `DtmRips(..., p = 2.0)` once alpha values are
converted to diameters.

### Flag-complex edge collapse

`EdgeCollapse.collapse` simplifies the weighted graph underlying a
Vietoris-Rips filtration while preserving persistent homology.

The resulting `EdgeCollapsedMetricSpace` implements `FiniteMetricSpace[Int]`,
so existing Vietoris-Rips streams and persistence engines can consume the
collapsed graph without special handling.

### Metric spaces

`FiniteMetricSpace.scala` abstracts "distance + finite point set": `ExplicitMetricSpace` (raw distance
matrix), `EuclideanMetricSpace` (coordinate array, on-demand Euclidean distance, `VantagePointTree`-backed `neighbors`
query), `IntMetricSpace` (reindexes to contiguous `0 until size`), `SparseMetricSpace` (reports `+Infinity`
beyond a fixed diameter cutoff, bounding Vietoris-Rips construction to a finite neighborhood per point).

`EuclideanMetricSpace` caches every pairwise distance only up to 8192 points;
above that, or with `cacheDistances = false`, it computes them on demand.
Never collect every pairwise distance into a collection: at 50,000 points that
is over a billion values.

> **Warning.** `SparseMetricSpace` reports `+Infinity` past its cutoff rather than excluding those pairs — it is not a
> thresholded neighbor oracle. Code that queries it expecting "unreachable" to mean "absent" will instead get
> back a real (if unusable) `Double` value; check `.isFinite` explicitly rather than assuming exclusion.

### Opt-in parallelism

A few of the more expensive per-cell computations can run on the common `ForkJoinPool`, opt-in via a
constructor flag defaulting to `false`, with output that is deterministic either way:
`AlphaDQPSettings.parallel` (the per-vertex QP solve), `CubicalGridStream.parallelFiltrationValue`, and
`CechCofaceSimplexStream.parallelFiltrationValue`. These are worth reaching for on large inputs where the
per-cell cost is real (a QP solve, a minimum enclosing ball). On Scala.js the flags are accepted and the work runs on
the one thread, giving the same answer.

## `Barcode.scala`: representing the output

`barcode/Barcode.scala` defines `BarcodeEndpoint` (`PositiveInfinity`/`NegativeInfinity`/
`OpenEndpoint`/`ClosedEndpoint`, with a total order that correctly interleaves finite and infinite
endpoints) and `PersistenceBar[FiltrationT, AnnotationT]` (dimension, lower/upper endpoint, an optional
annotation — in practice always the representative `Chain`). `Barcode` additionally implements algebra on
finitely presented persistence modules: `image`/`kernel`/`cokernel` of a map between two barcodes given as a matrix
(see [maps between barcodes](../user-guide/barcode-algebra.md)). Ordinary persistent-homology computation does not use it.

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


