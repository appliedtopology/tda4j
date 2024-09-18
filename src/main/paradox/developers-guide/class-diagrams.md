# Mapping the library: class- and type-diagrams

## Support for different coefficients

We handle coefficient types by requiring a `Fractional` instance to be defined, 
which produces all the expected arithmetic operations on coefficients.
`Fractional[Double]` already exists in Scala, and we also implement in the same
way as is main-stream in the persistent homology library ecosystem finite field
arithmetic with lookup tables for inverses.

```
classDiagram
    class FiniteField {
        val p: Int
        type Fp
        given val FpIsFractional: Fractional[Fp]
        extension Fp.norm() Fp
        extension Fp.toInt() Int
        extension Fp.toString() String
        extension Fp.toUInt() UInt
    }
    namespace Scala {
        class Double {
        }

        class `DoubleIsFractional:Fractional[Double]` {
        }
    }
    FiniteField -- Fractional

    class `Chain[CellT: Cell, CoefficientT: Fractional]` {
        ...
    }

    Double -- Fractional

    Fractional *-- `Chain[CellT: Cell, CoefficientT: Fractional]`
    `Cell[CellT]` *-- `Chain[CellT: Cell, CoefficientT: Fractional]`
    `Cell[CellT]` --> `Simplex[VertexT]`
    class `Cell[CellT]` {
<<interface Typeclass>>
boundary() Chain[CellT, CoefficientT]
}
class `Simplex[VertexT]` {
<<extends SortedSet, Cell>>
size : Int
iterator : Iterator[VertexT]
boundary() Chain[AbstractSimplex[VertexT],CoefficientT]
}
```


## How do we describe a Simplicial Complex?

```
classDiagram
    `Cell[CellT]` --> `Simplex[VertexT]`
    class `Cell[CellT]` {
<<interface>>
boundary() Chain[CellT, CoefficientT]
}
class `Simplex[VertexT]` {
<<extends SortedSet, Cell>>
size : Int
iterator : Iterator[VertexT]
boundary() Chain[AbstractSimplex[VertexT],CoefficientT]
}
class `SimplexContext[VertexT]` {
<<interface Context>>
type Simplex = AbstractSimplex[VertexT]
given Ordering[Simplex]
s(vs*: Simplex) Simplex
}
class `IterableOnce[T]` {
iterator : Iterator[T]
knownSize : Int
}
class `SimplexFiltration[VertexT,FiltrationT]` {
filtrationValue(simplex: AbstractSimplex[VertexT]) FiltrationT
}
class `SimplexStream[VertexT,FiltrationT]` {
<<extends Filtration[VertexT,FiltrationT], IterableOnce[AbstractSimplex[VertexT]]>>
}
`IterableOnce[T]` --> `SimplexStream[VertexT,FiltrationT]` : inherits
`SimplexFiltration[VertexT,FiltrationT]` --> `SimplexStream[VertexT,FiltrationT]` : inherits
`Simplex[VertexT]` "*" o-- "1" `SimplexStream[VertexT,FiltrationT]` : contains

class `ExplicitStream[VertexT,FiltrationT]` {
filtrationValues : Map[AbstractSimplex[VertexT],FiltrationT]
simplices: Seq[AbstractSimplex[VertexT]]
}
class `VietorisRips[VertexT]` {
metricSpace: FiniteMetricSpace[VertexT]
maxDimension: Int
maxFiltrationValue: Double
cliqueFinder: CliqueFinder[VertexT]
}
`SimplexStream[VertexT,FiltrationT]` --> `ExplicitStream[VertexT,FiltrationT]`: inherits
`SimplexStream[VertexT,FiltrationT]` --> `VietorisRips[VertexT]`: inherits FiltrationT=Double
`VietorisRips[VertexT]` -- `CliqueFinder[VertexT]` : uses
class `CliqueFinder[VertexT]` {
<<interface>>
apply(metricSpace, maxFiltrationValue, maxDimension) Seq[AbstractSimplex[VertexT]]
    }
`CliqueFinder[VertexT]` --> BronKerbosch : implements
`CliqueFinder[VertexT]` --> ZomorodianIncremental : implements
`CliqueFinder[VertexT]` --> SymmetricZomorodianIncremental : implements
```


## Revision 2024-09-18

```mermaid
classDiagram
    namespace Barcode_scala {
        class BarcodeEndpoint
        class PositiveInfinity
        class NegativeInfinity
        class OpenEndpoint
        class ClosedEndpoint
        class PersistenceBar {
            dim : int
            lower : BarcodeEndpoint
            upper : BarcodeEndpoint
            annotation : Option[AnnotationT]
            toString()
        }
        class BarcodeContext {
            FiltrationT bc FiltrationT : PersistenceBar
            FiltrationT clcl FiltrationT : PersistenceBar
            FiltrationT clop FiltrationT : PersistenceBar
            FiltrationT opcl FiltrationT : PersistenceBar
            FiltrationT opop FiltrationT : PersistenceBar
            clinf FiltrationT : PersistenceBar
            opinf FiltrationT : PersistenceBar
            infcl FiltrationT : PersistenceBar
            infop FiltrationT : PersistenceBar
        }
        class Barcode {
            isMap(List~PersistenceBar~, List~PersistenceBar~, RealMatrix) bool
            imageMatrix(List~PersistenceBar~, List~PersistenceBar~, RealMatrix) RealMatrix
            image(List~PersistenceBar~, List~PersistenceBar~, RealMatrix) List~PersistenceBar~
            kernel(List~PersistenceBar~, List~PersistenceBar~, RealMatrix) List~PersistenceBar~
            cokernelMatrix(List~PersistenceBar~, List~PersistenceBar~, RealMatrix) RealMatrix
            cokernel(List~PersistenceBar~, List~PersistenceBar~, RealMatrix) List~PersistenceBar~
            reduceMatrix(RealMatrix) RealMatrix
        }
    }
    BarcodeEndpoint --|> PositiveInfinity
    BarcodeEndpoint --|> NegativeInfinity
    BarcodeEndpoint --|> OpenEndpoint
    BarcodeEndpoint --|> ClosedEndpoint
```

```mermaid
classDiagram
    namespace Chain_scala {
        class HasBoundary {
            type Self : Ordering
            extension Self.boundary[CoefficientT : Fractional] : Chain[Self, CoefficientT]
        }
        class HasDimension {
            type Self
            extension Self.dim : Int
        }
        class Cell
        class OrderedCell
        class given_Ordering~OrderedCell~
        class OrderedBasis {
            type Self
            extension Self.leadingTerm: Tuple[Option[CellT], CoefficientT]
            extension Self.leadingCoefficient : CoefficientT
            extension Self.leadingCell : Option[CellT]
        }
        class Chain~CellT, CoefficientT~ {
            private entries : mutable.PriorityQueue[Tuple[CellT, CoefficientT]]
            collapseHead()
            collapseAll()
            isZero() bool
            items : Seq[Tuple[CellT, CoefficientT]]
            chainBoundary() Chain~CellT, CoefficientT~ 
        }
        class object_Chain {
            apply(cs : vararg Tuple[CellT, CoefficientT]) Chain~CellT, CoefficientT~
            apply(c : CellT) Chain~CellT, CoefficientT~
            from(cs : Seq[Tuple[CellT, CoefficientT]]) Chain~CellT, CoefficientT~ 
        }
        class given_Chain_is_OrderedBasis
        class ChainOps~CellT, CoefficientT~
    }
    HasBoundary --|> Cell
    HasDimension --|> Cell
    Cell --|> OrderedCell
    RingModule --|> ChainOps

```

```mermaid
classDiagram
    namespace Cube_scala {
        class ElementaryInterval {
            n : int
        }
        class DegenerateInterval
        class FullInterval
        class given_Ordering_ElementaryInterval
        class given_ElementaryInterval_is_OrderedCell
        class ElementaryCube {
            intervals : List[ElementaryInterval]
        }
    }
    ElementaryInterval --|> DegenerateInterval
    ElementaryInterval --|> FullInterval
```

```mermaid
classDiagram
    namespace FiniteField_scala {
        class FiniteField {
            p : int
            type Fp
            given Fp_is_Fractional
        }
    }
```

```mermaid
classDiagram
    namespace FiniteMetricSpace_scala {
        class FiniteMetricSpace {
            distance(vertex, vertex) Double
            size : int
            elements : Iterable[VertexT]
            contains(vertex) bool
            minimumEnclosingRadius : lazy Double
        }
        class MaximumDistanceFiltrationValue
        class ExplicitMetricSpace
        class EuclideanMetricSpace
    }
    FiniteMetricSpace --|> ExplicitMetricSpace
    FiniteMetricSpace --|> EuclideanMetricSpace
```

```mermaid
classDiagram
    namespace Homology_scala {
        class HomologyState {
            cycles : mutable.Map[CellT, Chain[CellT, CoefficientT]]
            cyclesBornBy : mutable.Map[CellT, CellT]
            boundaries : mutable.Map[CellT, Chain[CellT, CoefficientT]]
            boundariesBornBy : mutable.Map[CellT, CellT]
            coboundaries : mutable.Map[CellT, Chain[CellT, CoefficientT]]
            stream : CellStream[CellT, FiltrationT]
            current : FiltrationT
            barcode : mutable.ArrayDeque[Tuple[Int, FiltrationT, FiltrationT, Chain[CellT, CoefficientT]]
            diagramAt(f : FiltrationT) List[Tuple[Int,FiltrationT,FiltrationT]]
            barcodeAt(f : FiltrationT) List[PersistenceBar]
            advanceOne()
            advanceTo(f: FiltrationT)
            advanceAll()
        }
    }
```

```mermaid
classDiagram
    namespace RingModule_scala {
        class RingModule {
            zero : T
            isZero(t : T) bool
            plus(s : T, t: T) T
            minus(s : T, t: T) T
            negate(t: T) T
            scale(r : R, t : T) T
            extension T.+
            extension T.-
            extension T.unary_-
            extension infix T.mul
            extension R.⊠
            extension infix R.scale
        }
    }
```

```mermaid
classDiagram
    namespace Simplex_scala {
        class Simplex {
            vertices : SortedSet[VertexT]
            union(other : Simplex) Simplex
        }
        class given_Ordering_Simplex
        class given_Simplex_is_OrderedCell
        class object_Simplex {
            apply(vertices : vararg VertexT) Simplex
            from(vertices : Seq[VertexT]) Simplex
            empty() Simplex
            ∆(vertices : vararg VertexT) Simplex
        }
    }
```

```mermaid
classDiagram
    namespace SimplexStream_scala {
        class SimplexStream
    }
```

```mermaid
classDiagram
    namespace SymmetryGroup_scala {
        class SymmetryGroup
    }
```

```mermaid
classDiagram
    namespace UnionFind_scala {
        class UnionFind
    }
```

```mermaid
classDiagram
    namespace VietorisRips_scala {
        class VietorisRips
    }
```