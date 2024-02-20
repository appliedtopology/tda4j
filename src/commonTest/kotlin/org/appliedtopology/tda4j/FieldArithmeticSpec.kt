package org.appliedtopology.tda4j

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.property.checkAll
import space.kscience.kmath.operations.BigIntField
import space.kscience.kmath.operations.DoubleField
import space.kscience.kmath.operations.Field

fun FiniteField(p: Int) = BigIntField // placeholder stub waiting for an implementation to test

// I'm not at all sure this is how to write a function for this
fun <T, U : Field<T>> dosomething(
    a: T,
    b: T,
    field: U,
): T =
    with(field) {
        a - b
    }

class FieldArithmeticSpec : StringSpec({
    "Computing with doubles" {
        with(DoubleField) {
            withClue("Should be commutative") {
                checkAll<Pair<Double, Double>> {
                    val a = number(it.first)
                    val b = number(it.second)
                    (a * b).shouldBeEqual(b * a)
                    (a + b).shouldBeEqual(b + a)
                }
            }

            withClue("5+13") {
                (5 * one + 13 * one).shouldBeEqual(18 * one)
            }

            withClue("5-13") {
                (5 * one - 13 * one).shouldBeEqual(-8 * one)
            }

            withClue("calling a function") {
                dosomething(5 * one, 13 * one, this).shouldBeEqual(-8 * one)
            }

            withClue("Converting to a string should give the normalized range") {
                (3 * one).toString().shouldBeEqual("3.0")
                (-3 * one).toString().shouldBeEqual("-3.0")
                (14 * one).toString().shouldBeEqual("14.0")
            }
        }
    }

    "Computing with finite fields" {
        with(FiniteField(17)) {
            withClue("Should be commutative") {
                checkAll<Pair<Int, Int>> {
                    val a = number(it.first)
                    val b = number(it.second)
                    (a * b).shouldBeEqual(b * a)
                    (a + b).shouldBeEqual(b + a)
                }
            }

            withClue("5+13") {
                (5 * one + 17 * one).shouldBeEqual(1 * one)
            }

            withClue("5-13") {
                (5 * one - 17 * one).shouldBeEqual(9 * one)
            }

            withClue("calling a function") {
                dosomething(5 * one, 13 * one, this).shouldBeEqual(9 * one)
            }

            withClue("Converting to a string should give the normalized range") {
                (3 * one).toString().shouldBeEqual("3u")
                (-3 * one).toString().shouldBeEqual("-3u")
                (14 * one).toString().shouldBeEqual("-3u")
            }
        }
    }
})
