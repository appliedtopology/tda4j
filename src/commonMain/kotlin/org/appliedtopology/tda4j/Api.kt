@file:JvmName("Api")

package org.appliedtopology.tda4j

import kotlin.jvm.JvmName
import kotlin.math.abs
import kotlin.math.ulp

object DoubleContext : FieldContext<Double> {
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

object Api {
    fun <VertexT : Comparable<VertexT>, CoefficientT, R> tdaWith(
        fieldContext: FieldContext<CoefficientT>,
        chainContext: ChainContext<VertexT, CoefficientT>,
        block: ChainContext<VertexT, CoefficientT>.() -> R,
    ): R {
        with(fieldContext) {
            return chainContext.block()
        }
    }
}
