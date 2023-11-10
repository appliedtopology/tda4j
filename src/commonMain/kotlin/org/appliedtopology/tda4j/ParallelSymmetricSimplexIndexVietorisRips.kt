package org.appliedtopology.tda4j

import arrow.fx.coroutines.parMapNotNull
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

open class ParallelSymmetricSimplexIndexVietorisRips<GroupT>(
    metricSpace: FiniteMetricSpace<Int>,
    maxFiltrationValue: Double,
    maxDimension: Int,
    symmetryGroup: SymmetryGroup<GroupT, Int>,
) : SymmetricSimplexIndexVietorisRips<GroupT>(metricSpace, maxFiltrationValue, maxDimension, symmetryGroup) {
    override fun cliquesByDimension(d: Int): Sequence<Triple<Double, Int, Int>> =
        runBlocking(Dispatchers.Default) {
            if (dimensionRepresentatives[d].none()) {
                val filtered = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
                val si = SimplexIndexing(metricSpace.size)
                dimensionRepresentatives[d] =
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
                    }.await()
                        .map { it: Int ->
                            Triple(
                                filtered.filtrationValue(si.simplexAt(it, d + 1)) ?: Double.POSITIVE_INFINITY,
                                it, d + 1,
                            )
                        }
                        .sortedBy { it: Triple<Double, Int, Int> -> it.first }
                        .asSequence()
            }
            return@runBlocking dimensionRepresentatives[d]
        }
}
