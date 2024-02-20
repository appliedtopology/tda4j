package org.appliedtopology.tda4j

import kotlin.jvm.JvmInline

@JvmInline
value class Fp(val x: Int) {
    override fun toString(): String {
        return "Fp($x)"
    }
}

open class FiniteFieldContext(val p: Int) : FieldContext<Fp> {
    override val zero = Fp(0)
    override val one = Fp(1)

    override fun number(value: Number): Fp = Fp(value.toInt() % p)

    // but doesn't make sense when working with Uint
    override fun Fp.unaryMinus(): Fp {
        return Fp(p - this.x)
    }

    override fun add(
        left: Fp,
        right: Fp,
    ): Fp = Fp((left.x + right.x) % p)

    override fun multiply(
        left: Fp,
        right: Fp,
    ): Fp = Fp((left.x.toLong() * right.x.toLong() % p.toLong()).toInt())

    fun inverse(a: Int): Int {
        var u = a % p
        var v = p
        var x1 = 1
        var x2 = 0
        var q = 0
        var r = 0
        var x = 0
        while (u != 1) {
            q = v.floorDiv(u)
            r = v - q * u
            x = x2 - q * x1
            v = u
            u = r
            x2 = x1
            x1 = x
        }
        return (x1 % p)
    }

    val inverseTable: Map<Fp, Fp> = (0 until p - 1).associate { Fp(it) to Fp(inverse(it)) }

    override fun divide(
        left: Fp,
        right: Fp,
    ): Fp {
        require((right.x % p) != 0) { "Cannot divide by zero." }
        // This is ugly bc I don't think inverseTable[norm(b)]
        // should ever return a zero so long as we have the require statement above(?)
        return multiply(left, inverseTable[Fp(norm(right))] ?: zero)
    }

    fun norm(a: Fp): Int =
        if (a.x % p < 0) {
            (a.x % p) + p
        } else {
            a.x % p
        }

    fun canonical(a: Fp): Int = norm(a) - (p - 1) / 2
}
