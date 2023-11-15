package org.appliedtopology.tda4j

import arrow.fx.coroutines.parMapNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class ParallelSymmetricSimplexIndexVietorisRips<GroupT>(
    metricSpace: FiniteMetricSpace<Int>,
    maxFiltrationValue: Double,
    maxDimension: Int,
    symmetryGroup: SymmetryGroup<GroupT, Int>,
) : SymmetricSimplexIndexVietorisRips<GroupT>(metricSpace, maxFiltrationValue, maxDimension, symmetryGroup) {
    val scope = CoroutineScope(Dispatchers.Default)

    override fun cliquesByDimension(d: Int): Sequence<Triple<Double, Int, Int>> {
        runBlocking {
            if (dimensionRepresentatives[d].none()) {
                val filtered = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
                val si = SimplexIndexing(metricSpace.size)
                val dimensionRepresentativesJob =
                    async {
                        (0 until Combinatorics.binomial(metricSpace.size, d + 1))
                            .parMapNotNull {
                                if (symmetryGroup.isRepresentative(
                                        si.simplexAt(
                                            it,
                                            d + 1,
                                        ),
                                    )
                                ) {
                                    it
                                } else {
                                    null
                                }
                            }
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
                dimensionRepresentatives[d] = dimensionRepresentativesJob.await()
            }
        }
        return dimensionRepresentatives[d]
    }
}

class ParallelHyperCubeSymmetryGenerators(elementCount: Int) : HyperCubeSymmetryGenerators(elementCount) {
    val mutex: Mutex = Mutex(false)
    var representativesCache: MutableMap<Simplex, Simplex> = HashMap()

    override fun isRepresentative(simplex: AbstractSimplex<Int>): Boolean {
        // First, check if we have a cached representative
        if (simplex in representativesCache) return simplex == representativesCache[simplex]

        // Second, do the cheap check - if it fails this couldn't possibly be a representative
        if (!generators.all { g -> AbstractSimplex.compare(simplex, simplex.mapVertices(action(g))) <= 0 }) {
            return false
        }

        // Finally, generate the orbit, save the true representative and move on
        runBlocking {
            mutex.withLock {
                representativesCache[simplex] = representative(simplex)
            }
        }
        return simplex == representative(simplex)
    }
}
