package org.appliedtopology.tda4j

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan

class FiniteMetricSpaceSpec : FunSpec({
    val msX: FiniteMetricSpace<Int> = ExplicitMetricSpace<Int>(
        mapOf(Pair(0, 0) to 0.0, Pair(0, 1) to 1.0, Pair(1, 0) to 1.0, Pair(1, 1) to 0.0)
    )
    val msE: FiniteMetricSpace<Int> = EuclideanMetricSpace(
        arrayOf(arrayOf(0.0, 0.0), arrayOf(0.0, 1.0), arrayOf(1.0, 0.0), arrayOf(1.0, 1.0))
    )

    test("An explicit metric space is non-empty") {
        (msX.size) shouldBeGreaterThan 0
    }
    test("A euclidean metric space is non-empty") {
        (msE.size) shouldBeGreaterThan 0
    }

    test("An explicit metric space has all points it was given") {
        msX.elements.shouldContainExactly(0,1)
    }
    test("A euclidean metric space has all points it was given") {
        msE.elements.shouldContainExactly(0, 1, 2, 3)
    }
})
