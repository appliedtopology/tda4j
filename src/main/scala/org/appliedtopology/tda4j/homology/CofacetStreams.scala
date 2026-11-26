package org.appliedtopology.tda4j

import ParallelPackedCohomology.Space

/** The neighbours of every vertex in the order of the ranks of their edges (the order of the edges, shortest first), as
  * the walk of a [[CofacetStream]] needs them: `start(v) until start(v + 1)` index `vertex`, and `rank` (only when the
  * ranks are not in a dense table) holds each entry's edge rank.
  */
// Restricted (here and below): a public top-level name joins every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] final class RankedNeighbours(val start: Array[Int], val vertex: Array[Int], val rank: Array[Int])

private[tda4j] object RankedNeighbours:
  def of(space: Space): RankedNeighbours =
    val edges = space.edgeA.length
    val start = new Array[Int](space.n + 1)
    var r = 0
    while r < edges do
      start(space.edgeA(r) + 1) += 1
      start(space.edgeB(r) + 1) += 1
      r += 1
    var v = 0
    while v < space.n do
      start(v + 1) += start(v)
      v += 1
    val at = java.util.Arrays.copyOf(start, space.n)
    val vertex = new Array[Int](2 * edges)
    val rank = if space.sparseRanks then new Array[Int](2 * edges) else null
    r = 0
    while r < edges do
      val (a, b) = (space.edgeA(r), space.edgeB(r))
      vertex(at(a)) = b
      vertex(at(b)) = a
      if rank != null then
        rank(at(a)) = r
        rank(at(b)) = r
      at(a) += 1
      at(b) += 1
      r += 1
    RankedNeighbours(start, vertex, rank)

/** The cofacets of one column of a coboundary matrix, taken one at a time in the filtration order, times a coefficient,
  * from a key `startKey` on (exclusive).
  *
  * A cofacet `sigma + u` is completed by the last of the edges from `u` to `sigma`, which gives its block. The cofacets
  * in the column's own block (`u` in the lune of `sigma`) come first, ordered by `u`; the others follow, one to a
  * block, in the order of the edge that completes them: the sorted neighbour lists of the vertices of `sigma` are
  * walked together and a cofacet is taken at its last edge, so no diameter is computed and no tie resolved. The lune is
  * computed when the stream is first looked at beyond a bound (the start of its own block), not when it is made, and
  * not at all for a stream that starts in a later block: a coboundary that is added to a working column starts after
  * the pivot, since the entries up to the pivot cancel.
  */
private[tda4j] final class CofacetStream(
  space: Space,
  val vs: Array[Int],
  blockSigma: Int,
  factor: Int,
  startKey: Long
):
  import CofacetStream.*
  private val k = vs.length
  private val p = space.p
  private val startBlock: Int = if startKey == Long.MinValue then -1 else space.block(startKey)
  // The index of `sigma + u`, with `u` after `pp` of the vertices of `sigma`: lower(pp) + C(u, pp + 1) + upper(pp).
  private val lower = new Array[Long](k + 1)
  private val upper = new Array[Long](k + 1)
  private var inKeys: Array[Long] = null
  private var inCoefs: Array[Int] = null
  private var inAt = 0
  private var pos: Array[Int] = null
  private var rank: Array[Int] = null
  private var neighbours: RankedNeighbours = null

  /** The state: `Bound` (the head is a lower bound, the lune is not computed), `InStep`, `Out` or `Done`. */
  var phase: Int = Bound

  /** The key of the current cofacet, or, in the `Bound` phase, a key no larger than any of the stream's. */
  var headKey: Long = 0L

  /** The coefficient of the current cofacet in the working column (the sign times the factor, mod p). */
  var headCoef: Int = 0

  {
    var i = 0
    while i < k do
      lower(i + 1) = lower(i) + space.binom(i + 1)(vs(i))
      i += 1
    i = k - 1
    while i >= 0 do
      upper(i) = upper(i + 1) + space.binom(i + 2)(vs(i))
      i -= 1
    if startBlock <= blockSigma then headKey = blockSigma.toLong << space.bits
    else startOut()
  }

  def alive: Boolean = phase != Done
  def isBound: Boolean = phase == Bound

  // The key and coefficient of `sigma + u`, `u` after `pp` vertices, in a block.
  private inline def keyOf(block: Int, u: Int, pp: Int): Long =
    space.key(block, lower(pp) + space.binom(pp + 1)(u) + upper(pp))
  private inline def coefOf(pp: Int): Int = if (pp & 1) == 0 then factor else (p - factor) % p

  /** Computes the lune and moves to the first cofacet of the column's own block after the start, or beyond them. */
  def expand(): Unit =
    val r0 = blockSigma - 1
    val dense = !space.sparseRanks
    val table = space.rank
    val n = space.n
    // Keys ascend as `u` descends for the larger index first, and as `u` ascends for the smaller.
    val up = space.smallerFirst
    var keys = new Array[Long](16)
    var coefs = new Array[Int](16)
    var count = 0
    var u = if up then 0 else n - 1
    val step = if up then 1 else -1
    var pp = if up then 0 else k
    while u >= 0 && u < n do
      // `pp`: the number of vertices of sigma below `u`
      if up then while pp < k && vs(pp) < u do pp += 1
      else while pp > 0 && vs(pp - 1) > u do pp -= 1
      if !(pp < k && vs(pp) == u) && !(pp > 0 && vs(pp - 1) == u) then
        var ok = true
        var i = 0
        while ok && i < k do
          val q = if dense then table(vs(i) * n + u) else space.rankOf(vs(i), u)
          if q < 0 || q > r0 then ok = false
          i += 1
        if ok then
          val key = keyOf(blockSigma, u, pp)
          if key > startKey then
            if count == keys.length then
              keys = java.util.Arrays.copyOf(keys, 2 * count)
              coefs = java.util.Arrays.copyOf(coefs, 2 * count)
            keys(count) = key
            coefs(count) = coefOf(pp)
            count += 1
      u += step
    if count == 0 then startOut()
    else
      inKeys = keys
      inCoefs = coefs
      inAt = 0
      phase = InStep
      headKey = keys(0)
      headCoef = coefs(0)
      // `inKeys` is read up to `count`: keep the length in the array
      if count < keys.length then
        inKeys = java.util.Arrays.copyOf(keys, count)
        inCoefs = java.util.Arrays.copyOf(coefs, count)

  /** Moves to the next cofacet; `alive` tells whether there is one. */
  def advance(): Unit = phase match
    case InStep =>
      inAt += 1
      if inAt < inKeys.length then
        headKey = inKeys(inAt)
        headCoef = inCoefs(inAt)
      else
        inKeys = null
        inCoefs = null
        startOut()
    case Out   => nextOut()
    case Bound =>
      throw IllegalStateException("a stream is advanced before its lune is computed")
    case _ => ()

  // The walk over the neighbour lists, from the first edge that can complete a cofacet after the start.
  private def startOut(): Unit =
    neighbours = space.rankedNeighbours
    pos = new Array[Int](k)
    rank = new Array[Int](k)
    // an out-of-step cofacet is completed by an edge of rank at least `blockSigma` (the column's own edge has rank
    // blockSigma - 1), and its block, that rank + 1, must not precede the start's
    val minRank = math.max(blockSigma, startBlock - 1)
    val dense = !space.sparseRanks
    val n = space.n
    var i = 0
    while i < k do
      val v = vs(i)
      var lo = neighbours.start(v)
      var hi = neighbours.start(v + 1)
      while lo < hi do
        val mid = (lo + hi) >>> 1
        val q = if dense then space.rank(v * n + neighbours.vertex(mid)) else neighbours.rank(mid)
        if q < minRank then lo = mid + 1 else hi = mid
      pos(i) = lo
      rank(i) = rankAt(i)
      i += 1
    phase = Out
    nextOut()

  private def rankAt(i: Int): Int =
    val v = vs(i)
    if pos(i) >= neighbours.start(v + 1) then Int.MaxValue
    else if space.sparseRanks then neighbours.rank(pos(i))
    else space.rank(v * space.n + neighbours.vertex(pos(i)))

  private def nextOut(): Unit =
    val dense = !space.sparseRanks
    val table = space.rank
    val n = space.n
    var found = false
    while !found do
      var best = -1
      var bestRank = Int.MaxValue
      var i = 0
      while i < k do
        if rank(i) < bestRank then
          best = i
          bestRank = rank(i)
        i += 1
      if best < 0 then
        phase = Done
        found = true
      else
        val u = neighbours.vertex(pos(best))
        pos(best) += 1
        rank(best) = rankAt(best)
        var member = false
        i = 0
        while i < k do
          if vs(i) == u then member = true
          i += 1
        if !member then
          // the cofacet is completed here when every other edge to `u` is earlier
          var ok = true
          i = 0
          while ok && i < k do
            if i != best then
              val q = if dense then table(vs(i) * n + u) else space.rankOf(vs(i), u)
              if q < 0 || q > bestRank then ok = false
            i += 1
          if ok then
            var pp = 0
            while pp < k && vs(pp) < u do pp += 1
            val key = keyOf(bestRank + 1, u, pp)
            if key > startKey then
              headKey = key
              headCoef = coefOf(pp)
              found = true

private[tda4j] object CofacetStream:
  inline val Bound = 0
  inline val InStep = 1
  inline val Out = 2
  inline val Done = 3

/** A working column as a set of [[CofacetStream]]s in a binary heap by the key of their current cofacets: the column is
  * their sum, and its pivot is the smallest key whose coefficients do not cancel. Nothing is generated beyond the
  * entries the reduction asks for.
  */
private[tda4j] final class StreamColumn(p: Int):
  private var keys = new Array[Long](16)
  private var items = new Array[CofacetStream](16)
  private var stack = new Array[Int](16)
  var size = 0

  /** The coefficient of the pivot `pivot()` last returned. */
  var pivotCoefficient = 0

  def clear(): Unit =
    var i = 0
    while i < size do
      items(i) = null
      i += 1
    size = 0

  // A bound comes before a cofacet of the same key, so that the lune is computed before a key is summed.
  private inline def before(i: Int, j: Int): Boolean =
    keys(i) < keys(j) || keys(i) == keys(j) && items(i).isBound && !items(j).isBound

  def add(stream: CofacetStream): Unit =
    if stream.alive then
      if size == keys.length then
        keys = java.util.Arrays.copyOf(keys, 2 * size)
        items = java.util.Arrays.copyOf(items, 2 * size)
      var i = size
      size += 1
      keys(i) = stream.headKey
      items(i) = stream
      while i > 0 && before(i, (i - 1) / 2) do
        swap(i, (i - 1) / 2)
        i = (i - 1) / 2

  private def swap(i: Int, j: Int): Unit =
    val k = keys(i)
    keys(i) = keys(j)
    keys(j) = k
    val s = items(i)
    items(i) = items(j)
    items(j) = s

  private def pop(): CofacetStream =
    val top = items(0)
    size -= 1
    if size > 0 then
      keys(0) = keys(size)
      items(0) = items(size)
      items(size) = null
      var i = 0
      var go = true
      while go do
        val l = 2 * i + 1
        if l >= size then go = false
        else
          val m = if l + 1 < size && before(l + 1, l) then l + 1 else l
          if before(m, i) then
            swap(i, m)
            i = m
          else go = false
    else items(0) = null
    top

  // Moves every stream whose current cofacet has this key to its next one.
  private def advanceKey(key: Long): Unit =
    while size > 0 && keys(0) == key do
      val s = pop()
      s.advance()
      add(s)

  /** The smallest key with a non-zero total coefficient, left in the column; -1 for the zero column. */
  def pivot(): Long =
    var result = Long.MinValue
    while result == Long.MinValue do
      if size == 0 then result = -1L
      else if items(0).isBound then
        val s = pop()
        s.expand()
        add(s)
      else
        val key = keys(0)
        // the total over the streams at this key: the heap nodes with it form a subtree of the root
        var total = 0L
        var top = 1
        stack(0) = 0
        while top > 0 do
          top -= 1
          val i = stack(top)
          total += items(i).headCoef
          val l = 2 * i + 1
          if top + 2 > stack.length then stack = java.util.Arrays.copyOf(stack, 2 * stack.length)
          if l < size && keys(l) == key then
            stack(top) = l
            top += 1
          if l + 1 < size && keys(l + 1) == key then
            stack(top) = l + 1
            top += 1
        val sum = (total % p).toInt
        if sum != 0 then
          pivotCoefficient = sum
          result = key
        else advanceKey(key)
    result

  /** Moves every stream at the pivot, which `pivot()` returned, past it. */
  def cancel(key: Long): Unit = advanceKey(key)

  /** The streams of the column (for a column that is carried on elsewhere). */
  def streams: Array[CofacetStream] = java.util.Arrays.copyOf(items, size)

  def load(streams: Array[CofacetStream]): Unit =
    clear()
    var i = 0
    while i < streams.length do
      add(streams(i))
      i += 1

/** Sums of `factor * value` over keys mod p, in open addressing over primitive arrays: the reduction column `V` of a
  * column, from the columns it added.
  */
private[tda4j] final class ModMap(p: Int):
  private var keys = Array.fill(64)(-1L)
  private var values = new Array[Int](64)
  private var count = 0

  def clear(): Unit =
    java.util.Arrays.fill(keys, -1L)
    count = 0

  private def slot(key: Long, ks: Array[Long]): Int =
    val mask = ks.length - 1
    val h = (key * 0x9e3779b97f4a7c15L) ^ (key >>> 29)
    var s = (h ^ (h >>> 32)).toInt & mask
    while ks(s) != -1L && ks(s) != key do s = (s + 1) & mask
    s

  def add(key: Long, delta: Int): Unit =
    if 4 * (count + 1) > 3 * keys.length then grow()
    val s = slot(key, keys)
    if keys(s) == -1L then
      keys(s) = key
      values(s) = delta % p
      count += 1
    else values(s) = (values(s) + delta) % p

  private def grow(): Unit =
    val (oldKeys, oldValues) = (keys, values)
    keys = Array.fill(2 * oldKeys.length)(-1L)
    values = new Array[Int](2 * oldKeys.length)
    var i = 0
    while i < oldKeys.length do
      if oldKeys(i) != -1L then
        val s = slot(oldKeys(i), keys)
        keys(s) = oldKeys(i)
        values(s) = oldValues(i)
      i += 1

  /** The entries with a non-zero value: keys and values. */
  def nonZero(): (Array[Long], Array[Int]) =
    var m = 0
    var i = 0
    while i < keys.length do
      if keys(i) != -1L && values(i) != 0 then m += 1
      i += 1
    val ks = new Array[Long](m)
    val vs = new Array[Int](m)
    m = 0
    i = 0
    while i < keys.length do
      if keys(i) != -1L && values(i) != 0 then
        ks(m) = keys(i)
        vs(m) = values(i)
        m += 1
      i += 1
    (ks, vs)
