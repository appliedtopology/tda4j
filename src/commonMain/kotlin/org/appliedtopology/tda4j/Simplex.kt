package org.appliedtopology.tda4j

import arrow.core.padZip

public open class AbstractSimplex<VertexT>
    protected constructor(val vertexComparator: Comparator<VertexT>) : Set<VertexT> {
        @Suppress("ktlint:standard:property-naming")
        internal val _simplex: MutableSortedSet<VertexT> = MutableSortedSet(comparator = vertexComparator)

        override operator fun contains(element: VertexT): Boolean = _simplex.contains(element)

        override fun isEmpty(): Boolean = _simplex.isEmpty()

        override fun iterator(): Iterator<VertexT> = _simplex.iterator()

        override fun containsAll(elements: Collection<VertexT>): Boolean = _simplex.containsAll(elements)

        override fun equals(other: Any?): Boolean {
            if (other !is AbstractSimplex<*>) { // Cannot possibly be equal to a non-simplex
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
        ) = AbstractSimplex<R>(rComparator, _simplex.mapTo(HashSet<R>(_simplex.size), transform))

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

        fun <CoefficientT> boundary(fieldContext: FieldContext<CoefficientT>): Chain<VertexT, CoefficientT> =
            Chain(
                vertexComparator,
                SimplexComparator(vertexComparator),
                ArrayMutableSortedMap<AbstractSimplex<VertexT>, CoefficientT>(size, SimplexComparator(vertexComparator))
                    .apply {
                        _simplex
                            .asSequence()
                            .map { AbstractSimplex(vertexComparator, _simplex - it) }
                            .zip(alternatingSign.asSequence())
                            .forEach { (s, c) -> put(s, fieldContext.number(c)) }
                    },
            )

        fun plus(element: VertexT): AbstractSimplex<VertexT> {
            val vertices = HashSet(_simplex)
            vertices.add(element)
            return AbstractSimplex<VertexT>(vertexComparator, vertices)
        }

        val vertices: List<VertexT>
            get() = _simplex.toList()

        override fun toString(): String =
            _simplex.joinToString(
                ",",
                "abstractSimplexOf(",
                ")",
            )

        public companion object {
            public operator fun <VertexT> invoke(vertexComparator: Comparator<VertexT>): AbstractSimplex<VertexT> =
                AbstractSimplex(vertexComparator)

            public operator fun <VertexT> invoke(
                vertexComparator: Comparator<VertexT>,
                elements: Collection<VertexT>,
            ): AbstractSimplex<VertexT> {
                val retval: AbstractSimplex<VertexT> = AbstractSimplex(vertexComparator)
                retval._simplex.addAll(elements)
                return retval
            }

            public operator fun <VertexT : Comparable<VertexT>> invoke(): AbstractSimplex<VertexT> = AbstractSimplex(naturalOrder())

            public operator fun <VertexT : Comparable<VertexT>> invoke(elements: Collection<VertexT>): AbstractSimplex<VertexT> =
                AbstractSimplex(naturalOrder(), elements)

            public operator fun <VertexT : Comparable<VertexT>> invoke(vararg elements: VertexT): AbstractSimplex<VertexT> =
                AbstractSimplex(naturalOrder(), elements.toList())
        }
    }

public open class Lexicographic<T> protected constructor(val comparator: Comparator<T>) :
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

        public companion object {
            public operator fun <T> invoke(comparator: Comparator<T>): Lexicographic<T> = Lexicographic(comparator)

            public operator fun <T : Comparable<T>> invoke(): Lexicographic<T> = Lexicographic(naturalOrder())
        }
    }

public open class SimplexComparator<VertexT>
    protected constructor(val vertexComparator: Comparator<VertexT>) : Comparator<AbstractSimplex<VertexT>> {
        override fun compare(
            left: AbstractSimplex<VertexT>,
            right: AbstractSimplex<VertexT>,
        ) = Lexicographic(vertexComparator).compare(left.vertices, right.vertices)

        public companion object {
            public operator fun <VertexT> invoke(vertexComparator: Comparator<VertexT>): SimplexComparator<VertexT> =
                SimplexComparator(vertexComparator)

            public operator fun <VertexT : Comparable<VertexT>> invoke(): SimplexComparator<VertexT> = SimplexComparator(naturalOrder())
        }
    }

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
open class SimplexContext<VertexT>
    protected constructor(val vertexComparator: Comparator<VertexT>) {
        fun s(vararg vs: VertexT): AbstractSimplex<VertexT> = AbstractSimplex(vertexComparator, vs.toList())

        operator fun AbstractSimplex.Companion.invoke(): AbstractSimplex<VertexT> = AbstractSimplex<VertexT>(vertexComparator)

        operator fun AbstractSimplex.Companion.invoke(vararg elements: VertexT): AbstractSimplex<VertexT> =
            AbstractSimplex<VertexT>(vertexComparator, elements.toList())

        companion object {
            operator fun <VertexT> invoke(vertexComparator: Comparator<VertexT>): SimplexContext<VertexT> = SimplexContext(vertexComparator)

            operator fun <VertexT : Comparable<VertexT>> invoke(): SimplexContext<VertexT> = SimplexContext(naturalOrder())
        }
    }
