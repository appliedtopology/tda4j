package org.appliedtopology.tda4j

import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.shouldForAll
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.assume
import io.kotest.property.checkAll

class SymmetryGroupSpec : FunSpec({
    test("Factorial function computes correctly") {
        (1..5).map { Combinatorics.factorial(it) }
            .shouldContainInOrder(listOf(1, 2, 6, 24, 120))
    }

    test("Binomial Coefficient computes correctly") {
        (0..3).map { Combinatorics.binomial(3, it) }
            .shouldContainInOrder(listOf(1, 3, 3, 1))
        (0..4).map { Combinatorics.binomial(4, it) }
            .shouldContainInOrder(listOf(1, 4, 6, 4, 1))
        checkAll(Arb.int(2, 25), Arb.int(1, 25)) { a, b ->
            val n = maxOf(a, b)
            val k = minOf(a, b)
            assume(n > 2)
            assume(k > 1)
            assume(k < n)
            Combinatorics.binomial(n, 0).shouldBeEqual(1)
            Combinatorics.binomial(n, n).shouldBeEqual(1)
            Combinatorics.binomial(n, 1).shouldBeEqual(n)
            Combinatorics.binomial(n, n - 1).shouldBeEqual(n)
            (Combinatorics.binomial(n, k) + Combinatorics.binomial(n, k + 1))
                .shouldBeEqual(Combinatorics.binomial(n + 1, k + 1))
        }
    }

    val hcs = HyperCubeSymmetry(3)
    test("Permutations of 1,2,3 enumerate correctly") {
        hcs.elements.map { hcs.permutation(it) }.shouldContainInOrder(
            listOf(
                listOf(0, 1, 2),
                listOf(0, 2, 1),
                listOf(1, 0, 2),
                listOf(1, 2, 0),
                listOf(2, 0, 1),
                listOf(2, 1, 0),
            ),
        )
    }

    test("Permutations of 1,2,3 have the right reverse indices") {
        hcs.elements.shouldForAll { hcs.permutationIndex(hcs.permutation(it)) shouldBeEqual it }
    }

    test("Permutations of 1,2,3 shift bits correctly") {
        hcs.orbit(3).shouldContainExactly(listOf(3, 5, 6))
    }

    val hc2 = HyperCube(2)
    val hcs2 = HyperCubeSymmetry(2)
    val sstream = SymmetricZomorodianIncremental<Int>(hc2, 3.0, 3, hcs2)
    val expandseq = sstream.simplices as ExpandSequence<Int>

    test("An orbit can have a representative simplex") {
        hcs2.isRepresentative(abstractSimplexOf(0))
    }

    test("Orbits work as expected") {
        hcs2.orbit(abstractSimplexOf(0, 1, 3)).shouldContainExactly(
            abstractSimplexOf(0, 1, 3),
            abstractSimplexOf(0, 2, 3),
        )
    }

    test("Singleton orbits work as expected") {
        hcs2.orbit(abstractSimplexOf(0, 1, 2)).shouldBeEqual(setOf(abstractSimplexOf(0, 1, 2)))
    }

    test("SimplexStream should have the right representatives") {
        expandseq.representatives.shouldContainAll(
            abstractSimplexOf(0),
            abstractSimplexOf(1),
            abstractSimplexOf(3),
            abstractSimplexOf(0, 1),
            abstractSimplexOf(1, 3),
            abstractSimplexOf(0, 3),
            abstractSimplexOf(0, 1, 3),
            abstractSimplexOf(0, 1, 2),
        )
    }

    test("SimplexStream should have the right elements") {
        sstream.simplices.toList().shouldContainAll(
            abstractSimplexOf(0),
            abstractSimplexOf(1),
            abstractSimplexOf(2),
            abstractSimplexOf(3),
            abstractSimplexOf(0, 1),
            abstractSimplexOf(0, 2),
            abstractSimplexOf(1, 3),
            abstractSimplexOf(2, 3),
            abstractSimplexOf(0, 3),
            abstractSimplexOf(1, 2),
            abstractSimplexOf(0, 1, 2),
            abstractSimplexOf(0, 1, 3),
            abstractSimplexOf(0, 2, 3),
            abstractSimplexOf(1, 2, 3),
        )
    }
})
