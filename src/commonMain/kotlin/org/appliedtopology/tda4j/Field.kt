package org.appliedtopology.tda4j

import kotlin.math.abs
import kotlin.math.ulp

public interface Equality<T> {
    public infix fun T.eq(other: Any?): Boolean
}

public interface FieldContext<CoefficientT> : Equality<CoefficientT> {
    public val zero: CoefficientT
    public val one: CoefficientT

    public fun number(x: Number): CoefficientT

    public fun add(
        left: CoefficientT,
        right: CoefficientT,
    ): CoefficientT

    public fun subtract(
        left: CoefficientT,
        right: CoefficientT,
    ): CoefficientT = add(left, negate(right))

    public fun negate(x: CoefficientT): CoefficientT = subtract(zero, x)

    public fun multiply(
        left: CoefficientT,
        right: CoefficientT,
    ): CoefficientT

    public fun divide(
        left: CoefficientT,
        right: CoefficientT,
    ): CoefficientT = multiply(left, inverse(right))

    public fun inverse(x: CoefficientT): CoefficientT = divide(one, x)

    // Operator overloads
    public operator fun CoefficientT.unaryPlus(): CoefficientT = this@unaryPlus

    public operator fun CoefficientT.unaryMinus(): CoefficientT = negate(this@unaryMinus)

    public operator fun CoefficientT.plus(other: CoefficientT): CoefficientT = add(this@plus, other)

    public operator fun CoefficientT.minus(other: CoefficientT): CoefficientT = subtract(this@minus, other)

    public operator fun CoefficientT.times(other: CoefficientT): CoefficientT = multiply(this@times, other)

    public operator fun CoefficientT.div(other: CoefficientT): CoefficientT = divide(this@div, other)
}

public object DoubleContext : FieldContext<Double> {
    override fun divide(
        left: Double,
        right: Double,
    ): Double = left / right

    override val one: Double
        get() = 1.0
    override val zero: Double
        get() = 0.0

    override fun Double.unaryMinus(): Double = this@unaryMinus.unaryMinus()

    override fun multiply(
        left: Double,
        right: Double,
    ): Double = left * right

    override fun number(value: Number): Double = value.toDouble()

    override fun add(
        left: Double,
        right: Double,
    ): Double = left + right

    override fun Double.eq(other: Any?): Boolean =
        if (other is Number) {
            abs(this@eq - other.toDouble()) < this@eq.ulp
        } else {
            false
        }
}

/**
 * A context interface for simultaneously defining a `Field` of one type (`FieldT`) and a
 * `Group`of another type (`GroupT`) connected by scalar multiplication.
 *
 * The contract of this interface expects associativity, commutativity and distributivity
 * of all operations.
 *
 * To avoid ambiguities, the additive unit of the group part is named `origin`, with inspiration
 * from the geometry of vector spaces.
 *
 * A minimal implementation will override `origin`, `add`, `scale` and one of `negate` or `subtract`.
 */
public interface VectorSpaceContext<FieldT, VectorT> : FieldContext<FieldT> {
    // A module has addition, subtraction, unit, scalar multiplication

    public val origin: VectorT

    public fun vectorAdd(
        left: VectorT,
        right: VectorT,
    ): VectorT

    public fun vectorNegate(v: VectorT): VectorT = vectorSubtract(origin, v)

    public fun vectorSubtract(
        left: VectorT,
        right: VectorT,
    ): VectorT = vectorAdd(left, vectorNegate(right))

    public fun vectorScale(
        scalar: FieldT,
        vector: VectorT,
    ): VectorT

    // Type extension operators to enable all these within a context block
    public operator fun VectorT.unaryPlus(): VectorT = this@unaryPlus

    public operator fun VectorT.unaryMinus(): VectorT = vectorNegate(this@unaryMinus)

    public operator fun VectorT.plus(other: VectorT): VectorT = vectorAdd(this@plus, other)

    public operator fun VectorT.minus(other: VectorT): VectorT = vectorSubtract(this@minus, other)

    public operator fun FieldT.times(other: VectorT): VectorT = vectorScale(this@times, other)

    public operator fun VectorT.times(other: FieldT): VectorT = vectorScale(other, this@times)
}
