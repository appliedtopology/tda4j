pages = [{"l":"index.html#","e":false,"i":"","n":"TDA4j","t":"TDA4j","d":"","k":"static"},
{"l":"org/appliedtopology/tda4j.html#","e":false,"i":"","n":"org.appliedtopology.tda4j","t":"org.appliedtopology.tda4j","d":"","k":"package"},
{"l":"org/appliedtopology/tda4j.html#Simplex-0","e":false,"i":"","n":"Simplex","t":"Simplex = AbstractSimplex[Int]","d":"org.appliedtopology.tda4j","k":"type"},
{"l":"org/appliedtopology/tda4j/AbstractSimplex.html#","e":false,"i":"","n":"AbstractSimplex","t":"AbstractSimplex[VertexT](val vertexSet: SortedSet[VertexT])(using val ordering: Ordering[VertexT]) extends SortedSet[VertexT] with SortedSetOps[VertexT, AbstractSimplex, AbstractSimplex[VertexT]] with SortedSetFactoryDefaults[VertexT, AbstractSimplex, Set] with Cell","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/AbstractSimplex.html#ordering-0","e":false,"i":"","n":"ordering","t":"ordering: Ordering[VertexT]","d":"org.appliedtopology.tda4j.AbstractSimplex","k":"given"},
{"l":"org/appliedtopology/tda4j/AbstractSimplex$.html#","e":false,"i":"","n":"AbstractSimplex","t":"AbstractSimplex extends SortedIterableFactory[AbstractSimplex]","d":"org.appliedtopology.tda4j","k":"object"},
{"l":"org/appliedtopology/tda4j/BronKerbosch.html#","e":false,"i":"","n":"BronKerbosch","t":"BronKerbosch[VertexT] extends CliqueFinder[VertexT]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/BronKerbosch.html#apply-c5","e":false,"i":"","n":"apply","t":"apply(metricSpace: FiniteMetricSpace[VertexT], maxFiltrationValue: Double, maxDimension: Int): Seq[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.BronKerbosch","k":"def"},
{"l":"org/appliedtopology/tda4j/BronKerbosch.html#className-0","e":false,"i":"","n":"className","t":"className: String","d":"org.appliedtopology.tda4j.BronKerbosch","k":"val"},
{"l":"org/appliedtopology/tda4j/Cell.html#","e":false,"i":"","n":"Cell","t":"Cell","d":"org.appliedtopology.tda4j","k":"trait"},
{"l":"org/appliedtopology/tda4j/Cell.html#boundary-fffffa2b","e":false,"i":"","n":"boundary","t":"boundary(): Seq[Cell]","d":"org.appliedtopology.tda4j.Cell","k":"def"},
{"l":"org/appliedtopology/tda4j/CliqueFinder.html#","e":false,"i":"","n":"CliqueFinder","t":"CliqueFinder[VertexT] extends (FiniteMetricSpace[VertexT], Double, Int) => IterableOnce[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/CliqueFinder.html#className-0","e":false,"i":"","n":"className","t":"className: String","d":"org.appliedtopology.tda4j.CliqueFinder","k":"val"},
{"l":"org/appliedtopology/tda4j/CliqueFinder$.html#","e":false,"i":"","n":"CliqueFinder","t":"CliqueFinder","d":"org.appliedtopology.tda4j","k":"object"},
{"l":"org/appliedtopology/tda4j/CliqueFinder$.html#weightedEdges-4","e":false,"i":"","n":"weightedEdges","t":"weightedEdges[VertexT : Ordering](metricSpace: FiniteMetricSpace[VertexT], maxFiltrationValue: Double): Graph[VertexT, WUnDiEdge]","d":"org.appliedtopology.tda4j.CliqueFinder","k":"def"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#","e":false,"i":"","n":"EuclideanMetricSpace","t":"EuclideanMetricSpace(val pts: Seq[Seq[Double]]) extends FiniteMetricSpace[Int]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#distance-579","e":false,"i":"","n":"distance","t":"distance(x: Int, y: Int): Double","d":"org.appliedtopology.tda4j.EuclideanMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#elements-0","e":false,"i":"","n":"elements","t":"elements: Iterable[Int]","d":"org.appliedtopology.tda4j.EuclideanMetricSpace","k":"def"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#pts-0","e":false,"i":"","n":"pts","t":"pts: Seq[Seq[Double]]","d":"org.appliedtopology.tda4j.EuclideanMetricSpace","k":"val"},
{"l":"org/appliedtopology/tda4j/EuclideanMetricSpace.html#size-0","e":false,"i":"","n":"size","t":"size: Int","d":"org.appliedtopology.tda4j.EuclideanMetricSpace","k":"def"},
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
{"l":"org/appliedtopology/tda4j/Simplex$.html#","e":false,"i":"","n":"Simplex","t":"Simplex","d":"org.appliedtopology.tda4j","k":"object"},
{"l":"org/appliedtopology/tda4j/Simplex$.html#apply-fffff906","e":false,"i":"","n":"apply","t":"apply(vertices: Int*): AbstractSimplex[Int]","d":"org.appliedtopology.tda4j.Simplex","k":"def"},
{"l":"org/appliedtopology/tda4j/SimplexStream.html#","e":false,"i":"","n":"SimplexStream","t":"SimplexStream[VertexT, FiltrationT] extends Filtration[VertexT, FiltrationT] with IterableOnce[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j","k":"trait"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#","e":false,"i":"","n":"VietorisRips","t":"VietorisRips[VertexT](using ordering: Ordering[VertexT])(val metricSpace: FiniteMetricSpace[VertexT], val maxFiltrationValue: Double, val maxDimension: Int, val cliqueFinder: CliqueFinder[VertexT]) extends SimplexStream[VertexT, Double]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#cliqueFinder-0","e":false,"i":"","n":"cliqueFinder","t":"cliqueFinder: CliqueFinder[VertexT]","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#filtrationValue-0","e":false,"i":"","n":"filtrationValue","t":"filtrationValue: PartialFunction[AbstractSimplex[VertexT], Double]","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#iterator-0","e":false,"i":"","n":"iterator","t":"iterator: Iterator[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.VietorisRips","k":"def"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#maxDimension-0","e":false,"i":"","n":"maxDimension","t":"maxDimension: Int","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#maxFiltrationValue-0","e":false,"i":"","n":"maxFiltrationValue","t":"maxFiltrationValue: Double","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#metricSpace-0","e":false,"i":"","n":"metricSpace","t":"metricSpace: FiniteMetricSpace[VertexT]","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/VietorisRips.html#simplices-0","e":false,"i":"","n":"simplices","t":"simplices: IterableOnce[AbstractSimplex[VertexT]]","d":"org.appliedtopology.tda4j.VietorisRips","k":"val"},
{"l":"org/appliedtopology/tda4j/ZomorodianIncremental.html#","e":false,"i":"","n":"ZomorodianIncremental","t":"ZomorodianIncremental[VertexT] extends CliqueFinder[VertexT]","d":"org.appliedtopology.tda4j","k":"class"},
{"l":"org/appliedtopology/tda4j/ZomorodianIncremental.html#className-0","e":false,"i":"","n":"className","t":"className: String","d":"org.appliedtopology.tda4j.ZomorodianIncremental","k":"val"}];