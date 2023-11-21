package org.appliedtopology.tda4j

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.sequences.shouldContainAll

val dimension: Int = 4
val maxF = dimension.toDouble()
val maxD = 1 shl dimension

class ZomorodianProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    // val hcs4 = HyperCubeSymmetryGenerators(dimension)

    test("!Time VietorisRips construction") {
        val vr = ZomorodianIncremental<Int>(hc4, maxF, maxD)
        var size = 0
        (0..maxD).forEach { dim -> vr.simplicesByDimension(dim).forEach { size += 1 } }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})

class SymmetricZomorodianProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    val hcs4 = HyperCubeSymmetryGenerators(dimension)

    test("!Time SymmetricVietorisRips construction") {
        val vr = SymmetricZomorodianIncremental<Int>(hc4, maxF, maxD, hcs4)
        var size = 0
        (0..maxD).forEach { dim -> vr.simplicesByDimension(dim).forEach { size += 1 } }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})

class SimplexIndexingProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    // val hcs4 = HyperCubeSymmetryGenerators(dimension)

    test("Time SimplexIndexing construction") {
        val vr = SimplexIndexVietorisRips(hc4, maxF, maxD)
        var size = 0
        (0..maxD).forEach { dim -> vr.simplicesByDimension(dim).forEach { size += 1 } }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})

class SymmetricSimplexIndexingProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    val hcs4 = HyperCubeSymmetryGenerators(dimension)

    test("Time SymmetricSimplexIndexing construction") {
        val vr = SymmetricSimplexIndexVietorisRips(hc4, maxF, maxD, hcs4)
        var size = 0
        (0..maxD).forEach { dim -> vr.simplicesByDimension(dim).forEach { size += 1 } }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})

class ParallelSymmetricSimplexIndexingProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    val hcs4 = ParallelHyperCubeSymmetryGenerators(dimension)

    test("Time ParallelSymmetricSimplexIndexing construction") {
        val vr = ParallelSymmetricSimplexIndexVietorisRips(hc4, maxF, maxD, hcs4)
        vr.simplices
            .fold(0) { acc, _ -> acc + 1 }
            .shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }

    test("!ParallelSymmetricSimplexIndexing produces the same simplices as SymmetricSimplexIndexing") {
        val psvr = ParallelSymmetricSimplexIndexVietorisRips(hc4, maxF, maxD, hcs4)
        val svr = SimplexIndexVietorisRips(hc4, maxF, maxD)

        var size = 0
        (0..maxD).forEach {
            psvr.simplicesByDimension(it).shouldContainAll(svr.simplicesByDimension(it))
            svr.simplicesByDimension(it).shouldContainAll(psvr.simplicesByDimension(it))
            size += psvr.simplicesByDimension(it).toList().size
        }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }

    fun siEQpssi(d: Int) =
        test("!ParallelSymmetricSimplexIndexVietorisRips and SimplexIndexVietorsRips agree on HyperCube($d)") {
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
