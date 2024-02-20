package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll

val FiniteFieldArb: Arb<Fp> =
    arbitrary {
        Fp(it.random.nextInt())
    }

class FiniteFieldSpec : StringSpec({

    "numbers mod p should all have inverse" {
        val ff17 = FiniteFieldContext(17)
        with(ff17) {
            Fp(2) / Fp(20) shouldBeEqual Fp(1)
        }
    }

    "Subtraction works" {
        val ff17 = FiniteFieldContext(17)
        with(ff17) {
            val x = Fp(15)
            val y = Fp(10)

            y - x shouldBeEqual Fp(9)
        }
    }

    "xComparison should be right" {

        val ff17 = FiniteFieldContext(17)

        with(ff17) {
            var a = Fp(4)
            var b = Fp(20)

            // (b < a) shouldBe true // SHOULD finite field have natural orders?
        }
    }

    "be commutative" {
        val ff17 = FiniteFieldContext(17)
        with(ff17) {
            checkAll<Pair<Fp, Fp>> { (x, y) ->
                x * y shouldBeEqual y * x
                x + y shouldBeEqual y + x
            }
        }
    }

    "associativity" {
        val ff17 = FiniteFieldContext(17)
        with(ff17) {
            checkAll<Triple<Fp, Fp, Fp>> { (x, y, z) ->
                ((x * y) * z) shouldBeEqual (x * (y * z))
                ((x + y) + z) shouldBeEqual (x + (y + z))
            }
        }
    }

    "distributivity" {
        val ff17 = FiniteFieldContext(17)
        with(ff17) {
            checkAll<Triple<Fp, Fp, Fp>> { (x, y, z) ->
                x * (y + z) shouldBeEqual x * y + x * z
            }
        }
    }

    "units" {
        val ff17 = FiniteFieldContext(17)
        with(ff17) {
            checkAll<Fp> { x ->
                x - x shouldBeEqual zero
                x * (one / x) shouldBeEqual one
            }
        }
    }
})
