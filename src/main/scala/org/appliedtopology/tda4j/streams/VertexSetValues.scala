package org.appliedtopology.tda4j

/** A table from a set of vertices (ascending, in an `Int` array) to a value that is never `NaN`, for a filtration whose
  * value of a simplex depends on the values already computed for its faces ([[CechFiltration]]): no [[Simplex]] is
  * built to look a face up, and the keys are the vertices themselves, stored flat, so a lookup compares at most a few
  * ints.
  *
  * Safe to fill from several threads: the table is split by hash into shards, each guarded by its own lock (Scala.js
  * has one thread and no cost for it). A value, once stored, is never replaced.
  */
// Restricted: a public top-level `VertexSetValues` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] final class VertexSetValues:
  import VertexSetValues.*

  // `byWidth(w)` holds the sets of `w` vertices; grown (copied) under the lock, read without it.
  @volatile private var byWidth: Array[Array[Shard]] = new Array[Array[Shard]](8)

  /** The value stored for the set `vertices(from until from + width)`, `NaN` when there is none. */
  def get(vertices: Array[Int], from: Int, width: Int): Double =
    val tables = byWidth
    val shards = if width < tables.length then tables(width) else null
    if shards == null then Double.NaN
    else
      val hash = hashOf(vertices, from, width)
      shards(shardOf(hash)).get(vertices, from, hash)

  /** Stores `value` for the set `vertices(from until from + width)` unless it already has one; returns the value the
    * set has afterwards.
    */
  def putIfAbsent(vertices: Array[Int], from: Int, width: Int, value: Double): Double =
    val hash = hashOf(vertices, from, width)
    shardsOf(width)(shardOf(hash)).putIfAbsent(vertices, from, hash, value)

  /** The number of sets stored. */
  def size: Long =
    var total = 0L
    var w = 0
    while w < byWidth.length do
      val shards = byWidth(w)
      if shards != null then shards.foreach(shard => total += shard.size)
      w += 1
    total

  private def shardsOf(width: Int): Array[Shard] =
    val current = byWidth
    if width < current.length && current(width) != null then current(width)
    else
      this.synchronized {
        if width >= byWidth.length then
          byWidth = java.util.Arrays.copyOf(byWidth, math.max(width + 1, 2 * byWidth.length))
        if byWidth(width) == null then byWidth(width) = Array.fill(ShardCount)(new Shard(width))
        byWidth(width)
      }

object VertexSetValues:
  private val ShardCount = 64
  private def hashOf(vertices: Array[Int], from: Int, width: Int): Int =
    var h = width
    var i = from
    while i < from + width do
      h = (h + vertices(i)) * 0x9e3779b1
      h ^= h >>> 15
      i += 1
    h

  // The shard from the high bits, the slot in it from the low ones.
  private def shardOf(hash: Int): Int = (hash >>> 26) & (ShardCount - 1)

  /** An open-addressing table for the sets of one width; an empty slot holds `NaN`. */
  private final class Shard(width: Int):
    private var keys = new Array[Int](InitialSlots * width)
    private var values = Array.fill(InitialSlots)(Double.NaN)
    private var count = 0

    def size: Int = synchronized(count)

    def get(vertices: Array[Int], from: Int, hash: Int): Double = synchronized {
      values(find(vertices, from, hash))
    }

    def putIfAbsent(vertices: Array[Int], from: Int, hash: Int, value: Double): Double = synchronized {
      val slot = find(vertices, from, hash)
      if !values(slot).isNaN then values(slot)
      else
        System.arraycopy(vertices, from, keys, slot * width, width)
        values(slot) = value
        count += 1
        if 2 * count > values.length then grow()
        value
    }

    // The slot holding the set, or the empty one where it would go.
    private def find(vertices: Array[Int], from: Int, hash: Int): Int =
      val mask = values.length - 1
      var slot = hash & mask
      while !values(slot).isNaN && !sameKey(slot, vertices, from) do slot = (slot + 1) & mask
      slot

    private def sameKey(slot: Int, vertices: Array[Int], from: Int): Boolean =
      var i = 0
      while i < width && keys(slot * width + i) == vertices(from + i) do i += 1
      i == width

    private def grow(): Unit =
      val (oldKeys, oldValues) = (keys, values)
      keys = new Array[Int](2 * oldValues.length * width)
      values = Array.fill(2 * oldValues.length)(Double.NaN)
      var slot = 0
      while slot < oldValues.length do
        if !oldValues(slot).isNaN then
          val target = find(oldKeys, slot * width, hashOf(oldKeys, slot * width, width))
          System.arraycopy(oldKeys, slot * width, keys, target * width, width)
          values(target) = oldValues(slot)
        slot += 1

  private val InitialSlots = 16
