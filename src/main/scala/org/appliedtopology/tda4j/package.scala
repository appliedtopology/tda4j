package org.appliedtopology.tda4j

import cats.Show

/** A coefficient-field context for interactive/notebook-style work: `val tdalab = TDAlab(characteristic); import
  * tdalab.{*, given}` brings in `CoefficientT`, `Fp(...)`, the field's `given`, chain arithmetic (`⊠`, `+`, `-`) on
  * `Chain[Simplex[Int], CoefficientT]`, an implicit `Simplex -> Chain` widening and cats' `.show` syntax.
  * `characteristic = 0` selects `Double` coefficients (compared within `precision`); a prime `p` selects `Z/p`.
  *
  * Everything else -- `∆`, `Simplex`, `VietorisRips`, the engines, `CSV`, ... -- comes from the library itself (`import
  * org.appliedtopology.tda4j.*`), and default instances (`Simplex[Int] is OrderedCell`, `Show[Simplex[Int]]`, ...) come
  * from the types' own companions, so `TDAlab` neither re-exports nor shadows any of them. Never consulted by an engine
  * (see CLAUDE.md's generic-`given`-capture note).
  *
  * Scala forbids `import TDAlab(p = 3).{*, given}`, hence the `val` first.
  */
class TDAlab(characteristic: Int, precision: Double = 1e-9):

  import cats.syntax.all.*

  trait FieldData:
    type CoefficientT

    given CoefficientT is Field = compiletime.deferred

    def coeff(x: Int): CoefficientT

    def Fp(x: Int): CoefficientT = coeff(x)

  object FieldData:
    def apply(): FieldData = characteristic match
      case 0 =>
        new FieldData:
          override type CoefficientT = Double

          override given CoefficientT is Field = Field.DoubleApproximated(precision)

          override def coeff(x: Int): CoefficientT = x.toDouble
      case p if BigInt(characteristic).isProbablePrime(certainty = 100) =>
        val ff = FiniteField(p)
        import ff.given
        new FieldData:
          override type CoefficientT = ff.Fp

          override given CoefficientT is Field = summon[ff.Fp is Field]

          override def coeff(x: Int): CoefficientT = ff.Fp(x)
      case _ =>
        throw IllegalArgumentException(s"TDAlab: characteristic must be 0 or a prime, got $characteristic")

  val fieldData = FieldData()
  export fieldData.{*, given}

  type VertexT = Int
  val chainIsRingModule: Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT } =
    summon[Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT }]
  export chainIsRingModule.*
  given [T: Ordering] => Conversion[Simplex[T], Chain[Simplex[T], CoefficientT]] =
    Chain.apply

  // BEGIN generated re-exports
  // regenerate: python3 .claude/scripts/tdalab-exports.py (checked by TDAlabExportsSpec)
  export org.appliedtopology.tda4j.{
    ∆,
    AlphaComplexDQP,
    AlphaComplexDQPBuilder,
    AlphaComplexDQPException,
    AlphaComplexDQPStream,
    AlphaDQPSettings,
    AlphaShapeDQP,
    AlphaShapes,
    Barcode,
    BarcodeBuilder,
    BarcodeDistance,
    BarcodeEndpoint,
    BarcodeGenerators,
    BruteForce,
    CSV,
    Cech,
    CechFiltration,
    Cell,
    CellStream,
    CellularCohomologyEngine,
    CellularHomologyEngine,
    CellularPersistenceInChunksEngine,
    Chain,
    CholeskyWorkspace,
    CircularCoordinates,
    ClosedEndpoint,
    Coefficients,
    CofacetIterator,
    Cube,
    CubicalGridStream,
    CubicalHomologyEngine,
    CubicalImage,
    CubicalPersistenceInChunksEngine,
    DelaunaySimplex,
    Dipha,
    DistanceToMeasure,
    DoubleFiltration,
    Dowker,
    DowkerFiltration,
    DowkerGeometry,
    DtmRips,
    DualQP,
    EdgeCollapse,
    EdgeCollapsedMetricSpace,
    Epsilon,
    EuclideanMetricSpace,
    ExplicitCubicalStream,
    ExplicitMetricSpace,
    ExplicitStream,
    ExplicitStreamBuilder,
    FastAlphaHomologyEngine,
    FastAlphaTriangulationException,
    FastCubicalHomologyEngine,
    Field,
    Filterable,
    FilteredSimplexOrdering,
    Filtration,
    FiltrationOrdering,
    FiniteField,
    FiniteMetricSpace,
    GreedyPermutation,
    Gudhi,
    HasDimension,
    HelixDelaunay,
    Hyperplane,
    Hypersphere,
    Image,
    IntMetricSpace,
    JVPTree,
    Kruskal,
    LandmarkSelection,
    LandmarkSelector,
    LatticeReduction,
    LevelwiseSimplexStream,
    LimitedAlphaShapesStream,
    LimitedCubicalGridStream,
    LinearAlgebra,
    NegativeInfinity,
    NoIntegerCocycleException,
    OpenEndpoint,
    Optional,
    OrderedBasis,
    OrderedCell,
    PackedRipserCohomologyEngine,
    Perseus,
    Persistence,
    PersistenceBar,
    PersistenceDiagram,
    PersistenceEngine,
    PersistenceFilter,
    PersistenceInChunksEngine,
    PointCloud,
    PointCloudComplex,
    PositiveInfinity,
    PowerDistance,
    RecursiveStackSimplexEnumerator,
    RingModule,
    Ripser,
    RipserCohomologyEngine,
    Simplex,
    SimplexEdge,
    SimplexIndexing,
    SimplexStream,
    SimplicialHomologyEngine,
    SparseMetricSpace,
    SparseRips,
    SpatialQuery,
    StratifiedCellStream,
    TopCofacetEnumerator,
    Truncated,
    UnionFind,
    Vectorization,
    VietorisRips,
    Witness,
    WitnessGeometry,
    WitnessMetricSpace
  }
  export org.appliedtopology.tda4j.sset.{
    BettiNumbers,
    ClassifyingSpace,
    ComplexProjectivePlaneGenerator,
    ConeGenerator,
    CupProduct,
    FilteredSimplicialSetStream,
    FiniteGroup,
    FiniteMonoid,
    FiniteSimplicialSet,
    FundamentalGroup,
    GroupPresentation,
    HopfSphereGenerator,
    JoinGenerator,
    KleinGenerator,
    MinimalSphereGenerator,
    Nerve,
    NerveSimplex,
    PresentationCell,
    ProductGenerator,
    RealProjectiveGenerator,
    SSetElement,
    SSetMap,
    SimplicialSet,
    SimplicialSetCatalog,
    SimplicialSetStream,
    Steenrod,
    TorusGenerator
  }
  // END generated re-exports

  export cats.implicits.toShow
