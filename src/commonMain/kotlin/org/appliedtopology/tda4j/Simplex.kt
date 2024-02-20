package org.appliedtopology.tda4j

import arrow.core.padZip

open class AbstractSimplexWith<VertexT>(val vertexComparator: Comparator<VertexT>) : Set<VertexT> {
    @Suppress("ktlint:standard:property-naming")
    internal val _simplex: MutableSortedSetWith<VertexT> = MutableSortedSetWith(comparator = vertexComparator)

    constructor(vertexComparator: Comparator<VertexT>, elements: HashSet<VertexT>) : this(vertexComparator) {
        _simplex.addAll(elements)
    }
    constructor(vertexComparator: Comparator<VertexT>, elements: Collection<VertexT>) : this(vertexComparator) {
        _simplex.addAll(elements)
    }

    override operator fun contains(element: VertexT): Boolean = _simplex.contains(element)

    override fun isEmpty(): Boolean = _simplex.isEmpty()

    override fun iterator(): Iterator<VertexT> = _simplex.iterator()

    override fun containsAll(elements: Collection<VertexT>): Boolean = _simplex.containsAll(elements)

    override fun equals(other: Any?): Boolean {
        if (other !is AbstractSimplexWith<*>) { // Cannot possibly be equal to a non-simplex
            return false
        }
        val mine = this.vertices
        val thine = other.vertices
        return mine.containsAll(thine) and (thine.containsAll(mine))
    }

    override fun hashCode(): Int {
        return _simplex.hashCode()
    }

    override val size: Int
        get() = _simplex.size

    val dimension: Int
        get() = size - 1

    fun <R> mapVerticesWith(
        rComparator: Comparator<R>,
        transform: (VertexT) -> R,
    ) = AbstractSimplexWith<R>(rComparator, _simplex.mapTo(HashSet<R>(_simplex.size), transform))

    fun <R : Comparable<R>> mapVertices(transform: (VertexT) -> R) = mapVerticesWith(naturalOrder(), transform)

    private val alternatingSign: Iterator<Int>
        get() =
            iterator {
                var retval = 1
                while (true) {
                    yield(retval)
                    retval *= -1
                }
            }

    fun <CoefficientT> boundary(fieldContext: FieldContext<CoefficientT>): ChainWith<VertexT, CoefficientT> =
        ChainWith(
            vertexComparator,
            SimplexComparatorWith(vertexComparator),
            ArrayMutableSortedMapWith<AbstractSimplexWith<VertexT>, CoefficientT>(size, SimplexComparatorWith(vertexComparator))
                .apply {
                    _simplex
                        .asSequence()
                        .map { AbstractSimplexWith(vertexComparator, _simplex - it) }
                        .zip(alternatingSign.asSequence())
                        .forEach { (s, c) -> put(s, fieldContext.number(c)) }
                },
        )

    fun plus(element: VertexT): AbstractSimplexWith<VertexT> {
        val vertices = HashSet(_simplex)
        vertices.add(element)
        return AbstractSimplexWith<VertexT>(vertexComparator, vertices)
    }

    val vertices: List<VertexT>
        get() = _simplex.toList()

    override fun toString(): String =
        _simplex.joinToString(
            ",",
            "abstractSimplexOf(",
            ")",
        )
}

class AbstractSimplex<VertexT : Comparable<VertexT>>() : AbstractSimplexWith<VertexT>(naturalOrder()) {
    constructor(elements: Collection<VertexT>) : this() {
        _simplex.addAll(elements)
    }
}

open class LexicographicWith<T>(val comparator: Comparator<T>) :
    Comparator<Collection<T>> {
    override fun compare(
        left: Collection<T>,
        right: Collection<T>,
    ): Int {
        for (lr in left.reversed().padZip(
            right.reversed(),
        )) {
            val l = lr.first
            val r = lr.second
            if (l == null) return -1
            if (r == null) return +1
            val lv: T = l
            val rv: T = r
            if (comparator.compare(lv, rv) != 0) {
                return comparator.compare(lv, rv)
            }
        }
        return 0
    }
}

class Lexicographic<T : Comparable<T>> : LexicographicWith<T>(naturalOrder())

open class SimplexComparatorWith<VertexT>(val vertexComparator: Comparator<VertexT>) : Comparator<AbstractSimplexWith<VertexT>> {
    override fun compare(
        left: AbstractSimplexWith<VertexT>,
        right: AbstractSimplexWith<VertexT>,
    ) = LexicographicWith(vertexComparator).compare(left.vertices, right.vertices)
}

class SimplexComparator<VertexT : Comparable<VertexT>> : SimplexComparatorWith<VertexT>(naturalOrder())

typealias Simplex = AbstractSimplex<Int>

fun <VertexT : Comparable<VertexT>> abstractSimplexOf(vararg elements: VertexT): AbstractSimplex<VertexT> =
    AbstractSimplex(elements.toList())

fun <VertexT : Comparable<VertexT>> abstractSimplexOf(elements: Collection<VertexT>): AbstractSimplex<VertexT> =
    AbstractSimplex(elements.toList())

fun simplexOf(vararg elements: Int): Simplex = Simplex(elements.toList())

fun simplexOf(elements: Collection<Int>): Simplex = Simplex(elements.toList())

/**
 * Use `with(SimplexContext<VertexT>()) { ... }` to get the notation `s(v1,v2,v3,...,vn)`
 * to define a simplex object with the specified vertex type directly.
 *
 * For instance:
 * ```
 * with(SimplexContext<Char>()) {
 *   s('a','b','c').boundary == s('a','b') - s('a','c') + s('b','c')
 * }
 * ```
 *
 * This is automatically included when using a [ChainContext] context to set up choices
 * of both vertex-types and coefficient-types.
 */
open class SimplexContextWith<VertexT>(val vertexComparator: Comparator<VertexT>) {
    fun s(vararg vs: VertexT): AbstractSimplexWith<VertexT> = AbstractSimplexWith(vertexComparator, vs.toList())
}

open class SimplexContext<VertexT : Comparable<VertexT>>() : SimplexContextWith<VertexT>(naturalOrder())
