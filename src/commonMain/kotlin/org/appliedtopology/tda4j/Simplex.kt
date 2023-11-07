package org.appliedtopology.tda4j

import arrow.core.padZip

open class AbstractSimplex<VertexT : Comparable<VertexT>> : Set<VertexT> {
    private val _simplex: HashSet<VertexT> = HashSet()

    constructor() { }

    constructor(elements: HashSet<VertexT>) {
        _simplex.addAll(elements)
    }
    constructor(elements: Collection<VertexT>) {
        _simplex.addAll(elements)
    }

    override operator fun contains(element: VertexT): Boolean = _simplex.contains(element)

    override fun isEmpty(): Boolean = _simplex.isEmpty()

    override fun iterator(): Iterator<VertexT> = _simplex.iterator()

    override fun containsAll(elements: Collection<VertexT>): Boolean = _simplex.containsAll(elements)

    override fun equals(other: Any?): Boolean {
        val that: AbstractSimplex<VertexT>? = other as? AbstractSimplex<VertexT>
        if (that == null) return false
        val mine = this.vertices
        val thine = that.vertices
        return mine.containsAll(thine) and thine.containsAll(mine)
    }

    override fun hashCode(): Int {
        return _simplex.hashCode()
    }

    override val size: Int
        get() = _simplex.size

    fun <R : Comparable<R>> mapVertices(transform: (VertexT) -> R) =
        AbstractSimplex<R>(_simplex.mapTo(HashSet<R>(_simplex.size), transform))

    fun <CoefficientT> boundary(): Chain<VertexT, CoefficientT> = Chain<VertexT, CoefficientT>()

    fun plus(element: VertexT): AbstractSimplex<VertexT> {
        val vertices = HashSet(_simplex)
        vertices.add(element)
        return AbstractSimplex<VertexT>(vertices)
    }

    val vertices: List<VertexT>
        get() = _simplex.sorted()

    override fun toString(): String =
        _simplex.joinToString(
            ",",
            "abstractSimplexOf(",
            ")",
        )

    companion object {
        fun <VertexT : Comparable<VertexT>> compare(
            left: AbstractSimplex<VertexT>,
            right: AbstractSimplex<VertexT>,
        ): Int {
            for (lr in left.vertices.padZip(right.vertices)) {
                val l = lr.first
                val r = lr.second
                if (l == null) return -1
                if (r == null) return +1
                val lv: VertexT = l!!
                val rv: VertexT = r!!
                if (lv.compareTo(rv) != 0) {
                    return lv.compareTo(rv)
                }
            }
            return 0
        }
    }
}

typealias Simplex = AbstractSimplex<Int>

fun <VertexT : Comparable<VertexT>> abstractSimplexOf(vararg elements: VertexT): AbstractSimplex<VertexT> =
    AbstractSimplex(elements.toList())

fun <VertexT : Comparable<VertexT>> abstractSimplexOf(elements: Collection<VertexT>): AbstractSimplex<VertexT> =
    AbstractSimplex(elements.toList())

fun simplexOf(vararg elements: Int): Simplex = Simplex(elements.toList())

fun simplexOf(elements: Collection<Int>): Simplex = Simplex(elements.toList())
