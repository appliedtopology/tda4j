package org.appliedtopology.tda4j

import kotlin.math.absoluteValue

class SimplexIndexing(val vertexCount: Int) {
    val binomialTable =
        0.rangeTo(vertexCount).map { d ->
            Combinatorics.binomialDiagonal(d).take(vertexCount).toList()
        }

    tailrec fun simplexAt(
        n: Int,
        d: Int,
        upperAccum: Simplex = simplexOf(),
    ): Simplex {
        if (d < 0) return upperAccum
        if (n <= 0) return simplexOf(upperAccum + (0 until d))
        if (d == 0) return simplexOf(upperAccum + n)
        val binarySearchResult = binomialTable[d].binarySearch(n)
        val id = if (binarySearchResult < 0) (binarySearchResult.absoluteValue) - 2 else binarySearchResult

        return simplexAt(
            n - binomialTable[d][id],
            d - 1,
            simplexOf(upperAccum + (id + d)),
        )
    }

    fun simplexIndex(simplex: Simplex): Int = Companion.simplexIndex(simplex)

    companion object {
        fun simplexIndex(simplex: Simplex): Int =
            simplex.vertices.mapIndexed { i, v ->
                Combinatorics.binomial(v, i + 1)
            }.sum()
    }
}

class SimplexIndexVietorisRips(
    metricSpace: FiniteMetricSpace<Int>,
    maxFiltrationValue: Double,
    maxDimension: Int,
) : VietorisRips<Int>(metricSpace, maxFiltrationValue, maxDimension) {
    override fun cliques(): Sequence<Simplex> =
        sequence {
            with(SimplexIndexing(metricSpace.size)) {
                (0 until maxDimension).forEach { d ->
                    yieldAll(cliquesByDimension(d).map { fsd -> simplexAt(fsd.second, fsd.third) })
                }
            }
        }

    fun cliquesByDimension(d: Int): Sequence<Triple<Double, Int, Int>> =
        with(SimplexIndexing(metricSpace.size)) {
            val filtered = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
            (0 until Combinatorics.binomial(metricSpace.size, d + 1)).map {
                Triple(filtered.filtrationValue(simplexAt(it, d + 1)) ?: Double.POSITIVE_INFINITY, it, d + 1)
            }.sortedBy { it.first }.asSequence()
        }
}
