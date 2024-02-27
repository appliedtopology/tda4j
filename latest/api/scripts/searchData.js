pages = [{"l":"index.html#","e":false,"i":"","n":"root","t":"root","d":"","k":"static"},
{"l":"org/appliedtopology/tda4j.html#","e":false,"i":"","n":"org.appliedtopology.tda4j","t":"org.appliedtopology.tda4j","d":"","k":"package"},
{"l":"org/appliedtopology/tda4j.html#orderingBitSet-0","e":false,"i":"","n":"orderingBitSet","t":"orderingBitSet: Ordering[BitSet]","d":"org.appliedtopology.tda4j","k":"given"},
{"l":"org/appliedtopology/tda4j/AbstractSimplex.html#","e":false,"i":"","n":"AbstractSimplex","t":"AbstractSimplex[VertexT](val vertexSet: SortedSet[VertexT])(using val ordering: Ordering[VertexT]) extends Cell[AbstractSimplex[VertexT]] with SortedSet[VertexT] with SortedSetOps[VertexT, AbstractSimplex, AbstractSimplex[VertexT]] with SortedSetFactoryDefaults[VertexT, AbstractSimplex, Set]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/AbstractSimplex.html#ordering-0","e":false,"i":"","n":"ordering","t":"ordering: Ordering[VertexT]","d":"org.appliedtopology.tda4j.AbstractSimplex","k":"given"},
{"l":"org/appliedtopology/tda4j/AbstractSimplex$.html#","e":false,"i":"","n":"AbstractSimplex","t":"AbstractSimplex extends SortedIterableFactory[AbstractSimplex]","d":"org.appliedtopology.tda4j","k":"object"},
{"l":"org/appliedtopology/tda4j/BronKerbosch.html#","e":false,"i":"","n":"BronKerbosch","t":"BronKerbosch[VertexT] extends CliqueFinder[VertexT]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/BronKerbosch.html#apply-c5","e":false,"i":"","n":"apply","t":"apply(metricSpace: FiniteMetricSpace[VertexT], maxFiltrationValue: Double, maxDimension: Int): Seq[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.BronKerbosch","k":"def"},
{"l":"org/appliedtopology/tda4j/BronKerbosch.html#className-0","e":false,"i":"","n":"className","t":"className: String","d":"org.appliedtopology.tda4j.BronKerbosch","k":"val"},
{"l":"org/appliedtopology/tda4j/Cell.html#","e":false,"i":"","n":"Cell","t":"Cell[CellT <: Cell[CellT]]","d":"org.appliedtopology.tda4j","k":"trait"},
{"l":"org/appliedtopology/tda4j/Cell.html#boundary-7d4","e":false,"i":"","n":"boundary","t":"boundary[CoefficientT : Fractional]: Chain[CellT, CoefficientT]","d":"org.appliedtopology.tda4j.Cell","k":"def"},
{"l":"org/appliedtopology/tda4j/Chain.html#","e":false,"i":"","n":"Chain","t":"Chain[CellT <: Cell[CellT], CoefficientT](val chainMap: SortedMap[CellT, CoefficientT])(implicit evidence$1: Ordering[CellT], evidence$2: Fractional[CoefficientT])","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/Chain.html#chainMap-0","e":false,"i":"","n":"chainMap","t":"chainMap: SortedMap[CellT, CoefficientT]","d":"org.appliedtopology.tda4j.Chain","k":"val"},
{"l":"org/appliedtopology/tda4j/Chain$.html#","e":false,"i":"","n":"Chain","t":"Chain","d":"org.appliedtopology.tda4j","k":"object"},
{"l":"org/appliedtopology/tda4j/Chain$.html#apply-fffff718","e":false,"i":"","n":"apply","t":"apply[CellT <: Cell[LazyRef(...)] : Ordering, CoefficientT : Fractional](items: (CellT, CoefficientT)*): Chain[CellT, CoefficientT]","d":"org.appliedtopology.tda4j.Chain","k":"def"},
{"l":"org/appliedtopology/tda4j/Chain$.html#apply-fffffece","e":false,"i":"","n":"apply","t":"apply[CellT <: Cell[LazyRef(...)] : Ordering, CoefficientT](cell: CellT)(implicit evidence$8: Ordering[CellT], fr: Fractional[CoefficientT]): Chain[CellT, CoefficientT]","d":"org.appliedtopology.tda4j.Chain","k":"def"},
{"l":"org/appliedtopology/tda4j/ChainOps.html#","e":false,"i":"","n":"ChainOps","t":"ChainOps[CellT <: Cell[CellT], CoefficientT] extends RingModule[Chain[CellT, CoefficientT], CoefficientT]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/ChainOps.html#plus-90e","e":false,"i":"","n":"plus","t":"plus(x: Chain[CellT, CoefficientT], y: Chain[CellT, CoefficientT]): Chain[CellT, CoefficientT]","d":"org.appliedtopology.tda4j.ChainOps","k":"def"},
{"l":"org/appliedtopology/tda4j/ChainOps.html#scale-fffff093","e":false,"i":"","n":"scale","t":"scale(x: CoefficientT, y: Chain[CellT, CoefficientT]): Chain[CellT, CoefficientT]","d":"org.appliedtopology.tda4j.ChainOps","k":"def"},
{"l":"org/appliedtopology/tda4j/ChainOps.html#zero-0","e":false,"i":"","n":"zero","t":"zero: Chain[CellT, CoefficientT]","d":"org.appliedtopology.tda4j.ChainOps","k":"val"},
{"l":"org/appliedtopology/tda4j/CliqueFinder.html#","e":false,"i":"","n":"CliqueFinder","t":"CliqueFinder[VertexT] extends (FiniteMetricSpace[VertexT], Double, Int) => Seq[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/CliqueFinder.html#className-0","e":false,"i":"","n":"className","t":"className: String","d":"org.appliedtopology.tda4j.CliqueFinder","k":"val"},
{"l":"org/appliedtopology/tda4j/CliqueFinder$.html#","e":false,"i":"","n":"CliqueFinder","t":"CliqueFinder","d":"org.appliedtopology.tda4j","k":"object"},
{"l":"org/appliedtopology/tda4j/CliqueFinder$.html#BronKerboschAlgorithm-fffffe40","e":false,"i":"","n":"BronKerboschAlgorithm","t":"BronKerboschAlgorithm[VertexT : Ordering](maxDimension: Int, edges: Graph[VertexT, WUnDiEdge]): Set[Set[VertexT]]","d":"org.appliedtopology.tda4j.CliqueFinder","k":"def"},
{"l":"org/appliedtopology/tda4j/CliqueFinder$.html#simplexOrdering-fffffdc3","e":false,"i":"","n":"simplexOrdering","t":"simplexOrdering[VertexT : Ordering](metricSpace: FiniteMetricSpace[VertexT]): Ordering[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.CliqueFinder","k":"def"},
{"l":"org/appliedtopology/tda4j/CliqueFinder$.html#weightedEdges-4","e":false,"i":"","n":"weightedEdges","t":"weightedEdges[VertexT : Ordering](metricSpace: FiniteMetricSpace[VertexT], maxFiltrationValue: Double): Graph[VertexT, WUnDiEdge]","d":"org.appliedtopology.tda4j.CliqueFinder","k":"def"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#","e":false,"i":"","n":"EuclideanMetricSpace","t":"EuclideanMetricSpace(val pts: Seq[Seq[Double]]) extends FiniteMetricSpace[Int]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#distance-579","e":false,"i":"","n":"distance","t":"distance(x: Int, y: Int): Double","d":"org.appliedtopology.tda4j.EuclideanMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#elements-0","e":false,"i":"","n":"elements","t":"elements: Iterable[Int]","d":"org.appliedtopology.tda4j.EuclideanMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#pts-0","e":false,"i":"","n":"pts","t":"pts: Seq[Seq[Double]]","d":"org.appliedtopology.tda4j.EuclideanMetricSpace","k":"val"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#size-0","e":false,"i":"","n":"size","t":"size: Int","d":"org.appliedtopology.tda4j.EuclideanMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/ExpandList.html#","e":false,"i":"","n":"ExpandList","t":"ExpandList[VertexT, KeyT](val representatives: Seq[AbstractSimplex[VertexT]], val symmetry: SymmetryGroup[KeyT, VertexT])(implicit evidence$2: Ordering[VertexT]) extends IndexedSeq[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/ExpandList.html#currentOrbit-0","e":false,"i":"","n":"currentOrbit","t":"currentOrbit: Option[Seq[AbstractSimplex[VertexT]]]","d":"org.appliedtopology.tda4j.ExpandList","k":"var"},
{"l":"org/appliedtopology/tda4j/ExpandList.html#currentRepresentative-0","e":false,"i":"","n":"currentRepresentative","t":"currentRepresentative: Option[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.ExpandList","k":"var"},
{"l":"org/appliedtopology/tda4j/ExpandList.html#orbitRanges-0","e":false,"i":"","n":"orbitRanges","t":"orbitRanges: Seq[Int]","d":"org.appliedtopology.tda4j.ExpandList","k":"val"},
{"l":"org/appliedtopology/tda4j/ExpandList.html#orbitSizes-0","e":false,"i":"","n":"orbitSizes","t":"orbitSizes: Map[AbstractSimplex[VertexT], Int]","d":"org.appliedtopology.tda4j.ExpandList","k":"val"},
{"l":"org/appliedtopology/tda4j/ExpandList.html#repOrbitRanges-0","e":false,"i":"","n":"repOrbitRanges","t":"repOrbitRanges: Seq[(AbstractSimplex[VertexT], Int)]","d":"org.appliedtopology.tda4j.ExpandList","k":"val"},
{"l":"org/appliedtopology/tda4j/ExpandList.html#representatives-0","e":false,"i":"","n":"representatives","t":"representatives: Seq[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.ExpandList","k":"val"},
{"l":"org/appliedtopology/tda4j/ExpandList.html#symmetry-0","e":false,"i":"","n":"symmetry","t":"symmetry: SymmetryGroup[KeyT, VertexT]","d":"org.appliedtopology.tda4j.ExpandList","k":"val"},
{"l":"org/appliedtopology/tda4j/ExplicitMetricSpace.html#","e":false,"i":"","n":"ExplicitMetricSpace","t":"ExplicitMetricSpace(val dist: Seq[Seq[Double]]) extends FiniteMetricSpace[Int]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/ExplicitMetricSpace.html#dist-0","e":false,"i":"","n":"dist","t":"dist: Seq[Seq[Double]]","d":"org.appliedtopology.tda4j.ExplicitMetricSpace","k":"val"},
{"l":"org/appliedtopology/tda4j/ExplicitMetricSpace.html#distance-579","e":false,"i":"","n":"distance","t":"distance(x: Int, y: Int): Double","d":"org.appliedtopology.tda4j.ExplicitMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/ExplicitMetricSpace.html#elements-0","e":false,"i":"","n":"elements","t":"elements: Iterable[Int]","d":"org.appliedtopology.tda4j.ExplicitMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/ExplicitMetricSpace.html#size-0","e":false,"i":"","n":"size","t":"size: Int","d":"org.appliedtopology.tda4j.ExplicitMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/ExplicitStream.html#","e":false,"i":"","n":"ExplicitStream","t":"ExplicitStream[VertexT, FiltrationT](val filtrationValues: Map[AbstractSimplex[VertexT], FiltrationT], val simplices: Seq[AbstractSimplex[VertexT]])(using ordering: Ordering[FiltrationT]) extends SimplexStream[VertexT, FiltrationT]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/ExplicitStream.html#apply-f8a","e":false,"i":"","n":"apply","t":"apply(i: Int): AbstractSimplex[VertexT]","d":"org.appliedtopology.tda4j.ExplicitStream","k":"def"},
{"l":"org/appliedtopology/tda4j/ExplicitStream.html#filtrationValue-0","e":false,"i":"","n":"filtrationValue","t":"filtrationValue: PartialFunction[AbstractSimplex[VertexT], FiltrationT]","d":"org.appliedtopology.tda4j.ExplicitStream","k":"def"},
{"l":"org/appliedtopology/tda4j/ExplicitStream.html#filtrationValues-0","e":false,"i":"","n":"filtrationValues","t":"filtrationValues: Map[AbstractSimplex[VertexT], FiltrationT]","d":"org.appliedtopology.tda4j.ExplicitStream","k":"val"},
{"l":"org/appliedtopology/tda4j/ExplicitStream.html#iterator-0","e":false,"i":"","n":"iterator","t":"iterator: Iterator[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.ExplicitStream","k":"def"},
{"l":"org/appliedtopology/tda4j/ExplicitStream.html#length-0","e":false,"i":"","n":"length","t":"length: Int","d":"org.appliedtopology.tda4j.ExplicitStream","k":"def"},
{"l":"org/appliedtopology/tda4j/ExplicitStream.html#simplices-0","e":false,"i":"","n":"simplices","t":"simplices: Seq[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.ExplicitStream","k":"val"},
{"l":"org/appliedtopology/tda4j/ExplicitStreamBuilder.html#","e":false,"i":"","n":"ExplicitStreamBuilder","t":"ExplicitStreamBuilder[VertexT, FiltrationT](implicit evidence$2: Ordering[VertexT], ordering: Ordering[FiltrationT]) extends ReusableBuilder[(FiltrationT, AbstractSimplex[VertexT]), ExplicitStream[VertexT, FiltrationT]]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/ExplicitStreamBuilder.html#filtrationValues-0","e":false,"i":"","n":"filtrationValues","t":"filtrationValues: Map[AbstractSimplex[VertexT], FiltrationT]","d":"org.appliedtopology.tda4j.ExplicitStreamBuilder","k":"val"},
{"l":"org/appliedtopology/tda4j/ExplicitStreamBuilder.html#simplices-0","e":false,"i":"","n":"simplices","t":"simplices: Queue[(FiltrationT, AbstractSimplex[VertexT])]","d":"org.appliedtopology.tda4j.ExplicitStreamBuilder","k":"val"},
{"l":"org/appliedtopology/tda4j/Filtration.html#","e":false,"i":"","n":"Filtration","t":"Filtration[VertexT, FiltrationT]","d":"org.appliedtopology.tda4j","k":"trait"},
{"l":"org/appliedtopology/tda4j/Filtration.html#filtrationValue-0","e":false,"i":"","n":"filtrationValue","t":"filtrationValue: PartialFunction[AbstractSimplex[VertexT], FiltrationT]","d":"org.appliedtopology.tda4j.Filtration","k":"def"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace.html#","e":false,"i":"","n":"FiniteMetricSpace","t":"FiniteMetricSpace[VertexT]","d":"org.appliedtopology.tda4j","k":"trait"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace.html#contains-4ad","e":false,"i":"","n":"contains","t":"contains(x: VertexT): Boolean","d":"org.appliedtopology.tda4j.FiniteMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace.html#distance-fffffdbf","e":false,"i":"","n":"distance","t":"distance(x: VertexT, y: VertexT): Double","d":"org.appliedtopology.tda4j.FiniteMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace.html#elements-0","e":false,"i":"","n":"elements","t":"elements: Iterable[VertexT]","d":"org.appliedtopology.tda4j.FiniteMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace.html#size-0","e":false,"i":"","n":"size","t":"size: Int","d":"org.appliedtopology.tda4j.FiniteMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace$.html#","e":false,"i":"","n":"FiniteMetricSpace","t":"FiniteMetricSpace","d":"org.appliedtopology.tda4j","k":"object"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace$$MaximumDistanceFiltrationValue.html#","e":false,"i":"","n":"MaximumDistanceFiltrationValue","t":"MaximumDistanceFiltrationValue[VertexT](val metricSpace: FiniteMetricSpace[VertexT])(implicit evidence$1: Ordering[VertexT]) extends PartialFunction[AbstractSimplex[VertexT], Double]","d":"org.appliedtopology.tda4j.FiniteMetricSpace","k":"class"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace$$MaximumDistanceFiltrationValue.html#apply-eea","e":false,"i":"","n":"apply","t":"apply(spx: AbstractSimplex[VertexT]): Double","d":"org.appliedtopology.tda4j.FiniteMetricSpace.MaximumDistanceFiltrationValue","k":"def"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace$$MaximumDistanceFiltrationValue.html#isDefinedAt-fffffc2f","e":false,"i":"","n":"isDefinedAt","t":"isDefinedAt(spx: AbstractSimplex[VertexT]): Boolean","d":"org.appliedtopology.tda4j.FiniteMetricSpace.MaximumDistanceFiltrationValue","k":"def"},
{"l":"org/appliedtopology/tda4j/FiniteMetricSpace$$MaximumDistanceFiltrationValue.html#metricSpace-0","e":false,"i":"","n":"metricSpace","t":"metricSpace: FiniteMetricSpace[VertexT]","d":"org.appliedtopology.tda4j.FiniteMetricSpace.MaximumDistanceFiltrationValue","k":"val"},
{"l":"org/appliedtopology/tda4j/HyperCube.html#","e":false,"i":"","n":"HyperCube","t":"HyperCube(bitlength: Int) extends FiniteMetricSpace[BitSet]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/HyperCube.html#top-0","e":false,"i":"","n":"top","t":"top: BitSet","d":"org.appliedtopology.tda4j.HyperCube","k":"val"},
{"l":"org/appliedtopology/tda4j/HyperCubeSymmetry.html#","e":false,"i":"","n":"HyperCubeSymmetry","t":"HyperCubeSymmetry(bitlength: Int) extends SymmetryGroup[Int, BitSet]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/HyperCubeSymmetry.html#apply-fffff28a","e":false,"i":"","n":"apply","t":"apply(permutationIndex: Int): BitSet => BitSet","d":"org.appliedtopology.tda4j.HyperCubeSymmetry","k":"def"},
{"l":"org/appliedtopology/tda4j/HyperCubeSymmetry.html#applyPermutation-fffff28a","e":false,"i":"","n":"applyPermutation","t":"applyPermutation(permutationIndex: Int): Int => Int","d":"org.appliedtopology.tda4j.HyperCubeSymmetry","k":"def"},
{"l":"org/appliedtopology/tda4j/HyperCubeSymmetry.html#hypercube-0","e":false,"i":"","n":"hypercube","t":"hypercube: HyperCube","d":"org.appliedtopology.tda4j.HyperCubeSymmetry","k":"val"},
{"l":"org/appliedtopology/tda4j/HyperCubeSymmetry.html#permutations-0","e":false,"i":"","n":"permutations","t":"permutations: Permutations","d":"org.appliedtopology.tda4j.HyperCubeSymmetry","k":"val"},
{"l":"org/appliedtopology/tda4j/LazyVietorisRips$.html#","e":false,"i":"","n":"LazyVietorisRips","t":"LazyVietorisRips","d":"org.appliedtopology.tda4j","k":"object"},
{"l":"org/appliedtopology/tda4j/LazyVietorisRips$.html#apply-fffffd3f","e":false,"i":"","n":"apply","t":"apply[VertexT : Ordering](metricSpace: FiniteMetricSpace[VertexT], maxFiltrationValue: Double, maxDimension: Int): LazyList[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.LazyVietorisRips","k":"def"},
{"l":"org/appliedtopology/tda4j/Permutations.html#","e":false,"i":"","n":"Permutations","t":"Permutations(elementCount: Int)","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/Permutations.html#apply-fffff84b","e":false,"i":"","n":"apply","t":"apply(n: Int): List[Int]","d":"org.appliedtopology.tda4j.Permutations","k":"def"},
{"l":"org/appliedtopology/tda4j/Permutations.html#factorial-fffff0ab","e":false,"i":"","n":"factorial","t":"factorial(n: Int): Long","d":"org.appliedtopology.tda4j.Permutations","k":"def"},
{"l":"org/appliedtopology/tda4j/Permutations.html#size-0","e":false,"i":"","n":"size","t":"size: Long","d":"org.appliedtopology.tda4j.Permutations","k":"val"},
{"l":"org/appliedtopology/tda4j/RingModule.html#","e":false,"i":"","n":"RingModule","t":"RingModule[T, R]","d":"org.appliedtopology.tda4j","k":"trait"},
{"l":"org/appliedtopology/tda4j/RingModule.html#*>-fffffab9","e":false,"i":"R","n":"*>","t":"*>(t: T): T","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/RingModule.html#+-fffffab9","e":false,"i":"T","n":"+","t":"+(rhs: T): T","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/RingModule.html#--fffffab9","e":false,"i":"T","n":"-","t":"-(rhs: T): T","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/RingModule.html#<*-fffffab9","e":false,"i":"T","n":"<*","t":"<*(rhs: R): T","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/RingModule.html#minus-fffffab9","e":false,"i":"","n":"minus","t":"minus(x: T, y: T): T","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/RingModule.html#negate-d26","e":false,"i":"","n":"negate","t":"negate(x: T): T","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/RingModule.html#plus-fffffab9","e":false,"i":"","n":"plus","t":"plus(x: T, y: T): T","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/RingModule.html#scale-fffffab9","e":false,"i":"","n":"scale","t":"scale(x: R, y: T): T","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/RingModule.html#unary_--d26","e":false,"i":"T","n":"unary_-","t":"unary_-: T","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/RingModule.html#zero-0","e":false,"i":"","n":"zero","t":"zero: T","d":"org.appliedtopology.tda4j.RingModule","k":"val"},
{"l":"org/appliedtopology/tda4j/RingModule$.html#","e":false,"i":"","n":"RingModule","t":"RingModule","d":"org.appliedtopology.tda4j","k":"object"},
{"l":"org/appliedtopology/tda4j/RingModule$.html#apply-fffff350","e":false,"i":"","n":"apply","t":"apply[T, R](using rm: RingModule[T, R]): RingModule[T, R]","d":"org.appliedtopology.tda4j.RingModule","k":"def"},
{"l":"org/appliedtopology/tda4j/SimplexContext.html#","e":false,"i":"","n":"SimplexContext","t":"SimplexContext[VertexT]","d":"org.appliedtopology.tda4j","k":"trait"},
{"l":"org/appliedtopology/tda4j/SimplexContext.html#Simplex-0","e":false,"i":"","n":"Simplex","t":"Simplex = AbstractSimplex[VertexT]","d":"org.appliedtopology.tda4j.SimplexContext","k":"type"},
{"l":"org/appliedtopology/tda4j/SimplexContext.html#className-fffffb36","e":false,"i":"Simplex","n":"className","t":"className: String","d":"org.appliedtopology.tda4j.SimplexContext","k":"def"},
{"l":"org/appliedtopology/tda4j/SimplexContext.html#given_Ordering_Simplex-0","e":false,"i":"","n":"given_Ordering_Simplex","t":"given_Ordering_Simplex: Ordering[Simplex]","d":"org.appliedtopology.tda4j.SimplexContext","k":"given"},
{"l":"org/appliedtopology/tda4j/SimplexContext$Simplex$.html#","e":false,"i":"","n":"Simplex","t":"Simplex","d":"org.appliedtopology.tda4j.SimplexContext","k":"object"},
{"l":"org/appliedtopology/tda4j/SimplexContext$Simplex$.html#apply-fffff906","e":false,"i":"","n":"apply","t":"apply(vertices: VertexT*): Simplex","d":"org.appliedtopology.tda4j.SimplexContext.Simplex","k":"def"},
{"l":"org/appliedtopology/tda4j/SimplexContext$Simplex$.html#empty-0","e":false,"i":"","n":"empty","t":"empty: Simplex","d":"org.appliedtopology.tda4j.SimplexContext.Simplex","k":"def"},
{"l":"org/appliedtopology/tda4j/SimplexContext$Simplex$.html#from-fffff656","e":false,"i":"","n":"from","t":"from(iterableOnce: IterableOnce[VertexT]): Simplex","d":"org.appliedtopology.tda4j.SimplexContext.Simplex","k":"def"},
{"l":"org/appliedtopology/tda4j/SimplexContext$Simplex$.html#newBuilder-0","e":false,"i":"","n":"newBuilder","t":"newBuilder: Builder[VertexT, Simplex]","d":"org.appliedtopology.tda4j.SimplexContext.Simplex","k":"def"},
{"l":"org/appliedtopology/tda4j/SimplexContext$s$.html#","e":false,"i":"","n":"s","t":"s","d":"org.appliedtopology.tda4j.SimplexContext","k":"object"},
{"l":"org/appliedtopology/tda4j/SimplexContext$s$.html#apply-fffff906","e":false,"i":"","n":"apply","t":"apply(vertices: VertexT*): Simplex","d":"org.appliedtopology.tda4j.SimplexContext.s","k":"def"},
{"l":"org/appliedtopology/tda4j/SimplexStream.html#","e":false,"i":"","n":"SimplexStream","t":"SimplexStream[VertexT, FiltrationT] extends Filtration[VertexT, FiltrationT] with IterableOnce[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j","k":"trait"},
{"l":"org/appliedtopology/tda4j/SymmetricZomorodianIncremental.html#","e":false,"i":"","n":"SymmetricZomorodianIncremental","t":"SymmetricZomorodianIncremental[VertexT, KeyT](val symmetry: SymmetryGroup[KeyT, VertexT])(implicit evidence$3: Ordering[VertexT]) extends CliqueFinder[VertexT]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/SymmetricZomorodianIncremental.html#className-0","e":false,"i":"","n":"className","t":"className: String","d":"org.appliedtopology.tda4j.SymmetricZomorodianIncremental","k":"val"},
{"l":"org/appliedtopology/tda4j/SymmetricZomorodianIncremental.html#symmetry-0","e":false,"i":"","n":"symmetry","t":"symmetry: SymmetryGroup[KeyT, VertexT]","d":"org.appliedtopology.tda4j.SymmetricZomorodianIncremental","k":"val"},
{"l":"org/appliedtopology/tda4j/SymmetryGroup.html#","e":false,"i":"","n":"SymmetryGroup","t":"SymmetryGroup[KeyT, VertexT]()(implicit evidence$1: Ordering[VertexT])","d":"org.appliedtopology.tda4j","k":"trait"},
{"l":"org/appliedtopology/tda4j/SymmetryGroup.html#apply-29e","e":false,"i":"","n":"apply","t":"apply(groupElementKey: KeyT): VertexT => VertexT","d":"org.appliedtopology.tda4j.SymmetryGroup","k":"def"},
{"l":"org/appliedtopology/tda4j/SymmetryGroup.html#isRepresentative-fffffc2f","e":false,"i":"","n":"isRepresentative","t":"isRepresentative(simplex: AbstractSimplex[VertexT]): Boolean","d":"org.appliedtopology.tda4j.SymmetryGroup","k":"def"},
{"l":"org/appliedtopology/tda4j/SymmetryGroup.html#keys-0","e":false,"i":"","n":"keys","t":"keys: Iterable[KeyT]","d":"org.appliedtopology.tda4j.SymmetryGroup","k":"def"},
{"l":"org/appliedtopology/tda4j/SymmetryGroup.html#orbit-d3f","e":false,"i":"","n":"orbit","t":"orbit(simplex: AbstractSimplex[VertexT]): Set[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.SymmetryGroup","k":"def"},
{"l":"org/appliedtopology/tda4j/SymmetryGroup.html#representative-ffffff20","e":false,"i":"","n":"representative","t":"representative(simplex: AbstractSimplex[VertexT]): AbstractSimplex[VertexT]","d":"org.appliedtopology.tda4j.SymmetryGroup","k":"def"},
{"l":"org/appliedtopology/tda4j/TDAContext.html#","e":false,"i":"","n":"TDAContext","t":"TDAContext[VertexT, CoefficientT] extends ChainOps[AbstractSimplex[VertexT], CoefficientT] with SimplexContext[VertexT]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/TDAContext.html#given_Conversion_Simplex_Chain-0","e":false,"i":"","n":"given_Conversion_Simplex_Chain","t":"given_Conversion_Simplex_Chain: Conversion[Simplex, Chain[Simplex, CoefficientT]]","d":"org.appliedtopology.tda4j.TDAContext","k":"given"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#","e":false,"i":"","n":"VietorisRips","t":"VietorisRips[VertexT](using ordering: Ordering[VertexT])(val metricSpace: FiniteMetricSpace[VertexT], val maxFiltrationValue: Double, val maxDimension: Int, val cliqueFinder: CliqueFinder[VertexT]) extends SimplexStream[VertexT, Double]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#cliqueFinder-0","e":false,"i":"","n":"cliqueFinder","t":"cliqueFinder: CliqueFinder[VertexT]","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#filtrationValue-0","e":false,"i":"","n":"filtrationValue","t":"filtrationValue: PartialFunction[AbstractSimplex[VertexT], Double]","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#iterator-0","e":false,"i":"","n":"iterator","t":"iterator: Iterator[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.VietorisRips","k":"def"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#maxDimension-0","e":false,"i":"","n":"maxDimension","t":"maxDimension: Int","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#maxFiltrationValue-0","e":false,"i":"","n":"maxFiltrationValue","t":"maxFiltrationValue: Double","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#metricSpace-0","e":false,"i":"","n":"metricSpace","t":"metricSpace: FiniteMetricSpace[VertexT]","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#simplices-0","e":false,"i":"","n":"simplices","t":"simplices: Seq[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/ZomorodianIncremental.html#","e":false,"i":"","n":"ZomorodianIncremental","t":"ZomorodianIncremental[VertexT] extends CliqueFinder[VertexT]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/ZomorodianIncremental.html#className-0","e":false,"i":"","n":"className","t":"className: String","d":"org.appliedtopology.tda4j.ZomorodianIncremental","k":"val"}];