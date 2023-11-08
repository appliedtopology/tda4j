package org.appliedtopology.tda4j

fun interface CliqueFinder<VertexT : Comparable<VertexT>> {
    abstract fun cliques(
        metricSpace: FiniteMetricSpace<VertexT>,
        maxFiltrationValue: Double,
        maxDimension: Int,
    ): Sequence<AbstractSimplex<VertexT>>

    fun weightedEdges(
        metricSpace: FiniteMetricSpace<VertexT>,
        maxFiltrationValue: Double,
    ): Sequence<Pair<Double?, Pair<VertexT, VertexT>>> {
        return metricSpace.elements.flatMap({ v ->
            metricSpace.elements.filter({ v < it })
                .map({ w -> Pair(metricSpace.distance(v, w), Pair(v, w)) })
                .filter({ (d, _) -> (d != null) && (d <= maxFiltrationValue) })
        }).asSequence()
    }
}

open class VietorisRips<VertexT : Comparable<VertexT>>(
    val metricSpace: FiniteMetricSpace<VertexT>,
    val maxFiltrationValue: Double,
    val maxDimension: Int,
    val cliqueFinder: CliqueFinder<VertexT>,
) :
    SimplexStream<VertexT, Double>(),
        Filtered<VertexT, Double> by FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace) {
    val simplices: Sequence<AbstractSimplex<VertexT>> =
        cliqueFinder.cliques(metricSpace, maxFiltrationValue, maxDimension)

    override fun iterator(): Iterator<AbstractSimplex<VertexT>> = simplices.iterator()

    override val comparator: Comparator<AbstractSimplex<VertexT>>
        get() = getComparator(this)

    companion object {
        fun <VertexT : Comparable<VertexT>> getComparator(filtered: Filtered<VertexT, Double>): Comparator<AbstractSimplex<VertexT>> =
            compareBy<AbstractSimplex<VertexT>> { filtered.filtrationValue(it) }
                .thenComparator { x: AbstractSimplex<VertexT>, y: AbstractSimplex<VertexT> -> AbstractSimplex.compare(x, y) }
    }
}

class ZomorodianIncremental<VertexT : Comparable<VertexT>> : CliqueFinder<VertexT> {
    override fun cliques(
        metricSpace: FiniteMetricSpace<VertexT>,
        maxFiltrationValue: Double,
        maxDimension: Int,
    ): Sequence<AbstractSimplex<VertexT>> {
        val edges: List<Pair<Double?, Pair<VertexT, VertexT>>> =
            weightedEdges(metricSpace, maxFiltrationValue).sortedBy { it.first ?: Double.POSITIVE_INFINITY }.toList()
        val lowerNeighbors =
            buildMap {
                edges.forEach {
                        dvw ->
                    (
                        getOrPut(
                            dvw.second.second,
                            defaultValue = { -> hashSetOf<VertexT>() },
                        ) as MutableSet<VertexT>
                    ).add(dvw.second.first)
                }
            }

        val V: MutableSet<AbstractSimplex<VertexT>> = HashSet(metricSpace.size + edges.size)

        val tasks: ArrayDeque<Pair<AbstractSimplex<VertexT>, Set<VertexT>>> = ArrayDeque(edges.size)
        metricSpace.elements.forEach { vertex ->
            tasks.addFirst(
                Pair(
                    abstractSimplexOf(vertex),
                    lowerNeighbors.getOrElse(vertex, { -> emptySet() }),
                ),
            )
        }

        while (tasks.size > 0) {
            val task: Pair<AbstractSimplex<VertexT>, Set<VertexT>> = tasks.removeFirst()
            val tau: AbstractSimplex<VertexT> = task.first
            val N: Set<VertexT> = task.second
            V.add(tau)
            if (tau.size < maxDimension) {
                N.forEach { v: VertexT ->
                    run {
                        val sigma: AbstractSimplex<VertexT> = tau.plus(v)
                        val M: Set<VertexT> =
                            N.intersect(lowerNeighbors.get(v)?.asIterable() ?: emptySequence<VertexT>().asIterable())
                        tasks.addFirst(Pair(sigma, M))
                    }
                }
            }
        }

        val filtered: Filtered<VertexT, Double> = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
        return V.sortedWith(VietorisRips.getComparator(filtered)).asSequence()
    }
}