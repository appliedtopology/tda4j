package org.appliedtopology.tda4j

open class ArrayMutableSortedSet<T : Comparable<T>>(capacity: Int) : MutableSet<T> {
    constructor() : this(8)

    @Suppress("ktlint:standard:property-naming")
    protected val _set: ArrayList<T> = ArrayList(capacity)

    override fun add(element: T): Boolean {
        val index = _set.binarySearch(element)
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

    override fun contains(element: T): Boolean = _set.binarySearch(element) >= 0

    override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

    override fun iterator(): MutableIterator<T> = _set.iterator()

    override fun retainAll(elements: Collection<T>): Boolean = _set.retainAll(elements)

    override fun removeAll(elements: Collection<T>): Boolean = _set.removeAll(elements)

    override fun remove(element: T): Boolean {
        val index = _set.binarySearch(element)
        if (index < 0) {
            return false
        } else {
            _set.removeAt(index)
            return true
        }
    }

    override fun equals(other: Any?): Boolean {
        return _set.equals(other)
    }

    override fun hashCode(): Int {
        return _set.hashCode()
    }
}

typealias MutableSortedSet<V> = ArrayMutableSortedSet<V>

open class MutableBitSet(var capacity: Int) : MutableSet<Int> {
    val bitStorage: IntArray = IntArray(capacity.floorDiv(Int.SIZE_BITS))

    fun addressOf(element: Int): Pair<Int, Int> = Pair(element.floorDiv(Int.SIZE_BITS), element.mod(Int.SIZE_BITS))

    @Suppress("ktlint:standard:property-naming")
    var _size = 0

    override fun add(element: Int): Boolean {
        val (word, offset) = addressOf(element)
        if ((bitStorage[word] and (1 shl offset)) != 0) {
            return false
        } else {
            bitStorage[word] = bitStorage[word] or (1 shl offset)
            _size += 1
            return true
        }
    }

    fun allMasks(elements: Collection<Int>): Map<Int, Int> =
        elements
            .map { addressOf(it) }
            .groupingBy { it.first }
            .fold(0) { accumulator, element -> accumulator or (1 shl element.second) }

    override fun addAll(elements: Collection<Int>): Boolean {
        val edits = allMasks(elements)
        val editBits = edits.map { (word, mask) -> (bitStorage[word].inv() and mask) }
        val edited = editBits.any { mask -> mask != 0 }
        edits.forEach { (word, mask) -> bitStorage[word] = bitStorage[word] or mask }
        _size += editBits.map { it.countOneBits() }.sum()
        return edited
    }

    override val size: Int
        get() = _size

    override fun clear() {
        _size = 0
        bitStorage.fill(0)
    }

    override fun isEmpty(): Boolean = size == 0

    override fun containsAll(elements: Collection<Int>): Boolean =
        allMasks(elements)
            .any { (word, mask) -> (bitStorage[word].inv() and mask) != 0 }

    override fun contains(element: Int): Boolean {
        val (word, offset) = addressOf(element)
        return (bitStorage[word] and (1 shl offset)) != 0
    }

    override fun iterator(): MutableIterator<Int> {
        TODO("Not yet implemented")
    }

    override fun retainAll(elements: Collection<Int>): Boolean {
        TODO("Not yet implemented")
    }

    override fun removeAll(elements: Collection<Int>): Boolean {
        TODO("Not yet implemented")
    }

    override fun remove(element: Int): Boolean {
        TODO("Not yet implemented")
    }
}

class BitSet(val capacity: Int) : Set<Int> {
    @Suppress("ktlint:standard:property-naming")
    protected val _set = MutableBitSet(capacity)
    override val size: Int
        get() = _set.size

    override fun isEmpty(): Boolean = _set.isEmpty()

    override fun iterator(): Iterator<Int> = _set.iterator()

    override fun containsAll(elements: Collection<Int>): Boolean = _set.containsAll(elements)

    override fun contains(element: Int): Boolean = _set.contains(element)
}
