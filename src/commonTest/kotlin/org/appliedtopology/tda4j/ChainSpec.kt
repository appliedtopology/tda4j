package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

class ChainSpec : StringSpec(

    {

            val z1 = Chain(simplexOf(1, 2, 3) to 1)
            val z2 = Chain(simplexOf(1, 2) to 1.0, simplexOf(1, 3) to -1.0, simplexOf(2, 3) to 1.0)
            val z3 = Chain(simplexOf(1, 2, 5) to 1)
            val z4 = Chain(simplexOf(1, 4, 8) to 1)
            val z5 = Chain(simplexOf(1, 2) to - 1.0, simplexOf(1, 4) to 1.0, simplexOf(2, 3) to -1.0)
            val z6 = Chain(simplexOf(1, 2) to 1.0, simplexOf(2 , 3) to 1.0, simplexOf(1 , 3) to -1.0)
            val z7 = Chain(simplexOf(1, 2) to 1.0, simplexOf(1, 3) to -1.0, simplexOf(2, 3) to 1.0, simplexOf(3,4) to 0.0)

            "Return type of Chain applied to a single simplex" {
                val z1 = Chain(simplexOf(1, 2, 3) to 1.0)
                z1.shouldBeInstanceOf<Chain<Simplex, Double>>()
            }

            "Return type of Chain applied to several simplex/coefficient pairs" {
                val z2 = Chain(simplexOf(1, 2) to 1.0, simplexOf(1, 3) to -1.0, simplexOf(2, 3) to 1.0)
                z2.shouldBeInstanceOf<Chain<Simplex, Double>>()
            }


            "Maintain equality when its component simplex-coefficient pairs are permuted" {
                val z2 = Chain(simplexOf(1, 2) to 1.0, simplexOf(1, 3) to -1.0, simplexOf(2, 3) to 1.0)
                val z6 = Chain(simplexOf(2, 3) to 1.0, simplexOf(1, 3) to -1.0, simplexOf(1, 2) to 1.0,)
                z2 shouldBe z6
            }

            "Maintain equality to itself after the addition of simplices with 0-valued coefficient" {
                val z2 = Chain(simplexOf(1, 2) to 1.0, simplexOf(1, 3) to -1.0, simplexOf(2, 3) to 1.0)
                val z7 = Chain(simplexOf(1, 2) to 1.0, simplexOf(1, 3) to -1.0, simplexOf(2, 3) to 1.0, simplexOf(4, 5) to 0.0)
                z2 shouldBe z7
            }

            "Correctly perform scalar multiplication" {
                val chain = Chain(simplexOf(1, 2, 3) to 1)
                val expectedResult = Chain(simplexOf(1, 2, 3) to 2)
                val result = chain * 2

                result shouldBe expectedResult
            }

            "Correctly perform addition" {
                val chain1 = Chain(simplexOf(1, 2, 3) to 1.0)
                val chain2 = Chain(simplexOf(4, 5, 6) to 1.0)
                val expectedResult = Chain(simplexOf(1, 2, 3) to 1.0, simplexOf(4, 5, 6) to 1.0)
                val result = chain1 + chain2

                result shouldBe expectedResult
            }

            "Correctly perform unary minus" {
                val chain = Chain(simplexOf(1, 2, 3) to 1)
                val expectedResult = Chain(simplexOf(1, 2, 3) to -1)
                val result = -chain

                result shouldBe expectedResult
            }

            "Correctly perform subtraction" {
                val chain1 = Chain(simplexOf(1, 2, 3) to 1)
                val chain2 = Chain(simplexOf(4, 5, 6) to 1)
                val expectedResult = Chain(simplexOf(1, 2, 3) to 1, simplexOf(4, 5, 6) to -1)
                val result = chain1 - chain2

                result shouldBe expectedResult
            }

        "getCoefficients should return the correct coefficients for given simplices" {
            val simplex1 = abstractSimplexOf(1, 2, 3)
            val simplex2 = abstractSimplexOf(4, 5, 6)
            val simplex3 = abstractSimplexOf(7, 8, 9)

            val chain = Chain(simplex1 to 2, simplex2 to 3, simplex3 to 4)

            val coefficients = chain.getCoefficients(setOf(simplex1, simplex3))

            coefficients.size shouldBe 2
            coefficients[simplex1] shouldBe 2
            coefficients[simplex3] shouldBe 4
        }

        "updateCoefficient should update the coefficient for the given simplex" {
            val simplex1 = abstractSimplexOf(1, 2, 3)
            val simplex2 = abstractSimplexOf(4, 5, 6)

            val chain = Chain(simplex1 to 2, simplex2 to 3)

            chain.updateCoefficient(simplex1, 5)

            chain.getCoefficients(setOf(simplex1))[simplex1] shouldBe 5
        }


})
