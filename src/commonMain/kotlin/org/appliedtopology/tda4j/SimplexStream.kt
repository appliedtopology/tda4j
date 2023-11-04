package org.appliedtopology.tda4j

fun interface Filtered<VertexT : Comparable<VertexT>, FiltrationT : Comparable<FiltrationT>> {
    fun filtrationValue(simplex : AbstractSimplex<VertexT>) : FiltrationT?
}

abstract class SimplexStream<VertexT : Comparable<VertexT>, FiltrationT : Comparable<FiltrationT>> :
    Filtered<VertexT, FiltrationT>, Iterable<AbstractSimplex<VertexT>> {
    val comparator : Comparator<AbstractSimplex<VertexT>> =
        compareBy<AbstractSimplex<VertexT>> { filtrationValue(it) }
            .thenComparator({ a : AbstractSimplex<VertexT>, b : AbstractSimplex<VertexT> -> AbstractSimplex.compare(a,b) })

}

class ExplicitStream<VertexT : Comparable<VertexT>, FiltrationT : Comparable<FiltrationT>> :
    SimplexStream<VertexT, FiltrationT>(), MutableMap<AbstractSimplex<VertexT>, FiltrationT> {
    protected val filtrationValues : MutableMap<AbstractSimplex<VertexT>, FiltrationT> = HashMap()

    override fun filtrationValue(simplex : AbstractSimplex<VertexT>) : FiltrationT? = filtrationValues.get(simplex)

    override fun iterator(): Iterator<AbstractSimplex<VertexT>> =
        (filtrationValues.keys.toList()).sortedBy { filtrationValues.get(it) }.iterator()

    override fun equals(other: Any?): Boolean = filtrationValues.equals(other)

    override fun hashCode(): Int = filtrationValues.hashCode()

    override fun toString(): String {
        return super.toString()
    }

    override val size: Int
        get() = filtrationValues.size

    override val entries: MutableSet<MutableMap.MutableEntry<AbstractSimplex<VertexT>, FiltrationT>>
        get() = filtrationValues.entries

    override val keys: MutableSet<AbstractSimplex<VertexT>>
        get() = filtrationValues.keys

    override val values: MutableCollection<FiltrationT>
        get() = filtrationValues.values

    override fun clear() = filtrationValues.clear()

    override fun containsKey(key: AbstractSimplex<VertexT>): Boolean = filtrationValues.containsKey(key)

    override fun containsValue(value: FiltrationT): Boolean = filtrationValues.containsValue(value)

    override fun get(key: AbstractSimplex<VertexT>): FiltrationT? = filtrationValues.get(key)

    override fun isEmpty(): Boolean = filtrationValues.isEmpty()

    override fun put(key: AbstractSimplex<VertexT>, value: FiltrationT): FiltrationT? = filtrationValues.put(key, value)

    override fun putAll(from: Map<out AbstractSimplex<VertexT>, FiltrationT>) = filtrationValues.putAll(from)

    override fun remove(key: AbstractSimplex<VertexT>): FiltrationT? = filtrationValues.remove(key)
}