package org.appliedtopology.tda4j

import space.kscience.kmath.operations.Field
import kotlin.math.round
import kotlin.math.abs

class FiniteField(val p : UInt) :  Field<UInt> {

    override val zero: UInt = 0u
    override val one: UInt = 1u

    // but doesn't make sense when working with Uint
    override fun UInt.unaryMinus(): UInt {
        return (p-this)
    }

    //Also needs to be defined to implement field interface,
    //but I really don't understand the purpose
    override fun scale(a: UInt, value: Double):UInt {
        return p-((abs(round(a.toDouble()*value)).toUInt())%p)
    }
    override fun add(a: UInt, b: UInt): UInt = (a+b+p) % p
    override fun multiply(a: UInt, b: UInt): UInt = ((a.toULong() * b.toULong()) % p.toULong()).toUInt()

    //Size of UIntArray must be cast as int...
    val inverseTable: Map<UInt,UInt> = (0u until p-1u).associate{ it to inverse(it)}

    override fun divide(a: UInt, b: UInt): UInt {
        //require(b != 0u){ "Cannot divide by zero." }
        //This is ugly bc I don't think inverseTable[norm(b)]
        // should ever return a zero so long as we have the require statement above(?)
        return multiply(a, inverseTable[norm(b)]?:0u)
    }

    fun norm(a: UInt): UInt = a % p

    private fun inverse(a: UInt): UInt {
        return 1u
    }

    //Don't yet get how this works, but it allows for ff17.algebra
    fun algebra(function: () -> Unit) {

    }
}