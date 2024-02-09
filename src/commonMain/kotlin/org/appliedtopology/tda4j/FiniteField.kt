package org.appliedtopology.tda4j

import space.kscience.kmath.operations.Field

class FiniteField(val p : UInt) :  Field<UInt> {

    override val zero: UInt = 0u
    override val one: UInt = 1u

    //Unary minus must be defined to implement field interface,
    // but doesn't make sense in context of UInt
    override fun UInt.unaryMinus(): UInt {
        return (this)
    }

    //Also needs to be defined to implement field interface,
    // but I really don't understand the purpose
    override fun scale(a: UInt, value: Double):UInt {
        return (a)
    }
    override fun add(a: UInt, b: UInt): UInt = (a + b) % p
    override fun multiply(a: UInt, b: UInt): UInt = (a * b) % p

    val inverseTable = UIntArray((p-1u).toInt()) {
        if (it == 0)
            0u
        else
            inverse(it.toUInt())
    }

    // TODO:  Use inverse table for divide instead of calling inverse function
    override fun divide(a: UInt, b: UInt): UInt {
        return multiply(a, inverse(norm(b)))
    }

    private val p2: UInt = (p - 1u) / 2u

    fun norm(a: UInt): UInt {
        val r: UInt = a % p
        return when {
            r < -p2 -> r + p
            r > p2 -> r - p
            else -> r
        }
    }

    fun inverse(a: UInt): UInt {

        var u = a % p
        var v  = p
        var x1 = 1u
        var x2 = 0u
        var q = 0u
        var r = 0u
        var x = 0u
        while (u != 1u) {
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

    //Don't yet get how this works, but it allows for ff17.algebra
    fun algebra(function: () -> Unit) {

    }

}