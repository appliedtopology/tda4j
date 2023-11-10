package org.appliedtopology.tda4j

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.sequences.shouldContainAll
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

class SimplexIndexingSpec : StringSpec({
    "Error Case" {
        with(SimplexIndexing(15)) {
            simplexAt(72, 5).shouldBeEqual(simplexOf(8, 6, 3, 1, 0))
        }
    }
    "Examples from the Ripser paper, d=2, d=3" {
        with(SimplexIndexing(15)) {
            simplexIndex(simplexOf(0, 1, 2)).shouldBeEqual(0)
            simplexIndex(simplexOf(0, 1, 3)).shouldBeEqual(1)
            simplexIndex(simplexOf(0, 2, 3)).shouldBeEqual(2)
            simplexIndex(simplexOf(1, 2, 3)).shouldBeEqual(3)
            simplexIndex(simplexOf(0, 1, 4)).shouldBeEqual(4)
            simplexIndex(simplexOf(0, 3, 5)).shouldBeEqual(13)
            simplexIndex(simplexOf(0, 3, 5, 6)).shouldBeEqual(28)
            simplexIndex(simplexOf(0, 3, 4, 5)).shouldBeEqual(12)
            simplexIndex(simplexOf(0, 2, 3, 5)).shouldBeEqual(7)
            simplexIndex(simplexOf(0, 1, 3, 5)).shouldBeEqual(6)

            simplexAt(72, 5).shouldBeEqual(simplexOf(8, 6, 3, 1, 0))
            simplexAt(0, 3).shouldBeEqual(simplexOf(0, 1, 2))
            simplexAt(1, 3).shouldBeEqual(simplexOf(0, 1, 3))
            simplexAt(2, 3).shouldBeEqual(simplexOf(0, 2, 3))
            simplexAt(3, 3).shouldBeEqual(simplexOf(1, 2, 3))
            simplexAt(4, 3).shouldBeEqual(simplexOf(0, 1, 4))
            simplexAt(13, 3).shouldBeEqual(simplexOf(0, 3, 5))
            simplexAt(28, 4).shouldBeEqual(simplexOf(0, 3, 5, 6))
            simplexAt(12, 4).shouldBeEqual(simplexOf(0, 3, 4, 5))
            simplexAt(7, 4).shouldBeEqual(simplexOf(0, 2, 3, 5))
            simplexAt(6, 4).shouldBeEqual(simplexOf(0, 1, 3, 5))
        }
    }

    "simplexAt and simplexIndex are inverses" {
        val si = SimplexIndexing(25)
        checkAll(simplexArb(0 until 25, 0 until 25)) { simplex ->
            with(si) {
                simplexAt(simplexIndex(simplex), simplex.size).shouldBeEqual(simplex)
            }
        }
        checkAll(Arb.int(0 until 25), Arb.int(0 until 25)) { i, dim ->
            with(si) {
                withClue("Simplex # $i in dimension $dim") {
                    simplexIndex(simplexAt(i, dim)).shouldBeEqual(i)
                }
            }
        }
    }

    "SimplexIndexVietorisRips finds the correct cliques" {
        val hc2 = HyperCube(2)
        val vr =
            SimplexIndexVietorisRips(
                hc2,
                3.0,
                3,
            )
        vr.simplices.toList().shouldContainExactly(
            simplexOf(0), simplexOf(1), simplexOf(2), simplexOf(3),
            simplexOf(0, 1), simplexOf(0, 2),
            simplexOf(1, 3), simplexOf(2, 3),
            simplexOf(1, 2), simplexOf(0, 3),
            simplexOf(0, 1, 2), simplexOf(0, 1, 3),
            simplexOf(0, 2, 3), simplexOf(1, 2, 3),
        )
    }

    "SymmetricSimplexIndexVietorisRips finds the correct cliques" {
        val hc2 = HyperCube(2)
        val hcs2 = HyperCubeSymmetry(2)
        val vr = SymmetricSimplexIndexVietorisRips<Int>(hc2, 3.0, 3, hcs2)
        vr.simplices.toList().shouldContainExactly(
            simplexOf(0), simplexOf(1), simplexOf(2), simplexOf(3),
            simplexOf(0, 1), simplexOf(0, 2),
            simplexOf(1, 3), simplexOf(2, 3),
            simplexOf(1, 2), simplexOf(0, 3),
            simplexOf(0, 1, 2), simplexOf(0, 1, 3),
            simplexOf(0, 2, 3), simplexOf(1, 2, 3),
        )
    }

    fun siEQssi(d: Int) =
        "SymmetricSimplexIndexVietorisRips and SimplexIndexVietorsRips agree on HyperCube($d)" {
            val hc = HyperCube(d)
            val hcs = HyperCubeSymmetry(d)
            val vr = SimplexIndexVietorisRips(hc, d.toDouble(), (1 shl d))
            val svr = SymmetricSimplexIndexVietorisRips(hc, d.toDouble(), (1 shl d), hcs)

            svr.simplices.shouldContainAll(vr.simplices)
            vr.simplices.shouldContainAll(svr.simplices)

            (0..(1 shl d)).forEach {
                svr.simplicesByDimension(it).shouldContainAll(vr.simplicesByDimension(it))
                vr.simplicesByDimension(it).shouldContainAll(svr.simplicesByDimension(it))
            }
        }

    siEQssi(2)
    siEQssi(3)

    fun siEQpssi(d: Int) =
        "ParallelSymmetricSimplexIndexVietorisRips and SimplexIndexVietorsRips agree on HyperCube($d)" {
            val hc = HyperCube(d)
            val hcs = HyperCubeSymmetry(d)
            val vr = SimplexIndexVietorisRips(hc, d.toDouble(), (1 shl d))
            val psvr = ParallelSymmetricSimplexIndexVietorisRips(hc, d.toDouble(), (1 shl d), hcs)

            psvr.simplices.shouldContainAll(vr.simplices)
            vr.simplices.shouldContainAll(psvr.simplices)

            (0..(1 shl d)).forEach {
                psvr.simplicesByDimension(it).shouldContainAll(vr.simplicesByDimension(it))
                vr.simplicesByDimension(it).shouldContainAll(psvr.simplicesByDimension(it))
            }
        }

    siEQpssi(2)
    siEQpssi(3)
})
