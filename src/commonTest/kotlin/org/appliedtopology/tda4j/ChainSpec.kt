package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual

class ChainSpec : StringSpec( {

    "Chain shoudl be able to take just simplex as argument"{
        val chain1 = chainOf(simplexOf(1,2,3))
        val chain2 = chainOf(simplexOf(1,2,3) to 1, simplexOf(4,5,6) to 0)

        chain1 shouldBeEqual chain2
    }

    "Chain supports negation"{
        val chain1 = chainOf(simplexOf(2,3) to 2, simplexOf(1,2) to 1)
        val chain2 = chainOf(simplexOf(2,3) to -2, simplexOf(1,2) to -1)

        -chain1 shouldBeEqual chain2
    }

    "Chain supports addition"{
        val chain1 = chainOf(simplexOf(1,2) to 1, simplexOf(2,3) to 1)
        val chain2 = chainOf(simplexOf(2,3) to 1, simplexOf(3,4) to 1)
        val chain3 = chainOf(simplexOf(1,2) to 1, simplexOf(2,3) to 2, simplexOf(3,4) to 1)

        chain1+chain2 shouldBeEqual chain3
    }

    "Chain supports subtraction"{

        val chain1 = chainOf(simplexOf(2,3) to 2, simplexOf(1,2) to 1)
        val chain2 = chainOf(simplexOf(2,3) to 2, simplexOf(1,2) to 1)

        chain1-chain2 shouldBeEqual chainOf(simplexOf(1,2,3) to 0)
    }

    "Chain supports single coefficient updates"{

        val chain1 = chainOf(simplexOf(2,3) to 2, simplexOf(1,2) to 1)
        val chain2 = chainOf(simplexOf(2,3) to 3, simplexOf(1,2) to 1)

        chain1.updateCoefficient(simplexOf(2,3),3)

        chain1 shouldBeEqual chain2
    }

    "Chain supports coefficient retrieval"{

        val chain1 = chainOf(simplexOf(2,3) to 1, simplexOf(1,2) to 2)

        val singleQuery = chain1.getCoefficients(simplexOf(2,3))
        val singleQueryNull = chain1.getCoefficients(simplexOf(3,4))
        val severalQuery = chain1.getCoefficients(listOf(simplexOf(2,3), simplexOf(1,2)))

        singleQuery shouldBeEqual 1

        singleQueryNull shouldBeEqual 0

        severalQuery shouldBeEqual mapOf(simplexOf(2,3) to 1, simplexOf(1,2) to 2)
    }

})
