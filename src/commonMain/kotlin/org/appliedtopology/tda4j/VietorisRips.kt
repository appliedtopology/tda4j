package org.appliedtopology.tda4j

abstract class VietorisRips<VertexT : Comparable<VertexT>>(
    val metricSpace: FiniteMetricSpace<VertexT>,
    val maxFiltrationValue: Double,
    val maxDimension: Int,
) : SimplexStream<VertexT, Double>(),
    Filtered<VertexT, Double> by FiniteMetricSpace.maximumDistanceFiltrationValue(metricSpace) {
    abstract fun cliques(): Sequence<AbstractSimplex<VertexT>>

    private var simplexCache: Sequence<AbstractSimplex<VertexT>>? = null

    open val simplices: Sequence<AbstractSimplex<VertexT>>
        get() {
            simplexCache = simplexCache ?: cliques()
            return simplexCache ?: emptySequence()
        }

    open fun simplicesByDimension(d: Int): Sequence<AbstractSimplex<VertexT>> = simplices.filter { simplex -> simplex.dimension == d }

    fun weightedEdges(
        metricSpace: FiniteMetricSpace<VertexT>,
        maxFiltrationValue: Double,
    ): Sequence<Pair<Double?, Pair<VertexT, VertexT>>> {
        return metricSpace.elements.flatMap { v ->
            metricSpace.elements.filter { v < it }
                .map { w -> Pair(metricSpace.distance(v, w), Pair(v, w)) }
                .filter { (d, _) -> (d != null) && (d <= maxFiltrationValue) }
        }.asSequence()
    }

    override fun iterator(): Iterator<AbstractSimplex<VertexT>> = simplices.iterator()

    override val comparator: Comparator<AbstractSimplex<VertexT>>
        get() = getComparator(this)

    companion object {
        fun <VertexT : Comparable<VertexT>> getComparator(filtered: Filtered<VertexT, Double>): Comparator<AbstractSimplex<VertexT>> =
            compareBy<AbstractSimplex<VertexT>> { filtered.filtrationValue(it) }
                .thenBy { it.size }
                .thenComparator { x: AbstractSimplex<VertexT>, y: AbstractSimplex<VertexT> -> AbstractSimplex.compare(x, y) }
    }
}

class ZomorodianIncremental<VertexT : Comparable<VertexT>>(
    metricSpace: FiniteMetricSpace<VertexT>,
    maxFiltrationValue: Double,
    maxDimension: Int,
) : VietorisRips<VertexT>(metricSpace, maxFiltrationValue, maxDimension) {
    override fun cliques(): Sequence<AbstractSimplex<VertexT>> {
        val edges: List<Pair<Double?, Pair<VertexT, VertexT>>> =
            weightedEdges(metricSpace, maxFiltrationValue).sortedBy { it.first ?: Double.POSITIVE_INFINITY }.toList()
        val lowerNeighbors =
            buildMap {
                edges.forEach {
                        dvw ->
                    (
                        getOrPut(
                            dvw.second.second,
                            defaultValue = { hashSetOf<VertexT>() },
                        ) as MutableSet<VertexT>
                    ).add(dvw.second.first)
                }
            }

        val returnValues: MutableSet<AbstractSimplex<VertexT>> = HashSet(metricSpace.size + edges.size)

        val tasks: ArrayDeque<Pair<AbstractSimplex<VertexT>, Set<VertexT>>> = ArrayDeque(edges.size)
        metricSpace.elements.forEach { vertex ->
            tasks.addFirst(
                Pair(
                    abstractSimplexOf(vertex),
                    lowerNeighbors.getOrElse(vertex) { emptySet() },
                ),
            )
        }

        while (tasks.size > 0) {
            val task: Pair<AbstractSimplex<VertexT>, Set<VertexT>> = tasks.removeFirst()
            val tau: AbstractSimplex<VertexT> = task.first
            val lowerNeighborSet: Set<VertexT> = task.second
            returnValues.add(tau)
            if (tau.size < maxDimension) {
                lowerNeighborSet.forEach { v: VertexT ->
                    run {
                        val sigma: AbstractSimplex<VertexT> = tau.plus(v)
                        val lowerNeighborIntersection: Set<VertexT> =
                            lowerNeighborSet.intersect(lowerNeighbors[v]?.asIterable() ?: emptySequence<VertexT>().asIterable())
                        tasks.addFirst(Pair(sigma, lowerNeighborIntersection))
                    }
                }
            }
        }

        val filtered: Filtered<VertexT, Double> = FiniteMetricSpace.maximumDistanceFiltrationValue(metricSpace)
        return returnValues.sortedWith(getComparator(filtered)).asSequence()
    }
}

// default choice
fun <VertexT : Comparable<VertexT>> vietorisRips(
    metricSpace: FiniteMetricSpace<VertexT>,
    maxFiltrationValue: Double,
    maxDimension: Int,
) = ZomorodianIncremental(metricSpace, maxFiltrationValue, maxDimension)
