package org.appliedtopology.tda4j

open class ArrayMutableSortedSetBaseWith<T>(capacity: Int = 8, val comparator: Comparator<T>) {
    @Suppress("ktlint:standard:property-naming")
    protected val _set: ArrayList<T> = ArrayList(capacity)

    fun add(element: T): Boolean {
        val index = _set.binarySearch(element, comparator)
        if (index >= 0) {
            return false
        } else {
            _set.add((-index) - 1, element)
            return true
        }
    }

    fun addAll(elements: Collection<T>): Boolean {
        return elements.fold(false) { r, t -> r or add(t) }
    }

    val size: Int
        get() = _set.size

    fun clear() = _set.clear()

    fun isEmpty(): Boolean = _set.isEmpty()

    fun contains(element: T): Boolean = _set.binarySearch(element, comparator) >= 0

    fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

    fun iterator(): MutableIterator<T> = _set.iterator()

    fun retainAll(elements: Collection<T>): Boolean = _set.retainAll(elements)

    fun removeAll(elements: Collection<T>): Boolean = _set.removeAll(elements)

    override fun equals(other: Any?): Boolean {
        return _set.equals(other)
    }

    override fun hashCode(): Int {
        return _set.hashCode()
    }
}

open class ArrayMutableSortedSetBase<T : Comparable<T>>(capacity: Int = 8) :
    ArrayMutableSortedSetBaseWith<T>(capacity, naturalOrder())

open class ArrayMutableSortedSetWith<T>(
    capacity: Int = 8,
    comparator: Comparator<T>,
) :
    ArrayMutableSortedSetBaseWith<T>(capacity, comparator), MutableSet<T> {
    override fun remove(element: T): Boolean {
        val index = _set.binarySearch(element, comparator)
        if (index < 0) {
            return false
        } else {
            _set.removeAt(index)
            return true
        }
    }

    override fun toString(): String {
        return "ArrayMutableSortedSet(${_set.joinToString()})"
    }
}

open class ArrayMutableSortedSet<T : Comparable<T>>(capacity: Int = 8) :
    ArrayMutableSortedSetWith<T>(capacity, naturalOrder())

typealias MutableSortedSetWith<V> = ArrayMutableSortedSetWith<V>
typealias MutableSortedSet<V> = ArrayMutableSortedSet<V>

open class ArrayMutableSortedMapWith<K, V>(
    capacity: Int = 8,
    comparator: Comparator<K>,
    val defaultValue: V? = null,
) :
    ArrayMutableSortedSetBaseWith<K>(capacity, comparator), MutableMap<K, V> {
    protected val _values: ArrayList<V> = ArrayList(capacity)

    override fun containsKey(key: K): Boolean = _set.binarySearch(key, comparator) >= 0

    override fun containsValue(value: V): Boolean = _values.contains(value)

    override fun get(key: K): V? {
        val idx = _set.binarySearch(key, comparator)
        return if (idx >= 0) _values[idx] else null
    }

    class PairEntry<K, V>(override val key: K, override var value: V) : MutableMap.MutableEntry<K, V> {
        override fun setValue(newValue: V): V {
            value = newValue
            return value
        }

        override fun toString(): String {
            return "PairEntry($key, $value)"
        }
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = _set.zip(_values).map { (k, v) -> PairEntry(k, v) }.toMutableSet()
    override val keys: MutableSet<K>
        get() = _set.toMutableSet()

    override val values: MutableCollection<V>
        get() = _values.toMutableList()

    fun ordinalKey(index: Int): K? = _set.getOrNull(index)

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
        val idx = _set.binarySearch(key, comparator)
        if (idx >= 0) {
            val retval = _values[idx]
            _set.removeAt(idx)
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
        val idx = _set.binarySearch(key, comparator)
        if (idx >= 0) {
            val retval = _values[idx]
            _values[idx] = value
            return retval
        } else {
            _set.add((-idx) - 1, key)
            _values.add((-idx) - 1, value)
            return null
        }
    }

    override fun toString(): String {
        return "ArrayMutableSortedMap(${entries.joinToString()})"
    }
}

open class ArrayMutableSortedMap<K : Comparable<K>, V>(
    capacity: Int = 8,
    defaultValue: V? = null,
) :
    ArrayMutableSortedMapWith<K, V>(capacity, naturalOrder<K>(), defaultValue)
