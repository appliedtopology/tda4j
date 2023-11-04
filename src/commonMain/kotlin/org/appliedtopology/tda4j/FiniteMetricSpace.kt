package org.appliedtopology.tda4j

import kotlin.math.pow

interface FiniteMetricSpace<VertexT> {
    abstract fun distance(x: VertexT, y: VertexT): Double?

    abstract val size: Int

    abstract val elements: Iterable<VertexT>

    abstract fun contains(x: VertexT): Boolean

    companion object {
        fun <VertexT : Comparable<VertexT>, Double : Comparable<Double>>
        MaximumDistanceFiltrationValue(metricSpace: FiniteMetricSpace<VertexT>):
            Filtered<VertexT, Double> = Filtered { simplex: AbstractSimplex<VertexT> ->
            (
                if (simplex.size <= 1) {
                    0.0
                } else {
                    var distances = listOf(1.0, 2.0, 0.0)
                    var dist = simplex.vertices.flatMap({ v ->
                        simplex.vertices.filter({ it > v }).map({ w -> metricSpace.distance(v, w) })
                    })
                    distances.max()
                }
                ) as Double?
        }
    }
}

class ExplicitMetricSpace<VertexT>(val distances: Map<Pair<VertexT, VertexT>, Double>) : FiniteMetricSpace<VertexT> {
    override val elements: Set<VertexT> = distances.keys.map({ it.first }).toSet()

    override fun distance(x: VertexT, y: VertexT): Double? = distances[Pair(x, y)]

    override val size: Int = elements.size

    override fun contains(x: VertexT): Boolean = elements.contains(x)
}

class EuclideanMetricSpace(val points: Array<Array<Double>>) : FiniteMetricSpace<Int> {
    override fun distance(x: Int, y: Int): Double? =
        points.get(x)?.zip(points.get(y))?.map({
                xyi: Pair<Double, Double> ->
            (xyi.first - xyi.second).pow(2)
        })?.sum()?.pow(0.5)

    override val size: Int = points.size

    override val elements: Iterable<Int>
        get() = IntRange(0, size - 1)

    override fun contains(x: Int): Boolean = (0 <= x) and (x < size)
}
