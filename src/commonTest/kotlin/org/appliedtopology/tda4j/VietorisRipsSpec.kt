package org.appliedtopology.tda4j

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll

class VietorisRipsSpec() : FunSpec({
    val hc2 = HyperCube(2)
    val sstream = VietorisRips(hc2, 3.0, 3, ZomorodianIncremental<Int>())

    test("HyperCube(2) generates the right simplices") {
        sstream.simplices.toList().shouldContainAll(
            simplexOf(0), simplexOf(1), simplexOf(2), simplexOf(3),
            simplexOf(0, 1), simplexOf(0, 2),
            simplexOf(1, 3), simplexOf(2, 3),
            simplexOf(0, 3), simplexOf(1, 2),
            simplexOf(0, 1, 3), simplexOf(0, 2, 3),
            simplexOf(0, 1, 2), simplexOf(1, 2, 3),
        )
    }
})
