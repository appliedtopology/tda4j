package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe

class FiniteFieldSpec : StringSpec({

    "numbers mod p should all have inverse" {
        val ff17 = FiniteField(17u)
        ff17.algebra {
            2u/20u shouldBeEqual 1u
            }
        }

    "Subtraction works" {
        val ff17 = FiniteField(17u)
        ff17.algebra {
            val x = 15u
            val y = 10u

            y-x shouldBeEqual 9u
        }
    }

    "Comparison should be right"{

        val ff17 = FiniteField(17u)

        ff17.algebra  {
            var a = 4
            var b = 20

            (b < a) shouldBe true

        }

    }

    "be commutative" {
        val ff17 = FiniteField(17u)
        ff17.algebra  {

                val x = (-100..100).random().toUInt()
                val y = (-100..100).random().toUInt()

                x * y shouldBeEqual  y * x
                x + y shouldBeEqual y + x

            }
    }

        "associativity" {
            val ff17 = FiniteField(17u)
            ff17.algebra {
                val x = (-100..100).random().toUInt()
                val y = (-100..100).random().toUInt()
                val z = (-100..100).random().toUInt()

                ((x * y) * z) shouldBeEqual (x * (y * z))
                ((x + y) + z) shouldBeEqual (x + (y + z))
            }
        }
        "distributivity" {
            val ff17 = FiniteField(17u)
            ff17.algebra {

                val x = (-100..100).random().toUInt()
                val y = (-100..100).random().toUInt()
                val z = (-100..100).random().toUInt()


                x * (y + z) shouldBeEqual x * y + x * z
            }
        }
        "units" {
            val ff17 = FiniteField(17u)
            ff17.algebra {

                val x: UInt = (-100..100).random().toUInt()

                x - x shouldBeEqual 0u
                x * (1u / x) shouldBeEqual 1u
            }
        }

    })