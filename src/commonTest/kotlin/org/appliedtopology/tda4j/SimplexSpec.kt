package org.appliedtopology.tda4j.org.appliedtopology.tda4j

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.equals.shouldNotBeEqual
import io.kotest.matchers.types.shouldBeInstanceOf
import org.appliedtopology.tda4j.Chain
import org.appliedtopology.tda4j.Simplex
import org.appliedtopology.tda4j.simplexOf

class SimplexSpec : FunSpec({
    val simplex: Simplex = simplexOf(1, 2, 3)
    test("A simplex has a non-zero size") {
        simplex.size shouldBeGreaterThan 0
    }

    test("A simplex has a boundary") {
        simplex.boundary<Double>().shouldBeInstanceOf<Chain<Int, Double>>()
    }

    test("A non-zero simplex has a non-zero boundary") {
        simplexOf(1, 2, 3).boundary<Double>().shouldNotBeEqual(Chain<Int, Double>())
    }

    test("A simplex can receive an added vertex") {
        (simplex + 4).containsAll(setOf(1, 2, 3, 4)).shouldBeTrue()
    }

    test("A simplex can remove a vertex") {
        (simplex - 2).equals(listOf(1, 3)).shouldBeTrue()
    }
})
