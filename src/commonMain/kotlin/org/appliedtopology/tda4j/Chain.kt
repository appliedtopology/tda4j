package org.appliedtopology.tda4j

import arrow.core.padZip
import space.kscience.kmath.operations.FieldOps
import space.kscience.kmath.operations.Group
import space.kscience.kmath.operations.NumericAlgebra
import space.kscience.kmath.operations.Ring

interface FieldContext<CoefficientT> : Ring<CoefficientT>, FieldOps<CoefficientT>, NumericAlgebra<CoefficientT>

open class ChainWith<VertexT, CoefficientT>(
    val vertexComparator: Comparator<VertexT>,
    val simplexComparator: Comparator<AbstractSimplexWith<VertexT>>,
    val chainMap: MutableMap<AbstractSimplexWith<VertexT>, CoefficientT>,
) {
    constructor(vertexComparator: Comparator<VertexT>, simplexComparator: Comparator<AbstractSimplexWith<VertexT>>) :
        this(vertexComparator, simplexComparator, mutableMapOf()) { }

    fun <V> mapToChain(transform: (CoefficientT) -> V): ChainWith<VertexT, V> =
        ChainWith(
            this.vertexComparator,
            this.simplexComparator,
            mutableMapOf(*this.chainMap.map { (k, v) -> k to transform(v) }.toTypedArray()),
        )

    override fun toString(): String {
        val retval: String =
            chainMap.keys.sortedWith(simplexComparator).map { simplex ->
                "${chainMap[simplex]}*$simplex"
            }.joinToString(" + ")
        if (retval != "") {
            return (retval)
        } else {
            return ("0")
        }
    }

    operator fun set(
        simplex: AbstractSimplexWith<VertexT>,
        value: CoefficientT,
    ) {
        chainMap[simplex] = value
    }

    operator fun get(simplex: AbstractSimplexWith<VertexT>) = chainMap[simplex]
}

class Chain<VertexT : Comparable<VertexT>, CoefficientT>(
    chainMap: MutableMap<AbstractSimplex<VertexT>, CoefficientT>,
) : ChainWith<VertexT, CoefficientT>(
        naturalOrder(),
        SimplexComparator(),
        chainMap.mapKeysTo(
            ArrayMutableSortedMapWith<AbstractSimplexWith<VertexT>, CoefficientT>(chainMap.size, SimplexComparator()),
        ) { (s, c) -> AbstractSimplexWith(naturalOrder(), s.toList()) },
    )

open class ChainContextWith<VertexT, CoefficientT>(
    vertexComparator: Comparator<VertexT>,
    val coefficientContext: FieldContext<CoefficientT>,
) :
    Group<ChainWith<VertexT, CoefficientT>>,
        SimplexContextWith<VertexT>(vertexComparator) {
    fun ChainWith<VertexT, CoefficientT>.zipToChain(
        other: ChainWith<VertexT, CoefficientT>,
        operator: (CoefficientT, CoefficientT) -> CoefficientT,
    ): ChainWith<VertexT, CoefficientT> {
        val thismap = this@zipToChain.chainMap
        val thatmap = other.chainMap
        val mappings: List<Pair<AbstractSimplexWith<VertexT>, CoefficientT>> =
            thismap.padZip(thatmap).map {
                it.key to
                    operator(
                        (it.value.first ?: coefficientContext.zero),
                        (it.value.second ?: coefficientContext.zero),
                    )
            }
        val retmap = mutableMapOf(*mappings.toTypedArray())
        return(ChainWith(this.vertexComparator, this.simplexComparator, retmap))
    }

    override val zero: ChainWith<VertexT, CoefficientT>
        get() = ChainWith(vertexComparator, SimplexComparatorWith(vertexComparator))

    override fun ChainWith<VertexT, CoefficientT>.unaryMinus(): ChainWith<VertexT, CoefficientT> =
        with(coefficientContext) {
            this@unaryMinus.mapToChain { -it }
        }

    override fun add(
        left: ChainWith<VertexT, CoefficientT>,
        right: ChainWith<VertexT, CoefficientT>,
    ): ChainWith<VertexT, CoefficientT> = left.zipToChain(right, coefficientContext::add)

    operator fun CoefficientT.times(other: ChainWith<VertexT, CoefficientT>): ChainWith<VertexT, CoefficientT> =
        other.mapToChain {
            with(coefficientContext) {
                this@times * it
            }
        }

    operator fun CoefficientT.times(other: AbstractSimplexWith<VertexT>): ChainWith<VertexT, CoefficientT> = this@times * chainOf(other)

    fun AbstractSimplexWith<VertexT>.plus(other: ChainWith<VertexT, CoefficientT>): ChainWith<VertexT, CoefficientT> =
        other +
            ChainWith(
                other.vertexComparator, other.simplexComparator,
                mutableMapOf(this@plus to coefficientContext.one),
            )

    val AbstractSimplexWith<VertexT>.boundary: ChainWith<VertexT, CoefficientT>
        get() = this@boundary.boundary(coefficientContext)

    fun chainOf(simplex: AbstractSimplexWith<VertexT>) =
        ChainWith(
            vertexComparator,
            SimplexComparatorWith(vertexComparator),
            mutableMapOf(simplex to coefficientContext.one),
        )

    fun chainOf(vararg mappings: Pair<AbstractSimplexWith<VertexT>, CoefficientT>) =
        ChainWith(
            vertexComparator,
            SimplexComparatorWith(vertexComparator),
            mutableMapOf(*mappings.toList().toTypedArray()),
        )

    val ChainWith<VertexT, CoefficientT>.boundary
        get() =
            this.chainMap.map { (simplex, coeff) ->
                coeff * simplex.boundary
            }.fold(zero, ::add)

    val emptyChain: ChainWith<VertexT, CoefficientT> =
        ChainWith(
            vertexComparator,
            SimplexComparatorWith(vertexComparator),
            mutableMapOf(),
        )

    // TODO Implement these parts of the Chain interface!
    fun ChainWith<VertexT, CoefficientT>.isZero(): Boolean = TODO()

    val ChainWith<VertexT, CoefficientT>.leadingSimplex: AbstractSimplexWith<VertexT>
        get() = TODO()
    val ChainWith<VertexT, CoefficientT>.leadingCoefficient: CoefficientT
        get() = TODO()
}

class ChainContext<VertexT : Comparable<VertexT>, CoefficientT>(
    coefficientContext: FieldContext<CoefficientT>,
) : ChainContextWith<VertexT, CoefficientT>(naturalOrder(), coefficientContext)
