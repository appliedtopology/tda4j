package org.appliedtopology.tda4j

open class ArrayMutableSortedSet<T>
    protected constructor(capacity: Int = 8, val comparator: Comparator<T>) : MutableSet<T> {
        @Suppress("ktlint:standard:property-naming")
        internal val _set: ArrayList<T> = ArrayList(capacity)

        fun index(element: T): Int = _set.binarySearch(element, comparator)

        override fun add(element: T): Boolean {
            val index = index(element)
            if (index >= 0) {
                return false
            } else {
                _set.add((-index) - 1, element)
                return true
            }
        }

        override fun addAll(elements: Collection<T>): Boolean {
            return elements.fold(false) { r, t -> r or add(t) }
        }

        override val size: Int
            get() = _set.size

        override fun clear() = _set.clear()

        override fun isEmpty(): Boolean = _set.isEmpty()

        override fun contains(element: T): Boolean = index(element) >= 0

        override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

        override fun iterator(): MutableIterator<T> = _set.iterator()

        override fun retainAll(elements: Collection<T>): Boolean = _set.retainAll(elements)

        override fun removeAll(elements: Collection<T>): Boolean = _set.removeAll(elements)

        override fun equals(other: Any?): Boolean {
            return _set.equals(other)
        }

        override fun hashCode(): Int {
            return _set.hashCode()
        }

        override fun toString(): String {
            return "ArrayMutableSortedSet(${_set.joinToString()})"
        }

        override fun remove(element: T): Boolean {
            val index = index(element)
            if (index < 0) {
                return false
            } else {
                _set.removeAt(index)
                return true
            }
        }

        public companion object {
            public operator fun <T> invoke(
                capacity: Int = 8,
                comparator: Comparator<T>,
            ): ArrayMutableSortedSet<T> = ArrayMutableSortedSet(capacity, comparator)

            public operator fun <T : Comparable<T>> invoke(capacity: Int = 8): ArrayMutableSortedSet<T> =
                ArrayMutableSortedSet(capacity, naturalOrder())
        }
    }

typealias MutableSortedSet<V> = ArrayMutableSortedSet<V>

open class ArrayMutableSortedMap<K, V> protected constructor(
    capacity: Int = 8,
    val comparator: Comparator<K>,
    val defaultValue: V? = null,
) : MutableMap<K, V> {
    internal val _keys: ArrayMutableSortedSet<K> = ArrayMutableSortedSet(capacity, comparator)
    internal val _values: ArrayList<V> = ArrayList(capacity)

    override fun containsKey(key: K): Boolean = _keys.contains(key)

    override fun containsValue(value: V): Boolean = _values.contains(value)

    override fun get(key: K): V? {
        val idx = _keys.index(key)
        return if (idx >= 0) _values[idx] else null
    }

    public class PairEntry<K, V>(override val key: K, override var value: V) : MutableMap.MutableEntry<K, V> {
        override fun setValue(newValue: V): V {
            value = newValue
            return value
        }

        override fun toString(): String {
            return "PairEntry($key, $value)"
        }
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = _keys.zip(_values).map { (k, v) -> PairEntry(k, v) }.toMutableSet()
    override val keys: MutableSet<K>
        get() = _keys.toMutableSet()

    override val size: Int
        get() = _keys.size

    override val values: MutableCollection<V>
        get() = _values.toMutableList()

    override fun clear() {
        _keys.clear()
        _values.clear()
    }

    override fun isEmpty(): Boolean = _keys.isEmpty()

    fun ordinalKey(index: Int): K? = _keys._set.getOrNull(index)

    fun ordinalValue(index: Int): V? = _values.getOrElse(index) { i -> defaultValue }

    fun ordinalItem(index: Int): MutableMap.MutableEntry<K, V>? {
        val ok = ordinalKey(index)
        val ov = ordinalValue(index)
        if (ok == null || ov == null) {
            return null
        } else {
            return PairEntry(ordinalKey(index)!!, ordinalValue(index)!!)
        }
    }

    val headKey: K?
        get() = ordinalKey(0)
    val headValue: V?
        get() = ordinalValue(0)
    val headItem: MutableMap.MutableEntry<K, V>?
        get() = ordinalItem(0)

    fun replaceAllValues(transform: (V) -> V) {
        for (idx in _values.indices)
            _values[idx] = transform(_values[idx])
    }

    override fun remove(key: K): V? {
        val idx = _keys.index(key)
        if (idx >= 0) {
            val retval = _values[idx]
            _keys._set.removeAt(idx)
            _values.removeAt(idx)
            return retval
        } else {
            return null
        }
    }

    override fun putAll(from: Map<out K, V>) {
        from.forEach { (k, v) -> put(k, v) }
    }

    override fun put(
        key: K,
        value: V,
    ): V? {
        val idx = _keys.index(key)
        if (idx >= 0) {
            val retval = _values[idx]
            _values[idx] = value
            return retval
        } else {
            _keys.add(key)
            _values.add((-idx) - 1, value)
            return null
        }
    }

    override fun toString(): String {
        return "ArrayMutableSortedMap(${entries.joinToString()})"
    }

    companion object {
        operator fun <K, V> invoke(
            capacity: Int = 8,
            comparator: Comparator<K>,
            defaultValue: V? = null,
        ): ArrayMutableSortedMap<K, V> = ArrayMutableSortedMap(capacity, comparator, defaultValue)

        operator fun <K : Comparable<K>, V> invoke(
            capacity: Int = 8,
            defaultValue: V? = null,
        ): ArrayMutableSortedMap<K, V> = ArrayMutableSortedMap(capacity, naturalOrder(), defaultValue)
    }
}
