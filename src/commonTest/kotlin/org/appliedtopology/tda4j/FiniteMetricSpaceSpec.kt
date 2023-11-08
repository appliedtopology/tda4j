package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import space.kscience.kmath.nd.ShapeND
import space.kscience.kmath.structures.DoubleBuffer
import space.kscience.kmath.tensors.core.*

fun PointCloudArb(
    dimensions: Collection<Int> = (2..10).toList(),
    sizes: Collection<Int> = listOf(5, 10, 25, 50, 100, 500),
): Arb<DoubleTensor2D> =
    arbitrary {
        val dim = Arb.element(dimensions).bind()
        val size = Arb.element(sizes).bind()
        val doubles =
            Arb.doubleArray(
                Arb.of(listOf(dim * size)),
                Arb.double(-100.0, 100.0),
            ).bind()
        Double.tensorAlgebra.withBroadcast {
            fromArray(ShapeND(size, dim), doubles).asDoubleTensor2D()
        }
    }

class FiniteMetricSpaceSpec : StringSpec({
    val msX: FiniteMetricSpace<Int> =
        ExplicitMetricSpace<Int>(
            mapOf(Pair(0, 0) to 0.0, Pair(0, 1) to 1.0, Pair(1, 0) to 1.0, Pair(1, 1) to 0.0),
        )
    val msE: FiniteMetricSpace<Int> =
        EuclideanMetricSpace(
            DoubleTensor2D(2, 2, OffsetDoubleBuffer(DoubleBuffer(0.0, 1.0, 1.0, 0.0), 0, 4)),
        )

    "An explicit metric space is non-empty" {
        (msX.size) shouldBeGreaterThan 0
    }
    "A euclidean metric space is non-empty" {
        (msE.size) shouldBeGreaterThan 0
    }

    "An explicit metric space has all points it was given" {
        msX.elements.shouldContainExactly(0, 1)
    }
    "A euclidean metric space has all points it was given" {
        msE.elements.shouldContainExactly(0, 1)
    }

    "Generate random Euclidean metric space" {
        checkAll(PointCloudArb()) {
            it.colNum shouldBeGreaterThan 0
            it.rowNum shouldBeGreaterThan 0
            collect("${it.colNum} columns")
            collect("${it.rowNum} rows")
            Double.tensorAlgebra.withBroadcast {
                val diff = it.rowsByIndices(intArrayOf(1)) - it.rowsByIndices(intArrayOf(2))
                collect(kotlin.math.floor(kotlin.math.sqrt(diff.dot(diff.transposed())[intArrayOf(0, 0)]) / 10.0))
            }
        }
    }
})
