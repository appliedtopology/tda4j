package org.appliedtopology.tda4j

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.property.checkAll
import space.kscience.kmath.operations.algebra
import kotlin.jvm.JvmInline
import kotlin.math.roundToInt

@JvmInline value class IntModP(val value: Int)

interface FiniteFieldContext

inline operator fun <C : FiniteFieldContext, R> C.invoke(block: (C) -> R): R = block(this)

interface FieldOps<X> {
    val one: X
    val zero: X

    fun plus(
        left: X,
        right: X,
    ): X

    fun minus(
        left: X,
        right: X,
    ): X

    fun times(
        left: X,
        right: X,
    ): X

    fun div(
        left: X,
        right: X,
    ): X

    fun compare(
        left: X,
        right: X,
    ): Int

    fun number(x: Int): X

    fun number(x: Double): X

    fun exportToInt(x: X): Int
}

class FieldC<X>(val delegate: FieldOps<X>) : FiniteFieldContext {
    val one: X = delegate.one
    val zero: X = delegate.zero

    inline operator fun X.plus(other: X) = delegate.plus(this@plus, other)

    inline operator fun X.minus(other: X) = delegate.minus(this@minus, other)

    inline operator fun X.times(other: X) = delegate.times(this@times, other)

    inline operator fun X.div(other: X) = delegate.div(this@div, other)

    inline fun X.compareTo(other: X): Int = delegate.compare(this@compareTo, other)

    inline fun number(x: Double): X = delegate.number(x)

    inline fun number(x: Int): X = delegate.number(x)

    inline fun X.toInt(): Int = delegate.exportToInt(this)
}

class FiniteField(val p: Int) : FieldOps<IntModP> {
    override val one = IntModP(1)
    override val zero = IntModP(0)

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

    override fun exportToInt(self: IntModP): Int = canonical(self.value)

    override fun plus(
        left: IntModP,
        right: IntModP,
    ): IntModP = IntModP((left.value + right.value) % p)

    override fun minus(
        left: IntModP,
        right: IntModP,
    ): IntModP = IntModP((left.value - right.value) % p)

    override fun times(
        left: IntModP,
        right: IntModP,
    ): IntModP = IntModP((left.value * right.value) % p)

    override fun div(
        left: IntModP,
        right: IntModP,
    ): IntModP = IntModP((left.value / right.value) % p) // this is wrong

    override fun compare(
        left: IntModP,
        right: IntModP,
    ): Int = exportToInt(left).compareTo(exportToInt(right))

    override fun number(x: Int): IntModP = IntModP(canonical(x))

    override fun number(x: Double): IntModP = number(x.roundToInt())
}

val FiniteField.algebra
    get() = FieldC(this)

// I'm not at all sure this is how to write a function for this
fun <T, U : FieldC<T>> U.dosomething(
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
                (3 * one).toString().shouldBeEqual("3.0")
                (-3 * one).toString().shouldBeEqual("-3.0")
                (14 * one).toString().shouldBeEqual("14.0")
            }
        }
    }

    "Computing with finite fields" {
        val ff17 = FiniteField(17)
        with(ff17.algebra) {
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
                number(3).toString().shouldBeEqual("IntModP(value=3)")
                number(-3).toString().shouldBeEqual("IntModP(value=-3)")
                number(14).toString().shouldBeEqual("IntModP(value=-3)")
            }
        }
    }
})
