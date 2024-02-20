package org.appliedtopology.tda4j

import io.kotest.core.Tuple2
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.tuple
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.property.checkAll
import kotlin.math.max
import kotlin.math.pow

class HomologySpec : StringSpec({
    "We should be able to use Chain to compute a simple persistent homology barcode" {
        checkAll<Pair<Double, Double>> {
            // For a random pair of values a, b, we compute the Vietoris-Rips homology of the point set
            // { <0,0>, <0,a>, <b,0>
            val a = it.first
            val b = it.second
            val points =
                listOf(
                    doubleArrayOf(0.0, 0.0),
                    doubleArrayOf(a, 0.0),
                    doubleArrayOf(0.0, b),
                    doubleArrayOf(a, b),
                )

            with(ChainContext<Int, Double>(DoubleContext)) {
                val metric = EuclideanMetricSpace(points)
                val simplices = vietorisRips<Int>(metric, listOf(a, b, (a * a + b * b).pow(0.5)).max(), 3)

                val boundaries: MutableList<Chain<Int, Double>> = mutableListOf()
                val cycles: MutableList<Chain<Int, Double>> = mutableListOf()
                val intervals: MutableList<Tuple2<Double, Double>> = mutableListOf()

                // TODO: this needs to change so that it tracks the computations, so that we can
                // actually create the new cycle when it gets created.
                fun Chain<Int, Double>.reduceByBasis(basis: List<Chain<Int, Double>>): Chain<Int, Double> {
                    var changed: Boolean = true
                    var sigma = this
                    while (changed) {
                        changed = false
                        for (basisElement in basis) {
                            if (sigma.leadingSimplex == basisElement.leadingSimplex) {
                                sigma =
                                    (
                                        (basisElement.leadingCoefficient / sigma.leadingCoefficient) * sigma -
                                            (sigma.leadingCoefficient / basisElement.leadingCoefficient) * basisElement
                                    ) as Chain<Int, Double>
                                changed = true
                            }
                        }
                    }
                    return sigma
                }

                for (simplex in simplices) {
                    val boundary: Chain<Int, Double> = simplex.boundary as Chain<Int, Double>

                    val reducedboundary = boundary.reduceByBasis(boundaries)

                    if (reducedboundary.isZero()) { // ∂s is a boundary; s creates a new cycle!
                        cycles.add(reducedboundary) // this is not the right thing here
                    } else { // ∂s is a cycle, will kill its corresponding cycle in cycles
                        val birthcycle = cycles.first { reducedboundary.leadingSimplex == it.leadingSimplex }
                        intervals.add(
                            tuple(
                                simplices.filtrationValue(birthcycle.leadingSimplex as Simplex) ?: Double.NEGATIVE_INFINITY,
                                simplices.filtrationValue(reducedboundary.leadingSimplex as Simplex) ?: Double.POSITIVE_INFINITY,
                            ),
                        )
                        cycles.remove(birthcycle)
                        boundaries.add(reducedboundary)
                    }
                }

                cycles.shouldHaveSize(1) // connected component never dies
                intervals.shouldContainAll(
                    tuple(0, a),
                    tuple(0, b),
                    tuple(max(a, b), max(a, b)),
                ) // I'm not 100% sure that this is the right barcode, and it's midnight, so I'm stopping here.
            }
        }
    }
})
