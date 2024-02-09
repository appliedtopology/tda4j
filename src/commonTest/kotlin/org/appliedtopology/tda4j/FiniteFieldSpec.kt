package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
class FiniteFieldSpec : StringSpec({

    "Numbers mod p should all have inverse" {

        // not right lol

        val ff17 = FiniteField(17u)

        ff17.algebra {

            for (x in 1..16){

                x/x shouldBe 1
            }

        }
    }

    "Comparison should be right"{

        val ff17 = FiniteField(17u)

        ff17.algebra {

            var a = 4
            var b = 20

            (a>b) shouldBe true

        }

    }

        "be commutative" {
            val ff17 = FiniteField(17u)
            ff17.algebra {

                val x = (-100..100).random()
                val y = (-100..100).random()
                val z = (-100..100).random()

                x * y shouldBe y * x
                x + y shouldBe y + x
                x - y shouldBe -(y - x)

            }
        }

        "be associative" {
            val ff17 = FiniteField(17u)
            ff17.algebra {

                val x = (-100..100).random()
                val y = (-100..100).random()
                val z = (-100..100).random()

                x * y === y * x
                x + y === y + x
                x - y === -(y - x)

            }

        }

        "associativity"{
            val ff17 = FiniteField(17u)

            ff17.algebra {
                val x = (-100..100).random().toUInt()
                val y = (-100..100).random().toUInt()
                val z = (-100..100).random().toUInt()

                ((x * y) * z) shouldBe (x * (y * z))
                ((x + y) + z) shouldBe (x + (y + z))
            }
        }
        "distributivity" {
            val ff17 = FiniteField(17u)
            ff17.algebra {

                val x = (-100..100).random().toUInt()
                val y = (-100..100).random().toUInt()
                val z = (-100..100).random().toUInt()

                x * (y + z) shouldBe x * y + x * z

            }
        }
        "units" {
            val ff17 = FiniteField(17u)
            ff17.algebra {

                val x: UInt = (-100..100).random().toUInt()
                val y = (-100..100).random().toUInt()
                val z = (-100..100).random().toUInt()

                x - x shouldBe 0
                x * (1u / x) shouldBe 1
            }
        }

})