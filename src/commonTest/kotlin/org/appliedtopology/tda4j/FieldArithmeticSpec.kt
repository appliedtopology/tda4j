package org.appliedtopology.tda4j

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.property.assume
import io.kotest.property.checkAll

// I'm not at all sure this is how to write a function for this
fun <T, U : FieldContext<T>> U.dosomething(
    a: T,
    b: T,
): T = a - b

class FieldArithmeticSpec : StringSpec({
    "Computing with doubles" {
        with(DoubleContext) {
            withClue("Should be commutative") {
                checkAll<Pair<Float, Float>> {
                    assume(it.first.isFinite())
                    assume(it.second.isFinite())
                    val a = number(it.first)
                    val b = number(it.second)
                    ((a * b) eq (b * a)).shouldBeTrue()
                    ((a + b) eq (b + a)).shouldBeTrue()
                }
            }

            withClue("5+13") {
                ((number(5) + number(13)) eq (number(18))).shouldBeTrue()
            }

            withClue("5-13") {
                ((number(5) - number(13)) eq (number(-8))).shouldBeTrue()
            }

            withClue("calling a function") {
                (dosomething(number(5), number(13)) eq (number(-8))).shouldBeTrue()
            }
        }
    }

    "Computing with finite fields" {
        val ff17 = FiniteFieldContext(17)
        with(ff17) {
            withClue("Should be commutative") {
                checkAll<Pair<Short, Short>> {
                    val a = number(it.first)
                    val b = number(it.second)
                    ((a * b) eq (b * a)).shouldBeTrue()
                    ((a + b) eq (b + a)).shouldBeTrue()
                }
            }

            withClue("5+13") {
                val a = number(5)
                val b = number(13)
                val x = a + b
                x + x
                ((number(5) + number(13)) eq (one)).shouldBeTrue()
            }

            withClue("5-13") {
                ((number(5) - number(13)) eq (number(9))).shouldBeTrue()
            }

            withClue("calling a function") {
                (dosomething(number(5), number(13)) eq (number(9))).shouldBeTrue()
            }
        }
    }
})
