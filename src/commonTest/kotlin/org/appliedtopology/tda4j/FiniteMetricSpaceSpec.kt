package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll

fun pointCloudArb(
    dimensions: Collection<Int> = (2..10).toList(),
    sizes: Collection<Int> = listOf(5, 10, 25, 50, 100, 500),
): Arb<List<DoubleArray>> =
    arbitrary {
        val dim = Arb.element(dimensions).bind()
        val size = Arb.element(sizes).bind()
        val doubles =
            (1..size).map {
                Arb.doubleArray(
                    Arb.of(listOf(dim * size)),
                    Arb.double(-100.0, 100.0),
                ).bind()
            }
        doubles
    }

class FiniteMetricSpaceSpec : StringSpec({
    val msX: FiniteMetricSpace<Int> =
        ExplicitMetricSpace<Int>(
            mapOf(Pair(0, 0) to 0.0, Pair(0, 1) to 1.0, Pair(1, 0) to 1.0, Pair(1, 1) to 0.0),
        )
    val msE: FiniteMetricSpace<Int> =
        EuclideanMetricSpace(listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0)))

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
        checkAll(100, pointCloudArb()) {
            val space = EuclideanMetricSpace(it)
            space.pointsND

            space.pointsND.colNum shouldBeGreaterThan 0
            space.pointsND.rowNum shouldBeGreaterThan 0
            collect("${space.pointsND.colNum} columns")
            collect("${space.pointsND.rowNum} rows")
            collect(space.distance(1, 2))
        }
    }
})
