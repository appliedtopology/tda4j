package org.appliedtopology.tda4j

import java.util.concurrent.{Callable, ExecutorService, Executors}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.util.Random

/** An experiment (JVM only; #192, #194): Vietoris-Rips persistent cohomology over F_p on implicit coboundaries, as the
  * packed Ripser engine computes it (clearing, apparent pairs, a heap working column, cofacets enumerated on the fly),
  * with each degree's columns cut into units of whole edge blocks and the units reduced in parallel. A column whose
  * pivot leaves its unit is deferred: the deferred columns go on in parallel while their pivots are owned by the units'
  * pairs (phase 2a), then one by one in the sequential order (phase 2b). The pairs, and the reduction log of every
  * column, are those of one sequential reduction in the same order.
  *
  * A simplex is one `Long` key: its block (1 + the rank of its first longest edge, edges ranked by (length, smaller
  * end, larger end); 0 for a vertex) above `mask - index` (`index` its combinatorial index; a larger index is older, as
  * in the packed engine). Within one dimension, ascending keys are the filtration order, oldest first. When the block
  * and the index do not fit in one `Long` together (`Space.wide`), a key is two words: `mask - index`, which still
  * names the simplex within its dimension, and the block beside it in every array, heap and column that orders keys;
  * the order is then by block, then key. Coefficients are `Int`s mod `p`.
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.ParallelPackedCohomology <dataset> <n> <maxDim> <threshold|mer> <threads> [units per thread] [trials] [seed] [p]
  * }}}
  * `threads` 1 with 1 unit per thread is the sequential reduction; set the JVM's
  * `-Djava.util.concurrent.ForkJoinPool.common.parallelism` to `threads`, which the sorts use.
  */
object ParallelPackedCohomology:

  /** The largest prime a run can use: coefficients are `Int`s, and `(p - 1)^2 + p` must not overflow. */
  val MaxPrime: Int = 46000

  /** The edges within the threshold ranked, and the key encoding. */
  open class Space(
    val n: Int,
    distance: (Int, Int) => Double,
    val threshold: Double,
    val maxDim: Int,
    val p: Int,
    // Find apparent pairs Ripser's way (every facet's block from its pairs) instead of from the block's edge.
    val ripserApparent: Boolean = false,
    // Two-word keys even where one Long would do: for tests of the wide layout.
    forceWide: Boolean = false
  ):
    val rank: Array[Int] = Array.fill(n * n)(-1)
    private val (ea, eb, el) =
      val as = mutable.ArrayBuilder.ofInt()
      val bs = mutable.ArrayBuilder.ofInt()
      val ls = mutable.ArrayBuilder.ofDouble()
      for b <- 0 until n; a <- 0 until b do
        val l = distance(a, b)
        if l <= threshold then
          as += a; bs += b; ls += l
      val (a0, b0, l0) = (as.result(), bs.result(), ls.result())
      val order = SortIndices.sort(
        a0.length,
        (x, y) =>
          val c = java.lang.Double.compare(l0(x), l0(y))
          c < 0 || c == 0 && (a0(x) < a0(y) || a0(x) == a0(y) && b0(x) < b0(y))
      )
      (order.map(a0), order.map(b0), order.map(l0))
    val edgeA: Array[Int] = ea
    val edgeB: Array[Int] = eb
    val edgeLength: Array[Double] = el
    for r <- edgeA.indices do
      rank(edgeA(r) * n + edgeB(r)) = r
      rank(edgeB(r) * n + edgeA(r)) = r

    /** `binom(q)(v)` = C(v, q), for simplices of up to `maxDim + 2` vertices. */
    val binom: Array[Array[Long]] = Array.tabulate(maxDim + 3, n + 1)((q, v) => SimplexIndexing.binomial(v, q))
    // C(n, q) is not monotone in q: the largest index over every size used.
    val bits: Int = 64 - java.lang.Long.numberOfLeadingZeros((1 to math.min(maxDim + 2, n)).map(q => binom(q)(n)).max)
    require(bits <= 63, s"combinatorial indices do not fit in a Long: C($n, q) needs $bits bits for q <= ${maxDim + 2}")

    /** Whether keys are two words: the block and the index need more than 63 bits together. */
    val wide: Boolean = forceWide || bits + (32 - Integer.numberOfLeadingZeros(edgeA.length + 1)) > 63
    val mask: Long = if bits == 63 then Long.MaxValue else (1L << bits) - 1
    // Fermat: a^(p-2); products of two residues and sums of residues and such products must fit an Int (p <= MaxPrime).
    require(p <= MaxPrime, s"ParallelPackedCohomology: coefficients are Ints, so p <= $MaxPrime, got $p")
    val inverse: Array[Int] = Array.tabulate(p) { a =>
      var result = 1L
      var base = a.toLong
      var e = p - 2
      while e > 0 do
        if (e & 1) == 1 then result = result * base % p
        base = base * base % p
        e >>= 1
      if a == 0 then 0 else result.toInt
    }

    /** A one-word key's block; a two-word key carries its block beside it. */
    def block(key: Long): Int =
      if wide then throw IllegalStateException("a two-word key carries its block beside it")
      (key >>> bits).toInt
    def index(key: Long): Long = mask - (key & mask)
    def key(block: Int, index: Long): Long = if wide then mask - index else (block.toLong << bits) | (mask - index)
    def value(block: Int): Double = if block == 0 then 0.0 else edgeLength(block - 1)

    /** The `size` vertices of the simplex at `index`, ascending, into `out`. */
    def decode(index: Long, size: Int, out: Array[Int]): Unit =
      var idx = index
      var q = size
      var hi = n - 1
      while q >= 1 do
        // the largest v with C(v, q) <= idx
        var lo = q - 1
        var h = hi
        while lo < h do
          val mid = (lo + h + 1) >>> 1
          if binom(q)(mid) <= idx then lo = mid else h = mid - 1
        out(q - 1) = lo
        idx -= binom(q)(lo)
        hi = lo - 1
        q -= 1

    def indexOf(vs: Array[Int], size: Int): Long =
      var idx = 0L
      var i = 0
      while i < size do
        idx += binom(i + 1)(vs(i))
        i += 1
      idx

  /** A working column: a binary min-heap of (key, coefficient), equal keys combined when they reach the top. With
    * two-word keys (`wide`) each entry also holds its block, and entries are ordered by block, then key.
    */
  open class Heap(wide: Boolean, bits: Int):
    private var keys = new Array[Long](256)
    private var coefs = new Array[Int](256)
    private var blocks: Array[Int] = if wide then new Array[Int](256) else null
    var size = 0
    def clear(): Unit = size = 0
    private inline def less(k1: Long, b1: Int, k2: Long, b2: Int): Boolean =
      if wide then b1 < b2 || b1 == b2 && k1 < k2 else k1 < k2
    private inline def blockAt(i: Int): Int = if wide then blocks(i) else 0
    private inline def set(i: Int, k: Long, b: Int, c: Int): Unit =
      keys(i) = k
      coefs(i) = c
      if wide then blocks(i) = b

    /** Adds `c` times the key `k`, whose block is `b` (read only for two-word keys). */
    def push(k: Long, b: Int, c: Int): Unit =
      if size == keys.length then
        keys = java.util.Arrays.copyOf(keys, 2 * size)
        coefs = java.util.Arrays.copyOf(coefs, 2 * size)
        if wide then blocks = java.util.Arrays.copyOf(blocks, 2 * size)
      var i = size
      size += 1
      while i > 0 && less(k, b, keys((i - 1) / 2), blockAt((i - 1) / 2)) do
        set(i, keys((i - 1) / 2), blockAt((i - 1) / 2), coefs((i - 1) / 2))
        i = (i - 1) / 2
      set(i, k, b, c)
    private def pop(): Unit =
      size -= 1
      if size > 0 then
        val k = keys(size)
        val b = blockAt(size)
        val c = coefs(size)
        var i = 0
        var go = true
        while go do
          val l = 2 * i + 1
          if l >= size then go = false
          else
            val m = if l + 1 < size && less(keys(l + 1), blockAt(l + 1), keys(l), blockAt(l)) then l + 1 else l
            if less(keys(m), blockAt(m), k, b) then
              set(i, keys(m), blockAt(m), coefs(m))
              i = m
            else go = false
        set(i, k, b, c)

    /** The smallest key with a non-zero combined coefficient, left alone on top; -1 if the column is zero. */
    def pivot(p: Int): Long =
      var result = -1L
      while result < 0 && size > 0 do
        val k = keys(0)
        val b = blockAt(0)
        var c = 0
        while size > 0 && keys(0) == k do
          c = (c + coefs(0)) % p
          pop()
        if c != 0 then
          push(k, b, c)
          result = k
      result
    def topCoefficient: Int = coefs(0)

    /** The top key's block. */
    def topBlock: Int = if wide then blocks(0) else (keys(0) >>> bits).toInt

    /** Every entry, combined and ascending, as keys, coefficients and (two-word keys only, else `null`) blocks; empties
      * the heap.
      */
    def drain(p: Int): (Array[Long], Array[Int], Array[Int]) =
      val ks = mutable.ArrayBuilder.ofLong()
      val cs = mutable.ArrayBuilder.ofInt()
      val bs = if wide then mutable.ArrayBuilder.ofInt() else null
      while pivot(p) >= 0 do
        ks += keys(0)
        cs += coefs(0)
        if wide then bs += blocks(0)
        pop()
      (ks.result(), cs.result(), if wide then bs.result() else null)

  /** The column that owns a pivot, with its block: its reduced coboundary, or `null` arrays when that is its own
    * coboundary (`blocks`: the reduced column's blocks, for two-word keys only); `pivotBlock` the pivot's block, or -1
    * for an owner that is only looked up.
    */
  open class Owner(
    val column: Long,
    val columnBlock: Int,
    val keys: Array[Long],
    val coefs: Array[Int],
    val blocks: Array[Int] = null,
    val pivotBlock: Int = -1
  )

  /** A column whose pivot left its unit: its working column (`blocks` as in `Owner`) and its reduction log so far. */
  open class Deferred(
    val column: Long,
    val columnBlock: Int,
    var keys: Array[Long],
    var coefs: Array[Int],
    var blocks: Array[Int],
    val log: Log
  ):
    var additions = 0

  /** The columns added to one column, with their factors: V = column + sum of factor * V(added). */
  open class Log:
    val added = mutable.ArrayBuilder.ofLong()
    val factors = mutable.ArrayBuilder.ofInt()
    var length = 0
    def add(column: Long, factor: Int): Unit =
      added += column
      factors += factor
      length += 1

    // An ArrayBuilder's `result()` hands its array over and empties the builder when the array is exactly full, so a
    // second call throws: the entries are read once here, after the reduction has finished.
    private var addedColumns: Array[Long] | Null = null
    private var addedFactors: Array[Int] | Null = null
    def columns: Array[Long] =
      if addedColumns == null then addedColumns = added.result()
      addedColumns.nn
    def multipliers: Array[Int] =
      if addedFactors == null then addedFactors = factors.result()
      addedFactors.nn

  private class Scratch(space: Space):
    val vs = new Array[Int](space.maxDim + 3)
    val ws = new Array[Int](space.maxDim + 3)
    val cofKey = new Array[Long](space.n)
    val cofBlock = new Array[Int](space.n)
    val cofCoef = new Array[Int](space.n)
    val heap = Heap(space.wide, space.bits)
    var added = 0
    var rowBlock = -1 // the block of the pivot `run` returned last

  private val DEFER = Long.MinValue

  /** Per-degree counts and times. */
  open class Stats(val degree: Int, val columns: Int, val cleared: Int):
    var units = 0
    var apparent = 0
    var claimed1 = 0
    var deferred = 0
    var finished2a = 0
    var adds1 = 0L
    var adds2a = 0L
    var adds2b = 0L
    var nanosAssemble = 0L
    var nanos1 = 0L
    var nanos2a = 0L
    var nanos2b = 0L
    override def toString: String =
      f"degree $degree: $columns%,d columns ($cleared%,d cleared), $units units; assemble+sort ${nanosAssemble / 1e6}%.1f ms, " +
        f"phase 1 ${nanos1 / 1e6}%.1f, 2a ${nanos2a / 1e6}%.1f, 2b ${nanos2b / 1e6}%.1f ms; apparent $apparent%,d, " +
        f"claimed in phase 1 $claimed1%,d, deferred $deferred%,d ($finished2a%,d finished in 2a); " +
        f"additions $adds1%,d + $adds2a%,d + $adds2b%,d"

  /** One degree's pairs (birth key, death key or -1), its owners with reduced columns, its logs. */
  open class Degree(val degree: Int, val stats: Stats):
    val pairs = mutable.ArrayBuffer.empty[(Long, Long)]
    val bars = mutable.ArrayBuffer.empty[(Int, Double, Double)]
    // The column (birth key) of each bar of `bars`, for `cocycle` and for finding its pair.
    val barColumns = mutable.ArrayBuffer.empty[Long]
    val reduced = mutable.LongMap.empty[Owner] // by column, for the columns with additions
    val logs = mutable.LongMap.empty[Log]
    val essential = mutable.ArrayBuffer.empty[Long]
    val essentialBlocks = mutable.ArrayBuffer.empty[Int]

  open class Result(val space: Space, val degrees: Vector[Degree]):
    def bars: Vector[(Int, Double, Double)] = degrees.flatMap(_.bars).sorted

  private class WorkUnit(val start: Int, val end: Int, val lowBlock: Int, val highBlock: Int):
    val owners = mutable.LongMap.empty[Owner]
    val pivots = mutable.ArrayBuilder.ofLong()
    val deferred = mutable.ArrayBuffer.empty[Deferred]
    val pairs = mutable.ArrayBuffer.empty[(Long, Long)]
    val logs = mutable.LongMap.empty[Log]
    val reduced = mutable.LongMap.empty[Owner]
    val essential = mutable.ArrayBuffer.empty[Long]
    val essentialBlocks = mutable.ArrayBuffer.empty[Int]
    var apparent = 0
    var claimed = 0
    var additions = 0L

  // Every cofacet of the column in s.vs (size k) within the threshold, into s.cofKey / s.cofCoef; the count.
  private def cofacets(space: Space, s: Scratch, k: Int, sigmaIndex: Long, sigmaBlock: Int): Int =
    import space.*
    var iB = sigmaIndex
    var iA = 0L
    var pp = k
    var count = 0
    var v = n - 1
    while v >= 0 do
      if pp > 0 && s.vs(pp - 1) == v then
        iB -= binom(pp)(v)
        iA += binom(pp + 1)(v)
        pp -= 1
      else
        var ok = true
        var blk = sigmaBlock
        var u = 0
        while ok && u < k do
          val q = rank(s.vs(u) * n + v)
          if q < 0 then ok = false else if q + 1 > blk then blk = q + 1
          u += 1
        if ok then
          s.cofKey(count) = key(blk, iB + binom(pp + 1)(v) + iA)
          s.cofBlock(count) = blk
          s.cofCoef(count) = if (pp & 1) == 0 then 1 else p - 1
          count += 1
      v -= 1
    count

  // The column's apparent cofacet (the oldest cofacet, in the column's block, whose youngest facet is the column), or -1.
  // The oldest same-block cofacet adds the largest qualifying vertex; the youngest same-block facet of a simplex drops
  // its largest vertex outside the block's edge.
  private def apparentCofacet(space: Space, s: Scratch, k: Int, sigmaIndex: Long, sigmaBlock: Int): Long =
    import space.*
    if sigmaBlock == 0 then -1L
    else
      val r = sigmaBlock - 1
      val (a, b) = (edgeA(r), edgeB(r))
      var c = -1
      var i = k - 1
      while i >= 0 && c < 0 do
        if s.vs(i) != a && s.vs(i) != b then c = s.vs(i)
        i -= 1
      var iB = sigmaIndex
      var iA = 0L
      var pp = k
      var v = n - 1
      var found = -1L
      while found < 0 && v > c do
        if pp > 0 && s.vs(pp - 1) == v then
          iB -= binom(pp)(v)
          iA += binom(pp + 1)(v)
          pp -= 1
        else
          var ok = true
          var u = 0
          while ok && u < k do
            val q = rank(s.vs(u) * n + v)
            if q < 0 || q > r then ok = false
            u += 1
          if ok then found = key(sigmaBlock, iB + binom(pp + 1)(v) + iA)
        v -= 1
      found

  // The apparent facet of the row `row` (size k + 1, block rowBlock): its youngest same-block facet, if `row` is that
  // facet's oldest same-block cofacet; -1 otherwise. Leaves the facet's vertices in s.vs.
  private def apparentFacet(space: Space, s: Scratch, k: Int, row: Long, rowBlock: Int): Long =
    import space.*
    if rowBlock == 0 then -1L
    else
      decode(index(row), k + 1, s.ws)
      val r = rowBlock - 1
      val (a, b) = (edgeA(r), edgeB(r))
      var j = k
      while j >= 0 && (s.ws(j) == a || s.ws(j) == b) do j -= 1
      if j < 0 then -1L
      else
        val w = s.ws(j)
        var t = 0
        var i = 0
        while i <= k do
          if i != j then
            s.vs(t) = s.ws(i)
            t += 1
          i += 1
        var older = false
        var v = n - 1
        while !older && v > w do
          if v != a && v != b then
            var ok = true
            var u = 0
            while ok && u < k do
              val q = rank(s.vs(u) * n + v)
              if q < 0 || q > r then ok = false
              u += 1
            older = ok
          v -= 1
        if older then -1L else key(rowBlock, indexOf(s.vs, k))

  // The block of the simplex in `vs` (size k): 1 + the largest rank of its edges, as Ripser computes a diameter.
  def blockOf(space: Space, vs: Array[Int], k: Int): Int =
    var top = -1
    var i = 0
    while i < k do
      var j = i + 1
      while j < k do
        val q = space.rank(vs(i) * space.n + vs(j))
        if q > top then top = q
        j += 1
      i += 1
    top + 1

  // The youngest facet of the simplex in s.ws (size k + 1) with block `block`, Ripser's way: facets by increasing index
  // (dropping the largest vertex first), each block recomputed from its pairs; its vertices left in s.vs. The dropped
  // vertex, or -1.
  private def youngestFacetByPairs(space: Space, s: Scratch, k: Int, block: Int): Int =
    var dropped = -1
    var j = k
    while dropped < 0 && j >= 0 do
      var t = 0
      var i = 0
      while i <= k do
        if i != j then
          s.vs(t) = s.ws(i)
          t += 1
        i += 1
      if blockOf(space, s.vs, k) == block then dropped = s.ws(j)
      j -= 1
    dropped

  // The vertex of the oldest cofacet of the simplex in s.vs (size k) with block `block`, Ripser's way: every cofacet
  // from the largest added vertex down until one has that block. -1 if none.
  private def oldestCofacetVertexByScan(space: Space, s: Scratch, k: Int, target: Int): Int =
    import space.*
    var found = -1
    var v = n - 1
    while found < 0 && v >= 0 do
      var member = false
      var blk = target
      var ok = true
      var u = 0
      while ok && u < k do
        if s.vs(u) == v then member = true
        else
          val q = rank(s.vs(u) * n + v)
          if q < 0 then ok = false else if q + 1 > blk then blk = q + 1
        u += 1
      if !member && ok && blk == target then found = v
      v -= 1
    found

  private def apparentCofacetByScan(space: Space, s: Scratch, k: Int, sigmaIndex: Long, sigmaBlock: Int): Long =
    if sigmaBlock == 0 then -1L
    else
      val v = oldestCofacetVertexByScan(space, s, k, sigmaBlock)
      if v < 0 then -1L
      else
        // tau = sigma + v, ascending, into s.ws; sigma's vertices stay in s.vs until the facet search overwrites them.
        var t = 0
        var i = 0
        while i < k && s.vs(i) < v do
          s.ws(t) = s.vs(i)
          t += 1
          i += 1
        s.ws(t) = v
        t += 1
        while i < k do
          s.ws(t) = s.vs(i)
          t += 1
          i += 1
        val tauIndex = space.indexOf(s.ws, k + 1)
        val apparent = youngestFacetByPairs(space, s, k, sigmaBlock) == v
        // The facet search used s.vs: put sigma back, which the caller enumerates next.
        t = 0
        i = 0
        while i <= k do
          if s.ws(i) != v then
            s.vs(t) = s.ws(i)
            t += 1
          i += 1
        if apparent then space.key(sigmaBlock, tauIndex) else -1L

  private def apparentFacetByScan(space: Space, s: Scratch, k: Int, row: Long, rowBlock: Int): Long =
    if rowBlock == 0 then -1L
    else
      space.decode(space.index(row), k + 1, s.ws)
      val w = youngestFacetByPairs(space, s, k, rowBlock)
      if w < 0 || oldestCofacetVertexByScan(space, s, k, rowBlock) != w then -1L
      else space.key(rowBlock, space.indexOf(s.vs, k))

  // Adds factor * (owner's reduced column) to the heap, the factor cancelling the heap's pivot `row`; the factor.
  private def addOwner(space: Space, s: Scratch, k: Int, owner: Owner, row: Long): Int =
    val p = space.p
    val a = s.heap.topCoefficient
    if owner.keys != null then
      val f = (p - a) * space.inverse(owner.coefs(0)) % p
      var i = 0
      val blocks = owner.blocks
      while i < owner.keys.length do
        s.heap.push(owner.keys(i), if blocks != null then blocks(i) else 0, owner.coefs(i) * f % p)
        i += 1
      f
    else
      space.decode(space.index(owner.column), k, s.vs)
      val count = cofacets(space, s, k, space.index(owner.column), owner.columnBlock)
      var lead = 0
      var i = 0
      while i < count do
        if s.cofKey(i) == row then lead = s.cofCoef(i)
        i += 1
      val f = (p - a) * space.inverse(lead) % p
      i = 0
      while i < count do
        s.heap.push(s.cofKey(i), s.cofBlock(i), s.cofCoef(i) * f % p)
        i += 1
      f

  // Reduces the heap: while the pivot is owned (by `lookup`, given the pivot and its block, or as an apparent pair),
  // add the owner. Returns the unowned pivot (its block in s.rowBlock), -1 when the column is zero, DEFER when the
  // pivot's block is above `highBlock`.
  private def run(
    space: Space,
    s: Scratch,
    k: Int,
    lookup: (Long, Int) => Owner | Null,
    highBlock: Int,
    log: Log | Null
  ): Long =
    var result = Long.MaxValue
    var steps = 0
    while result == Long.MaxValue do
      steps += 1
      // A wrong owner (a wrong key, a wrong apparent pair) makes the pivot cycle instead of moving on.
      if steps > 10_000_000 then throw IllegalStateException("the reduction of a column does not terminate")
      val row = s.heap.pivot(space.p)
      val rowBlock = if row < 0 then -1 else s.heap.topBlock
      if row < 0 then result = -1L
      else if rowBlock > highBlock then result = DEFER
      else
        val known = lookup(row, rowBlock)
        val owner =
          if known != null then known
          else
            val facet =
              if space.ripserApparent then apparentFacetByScan(space, s, k, row, rowBlock)
              else apparentFacet(space, s, k, row, rowBlock)
            if facet >= 0 then Owner(facet, rowBlock, null, null) else null
        if owner == null then
          result = row
          s.rowBlock = rowBlock
        else
          val f = addOwner(space, s, k, owner, row)
          s.added += 1
          if log != null then log.add(owner.column, f)
    result

  private def parallel(pool: ExecutorService | Null, threads: Int, tasks: Int)(body: Int => Unit): Unit =
    if pool == null || threads == 1 then (0 until tasks).foreach(body)
    else
      val next = AtomicInteger(0)
      val workers = java.util.ArrayList[Callable[Unit]]()
      for _ <- 0 until threads do
        workers.add(() =>
          var t = next.getAndIncrement()
          while t < tasks do
            body(t)
            t = next.getAndIncrement()
        )
      pool.invokeAll(workers).forEach(_.get())

  private def sort(keys: Array[Long], threads: Int): Unit =
    if threads > 1 then java.util.Arrays.parallelSort(keys) else java.util.Arrays.sort(keys)

  // Two-word keys into order: by block (a counting sort, blocks being 0 .. blockCount - 1), then by key within each
  // block. `blocks` ends up sorted with `keys`.
  private def sortWide(keys: Array[Long], blocks: Array[Int], blockCount: Int, threads: Int): Unit =
    val start = new Array[Int](blockCount + 1)
    var i = 0
    while i < blocks.length do
      start(blocks(i) + 1) += 1
      i += 1
    var b = 0
    while b < blockCount do
      start(b + 1) += start(b)
      b += 1
    val next = start.clone()
    val sorted = new Array[Long](keys.length)
    i = 0
    while i < keys.length do
      sorted(next(blocks(i))) = keys(i)
      next(blocks(i)) += 1
      i += 1
    System.arraycopy(sorted, 0, keys, 0, keys.length)
    b = 0
    while b < blockCount do
      java.util.Arrays.fill(blocks, start(b), start(b + 1), b)
      b += 1
    val within: java.util.function.IntConsumer = b => java.util.Arrays.sort(keys, start(b), start(b + 1))
    val range = java.util.stream.IntStream.range(0, blockCount)
    (if threads > 1 then range.parallel() else range).forEach(within)

  /** Every bar of degrees `0 .. maxDim`; `unitsPerThread` 1 with `threads` 1 is the sequential reduction. */
  def persistence(
    space: Space,
    threads: Int,
    unitsPerThread: Int,
    cocycles: Boolean,
    pool: ExecutorService | Null,
    keepPairs: Boolean = false
  ): Result =
    import space.*
    val scratch = ThreadLocal.withInitial[Scratch](() => Scratch(space))
    var level: Array[Long] = Array.tabulate(n)(v => key(0, v.toLong))
    // The blocks of `level`, for two-word keys only.
    var levelBlocks: Array[Int] = if wide then new Array[Int](n) else null
    if wide then sortWide(level, levelBlocks, edgeA.length + 1, 1) else sort(level, 1)
    var cleared: Array[Long] = Array.empty
    val degrees = Vector.newBuilder[Degree]
    var assembleNanos = 0L
    for d <- 0 to maxDim do
      val k = d + 1
      val isCleared = (c: Long) => java.util.Arrays.binarySearch(cleared, c) >= 0
      val stats = Stats(d, level.length, cleared.length)
      stats.nanosAssemble = assembleNanos
      val degree = Degree(d, stats)
      val (keysOfLevel, blocksOfLevel) = (level, levelBlocks)
      def levelBlock(pos: Int): Int = if wide then blocksOfLevel(pos) else block(keysOfLevel(pos))

      // Units: contiguous runs of whole blocks, about level.length / (threads * unitsPerThread) columns each.
      val target = math.max(1, level.length / math.max(1, threads * unitsPerThread))
      val unitsB = mutable.ArrayBuffer.empty[WorkUnit]
      var start = 0
      while start < level.length do
        var end = math.min(level.length, start + target)
        if threads * unitsPerThread == 1 then end = level.length
        while end < level.length && levelBlock(end) == levelBlock(end - 1) do end += 1
        unitsB += WorkUnit(start, end, levelBlock(start), levelBlock(end - 1))
        start = end
      val units = unitsB.toArray
      stats.units = units.length
      val unitLow = units.map(_.lowBlock)
      def unitOf(b: Int): WorkUnit | Null =
        var i = java.util.Arrays.binarySearch(unitLow, b)
        if i < 0 then i = -i - 2
        if i >= 0 && units(i).highBlock >= b then units(i) else null

      // Phase 1: each unit youngest first, against its own owners.
      val t1 = System.nanoTime()
      parallel(pool, threads, units.length) { u =>
        val unit = units(u)
        val s = scratch.get()
        val lookup: (Long, Int) => Owner | Null = (row, _) => unit.owners.getOrNull(row)
        var pos = unit.end - 1
        while pos >= unit.start do
          val column = level(pos)
          if !isCleared(column) then
            val idx = index(column)
            val blk = levelBlock(pos)
            decode(idx, k, s.vs)
            val tau =
              if ripserApparent then apparentCofacetByScan(space, s, k, idx, blk)
              else apparentCofacet(space, s, k, idx, blk)
            if tau >= 0 then
              unit.apparent += 1
              unit.pivots += tau
              if keepPairs then unit.pairs += ((column, tau))
            else
              s.heap.clear()
              val count = cofacets(space, s, k, idx, blk)
              var i = 0
              while i < count do
                s.heap.push(s.cofKey(i), s.cofBlock(i), s.cofCoef(i))
                i += 1
              s.added = 0
              val log = if cocycles then Log() else null
              val row = run(space, s, k, lookup, unit.highBlock, log)
              unit.additions += s.added
              if row == DEFER then
                val (ks, cs, bs) = s.heap.drain(p)
                unit.deferred += Deferred(column, blk, ks, cs, bs, log)
              else
                if log != null && log.length > 0 then unit.logs(column) = log
                if row < 0 then
                  unit.essential += column
                  unit.essentialBlocks += blk
                  if keepPairs then unit.pairs += ((column, -1L))
                else
                  val rowBlock = s.rowBlock
                  val owner =
                    if s.added > 0 then
                      val (ks, cs, bs) = s.heap.drain(p)
                      Owner(column, blk, ks, cs, bs, rowBlock)
                    else Owner(column, blk, null, null, null, rowBlock)
                  if s.added > 0 then unit.reduced(column) = owner
                  unit.owners(row) = owner
                  unit.pivots += row
                  unit.claimed += 1
                  if keepPairs then unit.pairs += ((column, row))
          pos -= 1
      }
      stats.nanos1 = System.nanoTime() - t1
      stats.apparent = units.map(_.apparent).sum
      stats.claimed1 = units.map(_.claimed).sum
      stats.adds1 = units.map(_.additions).sum

      // Phase 2a: the deferred columns, in parallel, while their pivots belong to the units' pairs.
      // Youngest first: by block, then key, descending.
      val deferred = units
        .flatMap(_.deferred)
        .sortWith((a, b) => a.columnBlock > b.columnBlock || a.columnBlock == b.columnBlock && a.column > b.column)
      stats.deferred = deferred.length
      val t2 = System.nanoTime()
      val lookupUnits: (Long, Int) => Owner | Null = (row, rowBlock) =>
        val u = unitOf(rowBlock)
        if u == null then null else u.owners.getOrNull(row)
      val emptied = new Array[Boolean](deferred.length)
      val adds2a = new Array[Long](deferred.length)
      val slice = math.max(1, math.min(32, deferred.length / math.max(1, threads * 8)))
      parallel(pool, threads, (deferred.length + slice - 1) / slice) { c =>
        val s = scratch.get()
        for i <- c * slice until math.min(deferred.length, (c + 1) * slice) do
          val df = deferred(i)
          s.heap.clear()
          for j <- df.keys.indices do
            s.heap.push(df.keys(j), if df.blocks != null then df.blocks(j) else 0, df.coefs(j))
          s.added = 0
          // An unowned pivot here may still be owned by a deferred column: stop and leave it to phase 2b.
          val row = run(space, s, k, lookupUnits, Int.MaxValue, df.log)
          adds2a(i) = s.added
          df.additions = s.added
          if row == -1L then emptied(i) = true
          val (ks, cs, bs) = s.heap.drain(p)
          df.keys = ks
          df.coefs = cs
          df.blocks = bs
      }
      stats.nanos2a = System.nanoTime() - t2
      stats.adds2a = adds2a.sum
      stats.finished2a = emptied.count(identity)

      // Phase 2b: one by one, youngest first, against every owner so far.
      val t3 = System.nanoTime()
      val late = mutable.LongMap.empty[Owner]
      val latePivots = mutable.ArrayBuilder.ofLong()
      val lookupAll: (Long, Int) => Owner | Null = (row, rowBlock) =>
        val o = lookupUnits(row, rowBlock)
        if o != null then o else late.getOrNull(row)
      val s = scratch.get()
      var adds2b = 0L
      for i <- deferred.indices do
        val df = deferred(i)
        val log = df.log
        val row =
          if emptied(i) then -1L
          else
            s.heap.clear()
            for j <- df.keys.indices do
              s.heap.push(df.keys(j), if df.blocks != null then df.blocks(j) else 0, df.coefs(j))
            s.added = 0
            val r = run(space, s, k, lookupAll, Int.MaxValue, log)
            adds2b += s.added
            r
        if log != null && log.length > 0 then degree.logs(df.column) = log
        if row < 0 then
          degree.essential += df.column
          degree.essentialBlocks += df.columnBlock
          if keepPairs then degree.pairs += ((df.column, -1L))
        else
          val rowBlock = s.rowBlock
          val (ks, cs, bs) = s.heap.drain(p)
          val owner = Owner(df.column, df.columnBlock, ks, cs, bs, rowBlock)
          degree.reduced(df.column) = owner
          late(row) = owner
          latePivots += row
          if keepPairs then degree.pairs += ((df.column, row))
      stats.nanos2b = System.nanoTime() - t3
      stats.adds2b = adds2b

      for unit <- units do
        degree.pairs ++= unit.pairs
        degree.logs ++= unit.logs
        degree.reduced ++= unit.reduced
        degree.essential ++= unit.essential
        degree.essentialBlocks ++= unit.essentialBlocks
      // Bars of positive length: the owners' pairs, and the essential columns (death block -1).
      def bar(column: Long, columnBlock: Int, rowBlock: Int): Unit =
        val birth = value(columnBlock)
        val death = if rowBlock < 0 then Double.PositiveInfinity else value(rowBlock)
        if death > birth then
          degree.bars += ((d, birth, death))
          degree.barColumns += column
      for unit <- units do unit.owners.foreach((_, o) => bar(o.column, o.columnBlock, o.pivotBlock))
      late.foreach((_, o) => bar(o.column, o.columnBlock, o.pivotBlock))
      degree.essential.indices.foreach(i => bar(degree.essential(i), degree.essentialBlocks(i), -1))

      // Clearing for the next degree, and its simplices: every canonical cofacet within the threshold.
      val pivotChunks = units.map(_.pivots.result()) :+ latePivots.result()
      cleared = new Array[Long](pivotChunks.map(_.length).sum)
      var at = 0
      for chunk <- pivotChunks do
        System.arraycopy(chunk, 0, cleared, at, chunk.length)
        at += chunk.length
      sort(cleared, threads)
      if d < maxDim then
        val t4 = System.nanoTime()
        val chunks = math.max(1, threads * 8)
        val parts = new Array[Array[Long]](chunks)
        val blockParts = new Array[Array[Int]](chunks)
        val current = level
        parallel(pool, threads, chunks) { c =>
          val s = scratch.get()
          val out = mutable.ArrayBuilder.ofLong()
          val outBlocks = if wide then mutable.ArrayBuilder.ofInt() else null
          for pos <- (c * current.length.toLong / chunks).toInt until ((c + 1) * current.length.toLong / chunks).toInt
          do
            val column = current(pos)
            val columnBlock = levelBlock(pos)
            val idx = index(column)
            decode(idx, k, s.vs)
            val top = s.vs(k - 1)
            var v = n - 1
            while v > top do
              var ok = true
              var blk = columnBlock
              var u = 0
              while ok && u < k do
                val q = rank(s.vs(u) * n + v)
                if q < 0 then ok = false else if q + 1 > blk then blk = q + 1
                u += 1
              if ok then
                out += key(blk, idx + binom(k + 1)(v))
                if wide then outBlocks += blk
              v -= 1
          parts(c) = out.result()
          if wide then blockParts(c) = outBlocks.result()
        }
        level = new Array[Long](parts.map(_.length).sum)
        levelBlocks = if wide then new Array[Int](level.length) else null
        at = 0
        for c <- parts.indices do
          System.arraycopy(parts(c), 0, level, at, parts(c).length)
          if wide then System.arraycopy(blockParts(c), 0, levelBlocks, at, parts(c).length)
          at += parts(c).length
        if wide then sortWide(level, levelBlocks, edgeA.length + 1, threads) else sort(level, threads)
        assembleNanos = System.nanoTime() - t4
      degrees += degree
    val result = degrees.result()
    // Read each log's entries once, here on one thread, so that cocycles can be built from several threads.
    for degree <- result; log <- degree.logs.values do
      log.columns
      log.multipliers
    Result(space, result)

  /** The coboundary of a cochain of keys of size `k`, as a map key -> coefficient (zeros dropped). */
  def coboundaryOf(space: Space, k: Int, cochain: collection.Map[Long, Int]): Map[Long, Int] =
    val s = Scratch(space)
    val sum = mutable.LongMap.empty[Int]
    for (c, f) <- cochain do
      space.decode(space.index(c), k, s.vs)
      val count = cofacets(space, s, k, space.index(c), blockOf(space, s.vs, k))
      for i <- 0 until count do sum(s.cofKey(i)) = (sum.getOrElse(s.cofKey(i), 0) + f * s.cofCoef(i)) % space.p
    sum.filter(_._2 != 0).toMap

  /** V for a column: the column plus factor * V(added) over its log, recursively. */
  def cocycle(degree: Degree, p: Int, column: Long): Map[Long, Int] =
    val memo = mutable.LongMap.empty[Map[Long, Int]]
    def v(c: Long): Map[Long, Int] = memo.get(c) match
      case Some(known) => known
      case None        =>
        val result = degree.logs.get(c) match
          case None      => Map(c -> 1)
          case Some(log) =>
            val acc = mutable.LongMap(c -> 1)
            val (added, factors) = (log.columns, log.multipliers)
            for i <- added.indices; (e, x) <- v(added(i)) do acc(e) = (acc.getOrElse(e, 0) + factors(i) * x) % p
            acc.filter(_._2 != 0).toMap
        memo(c) = result
        result
    v(column)

  def main(arguments: Array[String]): Unit =
    val check = arguments.contains("check")
    val ripserApparent = arguments.contains("ripser-apparent")
    val args = arguments.filterNot(a => a == "check" || a == "ripser-apparent")
    val dataset = args(0)
    val n = args(1).toInt
    val maxDim = args(2).toInt
    val threads = args(4).toInt
    val unitsPerThread = if args.length > 5 then args(5).toInt else 8
    val trials = if args.length > 6 then args(6).toInt else 5
    val seed = if args.length > 7 then args(7).toLong else 42L
    val p = if args.length > 8 then args(8).toInt else 17
    val pts = BlockParallelReduction.points(dataset, n, Random(seed))
    val metricSpace = EuclideanMetricSpace(pts)
    val threshold = if args(3) == "mer" then metricSpace.minimumEnclosingRadius else args(3).toDouble
    val common = java.util.concurrent.ForkJoinPool.getCommonPoolParallelism
    println(
      s"$dataset n=$n maxDim=$maxDim threshold=$threshold p=$p threads=$threads units/thread=$unitsPerThread " +
        s"(common pool parallelism $common)${if ripserApparent then ", apparent pairs Ripser's way" else ""}"
    )
    val pool = if threads > 1 then Executors.newFixedThreadPool(threads) else null
    def once(): (Long, Result) =
      val t0 = System.nanoTime()
      val dist = new Array[Double](n * n)
      for i <- 0 until n; j <- 0 until n do dist(i * n + j) = metricSpace.distance(i, j)
      val space = Space(n, (i, j) => dist(i * n + j), threshold, maxDim, p, ripserApparent)
      val r = persistence(space, threads, unitsPerThread, cocycles = true, pool)
      (System.nanoTime() - t0, r)
    val runs = (1 to trials).map(_ => once())
    val times = runs.map(_._1).sorted
    val last = runs.last._2
    println(
      f"  bars ${last.bars.size}, total median ${times(times.size / 2) / 1e6}%.1f ms, min ${times.head / 1e6}%.1f ms"
    )
    last.degrees.foreach(d => println("  " + d.stats))
    if check then
      val packed = BlockParallelReduction.packedBars(metricSpace, maxDim, threshold, p)
      println(s"  same bars as the packed Ripser engine: ${last.bars == packed}")
    if pool != null then pool.shutdown()
