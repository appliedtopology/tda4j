package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.checkAll

class GeneratorsEnoughSpec : StringSpec({
    val sg = HyperCubeSymmetry(5)
    val hc = HyperCube(5)
    val gens =
        listOf(
            sg.permutationIndex(listOf(2, 1, 3, 4, 5).map { it - 1 }),
            sg.permutationIndex(listOf(1, 3, 2, 4, 5).map { it - 1 }),
            sg.permutationIndex(listOf(1, 2, 4, 3, 5).map { it - 1 }),
            sg.permutationIndex(listOf(1, 2, 3, 5, 4).map { it - 1 }),
        )
    "Checking group generators suffices for representative recognition" {
        checkAll<Simplex>(simplexArb((1..hc.top).toList(), 0..15)) { s ->
            collect(
                gens.all { g -> SimplexComparator<Int>().compare(s, s.mapVertices(sg.action(g))) <= 0 } ==
                    sg.isRepresentative(s),
            )
        }
    }
})
