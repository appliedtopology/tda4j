# Mapping the library: class diagrams

@:callout(info)
This page is a structural sketch to help you get oriented, not an exhaustive or automatically generated
reference — field names and signatures can drift out of sync with source over time. When a diagram and the
actual `.scala` file disagree, trust the file.
@:@

## Typeclass hierarchy (`RingModule.scala`, `Field.scala`, `Chain.scala`)

```mermaid
classDiagram
    class RingModule {
        <<typeclass: type Self, type R>>
        zero: Self
        plus(x, y) Self
        minus(x, y) Self
        negate(x) Self
        scale(r, y) Self
        +(rhs) Self
        -(rhs) Self
        unary_-() Self
        R.⊠(t) Self
    }
    class Field {
        <<typeclass: type Self>>
        plus(x, y) Self
        times(x, y) Self
        divide(x, y) Self
        invert(x) Self
        zero: Self
        one: Self
    }
    class HasDimension {
        <<typeclass: type Self>>
        dim: Int
    }
    class Cell {
        <<typeclass: type Self, extends HasDimension>>
        boundary~CoefficientT~() Seq~Tuple2~
    }
    class OrderedCell {
        <<typeclass: type Self : Ordering as ordering, extends Cell>>
    }
    class OrderedBasis {
        <<typeclass: type Self, requires CellT:Ordering, CoefficientT:Field>>
        leadingCell: Option~CellT~
        leadingCoefficient: CoefficientT
        leadingTerm: Tuple2
    }
    HasDimension <|-- Cell
    Cell <|-- OrderedCell
    Simplex ..|> OrderedCell : given instance
    Cube ..|> OrderedCell : given instance
    FiniteSimplicialSet ..|> OrderedCell : per-instance given (cellInstance)
    Chain ..|> OrderedBasis : given instance
    Chain ..|> RingModule : given instance
```

Three concrete `OrderedCell` instances exist: `Simplex[VertexT]`, `Cube`, and a `FiniteSimplicialSet[G]`'s
own generators (that last one is a per-instance `given`, not a global one, since its boundary depends on
that particular simplicial set's own face data). There is no dual `Cocell`/`OrderedCocell` trait pair (an
earlier version had one; removed as the wrong shape for coboundary, which is extrinsic to a cell, not
intrinsic like `boundary`) — `RipserCohomologyContext`/`PackedRipserCohomologyContext` compute coboundaries
directly against `SimplexIndexing` instead (see [Persistence engines](persistence-engines.md)). See the
[Scala 3 primer](scala3-primer.md) for what "typeclass: type Self" and "given instance" mean concretely in
this codebase's syntax.

## `Chain[CellT, CoefficientT]` (`Chain.scala`)

```mermaid
classDiagram
    class Chain {
        -entries: PriorityQueue~Tuple2~
        collapseHead() Unit
        collapseAll() Unit
        isZero() Boolean
        items: Seq~Tuple2~
    }
    class `Chain$` {
        <<companion object>>
        empty~CellT,CoefficientT~() Chain
        apply(cs: Tuple2*) Chain
        from(cs: Seq~Tuple2~) Chain
        reduceBy(z, basis, log) Tuple2
        reduceByUntil(z, basis, log, stop) Tuple2
    }
    `Chain$` ..> Chain : constructs
```

`reduceBy`/`reduceByUntil` are the shared reduction primitives every persistence engine in `Homology.scala`
builds on — see [Architecture](architecture.md) and
[Hard-won invariants #4](gotchas.md).

## `Simplex[VertexT]` (`Simplex.scala`, `SimplexOps.scala`)

```mermaid
classDiagram
    class Simplex {
        <<opaque type = SortedSet~VertexT~>>
        underlying: SortedSet~VertexT~
        dim: Int
        boundary~CoefficientT~() Seq~Tuple2~
    }
    class `Simplex$` {
        <<companion object>>
        apply(vertices: VertexT*) Simplex
        from(vertices: Seq~VertexT~) Simplex
        unapplySeq(s: Simplex) Option~Seq~
    }
    Simplex ..|> OrderedCell : given defaultSimplexIsOrderedCell
```

`SimplexOps.scala` adds a large `extension` block delegating most of `SortedSet`'s surface (`.size`,
`.map`, `.union`, `.dropIndex`, ...) so `Simplex` "feels like" a set even though it's a zero-cost opaque
wrapper at runtime — see the [primer](scala3-primer.md).

## Streams (`SimplexStream.scala`, `SimplexIndexing.scala`, `VietorisRips.scala`)

```mermaid
classDiagram
    class Filtration {
        <<typeclass: CellT:Cell, FiltrationT:Ordering,FiltrationT:Filterable>>
        filtrationValue: PartialFunction
    }
    class CellStream {
        <<typeclass: extends Filtration, IterableOnce>>
        filtrationOrdering: Ordering~CellT~
    }
    class SimplexStream {
        <<CellStream specialized to Simplex~VertexT~>>
    }
    class StratifiedCellStream {
        iterateDimension: PartialFunction~Int, Iterator~
    }
    Filtration <|-- CellStream
    CellStream <|-- SimplexStream
    CellStream <|-- StratifiedCellStream
    SimplexStream <|-- StratifiedSimplexStream
    StratifiedCellStream <|-- StratifiedSimplexStream
    StratifiedSimplexStream <|-- CofaceSimplexStream
    CofaceSimplexStream <|-- EnumeratingCofaceSimplexStream
    EnumeratingCofaceSimplexStream <|-- RipserCofaceSimplexStream
    RipserCofaceSimplexStream <|-- CechCofaceSimplexStream
    RipserCofaceSimplexStream <|-- LazyWitnessSimplexStream
    RipserCofaceSimplexStream <|-- WitnessCofaceSimplexStream
    RipserCofaceSimplexStream <|-- SheehyRipsSimplexStream
    EnumeratingCofaceSimplexStream <|-- InorderCofaceSimplexStream
    SimplexStream <|-- ExplicitStream
    StratifiedSimplexStream <|-- RecursiveStackVietorisRipsSimplexStream
    StratifiedSimplexStream <|-- IncrementalVietorisRipsSimplexStream
    StratifiedSimplexStream <|-- AlphaShapes
    AlphaShapes <|-- HelixDelaunay
    AlphaShapes <|-- AlphaShapeDQP
    StratifiedCellStream <|-- CubicalGridStream
    StratifiedCellStream <|-- ExplicitCubicalStream
    CellStream <|-- SimplicialSetStream
    StratifiedCellStream <|-- FilteredSimplicialSetStream
```

These are **alternate stream implementations with a common output contract, not layers on top of one
another** — see [Architecture](architecture.md). `CubicalGridStream`/`ExplicitCubicalStream` produce
`Cube`s rather than `Simplex`es; `SimplicialSetStream`/`FilteredSimplicialSetStream` produce a
`FiniteSimplicialSet[G]`'s own generator type `G`.

## Persistence engines (`homology/Homology.scala`, `homology/PackedRipserCohomology.scala`, `homology/FastCubicalHomology.scala`, `homology/FastAlphaHomology.scala`)

Deliberately *not* diagrammed field-by-field here — their exact state and trust status belongs in one
place. See [Persistence engines](persistence-engines.md) for the full, current picture across
`CellularHomologyContext`/`SimplicialHomologyContext`,
`CellularPersistenceInChunksContext`/`PersistenceInChunksContext`,
`RipserCohomologyContext`, `PackedRipserCohomologyContext`, `CellularCohomologyContext`,
`FastCubicalHomologyContext` (2D cubical grids only), and `FastAlphaHomologyContext` (2D `HelixDelaunay`
alpha complexes only, not yet wired into `matlab`/`cli`).

## Circular coordinates (`homology/CircularCoordinates.scala`)

```mermaid
classDiagram
    class CircularCoordinates {
        <<object>>
        h1Bars(metricSpace, maxFiltrationValue) IndexedSeq~(Double, Double)~
        compute(metricSpace, r, cocycleIndex, prime, maxFiltrationValue) Result
    }
    class Result {
        theta: Map~Int, Double~
        birth: Double
        death: Double
        r: Double
        prime: Int
    }
    class NoIntegerCocycleException {
        <<RuntimeException>>
    }
    CircularCoordinates --> Result : returns
    CircularCoordinates ..> CellularCohomologyContext : computes K_r's cohomology with
    CircularCoordinates ..> NoIntegerCocycleException : throws (no ℤ-lift at prime)
```

A standalone construction, not a fifth persistence engine — see [Architecture](architecture.md)'s own
`homology.CircularCoordinates` section for the truncated-complex reframing, the harmonic-smoothing linear
system, and why the output is a per-point angle map rather than a barcode.

## Metric spaces (`FiniteMetricSpace.scala`)

```mermaid
classDiagram
    class FiniteMetricSpace {
        <<typeclass: type VertexT>>
        distance(x, y) Double
        size: Int
        elements: Iterable~VertexT~
        minimumEnclosingRadius: Double
    }
    FiniteMetricSpace <|-- IntMetricSpace
    FiniteMetricSpace <|-- ExplicitMetricSpace
    FiniteMetricSpace <|-- EuclideanMetricSpace
    FiniteMetricSpace <|-- SparseMetricSpace
    class SpatialQuery {
        <<typeclass: type VertexT>>
        neighbors(v, epsilon) Set~VertexT~
    }
    SpatialQuery <|-- JVPTree
    SpatialQuery <|-- BruteForce
    SparseMetricSpace --> SpatialQuery : uses (JVPTree)
```

## Barcode representation (`Barcode.scala`, package `org.appliedtopology.tda4j.barcode`)

```mermaid
classDiagram
    class BarcodeEndpoint {
        <<sealed trait>>
        flip() BarcodeEndpoint
        isFinite: Boolean
    }
    BarcodeEndpoint <|-- PositiveInfinity
    BarcodeEndpoint <|-- NegativeInfinity
    BarcodeEndpoint <|-- OpenEndpoint
    BarcodeEndpoint <|-- ClosedEndpoint
    class PersistenceBar {
        dim: Int
        lower: BarcodeEndpoint
        upper: BarcodeEndpoint
        annotation: Option~AnnotationT~
    }
    class Barcode {
        isMap(source, target, matrix) Boolean
        image(source, target, matrix) List~PersistenceBar~
        kernel(source, target, matrix) List~PersistenceBar~
        cokernel(source, target, matrix) List~PersistenceBar~
    }
    class BarcodeDistance {
        <<object>>
        bottleneckDistance(diagram1, diagram2, groundNorm) Double
        wassersteinDistance(diagram1, diagram2, order, groundNorm) Double
        bottleneckDistanceByDimension(diagram1, diagram2, groundNorm) Map~Int, Double~
        wassersteinDistanceByDimension(diagram1, diagram2, order, groundNorm) Map~Int, Double~
    }
    class Vectorization {
        <<object>>
        landscape(diagram, numLevels, tMin, tMax, resolution) Array~Array~Double~~
        persistenceImage(diagram, sigma, birthRange, persistenceRange, birthResolution, persistenceResolution, weightCap) Array~Array~Double~~
    }
    BarcodeDistance ..> PersistenceBar : reads
    Vectorization ..> PersistenceBar : reads
```

`AnnotationT` in practice is always `Chain[CellT, CoefficientT]` — the representative cycle/cocycle for a
bar, when an engine tracks one. `BarcodeDistance`/`Vectorization` only ever read a bar's `dim`/`lower`/`upper`
(never `annotation`), and are specialized to `PersistenceBar[Double, _]` rather than sharing `Barcode`'s own
`FiltrationT: Ordering` genericity — see [Architecture](architecture.md)'s "`Barcode.scala`" section
for why, and for `BipartiteMatching.scala`'s two package-private combinatorial primitives
(`HopcroftKarp`/`Hungarian`) `BarcodeDistance` is built on, omitted here as an implementation detail rather
than part of this package's public shape.
