package org.appliedtopology.tda4j

public fun interface Filtered<VertexT : Comparable<VertexT>, FiltrationT : Comparable<FiltrationT>> {
    public fun filtrationValue(simplex: AbstractSimplex<VertexT>): FiltrationT?
}

public abstract class SimplexStream<VertexT : Comparable<VertexT>, FiltrationT : Comparable<FiltrationT>> :
    Filtered<VertexT, FiltrationT>, Iterable<AbstractSimplex<VertexT>> {
    public open val comparator: Comparator<AbstractSimplex<VertexT>> =
        compareBy<AbstractSimplex<VertexT>> { filtrationValue(it) }
            .thenComparator(SimplexComparator<VertexT>()::compare)
}

public open class ExplicitStream<VertexT : Comparable<VertexT>, FiltrationT : Comparable<FiltrationT>> :
    SimplexStream<VertexT, FiltrationT>(), MutableMap<AbstractSimplex<VertexT>, FiltrationT> {
    internal val filtrationValues: MutableMap<AbstractSimplex<VertexT>, FiltrationT> = HashMap()

    override fun filtrationValue(simplex: AbstractSimplex<VertexT>): FiltrationT? = filtrationValues[simplex]

    override fun iterator(): Iterator<AbstractSimplex<VertexT>> =
        (filtrationValues.keys.toList()).sortedBy { filtrationValues[it] }.iterator()

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

    override fun clear(): Unit = filtrationValues.clear()

    override fun containsKey(key: AbstractSimplex<VertexT>): Boolean = filtrationValues.containsKey(key)

    override fun containsValue(value: FiltrationT): Boolean = filtrationValues.containsValue(value)

    override fun get(key: AbstractSimplex<VertexT>): FiltrationT? = filtrationValues[key]

    override fun isEmpty(): Boolean = filtrationValues.isEmpty()

    override fun put(
        key: AbstractSimplex<VertexT>,
        value: FiltrationT,
    ): FiltrationT? = filtrationValues.put(key, value)

    override fun putAll(from: Map<out AbstractSimplex<VertexT>, FiltrationT>): Unit = filtrationValues.putAll(from)

    override fun remove(key: AbstractSimplex<VertexT>): FiltrationT? = filtrationValues.remove(key)
}
