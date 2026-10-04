package org.appliedtopology.tda4j

import cats.Show

/** What every lab shares: a coefficient field chosen by its characteristic (`0` for `Double` compared within
  * `precision`, a prime `p` for `Z/p`), with `CoefficientT`, `Fp(...)` and the field's `given`; flat re-exports of the
  * whole library and the `sset` add-on, so `import lab.{*, given}` is the only import a lab user needs; and cats'
  * `.show` syntax. Concrete labs add the conveniences of their setting: [[TDAlab]] (simplicial), [[CubicalLab]].
  * Engines never consult a lab: it is for writing chain algebra by hand.
  */
abstract class Lab(characteristic: Int, precision: Double = 1e-9):
  val coefficients: Coefficients = Coefficients(characteristic, precision)
  type CoefficientT = coefficients.C
  given coefficientField: (CoefficientT is Field) = coefficients.field

  /** A coefficient from an integer (reduced mod p, or as a Double). */
  def Fp(x: Int): CoefficientT = coefficients.fromInt(x)

  // BEGIN generated re-exports
  // regenerate: python3 .claude/scripts/tdalab-exports.py (checked by TDAlabExportsSpec)
  export org.appliedtopology.tda4j.{
    ∆,
    AlphaBackend,
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
    Representatives,
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

/** The simplicial lab: `import TDAlab.F17.{*, given}` (or `val lab = TDAlab(p); import lab.{*, given}` for another
  * prime) brings in everything [[Lab]] does, plus chain arithmetic (`⊠`, `+`, `-`) on `Chain[Simplex[Int],
  * CoefficientT]` and an implicit `Simplex -> Chain` widening, so chains can be written straight up and down: `Fp(2) ⊠
  * ∆(1, 2) - ∆(2, 3)`.
  */
class TDAlab(characteristic: Int, precision: Double = 1e-9) extends Lab(characteristic, precision):
  type VertexT = Int
  val chainIsRingModule: Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT } =
    summon[Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT }]
  export chainIsRingModule.*
  given [T: Ordering] => Conversion[Simplex[T], Chain[Simplex[T], CoefficientT]] =
    Chain.apply

/** Prebuilt simplicial labs: `import TDAlab.F17.{*, given}`. F17 is the library default (`FiniteField.DefaultPrime`).
  */
object TDAlab:
  object F2 extends TDAlab(2)
  object F3 extends TDAlab(3)
  object F17 extends TDAlab(17)
  object Reals extends TDAlab(0)

/** The cubical lab: [[Lab]] plus chain arithmetic on `Chain[Cube, CoefficientT]` and a `Cube -> Chain` widening (and no
  * simplex conveniences). `import CubicalLab.F17.{*, given}`.
  */
class CubicalLab(characteristic: Int, precision: Double = 1e-9) extends Lab(characteristic, precision):
  val chainIsRingModule: Chain[Cube, CoefficientT] is RingModule { type R = CoefficientT } =
    summon[Chain[Cube, CoefficientT] is RingModule { type R = CoefficientT }]
  export chainIsRingModule.*
  given Conversion[Cube, Chain[Cube, CoefficientT]] = Chain.apply

/** Prebuilt cubical labs: `import CubicalLab.F17.{*, given}`. */
object CubicalLab:
  object F2 extends CubicalLab(2)
  object F3 extends CubicalLab(3)
  object F17 extends CubicalLab(17)
  object Reals extends CubicalLab(0)
