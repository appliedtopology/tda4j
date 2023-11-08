package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.equals.shouldNotBeEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map

fun <VertexT : Comparable<VertexT>> simplexArb(
    vertices: Collection<VertexT>,
    dimRange: IntRange = 0..vertices.size,
): Arb<AbstractSimplex<VertexT>> = Arb.list(Arb.element(vertices), dimRange).map { vxs -> abstractSimplexOf(vxs) }

fun simplexArb(
    vertices: IntRange,
    dimRange: IntRange = 0..vertices.last(),
): Arb<AbstractSimplex<Int>> = simplexArb(vertices.toList(), dimRange)

class SimplexSpec : StringSpec({
    val simplex: Simplex = simplexOf(1, 2, 3)
    "A simplex has a non-zero size" {
        simplex.size shouldBeGreaterThan 0
    }

    "A simplex has a boundary" {
        simplex.boundary<Double>().shouldBeInstanceOf<Chain<Int, Double>>()
    }

    "A non-zero simplex has a non-zero boundary" {
        simplexOf(1, 2, 3).boundary<Double>().shouldNotBeEqual(Chain<Int, Double>())
    }

    "A simplex can receive an added vertex" {
        (simplex + 4).shouldBeEqual(simplexOf(1, 2, 3, 4))
    }

    "A simplex can remove a vertex" {
        (simplex - 2).shouldBeEqual(simplexOf(1, 3))
    }

    "Two simplices can be equal" {
        simplexOf(0, 1, 2).shouldBeEqual(simplexOf(0, 1, 2))
    }

    "Lexicographic ordering works" {
        Simplex.compare(simplexOf(0, 1), simplexOf(0, 2)).shouldBeLessThan(0)
    }
})
