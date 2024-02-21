package org.appliedtopology.tda4j

import arrow.core.padZip
import space.kscience.kmath.operations.Group

public open class Chain<VertexT, CoefficientT> protected constructor(
    public val vertexComparator: Comparator<VertexT>,
    public val simplexComparator: Comparator<AbstractSimplex<VertexT>>,
    internal val chainMap: MutableMap<AbstractSimplex<VertexT>, CoefficientT>,
) {
    public fun <V> mapToChain(transform: (CoefficientT) -> V): Chain<VertexT, V> =
        Chain(
            this.vertexComparator,
            this.simplexComparator,
            mutableMapOf(*this.chainMap.map { (k, v) -> k to transform(v) }.toTypedArray()),
        )

    override fun toString(): String {
        val retval: String =
            chainMap.keys.sortedWith(simplexComparator).map { simplex ->
                "${chainMap[simplex]}*$simplex"
            }.joinToString(" + ")
        return if (retval != "") {
            (retval)
        } else {
            ("0")
        }
    }

    public operator fun set(
        simplex: AbstractSimplex<VertexT>,
        value: CoefficientT,
    ) {
        chainMap[simplex] = value
    }

    public fun getOrNull(simplex: AbstractSimplex<VertexT>): CoefficientT? = chainMap[simplex]

    public companion object {
        public operator fun <VertexT, CoefficientT> invoke(
            vertexComparator: Comparator<VertexT>,
            simplexComparator: Comparator<AbstractSimplex<VertexT>>,
            chainMap: MutableMap<AbstractSimplex<VertexT>, CoefficientT>,
        ): Chain<VertexT, CoefficientT> = Chain(vertexComparator, simplexComparator, chainMap)

        public operator fun <VertexT : Comparable<VertexT>, CoefficientT> invoke(
            simplexComparator: Comparator<AbstractSimplex<VertexT>>,
            chainMap: MutableMap<AbstractSimplex<VertexT>, CoefficientT>,
        ): Chain<VertexT, CoefficientT> = Chain(naturalOrder(), simplexComparator, chainMap)

        public operator fun <VertexT : Comparable<VertexT>, CoefficientT> invoke(
            chainMap: MutableMap<AbstractSimplex<VertexT>, CoefficientT>,
        ) = Chain(naturalOrder(), SimplexComparator(), chainMap)

        public operator fun <VertexT, CoefficientT> invoke(
            vertexComparator: Comparator<VertexT>,
            simplexComparator: Comparator<AbstractSimplex<VertexT>>,
        ): Chain<VertexT, CoefficientT> = Chain(vertexComparator, simplexComparator, mutableMapOf())
    }
}

public class ChainContext<VertexT, CoefficientT> protected constructor(
    vertexComparator: Comparator<VertexT>,
    public val coefficientContext: FieldContext<CoefficientT>,
) :
    Group<Chain<VertexT, CoefficientT>>,
        SimplexContext<VertexT>(vertexComparator) {
        public fun Chain<VertexT, CoefficientT>.zipToChain(
            other: Chain<VertexT, CoefficientT>,
            operator: (CoefficientT, CoefficientT) -> CoefficientT,
        ): Chain<VertexT, CoefficientT> {
            val thismap = this@zipToChain.chainMap
            val thatmap = other.chainMap
            val mappings: List<Pair<AbstractSimplex<VertexT>, CoefficientT>> =
                thismap.padZip(thatmap).map {
                    it.key to
                        operator(
                            (it.value.first ?: coefficientContext.zero),
                            (it.value.second ?: coefficientContext.zero),
                        )
                }
            val retmap = mutableMapOf(*mappings.toTypedArray())
            return(Chain(this.vertexComparator, this.simplexComparator, retmap))
        }

        override val zero: Chain<VertexT, CoefficientT>
            get() = Chain(vertexComparator, SimplexComparator(vertexComparator))

        override fun Chain<VertexT, CoefficientT>.unaryMinus(): Chain<VertexT, CoefficientT> =
            with(coefficientContext) {
                this@unaryMinus.mapToChain { -it }
            }

        override fun add(
            left: Chain<VertexT, CoefficientT>,
            right: Chain<VertexT, CoefficientT>,
        ): Chain<VertexT, CoefficientT> = left.zipToChain(right, coefficientContext::add)

        public operator fun CoefficientT.times(other: Chain<VertexT, CoefficientT>): Chain<VertexT, CoefficientT> =
            other.mapToChain {
                with(coefficientContext) {
                    this@times * it
                }
            }

        public operator fun CoefficientT.times(other: AbstractSimplex<VertexT>): Chain<VertexT, CoefficientT> = this@times * Chain(other)

        public fun AbstractSimplex<VertexT>.plus(other: Chain<VertexT, CoefficientT>): Chain<VertexT, CoefficientT> =
            other +
                Chain(
                    other.vertexComparator, other.simplexComparator,
                    mutableMapOf(this@plus to coefficientContext.one),
                )

        public val AbstractSimplex<VertexT>.boundary: Chain<VertexT, CoefficientT>
            get() = this@boundary.boundary(coefficientContext)

        public operator fun Chain.Companion.invoke(simplex: AbstractSimplex<VertexT>): Chain<VertexT, CoefficientT> =
            Chain(vertexComparator, SimplexComparator(vertexComparator), mutableMapOf(simplex to coefficientContext.one))

        public operator fun Chain.Companion.invoke(
            vararg mappings: Pair<AbstractSimplex<VertexT>, CoefficientT>,
        ): Chain<VertexT, CoefficientT> =
            Chain(
                vertexComparator,
                SimplexComparator(vertexComparator),
                mutableMapOf(*mappings.toList().toTypedArray()),
            )

        public operator fun Chain<VertexT, CoefficientT>.get(simplex: AbstractSimplex<VertexT>): CoefficientT =
            this@get.getOrNull(simplex) ?: coefficientContext.zero

        public val Chain<VertexT, CoefficientT>.boundary: Chain<VertexT, CoefficientT>
            get() =
                this.chainMap.map { (simplex, coeff) ->
                    coeff * simplex.boundary
                }.fold(zero, ::add)

        public val emptyChain: Chain<VertexT, CoefficientT> =
            Chain(
                vertexComparator,
                SimplexComparator(vertexComparator),
                mutableMapOf(),
            )

        // TODO Implement these parts of the Chain interface!
        public fun Chain<VertexT, CoefficientT>.isZero(): Boolean = TODO()

        public val Chain<VertexT, CoefficientT>.leadingSimplex: AbstractSimplex<VertexT>
            get() = TODO()
        public val Chain<VertexT, CoefficientT>.leadingCoefficient: CoefficientT
            get() = TODO()

        public companion object {
            public operator fun <VertexT, CoefficientT> invoke(
                vertexComparator: Comparator<VertexT>,
                coefficientContext: FieldContext<CoefficientT>,
            ): ChainContext<VertexT, CoefficientT> = ChainContext(vertexComparator, coefficientContext)

            public operator fun <VertexT : Comparable<VertexT>, CoefficientT> invoke(
                coefficientContext: FieldContext<CoefficientT>,
            ): ChainContext<VertexT, CoefficientT> = ChainContext(kotlin.comparisons.naturalOrder<VertexT>(), coefficientContext)

            public operator fun <VertexT : Comparable<VertexT>> invoke(): ChainContext<VertexT, Double> =
                ChainContext(naturalOrder(), DoubleContext)
        }
    }
