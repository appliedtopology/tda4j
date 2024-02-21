package org.appliedtopology.tda4j

import space.kscience.kmath.operations.FieldOps
import space.kscience.kmath.operations.NumericAlgebra
import space.kscience.kmath.operations.Ring
import kotlin.math.abs
import kotlin.math.ulp

public interface Equality<T> {
    public infix fun T.eq(other: Any?): Boolean
}

public interface FieldContext<CoefficientT> :
    Ring<CoefficientT>, FieldOps<CoefficientT>, NumericAlgebra<CoefficientT>, Equality<CoefficientT>

public object DoubleContext : FieldContext<Double> {
    override fun divide(
        left: Double,
        right: Double,
    ): Double = left / right

    override val one: Double
        get() = 1.0
    override val zero: Double
        get() = 0.0

    override fun multiply(
        left: Double,
        right: Double,
    ): Double = left * right

    override fun number(value: Number): Double = value.toDouble()

    override fun Double.unaryMinus(): Double = -this

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
