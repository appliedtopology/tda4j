package org.appliedtopology.tda4j

import kotlin.math.pow

interface FiniteMetricSpace<VertexT> {
    abstract fun distance(x: VertexT, y: VertexT): Double?

    abstract val size: Int

    abstract val elements: Iterable<VertexT>

    abstract fun contains(x: VertexT): Boolean

    companion object {
        fun <VertexT : Comparable<VertexT>> MaximumDistanceFiltrationValue(metricSpace: FiniteMetricSpace<VertexT>):
            Filtered<VertexT, Double> =
            Filtered { simplex: AbstractSimplex<VertexT> ->
                (
                    if (simplex.size <= 1) {
                        0.0
                    } else {
                        simplex.vertices.flatMap({ v ->
                            simplex.vertices.filter({ it > v }).map({ w -> metricSpace.distance(v, w) })
                        }).maxOf { it ?: Double.POSITIVE_INFINITY }
                    }
                    )
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

class HyperCube(val dimension: Int) : FiniteMetricSpace<Int> {
    val top: Int = (1 shl dimension) - 1

    override fun distance(x: Int, y: Int): Double? = (x xor y).countOneBits().toDouble()

    override val size: Int = (1 shl dimension)

    override val elements: Iterable<Int> = 0..top

    override fun contains(x: Int): Boolean = (x and top) == x
}
