package org.appliedtopology.tda4j

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.sequences.shouldContainExactly

class SimplexStreamSpec : FunSpec({
    val esb: ExplicitStream<Int, Double> = ExplicitStream()
    test("Adding a simplex to the stream works.") {
        esb[simplexOf(1, 2)] = 1.0
    }
    test("Adding several simplices to the stream works.") {
        esb.putAll(mapOf(Pair(simplexOf(1, 3), 0.0), Pair(simplexOf(2, 3), 2.0)))
    }
    test("SimplexStream contains all added simplices in the right order") {
        esb.iterator().asSequence()
            .shouldContainExactly(simplexOf(1, 3), simplexOf(1, 2), simplexOf(2, 3))
    }
})
