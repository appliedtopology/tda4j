package org.appliedtopology.tda4j

import space.kscience.kmath.linear.Matrix
import space.kscience.kmath.linear.asMatrix
import space.kscience.kmath.linear.linearSpace
import space.kscience.kmath.linear.transpose
import space.kscience.kmath.nd.StructureND
import space.kscience.kmath.nd.as2D
import space.kscience.kmath.operations.algebra
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.pow

interface FiniteMetricSpace<VertexT> {
    fun distance(
        x: VertexT,
        y: VertexT,
    ): Double

    val size: Int

    val elements: Iterable<VertexT>

    fun contains(x: VertexT): Boolean

    companion object {
        fun <VertexT : Comparable<VertexT>> maximumDistanceFiltrationValue(
            metricSpace: FiniteMetricSpace<VertexT>,
        ): Filtered<VertexT, Double> =
            Filtered { simplex: AbstractSimplex<VertexT> ->
                (
                    if (simplex.size <= 1) {
                        0.0
                    } else {
                        simplex.vertices.flatMap { v ->
                            simplex.vertices.filter { it > v }.map { w -> metricSpace.distance(v, w) }
                        }.maxOf { it }
                    }
                )
            }
    }
}

class ExplicitMetricSpace<VertexT>(val distances: Map<Pair<VertexT, VertexT>, Double>) : FiniteMetricSpace<VertexT> {
    override val elements: Set<VertexT> = distances.keys.map { it.first }.toSet()

    override fun distance(
        x: VertexT,
        y: VertexT,
    ): Double = distances[Pair(x, y)] ?: Double.POSITIVE_INFINITY

    override val size: Int = elements.size

    override fun contains(x: VertexT): Boolean = elements.contains(x)
}

class EuclideanMetricSpace(val points: List<DoubleArray>) : FiniteMetricSpace<Int> {
    val pointsND: Matrix<Double> =
        with(Double.algebra.linearSpace) {
            StructureND.auto(points.size, points[0].size) { (i, j) ->
                points[i][j]
            }
        }.as2D()

    override fun distance(
        x: Int,
        y: Int,
    ): Double {
        @Suppress("ktlint:standard:property-naming")
        with(Double.algebra.linearSpace) {
            val x_y: Matrix<Double> = (pointsND.rows[x] - pointsND.rows[y]).asMatrix()
            return x_y.dot(x_y.transpose())[0, 0].pow(0.5)
        }
    }

    override val size: Int = pointsND.shape[0]

    override val elements: Iterable<Int>
        get() = IntRange(0, size - 1)

    override fun contains(x: Int): Boolean = (0 <= x) and (x < size)
}

class HyperCube(val dimension: Int) : FiniteMetricSpace<Int> {
    val top: Int = (1 shl dimension) - 1

    override fun distance(
        x: Int,
        y: Int,
    ): Double = (x xor y).countOneBits().toDouble()

    override val size: Int = (1 shl dimension)

    override val elements: Iterable<Int> = 0..top

    override fun contains(x: Int): Boolean = (x and top) == x
}
