package org.appliedtopology.tda4j

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

class HyperCubeSymmetry(val elementCount: Int) : SymmetryGroup<Int, Int> {
    override val elements: Collection<Int> = (0..factorial(elementCount) - 1).toList()

    val permutations: List<Map<Int, Int>> = elements.map {
        run {
            val p = permutation(it)
            (0..(1 shl elementCount) - 1).associateWith { bits ->
                (0..elementCount - 1)
                    .map { ((bits and (1 shl it) shr it) shl p[it]) }
                    .fold(0, { x, y -> x or y })
            }
        }
    }
    val knownRepresentatives: MutableSet<AbstractSimplex<Int>> = HashSet()

    fun permutation(n: Int): List<Int> = buildList(elementCount) {
        val source = (0..elementCount - 1).toMutableList()
        var pos = n

        while (source.isNotEmpty()) {
            val div = pos.floorDiv(factorial(source.size - 1))
            pos = pos.mod(factorial(source.size - 1))
            add(source.removeAt(div))
        }
    }

    override fun action(g: Int): (Int) -> Int = { permutations[g][it] ?: it }

    override fun isRepresentative(simplex: AbstractSimplex<Int>): Boolean {
        if (simplex in knownRepresentatives) return true
        val isR = super.isRepresentative(simplex)
        if (isR) knownRepresentatives.add(simplex)
        return isR
    }

    companion object {
        fun factorial(n: Int, accum: Int = 1): Int {
            return if (n <= 1) accum else factorial(n - 1, n * accum)
        }
    }
}

class ExpandSequence<VertexT : Comparable<VertexT>> (
    val representatives: List<AbstractSimplex<VertexT>>,
    val symmetryGroup: SymmetryGroup<*, VertexT>
) : Sequence<AbstractSimplex<VertexT>> {
    val orbitSizes: List<Int> = representatives.map { symmetryGroup.orbit(it).size }
    val orbitRanges: List<Int> = orbitSizes.scan(0) { x, y -> x + y }

    val size: Int = orbitSizes.sum()

    fun get(index: Int): AbstractSimplex<VertexT> {
        val orbitIndex = orbitRanges.indexOfFirst { it >= index } - 1
        return symmetryGroup.orbit(representatives[orbitIndex]).toList()[index - orbitRanges[orbitIndex]]
    }

    fun isEmpty(): Boolean = size == 0

    override fun iterator(): Iterator<AbstractSimplex<VertexT>> = sequence {
        representatives.forEach({ t -> yieldAll(symmetryGroup.orbit(t)) })
    }.iterator()
}

class SymmetricZomorodianIncremental<VertexT : Comparable<VertexT>>(val symmetryGroup: SymmetryGroup<*, VertexT>) : CliqueFinder<VertexT> {
    override fun cliques(
        metricSpace: FiniteMetricSpace<VertexT>,
        maxFiltrationValue: Double,
        maxDimension: Int
    ): Sequence<AbstractSimplex<VertexT>> {
        val edges: List<Pair<Double?, Pair<VertexT, VertexT>>> =
            weightedEdges(metricSpace, maxFiltrationValue).sortedBy { it.first }.toList()
        val lowerNeighbors = buildMap {
            edges.forEach {
                    dvw ->
                (
                    getOrPut(
                        dvw.second.second,
                        defaultValue = { -> hashSetOf<VertexT>() }
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
                    lowerNeighbors.getOrElse(vertex, { -> emptySet() })
                )
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
                            N.intersect(lowerNeighbors.get(v)?.asIterable() ?: emptySequence<VertexT>().asIterable())
                        tasks.addFirst(Pair(sigma, M))
                    }
                }
            }
        }

        val filtered: Filtered<VertexT, Double> = FiniteMetricSpace.MaximumDistanceFiltrationValue(metricSpace)
        return ExpandSequence<VertexT> (V.sortedWith(VietorisRips.getComparator(filtered)), symmetryGroup)
    }
}
