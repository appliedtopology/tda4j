package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual

class ChainSpec : StringSpec({
    "Chain shoudl be able to take just simplex as argument" {
        with(ChainContext<Int, Double>(DoubleContext)) {
            val chain1 = Chain(simplexOf(1, 2, 3))
            val chain2 = Chain(simplexOf(1, 2, 3) to 1.0, simplexOf(4, 5, 6) to 0.0)

            chain1 shouldBeEqual chain2
        }
    }

    "Chain supports negation" {
        with(ChainContext<Int, Double>(DoubleContext)) {
            val chain1 = Chain(simplexOf(2, 3) to 2.0, simplexOf(1, 2) to 1.0)
            val chain2 = Chain(simplexOf(2, 3) to -2.0, simplexOf(1, 2) to -1.0)

            -chain1 shouldBeEqual chain2
        }
    }

    "Chain supports addition" {
        with(ChainContext<Int, Double>(DoubleContext)) {
            val chain1 = Chain(simplexOf(1, 2) to 1.0, simplexOf(2, 3) to 1.0)
            val chain2 = Chain(simplexOf(2, 3) to 1.0, simplexOf(3, 4) to 1.0)
            val chain3 = Chain(simplexOf(1, 2) to 1.0, simplexOf(2, 3) to 2.0, simplexOf(3, 4) to 1.0)

            chain1 + chain2 shouldBeEqual chain3
        }
    }

    "Chain supports subtraction" {
        with(ChainContext<Int, Double>(DoubleContext)) {
            val chain1 = Chain(simplexOf(2, 3) to 2.0, simplexOf(1, 2) to 1.0)
            val chain2 = Chain(simplexOf(2, 3) to 2.0, simplexOf(1, 2) to 1.0)

            chain1 - chain2 shouldBeEqual Chain(simplexOf(1, 2, 3) to 0.0)
        }
    }

    "Chain supports single coefficient updates" {
        with(ChainContext<Int, Double>(DoubleContext)) {
            val chain1 = Chain(simplexOf(2, 3) to 2.0, simplexOf(1, 2) to 1.0)
            val chain2 = Chain(simplexOf(2, 3) to 3.0, simplexOf(1, 2) to 1.0)

            // chain1.updateCoefficient(simplexOf(2, 3), 3.0)
            chain1[simplexOf(2, 3)] = 3.0

            chain1 shouldBeEqual chain2
        }
    }

    "Chain supports coefficient retrieval" {
        with(ChainContext<Int, Double>(DoubleContext)) {
            val chain1 = Chain(simplexOf(2, 3) to 1.0, simplexOf(1, 2) to 2.0)

            val singleQuery = chain1[simplexOf(2, 3)]
            val singleQueryNull = chain1[simplexOf(3, 4)]
            // val severalQuery = chain1[listOf(simplexOf(2, 3), simplexOf(1, 2))]

            singleQuery.shouldBeEqual(1.0)

            singleQueryNull.shouldBeEqual(0.0)

            // severalQuery shouldBeEqual mapOf(simplexOf(2, 3) to 1, simplexOf(1, 2) to 2)
        }
    }
})
