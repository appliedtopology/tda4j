package org.appliedtopology.tda4j

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.property.checkAll
import space.kscience.kmath.operations.Field
import space.kscience.kmath.operations.algebra
import kotlin.jvm.JvmInline
import kotlin.math.roundToInt

@JvmInline value class Fp(val value: Int)

class FiniteField(val p: Int) : Field<Fp> {
    override fun divide(
        left: Fp,
        right: Fp,
    ): Fp {
        TODO("Not yet implemented")
    }

    override val one: Fp
        get() = Fp(1)
    override val zero: Fp
        get() = Fp(0)

    override fun scale(
        a: Fp,
        value: Double,
    ): Fp = Fp((a.value.toDouble() * value).roundToInt())

    override fun multiply(
        left: Fp,
        right: Fp,
    ): Fp = Fp((left.value * right.value) % p)

    override fun Fp.unaryMinus(): Fp = Fp(p - (value % p))

    override fun add(
        left: Fp,
        right: Fp,
    ): Fp = Fp((left.value + right.value) % p)

    fun canonical(x: Int): Int {
        var value = x % p
        if (value < 0) {
            value += p
        }
        if (value > (p - 1) / 2) {
            return (-p) + value % p
        } else {
            return value % p
        }
    }

    fun exportToInt(self: Fp): Int = canonical(self.value)

    fun Fp.toInt(): Int = exportToInt(this@toInt)

    override fun number(value: Number): Fp {
        return super.number(canonical(value.toInt()))
    }
}

// I'm not at all sure this is how to write a function for this
fun <T, U : Field<T>> U.dosomething(
    a: T,
    b: T,
): T = a - b

class FieldArithmeticSpec : StringSpec({
    "Computing with doubles" {
        with(Double.algebra) {
            withClue("Should be commutative") {
                checkAll<Pair<Double, Double>> {
                    val a = number(it.first)
                    val b = number(it.second)
                    (a * b).shouldBeEqual(b * a)
                    (a + b).shouldBeEqual(b + a)
                }
            }

            withClue("5+13") {
                (number(5) + number(13)).shouldBeEqual(number(18))
            }

            withClue("5-13") {
                (number(5) - number(13)).shouldBeEqual(number(-8))
            }

//            withClue("calling a function") {
//                dosomething(number(5), number(13)).shouldBeEqual(number(-8))
//            }

            withClue("Converting to a string should give the normalized range") {
                number(3).toString().shouldBeEqual("3.0")
                number(-3).toString().shouldBeEqual("-3.0")
                number(14).toString().shouldBeEqual("14.0")
            }
        }
    }

    "Computing with finite fields" {
        val ff17 = FiniteField(17)
        with(ff17) {
            withClue("Should be commutative") {
                checkAll<Pair<Int, Int>> {
                    val a = number(it.first)
                    val b = number(it.second)
                    (a * b).shouldBeEqual(b * a)
                    (a + b).shouldBeEqual(b + a)
                }
            }

            withClue("5+13") {
                val a = number(5)
                val b = number(13)
                val x = a + b
                x + x
                (number(5) + number(13)).shouldBeEqual(one)
            }

            withClue("5-13") {
                (number(5) - number(13)).toInt().shouldBeEqual(number(9).toInt())
            }

            withClue("calling a function") {
                dosomething(number(5), number(13)).toInt().shouldBeEqual(number(9).toInt())
            }

            withClue("Converting to a string should give the normalized range") {
                number(3).toString().shouldBeEqual("Fp(value=3)")
                number(-3).toString().shouldBeEqual("Fp(value=-3)")
                number(14).toString().shouldBeEqual("Fp(value=-3)")
            }
        }
    }
})
