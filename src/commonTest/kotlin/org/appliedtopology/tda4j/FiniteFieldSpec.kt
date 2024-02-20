package org.appliedtopology.tda4j

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.should
import io.kotest.matchers.string.startWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll

public val FiniteFieldContext.FiniteFieldArb: Arb<Fp>
    get() =
        arbitrary {
            this@FiniteFieldArb.fp(it.random.nextInt())
        }

public class FiniteFieldSpec : StringSpec({
    val ff17 = FiniteFieldContext(17)

    "the finite field construction interface should do modulo directly" {
        with(ff17) {
            checkAll<Int> {
                fp(it).toInt() shouldBeLessThanOrEqual 8
                fp(it).toInt() shouldBeGreaterThanOrEqual -8
                fp(it).x shouldBeLessThan 17
            }
        }
    }

    "numbers mod p should all have inverse" {
        with(ff17) {
            (fp(2) * fp(9) eq fp(1)).shouldBeTrue()
        }
    }

    "Subtraction works" {
        with(ff17) {
            val x = fp(15)
            val y = fp(10)

            ((y - x) eq fp(12)).shouldBeTrue()
        }
    }

    "xComparison should be right" {
        with(ff17) {
            var a = fp(4)
            var b = fp(20)

            // (b < a) shouldBe true // SHOULD finite field have natural orders?
        }
    }

    "be commutative" {
        with(ff17) {
            checkAll(ff17.FiniteFieldArb, ff17.FiniteFieldArb) { a, b ->
                ((a * b) eq (b * a)).shouldBeTrue()
                ((a + b) eq (b + a)).shouldBeTrue()
            }
        }
    }
    /**
     * 0) fp(1941174536)
     * 1) fp(406594172)
     * 2) fp(11244580)
     *
     */
    "associativity" {
        with(ff17) {
            checkAll(ff17.FiniteFieldArb, ff17.FiniteFieldArb, ff17.FiniteFieldArb) { x, y, z ->
                (((x * y) * z) eq (x * (y * z))).shouldBeTrue()
                (((x + y) + z) eq (x + (y + z))).shouldBeTrue()
            }
        }
    }

    "distributivity" {
        with(ff17) {
            checkAll(ff17.FiniteFieldArb, ff17.FiniteFieldArb, ff17.FiniteFieldArb) { x, y, z ->
                ((x * (y + z)) eq (x * y + x * z)).shouldBeTrue()
            }
        }
    }

    "units" {
        with(ff17) {
            checkAll(ff17.FiniteFieldArb) { x ->
                ((x - x) eq zero).shouldBeTrue()
                if (x eq zero) {
                    val exception =
                        shouldThrow<IllegalArgumentException> {
                            ((x * (one / x)) eq one)
                        }
                    exception.message should startWith("Cannot divide by zero.")
                } else {
                    ((x * (one / x)) eq one).shouldBeTrue()
                }
            }
        }
    }
})
