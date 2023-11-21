package org.appliedtopology.tda4j

import kotlin.math.absoluteValue

open class SimplexIndexing(val vertexCount: Int) {
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

    fun cofacetIterator(
        simplex: Simplex,
        all_cofacets: Boolean = true,
    ): Iterator<Int> = cofacetIterator(simplexIndex(simplex), simplex.size, simplex, all_cofacets)

    fun cofacetIterator(
        index: Int,
        size: Int,
        simplex: Simplex? = null,
        all_cofacets: Boolean = true,
    ): Iterator<Int> =
        iterator {
            val theSimplex = simplex ?: simplexAt(index, size)
            var idx_below = index
            var idx_above = 0
            var k = size
            loop@ for (j in (vertexCount - 1) downTo 0) {
                if (j in theSimplex) {
                    if (!all_cofacets) break@loop
                    idx_below -= Combinatorics.binomial(j, k)
                    idx_above += Combinatorics.binomial(j, k + 1)
                    k -= 1
                } else {
                    yield(idx_below + Combinatorics.binomial(j, k + 1) + idx_above)
                }
            }
        }

    fun facetIterator(simplex: Simplex): Iterator<Int> = facetIterator(simplexIndex(simplex), simplex.size, simplex)

    fun facetIterator(
        index: Int,
        size: Int,
        simplex: Simplex? = null,
    ): Iterator<Int> =
        iterator {
            val theSimplex = simplex ?: simplexAt(index, size)
            var idx_below = index
            var idx_above = 0

            for (k in (size - 1 downTo 0)) {
                val j = theSimplex.vertices[k]
                idx_below -= Combinatorics.binomial(j, k + 1)
                yield(idx_below + idx_above)
                idx_above += Combinatorics.binomial(j, k)
            }
        }

    fun simplexIndex(simplex: Simplex): Int = Companion.simplexIndex(simplex)

    companion object {
        fun simplexIndex(simplex: Simplex): Int =
            simplex.vertices.mapIndexed { i, v ->
                Combinatorics.binomial(v, i + 1)
            }.sum()
    }
}

open class SimplexIndexVietorisRips(
    metricSpace: FiniteMetricSpace<Int>,
    maxFiltrationValue: Double,
    maxDimension: Int,
) : VietorisRips<Int>(metricSpace, maxFiltrationValue, maxDimension) {
    override fun cliques(): Sequence<AbstractSimplex<Int>> = simplices

    override val simplices: Sequence<Simplex> =
        sequence {
            (0 until maxDimension).forEach { d -> yieldAll(simplicesByDimension(d)) }
        }

    open override fun simplicesByDimension(d: Int): Sequence<Simplex> =
        sequence {
            with(SimplexIndexing(metricSpace.size)) {
                yieldAll(cliquesByDimension(d).map { fsd -> simplexAt(fsd.second, fsd.third) })
            }
        }

    open fun cliquesByDimension(d: Int): Sequence<Triple<Double, Int, Int>> =
        with(SimplexIndexing(metricSpace.size)) {
            val filtered = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
            (0 until Combinatorics.binomial(metricSpace.size, d + 1)).map {
                Triple(filtered.filtrationValue(simplexAt(it, d + 1)) ?: Double.POSITIVE_INFINITY, it, d + 1)
            }.sortedBy { it.first }.asSequence()
        }
}

open class SymmetricSimplexIndexVietorisRips<GroupT>(
    metricSpace: FiniteMetricSpace<Int>,
    maxFiltrationValue: Double,
    maxDimension: Int,
    val symmetryGroup: SymmetryGroup<GroupT, Int>,
) : SimplexIndexVietorisRips(metricSpace, maxFiltrationValue, maxDimension) {
    override fun simplicesByDimension(d: Int): Sequence<Simplex> =
        sequence {
            with(SimplexIndexing(metricSpace.size)) {
                cliquesByDimension(d).forEach {
                    yieldAll(symmetryGroup.orbit(simplexAt(it.second, it.third)))
                }
            }
        }

    open var dimensionRepresentatives: Array<Sequence<Triple<Double, Int, Int>>> =
        Array<Sequence<Triple<Double, Int, Int>>>(
            maxDimension + 1,
        ) { emptySequence<Triple<Double, Int, Int>>() }

    override fun cliquesByDimension(d: Int): Sequence<Triple<Double, Int, Int>> {
        if (dimensionRepresentatives[d].none()) {
            val filtered = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
            val si = SimplexIndexing(metricSpace.size)
            dimensionRepresentatives[d] =
                (0 until Combinatorics.binomial(metricSpace.size, d + 1))
                    .filter { symmetryGroup.isRepresentative(si.simplexAt(it, d + 1)) }
                    .map {
                        Triple(
                            filtered.filtrationValue(si.simplexAt(it, d + 1)) ?: Double.POSITIVE_INFINITY,
                            it, d + 1,
                        )
                    }.sortedBy { it.first }.asSequence()
        }
        return dimensionRepresentatives[d]
    }
}
