package org.appliedtopology.tda4j

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual

val dimension: Int = 4
val maxF = dimension.toDouble()
val maxD = 1 shl dimension

class ZomorodianProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    // val hcs4 = HyperCubeSymmetryGenerators(dimension)

    test("!Time VietorisRips construction") {
        val vr = ZomorodianIncremental<Int>(hc4, maxF, maxD)
        var size = 0
        (0..maxD).forEach { dim -> vr.simplicesInDimension(dim).forEach { size += 1 } }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})

class SymmetricZomorodianProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    val hcs4 = HyperCubeSymmetryGenerators(dimension)

    test("!Time SymmetricVietorisRips construction") {
        val vr = SymmetricZomorodianIncremental<Int>(hc4, maxF, maxD, hcs4)
        var size = 0
        (0..maxD).forEach { dim -> vr.simplicesInDimension(dim).forEach { size += 1 } }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})

class SimplexIndexingProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    // val hcs4 = HyperCubeSymmetryGenerators(dimension)

    test("Time SimplexIndexing construction") {
        val vr = SimplexIndexVietorisRips(hc4, maxF, maxD)
        var size = 0
        (0..maxD).forEach { dim -> vr.simplicesInDimension(dim).forEach { size += 1 } }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})

class SymmetricSimplexIndexingProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    val hcs4 = HyperCubeSymmetryGenerators(dimension)

    test("Time SymmetricSimplexIndexing construction") {
        val vr = SymmetricSimplexIndexVietorisRips(hc4, maxF, maxD, hcs4)
        var size = 0
        (0..maxD).forEach { dim ->
            run {
                vr.simplicesInDimension(dim).forEach { size += 1 }
                println("SSI Dimension $dim done...")
            }
        }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})
