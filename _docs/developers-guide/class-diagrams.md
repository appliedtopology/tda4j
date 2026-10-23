---
layout: main
title: Mapping the library: class diagrams
---


> **Note.** This page is a structural sketch to help you get oriented, not an exhaustive or automatically generated
> reference — field names and signatures can drift out of sync with source over time. When a diagram and the
> actual `.scala` file disagree, trust the file.

## Typeclass hierarchy (`RingModule.scala`, `Field.scala`, `Chain.scala`)

```mermaid
classDiagram
    class RingModule {
        <<typeclass: type Self, type R>>
        zero: Self
        plus(x, y) Self
        minus(x, y) Self
        negate(x) Self
        scalarMultiply(r, y) Self
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

Three concrete `OrderedCell` instances exist: `Simplex[VertexT]`, `Cube`, and a
`FiniteSimplicialSet[G]`'s own generators. The latter is a per-instance `given`
because its boundary depends on that simplicial set's face data.

See the [Scala 3 primer](scala3-primer.md) for the typeclass and `given` syntax.

## `Chain[CellT, CoefficientT]` (`Chain.scala`)

```mermaid
classDiagram
    class Chain {
        <<abstract>>
        cellOrdering: Ordering~CellT~
        coefficientField: Field~CoefficientT~
        entryIterator()* Iterator~Tuple2~
        collapseHead() Unit
        collapseAll() Unit
        isZero() Boolean
        terms: Seq~Tuple2~
    }
    class HeapChain {
        <<private tda4j>>
        queue: PriorityQueue~Tuple2~
    }
    class PackedChain {
        <<private tda4j>>
        keys: Array~Int~
        coefficients: Array~AnyRef~
        decoder: CellDecoder~CellT~
    }
    Chain <|-- HeapChain
    Chain <|-- PackedChain
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
    SimplexStream <|-- LevelwiseSimplexStream
    StratifiedCellStream <|-- LevelwiseSimplexStream
    LevelwiseSimplexStream <|-- CofaceSimplexStream
    CofaceSimplexStream <|-- EnumeratingCofaceSimplexStream
    EnumeratingCofaceSimplexStream <|-- RipserCofaceSimplexStream
    RipserCofaceSimplexStream <|-- CechCofaceSimplexStream
    RipserCofaceSimplexStream <|-- LazyWitnessSimplexStream
    RipserCofaceSimplexStream <|-- WitnessCofaceSimplexStream
    RipserCofaceSimplexStream <|-- SheehyRipsSimplexStream
    RipserCofaceSimplexStream <|-- DowkerCofaceSimplexStream
    EnumeratingCofaceSimplexStream <|-- LayeredInorderSimplexStream
    SimplexStream <|-- ExplicitStream
    LevelwiseSimplexStream <|-- RecursiveStackVietorisRipsSimplexStream
    LevelwiseSimplexStream <|-- IncrementalVietorisRipsSimplexStream
    LevelwiseSimplexStream <|-- AlphaShapes
    AlphaShapes <|-- DelaunayAlphaShapes
    DelaunayAlphaShapes <|-- BowyerWatsonDelaunay
    DelaunayAlphaShapes <|-- HelixDelaunay
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

## Persistence engines

Persistence-engine state is deliberately not diagrammed here. See
[Persistence engines](persistence-engines.md) for the current engine hierarchy,
roles, and supported complex types.

## Circular coordinates (`homology/CircularCoordinates.scala`)

```mermaid
classDiagram
    class CircularCoordinates {
        <<object>>
        h1Bars(metricSpace, maxFiltrationValue) IndexedSeq~(Double, Double)~
        compute(metricSpace, r, cocycleIndex, prime, maxFiltrationValue) Result
        computeToroidal(metricSpace, r, cocycleIndices, prime, reduce, maxFiltrationValue) ToroidalResult
    }
    class Result {
        theta: Map~Int, Double~
        birth: Double
        death: Double
        r: Double
        prime: Int
    }
    class ToroidalResult {
        theta: IndexedSeq~Map~Int, Double~~
        cocycleIndices: IndexedSeq~Int~
        basisChange: Array~Array~Int~~
        originalGram: Array~Array~Double~~
        reducedGram: Array~Array~Double~~
        r: Double
        prime: Int
    }
    class NoIntegerCocycleException {
        <<RuntimeException>>
    }
    class LatticeReduction {
        <<object>>
        reduce(gram, delta) Result
        isReduced(gram, delta, tol) Boolean
    }
    CircularCoordinates --> Result : returns (compute)
    CircularCoordinates --> ToroidalResult : returns (computeToroidal)
    CircularCoordinates ..> CellularCohomologyEngine : computes K_r's cohomology with
    CircularCoordinates ..> NoIntegerCocycleException : throws (no ℤ-lift at prime)
    CircularCoordinates ..> LatticeReduction : reduces the chosen classes' Gram matrix with
```

A standalone construction rather than a persistence engine.
`compute` produces circle-valued coordinates from one persistent H¹ class;
`computeToroidal` extends this to several simultaneous classes using
`LatticeReduction`.

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
    SpatialQuery <|-- VantagePointQuery
    SpatialQuery <|-- BruteForce
    SparseMetricSpace --> SpatialQuery : uses (VantagePointQuery)
```

## Barcode representation (`barcode/Barcode.scala`)

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
`AnnotationT` is typically a representative `Chain`.
`BarcodeDistance` and `Vectorization` operate only on persistence-bar endpoints
and do not use annotations.

## The public API layer (`homology/Persistence.scala`, `barcode/PersistenceDiagram.scala`, `package.scala`)

```mermaid
classDiagram
    direction LR
    class Persistence {
        <<object>>
        apply(input, options...) PersistenceDiagram
    }
    class Input~CellT~ {
        <<into trait>>
        stream(maxDim, threshold, complex)
        scale: Option~Double~
        cells: OrderedCell instance
        ripserApplies(complex) Boolean
        cubicalGrid: Option~Grid~
    }
    class Engine {
        <<enum>>
        Auto, Chunks, Naive
        Cohomology, Ripser, FastCubical
    }
    class Representatives {
        <<enum>>
        Cycles, Cocycles
    }
    class PointCloudComplex {
        <<trait>>
        fromPoints(points, maxDim, threshold)
    }
    class PersistenceDiagram~CellT~ {
        <<trait>>
        type Coefficient
        bars: List~PersistenceBar~
        dim(k), at(f), longerThan(x)
        significant(), bettiNumbers
    }
    class Coefficients {
        <<trait>>
        type C
        field: C is Field
        fromInt(x) C
    }
    Persistence ..> Input : takes
    Persistence ..> Engine : takes
    Persistence ..> Representatives : takes
    Persistence ..> PointCloudComplex : takes
    Persistence ..> Coefficients : picks the field
    Persistence ..> PersistenceDiagram : returns
```

`Input` is an `into` trait: points, metric spaces, images and streams convert
to one at the call site. `Engine` and `Input` are nested in `object
Persistence`. `VietorisRips`, `Cech` and `AlphaShapes` implement
`PointCloudComplex`. See [The public API](public-api.md).

```mermaid
classDiagram
    class Lab {
        <<abstract>>
        coefficients: Coefficients
        Fp(x) CoefficientT
    }
    class TDAlab {
        chain arithmetic on simplices
        F2, F3, F17, Reals
    }
    class CubicalLab {
        chain arithmetic on cubes
        F2, F3, F17, Reals
    }
    Lab <|-- TDAlab
    Lab <|-- CubicalLab
    Lab ..> Coefficients : holds
```

## Nerves and group homology (`sset/`)

```mermaid
classDiagram
    class SimplicialSet~G~ {
        <<trait: lazy, possibly infinite>>
        generators(n) Iterable~G~
        faces: G => IndexedSeq~SSetElement~
        skeleton(n) FiniteSimplicialSet
        persistentHomology(filtrationValue, maxDegree, prime) PersistenceDiagram
    }
    class FiniteMonoid {
        table: Array~Array~Int~~
        multiply(a, b) Int
    }
    class FiniteGroup {
        inverse(a) Int
        subgroupGeneratedBy(gens) Set~Int~
        pSubgroups(p) IndexedSeq~Set~Int~~
    }
    class FiniteCategory {
        <<trait>>
        source(f) Int
        target(f) Int
        identityOf(x) Int
        andThen(f, g) Int
        validate() Seq~String~
    }
    class RewritingSystem {
        <<case class>>
        normalForms
        levels
        validate() Seq~String~
        respects(chain) Seq~String~
    }
    class CollapsedNerve {
        criticalCells(n) IndexedSeq~NerveSimplex~
        morseBoundary(c) Map
        lift(c) Map
        stream(chain, maxDegree) StratifiedCellStream
        persistentHomology(chain, maxDegree, prime) PersistenceDiagram
    }
    class ClassifyingSpace {
        <<object>>
        persistentGroupHomology(g, chain, maxDegree, prime) PersistenceDiagram
        bettiNumbers(g, maxDegree, prime) Vector~Int~
    }
    SimplicialSet <|-- FiniteSimplicialSet
    SimplicialSet <|-- Nerve
    SimplicialSet <|-- CategoryNerve
    FiniteMonoid <|-- FiniteGroup
    FiniteCategory <|-- HomotopyOrbitCategory
    Nerve ..> FiniteMonoid : nerve of
    CategoryNerve ..> FiniteCategory : nerve of
    RewritingSystem ..> FiniteMonoid : presents
    CollapsedNerve ..> RewritingSystem : collapses by
    CollapsedNerve ..> Nerve : lifts to
    ClassifyingSpace ..> CollapsedNerve : when the chain refines
    ClassifyingSpace ..> Nerve : otherwise
```

See [Nerves and group homology](nerves.md).

## Plots (`plot/`)

```mermaid
classDiagram
    class Plot {
        <<trait>>
        fragment(theme) String
        html(theme) String
        svgFile(theme) String
        view() this
        save(path, theme) Path
    }
    class Figure {
        <<case class>>
        marks: Vector~Mark~
        axes, legend, colorbar
        over(under) Figure
        svg(theme) String
    }
    class Scene {
        <<case class: 3-D>>
        layers
        over(under) Scene
        staticSvg(theme) String
    }
    class Board {
        <<case class>>
        plots: Seq~Figure~
    }
    class Mark {
        <<sealed trait>>
        paints: IndexedSeq~Paint~
        tips: IndexedSeq~String~
    }
    class Palette {
        <<case class>>
        hex(paint, theme) String
        css(paint) String
    }
    class Paint {
        <<enum>>
        Series(k), Ink, Secondary, Muted, Grid, Axis, Surface, Fixed(hex)
    }
    class Theme {
        <<enum>>
        Auto, Light, Dark
    }
    Plot <|.. Figure
    Plot <|.. Scene
    Plot <|.. Board
    Figure o-- Mark
    Mark ..> Paint : colored by role
    Palette ..> Paint : resolves
    Palette ..> Theme : per
    Svg ..> Figure : renders
    Viewer ..> Plot : shows
```

`object Plot` (the TDA constructors: `barcode`, `diagram`, `complex`,
`cycle`, ...) shares the trait's name. See [Plotting internals](plotting.md).
