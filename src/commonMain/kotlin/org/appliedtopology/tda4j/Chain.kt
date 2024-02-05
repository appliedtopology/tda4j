package org.appliedtopology.tda4j

class Chain<VertexT : Comparable<VertexT>, CoefficientT : Number>(
    val chainMap: ArrayMutableSortedMap<AbstractSimplex<VertexT>, CoefficientT> = ArrayMutableSortedMap()
) {

    //Need to fix this so Chain(simplexOf(1,2,3)) w/o specified coefficient is valid instance of chain.
    constructor(simplexOf: AbstractSimplex<VertexT>) : this(){
        chainMap[simplexOf] = 1 as CoefficientT
    }
    constructor(vararg simplexCoefficients: Pair<AbstractSimplex<VertexT>, CoefficientT>) : this() {
        simplexCoefficients.forEach { (simplex, coefficient) ->
            chainMap[simplex] = coefficient
        }
    }

    operator fun unaryMinus(): Chain<VertexT, CoefficientT> {

        val result = Chain(chainMap)

        for ((simplex, coefficient) in chainMap.entries) {

            result.chainMap[simplex] = negateCoefficient(coefficient)
        }
        return result
    }

    operator fun plus(other: Chain<VertexT, CoefficientT>): Chain<VertexT, CoefficientT> {
        val result = Chain(chainMap)

        for ((simplex, coefficient) in other.chainMap.entries) {
            if (result.chainMap.containsKey(simplex)) {
                val currentCoefficient = result.chainMap[simplex]!!
                result.chainMap[simplex] = addNumbers(currentCoefficient, coefficient)
            } else {
                result.chainMap[simplex] = coefficient
            }
        }

        return result
    }

    operator fun minus(other: Chain<VertexT, CoefficientT>): Chain<VertexT, CoefficientT> {
        val result = Chain(chainMap)

        return this + (-other)
    }


    operator fun times(scalar: CoefficientT): Chain<VertexT, CoefficientT> {

        val result = Chain(chainMap)

        for ((simplex, coefficient) in chainMap.entries) {

            result.chainMap[simplex] = multiplyCoefficient(scalar, coefficient)
        }

        return result
    }

    fun getCoefficients(simplices: Collection<AbstractSimplex<VertexT>>): Map<AbstractSimplex<VertexT>, CoefficientT> {
        // Retrieve the coefficients for the given simplices
        return chainMap.filterKeys { it in simplices }
    }

    fun updateCoefficient(simplex: AbstractSimplex<VertexT>, newCoefficient: CoefficientT) {
        // Update the coefficient for the given simplex
        chainMap[simplex] = newCoefficient
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chain<*, *>) return false

        // Check if chain maps are equal based on common simplices
        val commonSimplices = chainMap.keys.intersect(other.chainMap.keys)

        for (simplex in commonSimplices) {
            if (chainMap[simplex] != other.chainMap[simplex]) return false
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
