package org.appliedtopology.tda4j

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual

val dimension: Int = 4
val maxF = dimension.toDouble()
val maxD = 1 shl dimension
class ZomorodianProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    val hcs4 = HyperCubeSymmetry(dimension)

    test("Time VietorisRips construction") {
        val vr = VietorisRips<Int>(hc4, maxF, maxD, ZomorodianIncremental<Int>())
        val sstream = vr.simplices
        var size = 0
        sstream.forEach { size += 1 }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})

class SymmetricZomorodianProfilingSpec : FunSpec({
    val hc4 = HyperCube(dimension)
    val hcs4 = HyperCubeSymmetry(dimension)

    test("Time SymmetricVietorisRips construction") {
        val vr = VietorisRips<Int>(hc4, maxF, maxD, SymmetricZomorodianIncremental<Int>(hcs4))
        val sstream = vr.simplices
        var size = 0
        sstream.forEach { size += 1 }
        size.shouldBeEqual((1 shl (1 shl dimension)) - 1)
    }
})
