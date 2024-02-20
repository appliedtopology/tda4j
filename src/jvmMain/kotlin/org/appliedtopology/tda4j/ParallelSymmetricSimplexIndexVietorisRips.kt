package org.appliedtopology.tda4j

import java.util.concurrent.locks.ReentrantLock

open class ParallelSymmetricSimplexIndexVietorisRips<GroupT>(
    metricSpace: FiniteMetricSpace<Int>,
    maxFiltrationValue: Double,
    maxDimension: Int,
    symmetryGroup: SymmetryGroup<GroupT, Int>,
) : SymmetricSimplexIndexVietorisRips<GroupT>(metricSpace, maxFiltrationValue, maxDimension, symmetryGroup) {
    override var dimensionRepresentatives: Array<Sequence<Triple<Double, Int, Int>>> =
        Array<Sequence<Triple<Double, Int, Int>>>(maxDimension + 1) { emptySequence() }

    override fun cliquesByDimension(d: Int): Sequence<Triple<Double, Int, Int>> {
        while (dimensionRepresentatives[d].none()) {
            val filtered = FiniteMetricSpace.maximumDistanceFiltrationValue(metricSpace)
            val si = SimplexIndexing(metricSpace.size)
            dimensionRepresentatives[d] =
                (0 until Combinatorics.binomial(metricSpace.size, d + 1))
                    .toList()
                    .parallelStream()
                    .filter { symmetryGroup.isRepresentative(si.simplexAt(it, d + 1)) }
                    .collect(java.util.stream.Collectors.toList())
                    .map { it: Int ->
                        Triple(
                            filtered.filtrationValue(si.simplexAt(it, d + 1)) ?: Double.POSITIVE_INFINITY,
                            it,
                            d + 1,
                        )
                    }
                    .sortedBy { it: Triple<Double, Int, Int> -> it.first }
                    .asSequence()
        }
        return dimensionRepresentatives[d]
    }
}

class ParallelHyperCubeSymmetryGenerators(elementCount: Int) : HyperCubeSymmetryGenerators(elementCount) {
    val mutex: ReentrantLock = ReentrantLock()
    var representativesCache: MutableMap<Simplex, Simplex> = HashMap()

    override fun isRepresentative(simplex: AbstractSimplex<Int>): Boolean {
        // First, check if we have a cached representative
        if (simplex in representativesCache) return simplex == representativesCache[simplex]

        // Second, do the cheap check - if it fails this couldn't possibly be a representative
        if (!generators.all { g -> SimplexComparator<Int>().compare(simplex, simplex.mapVertices(action(g))) <= 0 }) {
            return false
        }

        // Finally, generate the orbit, save the true representative and move on
        val rep = super.representative(simplex)
        try {
            mutex.lock()
            representativesCache[simplex] = rep
        } finally {
            mutex.unlock()
        }
        return simplex == rep
    }
}
