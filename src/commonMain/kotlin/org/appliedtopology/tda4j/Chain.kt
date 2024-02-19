package org.appliedtopology.tda4j

open class Chain<VertexT : Comparable<VertexT>, CoefficientT : Number>{

    val chainMap: ArrayMutableSortedMap<AbstractSimplex<VertexT>, CoefficientT> = ArrayMutableSortedMap()

    companion object {
        // Static factory method chainOf
        fun <VertexT : Comparable<VertexT>, CoefficientT : Number> apply(vararg simplexCoefficients: Pair<AbstractSimplex<VertexT>, CoefficientT>): Chain<VertexT, CoefficientT> {
            val chain = Chain<VertexT, CoefficientT>()
            simplexCoefficients.filter { (_, coefficient) -> coefficient!= 0 as CoefficientT }.forEach { (simplex, coefficient) ->
                chain.chainMap[simplex] = coefficient
            }
            return chain
        }
        fun <VertexT : Comparable<VertexT>> apply(vararg simplex: AbstractSimplex<VertexT>): Chain<VertexT, Int> {
            val chain = Chain<VertexT, Int>()
            simplex.forEach { simplex ->
                chain.chainMap[simplex] = 1
            }
            return chain
        }

    }

    operator fun unaryMinus(): Chain<VertexT, CoefficientT>{
        chainMap.replaceAllValues {v -> negateCoefficient(v)}
        return this
    }

    operator fun plus(other: Chain<VertexT, CoefficientT>): Chain<VertexT, CoefficientT> {

        val result = Chain<VertexT, CoefficientT>()
        val commonKeys = this.chainMap.keys.union(other.chainMap.keys)
        for (key in commonKeys) {

            result.chainMap[key] = addNumbers(this.chainMap[key]?:0 as CoefficientT,other.chainMap[key]?:0 as CoefficientT)

        }
        return result
    }
    operator fun minus(other: Chain<VertexT, CoefficientT>): Chain<VertexT, CoefficientT> {

        val result = Chain<VertexT, CoefficientT>()
        val commonKeys = this.chainMap.keys.union(other.chainMap.keys)
        for (key in commonKeys) {

            result.chainMap[key] = addNumbers(this.chainMap[key]?:0 as CoefficientT,negateCoefficient(other.chainMap[key]?:0 as CoefficientT))

        }
        return result
    }

    operator fun times(scalar: CoefficientT): Chain<VertexT, CoefficientT> {

        val result = Chain<VertexT, CoefficientT>()
        for ((simplex, coefficient) in chainMap.entries) {

            result.chainMap[simplex] = multiplyCoefficient(scalar, coefficient)
        }
        return result
    }

    fun getCoefficients(simplex: AbstractSimplex<VertexT>): CoefficientT {
        // Retrieve the coefficient for the given simplex
        return chainMap[simplex] ?: 0 as CoefficientT
    }
    fun getCoefficients(simplices: Collection<AbstractSimplex<VertexT>>): Map<AbstractSimplex<VertexT>, CoefficientT> {
        // Retrieve the coefficients for the given simplices
        return chainMap.filterKeys { it in simplices }
    }

    fun updateCoefficient(simplex: AbstractSimplex<VertexT>, newCoefficient: CoefficientT) {
        // Update the coefficient for the given simplex
        chainMap[simplex] = newCoefficient
    }

    //Fix equality defintion... is wrong.
    override fun equals(other: Any?): Boolean {

        if (this === other) return true
        if (other !is Chain<*, *>) return false

        // Check that all of the keys and values are the same
        val commonKeys = this.chainMap.keys.union(other.chainMap.keys)

        for (key in commonKeys) {

            val thisValue = this.chainMap[key] ?: 0
            val otherValue = other.chainMap[key] ?: 0

            if (thisValue != otherValue) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        return chainMap.hashCode()
    }

    fun <VertexT : Comparable<VertexT>, CoefficientT : Number> Chain<VertexT, CoefficientT>.getLeadingTerm(): Pair<AbstractSimplex<VertexT>, CoefficientT>? {
        return chainMap.entries.firstOrNull()?.let { entry ->
            entry.key to entry.value
        }
    }

    fun <VertexT : Comparable<VertexT>, CoefficientT : Number> Chain<VertexT, CoefficientT>.getLeadingCoefficient(): CoefficientT? {
        return chainMap.entries.maxByOrNull { it.key }?.value
    }

    // Need to find way to not use below methods using kmath?
    private fun multiplyCoefficient(scalar: CoefficientT, coefficient: CoefficientT): CoefficientT {
        return when (coefficient) {
            is Double -> (coefficient.toDouble() * scalar.toDouble()) as CoefficientT
            is Float -> (coefficient.toFloat() * scalar.toFloat()) as CoefficientT
            is Long -> (coefficient.toLong() * scalar.toLong()) as CoefficientT
            is Int -> (coefficient.toInt() * scalar.toInt()) as CoefficientT
            else -> throw IllegalArgumentException("Unsupported number type")
        }
    }


    private fun negateCoefficient(coefficient: CoefficientT): CoefficientT {
        return when (coefficient) {
            is Double -> -coefficient
            is Float -> -coefficient
            is Long -> -coefficient
            is Int -> -coefficient
            else -> throw IllegalArgumentException("Unsupported number type")
        } as CoefficientT
    }

    private fun addNumbers(a: CoefficientT, b: CoefficientT): CoefficientT {
        return when (a) {
            is Double -> (a.toDouble() + b.toDouble()) as CoefficientT
            is Float -> (a.toFloat() + b.toFloat()) as CoefficientT
            is Long -> (a.toLong() + b.toLong()) as CoefficientT
            is Int -> (a.toInt() + b.toInt()) as CoefficientT
            else -> throw IllegalArgumentException("Unsupported number type")
        }
    }
}

fun <VertexT : Comparable<VertexT>, CoefficientT : Number> chainOf(vararg simplexCoefficients: Pair<AbstractSimplex<VertexT>, CoefficientT>): Chain<VertexT, CoefficientT> {
    // Delegate to the companion object's chainOf function
    return Chain.apply(*simplexCoefficients)
}

fun <VertexT : Comparable<VertexT>> chainOf(vararg simplex: AbstractSimplex<VertexT>): Chain<VertexT, Int> {
    // Delegate to the companion object's chainOf function
    return Chain.apply(*simplex)
}
