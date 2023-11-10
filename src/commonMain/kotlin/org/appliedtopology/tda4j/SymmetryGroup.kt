package org.appliedtopology.tda4j

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SymmetryGroup<GroupT, VertexT : Comparable<VertexT>> {
    val elements: Collection<GroupT>

    fun action(g: GroupT): (VertexT) -> VertexT

    fun orbit(vertex: VertexT): Set<VertexT> = elements.map { action(it)(vertex) }.toSet()

    fun orbit(simplex: AbstractSimplex<VertexT>): Set<AbstractSimplex<VertexT>> =
        elements.map { g -> abstractSimplexOf(simplex.map { v -> action(g)(v) }) }.toSet()

    fun representative(vertex: VertexT): VertexT = orbit(vertex).min()

    fun representative(simplex: AbstractSimplex<VertexT>): AbstractSimplex<VertexT> =
        orbit(simplex).minWith { left, right -> AbstractSimplex.compare(left, right) }

    fun isRepresentative(vertex: VertexT): Boolean = vertex == representative(vertex)

    fun isRepresentative(simplex: AbstractSimplex<VertexT>): Boolean = simplex == representative(simplex)
}

object Combinatorics {
    tailrec fun factorial(
        n: Int,
        accum: Int = 1,
    ): Int {
        return if (n <= 1) accum else factorial(n - 1, n * accum)
    }

    /* See  https://en.wikipedia.org/wiki/Binomial_coefficient#In_programming_languages
     * and in particular the C language implementation for details on this tail recursion
     */
    fun binomial(
        n: Int,
        k: Int,
    ): Int {
        if (k > n / 2) return binomial(n, n - k)
        if (k < 0) return 0
        if (k > n) return 0
        if (k == 0) return 1
        if (k == n) return 1
        return (0 until k).fold(1) { c, i -> (c * (n - i)).floorDiv(i + 1) }
    }

    fun binomialDiagonal(k: Int): Sequence<Int> =
        sequence {
            var n = k
            var accum = 1L
            while (true) {
                yield(accum.toInt())
                n += 1
                accum = (accum * n).floorDiv(n - k)
            }
        }
}

open class HyperCubeSymmetry(val elementCount: Int) : SymmetryGroup<Int, Int> {
    override val elements: Collection<Int> = (0..Combinatorics.factorial(elementCount) - 1).toList()

    val permutations: List<Map<Int, Int>> =
        elements.map {
            run {
                val p = permutation(it)
                (0..(1 shl elementCount) - 1).associateWith { bits ->
                    (0..elementCount - 1)
                        .map { ((bits and (1 shl it) shr it) shl p[it]) }
                        .fold(0) { x, y -> x or y }
                }
            }
        }

    fun permutation(n: Int): List<Int> =
        buildList(elementCount) {
            val source = (0..elementCount - 1).toMutableList()
            var pos = n

            while (source.isNotEmpty()) {
                val div = pos.floorDiv(Combinatorics.factorial(source.size - 1))
                pos = pos.mod(Combinatorics.factorial(source.size - 1))
                add(source.removeAt(div))
            }
        }

    fun permutationIndex(p: List<Int>): Int {
        if (p.sorted() != (0..elementCount - 1).toList()) {
            throw NoSuchElementException("Not a permutation of 0..${elementCount - 1}")
        }

        var pmut = p.toMutableList()
        var pos = 0
        while (pmut.isNotEmpty()) {
            val i = pmut.removeFirst()
            pmut = pmut.map { it - (if (it > i) 1 else 0) }.toMutableList()
            pos += i * Combinatorics.factorial(pmut.size)
        }
        return pos
    }

    override fun action(g: Int): (Int) -> Int = { permutations[g][it] ?: it }
}

class HyperCubeSymmetryGenerators(elementCount: Int) : HyperCubeSymmetry(elementCount) {
    val generators: List<Int> =
        (0..elementCount - 2)
            .map { listOf((0..it - 1).toList(), listOf(it + 1, it), (it + 2..elementCount - 1).toList()).flatten() }
            .map { permutationIndex(it) }

    val pseudoRepresentatives: MutableMap<Simplex, Simplex> = HashMap()

    override fun representative(simplex: Simplex): Simplex {
        // First, check the cache
        if (simplex in pseudoRepresentatives) return pseudoRepresentatives[simplex]!!

        // Do it the hard way
        return super.representative(simplex)
    }

    override fun isRepresentative(simplex: AbstractSimplex<Int>): Boolean {
        // First, check the cache
        if (simplex in pseudoRepresentatives) return simplex == pseudoRepresentatives[simplex]

        // Second, do the cheap check - if it fails this couldn't possibly be a representative
        if (!generators.all { g -> AbstractSimplex.compare(simplex, simplex.mapVertices(action(g))) <= 0 }) {
            return false
        }

        // Finally, generate the orbit, save the true representative and move on
        runBlocking {
            launch {
                mutex.withLock {
                    pseudoRepresentatives[simplex] = representative(simplex)
                }
            }
        }
        return simplex == pseudoRepresentatives[simplex]
    }

    val mutex = Mutex()
}

class ExpandSequence<VertexT : Comparable<VertexT>>(
    val representatives: List<AbstractSimplex<VertexT>>,
    val symmetryGroup: SymmetryGroup<*, VertexT>,
) : Sequence<AbstractSimplex<VertexT>> {
    val orbitSizes: List<Int> = representatives.map { symmetryGroup.orbit(it).size }
    val orbitRanges: List<Int> = orbitSizes.scan(0) { x, y -> x + y }

    val size: Int = orbitSizes.sum()

    fun get(index: Int): AbstractSimplex<VertexT> {
        val orbitIndex = orbitRanges.indexOfFirst { it >= index } - 1
        return symmetryGroup.orbit(representatives[orbitIndex]).toList()[index - orbitRanges[orbitIndex]]
    }

    fun isEmpty(): Boolean = size == 0

    override fun iterator(): Iterator<AbstractSimplex<VertexT>> =
        sequence {
            representatives.forEach { t -> yieldAll(symmetryGroup.orbit(t)) }
        }.iterator()
}

class SymmetricZomorodianIncremental<VertexT : Comparable<VertexT>>(
    metricSpace: FiniteMetricSpace<VertexT>,
    maxFiltrationValue: Double,
    maxDimension: Int,
    val symmetryGroup: SymmetryGroup<*, VertexT>,
) :
    VietorisRips<VertexT>(metricSpace, maxFiltrationValue, maxDimension) {
    override fun cliques(): Sequence<AbstractSimplex<VertexT>> {
        val edges: List<Pair<Double?, Pair<VertexT, VertexT>>> =
            weightedEdges(metricSpace, maxFiltrationValue).sortedBy { it.first }.toList()
        val lowerNeighbors =
            buildMap {
                edges.forEach {
                        dvw ->
                    (
                        getOrPut(
                            dvw.second.second,
                            defaultValue = { hashSetOf<VertexT>() },
                        ) as MutableSet<VertexT>
                    ).add(dvw.second.first)
                }
            }

        val V: MutableSet<AbstractSimplex<VertexT>> = HashSet(metricSpace.size + edges.size)

        val tasks: ArrayDeque<Pair<AbstractSimplex<VertexT>, Set<VertexT>>> = ArrayDeque(edges.size)
        metricSpace.elements.forEach { vertex ->
            tasks.addFirst(
                Pair(
                    abstractSimplexOf(vertex),
                    lowerNeighbors.getOrElse(vertex) { emptySet() },
                ),
            )
        }

        while (tasks.size > 0) {
            val task: Pair<AbstractSimplex<VertexT>, Set<VertexT>> = tasks.removeFirst()
            val tau: AbstractSimplex<VertexT> = task.first
            val N: Set<VertexT> = task.second
            if (symmetryGroup.isRepresentative(tau)) V.add(tau)
            if (tau.size < maxDimension) {
                N.forEach { v: VertexT ->
                    run {
                        val sigma: AbstractSimplex<VertexT> = tau.plus(v)
                        val M: Set<VertexT> =
                            N.intersect(lowerNeighbors[v]?.asIterable() ?: emptySequence<VertexT>().asIterable())
                        tasks.addFirst(Pair(sigma, M))
                    }
                }
            }
        }

        val filtered: Filtered<VertexT, Double> = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
        return ExpandSequence<VertexT>(V.sortedWith(getComparator(filtered)), symmetryGroup)
    }
}
