package org.appliedtopology.tda4j

import scala.collection.mutable

/** Persistent cohomology of a cubical grid by Bauer's Ripser algorithm on the grid itself: what
  * [[CellularCohomologyEngine]] computes on a [[CubicalGridStream]], or on its cells up to dimension `topDim` as a
  * [[LimitedCubicalGridStream]] shows them -- the same bars, pairs, cocycles and cycles, term for term and in the same
  * list order -- without a `Cube`, a hash of one or a `Chain` inside the reduction.
  *
  * A cell is its index in the doubled grid (KMM encoding, see [[Cube]]). Within a dimension cells are ordered by the
  * rank of their value ([[GridRanks]]) and then by index: the generic engine's order there, value ascending, then the
  * encoding ascending. Boundaries and coboundaries come from index arithmetic. Clearing as in the generic engine;
  * apparent pairs (a cell whose oldest same-value cofacet has it as its youngest same-value facet) are paired without a
  * reduction and their columns rebuilt when another column needs them, which changes no output (`useApparentPairs =
  * false` turns them off). Cycles come from the involution on the same pairs, specialised to packed cells as
  * [[PackedRipserCohomologyEngine]] does it. A cell every pixel around which is masked (NaN) is not in the complex: it
  * has rank [[GridRanks.masked]], and is never listed nor met as a cofacet.
  */
open class PackedCubicalCohomologyEngine[C: Field](
  grid: CubicalGridStream,
  topDim: Int,
  ranks: GridRanks,
  useApparentPairs: Boolean = true
):
  private val fr = summon[C is Field]
  private val d = grid.ambientDim
  require(topDim >= 0 && topDim <= d, s"PackedCubicalCohomologyEngine: topDim $topDim outside 0..$d")
  private val shape = ranks.shape
  private val distinct = ranks.distinct
  private val masked = ranks.masked
  private val extent: Array[Int] = shape.map(n => 2 * n + 1)
  private val totalCells: Int = GridRanks.checkedProduct(extent, "PackedCubicalCohomologyEngine")
  private val weight: Array[Int] = GridRanks.strides(extent)

  /** Every cell's rank: the smallest rank of the pixels containing it (separable, as `CubicalGridStream`'s values);
    * `masked` when they are all masked.
    */
  private val cellRank: Array[Int] =
    val r = new Array[Int](totalCells)
    java.util.Arrays.fill(r, Int.MaxValue)
    var p = 0
    while p < ranks.size do
      var rem = p
      var key = 0
      var i = d - 1
      while i >= 0 do
        key += (2 * (rem % shape(i)) + 1) * weight(i)
        rem /= shape(i)
        i -= 1
      r(key) = ranks.rank(p)
      p += 1
    // Along each axis, a position even there takes the smaller of its two neighbours (odd there).
    var axis = 0
    while axis < d do
      val w = weight(axis)
      val len = extent(axis)
      var base0 = 0
      while base0 < totalCells do
        var t = 0
        while t < len do
          val base = base0 + t * w
          var lo = 0
          while lo < w do
            val below = if t > 0 then r(base + lo - w) else Int.MaxValue
            val above = if t < len - 1 then r(base + lo + w) else Int.MaxValue
            r(base + lo) = math.min(below, above)
            lo += 1
          t += 2
        base0 += w * len
      axis += 1
    r

  /** A cell as one `Long` that orders like the engine: rank, then index. */
  private inline def packed(key: Int): Long = (cellRank(key).toLong << 32) | key.toLong
  private inline def keyOf(packedCell: Long): Int = (packedCell & 0xffffffffL).toInt
  private def valueOf(key: Int): Double = distinct(cellRank(key))

  // The representatives' decoder: they keep it, so it holds only the strides and the coordinate boxes.
  private val cubes = GridCubes(shape)
  private def cubeOf(key: Int): Cube = cubes(key)

  /** The generic engine's order on the cells of one dimension; its reverse for cycles. Standalone (see
    * [[GridCellOrder]]): every representative keeps its order.
    */
  private val olderFirst: Ordering[Cube] = GridCellOrder(cellRank, weight)
  private val youngestFirstOrder: Ordering[Cube] = olderFirst.reverse

  // `cubeIsOrderedCell`'s boundary signs: along the nondegenerate axis of rank r (among the cube's nondegenerate axes),
  // the upper face carries `one` for even r and `-one` for odd r, the lower face the negation.
  private val upperEven: C = fr.one
  private val upperOdd: C = fr.negate(fr.one)
  private val lowerEven: C = fr.negate(upperEven)
  private val lowerOdd: C = fr.negate(upperOdd)

  private val coords = new Array[Int](d)
  private def decode(key: Int): Unit =
    var i = 0
    while i < d do
      coords(i) = (key / weight(i)) % extent(i)
      i += 1

  /** Each cofacet of `sigma` in the complex with the coefficient of `sigma` in its boundary. Only for `sigma` of
    * dimension below `topDim`: the complex has no cell above it. (Every facet of a cell in the complex is in it.)
    */
  private inline def foreachCofacet(sigma: Int)(inline f: (Int, C) => Unit): Unit =
    decode(sigma)
    var oddBefore = 0
    var a = 0
    while a < d do
      val c = coords(a)
      if (c & 1) == 0 then
        if c > 0 && cellRank(sigma - weight(a)) != masked then
          f(sigma - weight(a), if (oddBefore & 1) == 0 then upperEven else upperOdd)
        if c < extent(a) - 1 && cellRank(sigma + weight(a)) != masked then
          f(sigma + weight(a), if (oddBefore & 1) == 0 then lowerEven else lowerOdd)
      else oddBefore += 1
      a += 1

  /** Each facet of `tau` with its boundary coefficient. */
  private inline def foreachFacet(tau: Int)(inline f: (Int, C) => Unit): Unit =
    decode(tau)
    var rank = 0
    var a = 0
    while a < d do
      if (coords(a) & 1) == 1 then
        f(tau + weight(a), if (rank & 1) == 0 then upperEven else upperOdd)
        f(tau - weight(a), if (rank & 1) == 0 then lowerEven else lowerOdd)
        rank += 1
      a += 1

  // The oldest cofacet with sigma's value (the pivot of its coboundary when there is one), -1 if none.
  private def zeroPivotCofacet(sigma: Int): Int =
    val r = cellRank(sigma)
    var best = -1
    foreachCofacet(sigma) { (tau, _) => if cellRank(tau) == r && (best < 0 || tau < best) then best = tau }
    best

  // The youngest facet with tau's value, -1 if none.
  private def zeroPivotFacet(tau: Int): Int =
    val r = cellRank(tau)
    var best = -1
    foreachFacet(tau) { (sigma, _) => if cellRank(sigma) == r && sigma > best then best = sigma }
    best

  private def zeroApparentCofacet(sigma: Int): Int =
    val tau = zeroPivotCofacet(sigma)
    if tau >= 0 && zeroPivotFacet(tau) == sigma then tau else -1

  private def zeroApparentFacet(tau: Int): Int =
    val sigma = zeroPivotFacet(tau)
    if sigma >= 0 && zeroPivotCofacet(sigma) == tau then sigma else -1

  /** The coefficient of the cofacet `tau` in the coboundary of `sigma`. */
  private def cofacetCoefficient(sigma: Int, tau: Int): C =
    var found = fr.zero
    foreachCofacet(sigma)((cell, c) => if cell == tau then found = c)
    found

  /** The coefficient of the facet `sigma` in the boundary of `tau`. */
  private def facetCoefficient(tau: Int, sigma: Int): C =
    var found = fr.zero
    foreachFacet(tau)((cell, c) => if cell == sigma then found = c)
    found

  /** The cells of dimension `k` in the complex, oldest first (rank, then index): a stable counting sort by rank of the
    * cells taken in ascending index.
    */
  private def level(k: Int): Array[Int] =
    val m = distinct.length
    val start = new Array[Int](m + 1)
    foreachCellAscending(k)(key => if cellRank(key) != masked then start(cellRank(key) + 1) += 1)
    var r = 1
    while r <= m do
      start(r) += start(r - 1)
      r += 1
    val out = new Array[Int](start(m))
    foreachCellAscending(k) { key =>
      val rank = cellRank(key)
      if rank != masked then
        out(start(rank)) = key
        start(rank) += 1
    }
    out

  // The cells of dimension `k` (`k` odd coordinates) in ascending index: a prefix of the doubled grid with `j` odd
  // coordinates holds the cells whose last coordinate is odd when j = k - 1, even when j = k.
  private inline def foreachCellAscending(k: Int)(inline f: Int => Unit): Unit =
    val last = d - 1
    DoubledGrid.foreachPrefix(shape, ascending = true) { c =>
      var odd = 0
      var base = 0
      var i = 0
      while i < last do
        odd += c(i) & 1
        base += c(i) * weight(i)
        i += 1
      val lastParity = k - odd
      if lastParity == 0 || lastParity == 1 then
        var t = lastParity
        while t < extent(last) do
          f(base + t)
          t += 2
    }

  /** A reduced column: each cell once, by its key, with its non-zero coefficient, the pivot first and the rest in the
    * order the column was reduced in (the heap's: ascending for the cohomology reduction, descending for the cycles).
    * Never changed, so a cycle's chain shares its arrays.
    */
  private final class Column(val keys: Array[Int], val coefficients: Array[Any]):
    def leadingCoefficient: C = coefficients(0).asInstanceOf[C]
    def isEmpty: Boolean = keys.isEmpty

  private val emptyColumn = Column(Array.empty[Int], Array.empty[Any])

  /** The column being reduced: a binary heap of (packed cell, coefficient), oldest on top for the cohomology reduction,
    * youngest on top for the cycles. Equal cells are combined when they reach the top.
    */
  private final class WorkingColumn(youngestFirst: Boolean):
    private var cells = new Array[Long](64)
    private var coef = new Array[Any](64)
    private var size = 0
    var pivotCell: Long = 0L
    var pivotCoefficient: C = fr.zero

    private inline def less(i: Int, j: Int): Boolean =
      if youngestFirst then cells(i) > cells(j) else cells(i) < cells(j)
    private def swap(i: Int, j: Int): Unit =
      val x = cells(i)
      cells(i) = cells(j)
      cells(j) = x
      val c = coef(i)
      coef(i) = coef(j)
      coef(j) = c

    def push(cell: Long, c: C): Unit =
      if size == cells.length then
        cells = java.util.Arrays.copyOf(cells, 2 * size)
        coef = java.util.Arrays.copyOf(coef.asInstanceOf[Array[AnyRef]], 2 * size).asInstanceOf[Array[Any]]
      cells(size) = cell
      coef(size) = c
      var k = size
      size += 1
      while k > 0 && less(k, (k - 1) / 2) do
        swap(k, (k - 1) / 2)
        k = (k - 1) / 2

    private def popTop(): Unit =
      size -= 1
      if size > 0 then
        cells(0) = cells(size)
        coef(0) = coef(size)
        var k = 0
        var done = false
        while !done do
          val l = 2 * k + 1
          val r = l + 1
          var m = k
          if l < size && less(l, m) then m = l
          if r < size && less(r, m) then m = r
          if m == k then done = true
          else
            swap(k, m)
            k = m

    /** Finds the leading term, combining equal cells and dropping those that cancel; false if the column is zero. The
      * pivot stays in the column.
      */
    def pivot(): Boolean =
      var found = false
      while !found && size > 0 do
        val cell = cells(0)
        var sum = coef(0).asInstanceOf[C]
        popTop()
        while size > 0 && cells(0) == cell do
          sum = fr.plus(sum, coef(0).asInstanceOf[C])
          popTop()
        if !fr.isEqual(sum, fr.zero) then
          push(cell, sum)
          pivotCell = cell
          pivotCoefficient = sum
          found = true
      found

    def addScaled(column: Column, factor: C): Unit =
      var k = 0
      while k < column.keys.length do
        push(packed(column.keys(k)), fr.times(factor, column.coefficients(k).asInstanceOf[C]))
        k += 1

    // Combining here keeps repeats and cancelling pairs out of the stored columns, which are added again and again:
    // left in, they made the 3-D reductions superlinear (64³ noise: 40 s; .claude/WORKLOG-cubical-performance.md).
    /** Empties the column into a reduced column: every cell once, with its non-zero combined coefficient, in heap order
      * (the pivot first); empty if the column is zero. The cells are popped in order, so the column comes out sorted:
      * ascending for the cohomology reduction, descending for the cycles.
      */
    def drain(): Column =
      val end = size
      var n = 0
      while size > 0 do
        val cell = cells(0)
        var sum = coef(0).asInstanceOf[C]
        popTop()
        while size > 0 && cells(0) == cell do
          sum = fr.plus(sum, coef(0).asInstanceOf[C])
          popTop()
        if !fr.isEqual(sum, fr.zero) then
          // Each result takes at least one pop, so the slots the heap has given up (the end of its arrays, last slot
          // first) hold the output until it is copied out at its exact size.
          cells(end - 1 - n) = cell
          coef(end - 1 - n) = sum
          n += 1
      if n == 0 then emptyColumn
      else
        val outKeys = new Array[Int](n)
        val outCoefficients = new Array[Any](n)
        var i = 0
        while i < n do
          outKeys(i) = keyOf(cells(end - 1 - i))
          outCoefficients(i) = coef(end - 1 - i)
          i += 1
        Column(outKeys, outCoefficients)

  /** The pairing in report order (degree ascending, then the birth cell youngest first), one row per pair, zero-length
    * ones included.
    */
  private final class Rows(capacity: Int):
    var size: Int = 0
    var dims: Array[Byte] = new Array[Byte](capacity)
    var births: Array[Int] = new Array[Int](capacity)
    var deaths: Array[Int] = new Array[Int](capacity) // -1: essential
    var apparent: Array[Boolean] = new Array[Boolean](capacity)

    def add(dim: Int, birth: Int, death: Int, isApparent: Boolean): Int =
      if size == births.length then
        val grown = math.min(2L * size + 16, Int.MaxValue.toLong - 8).toInt
        dims = java.util.Arrays.copyOf(dims, grown)
        births = java.util.Arrays.copyOf(births, grown)
        deaths = java.util.Arrays.copyOf(deaths, grown)
        apparent = java.util.Arrays.copyOf(apparent, grown)
      dims(size) = dim.toByte
      births(size) = birth
      deaths(size) = death
      apparent(size) = isApparent
      size += 1
      size - 1

    def isZeroLength(row: Int): Boolean = deaths(row) >= 0 && cellRank(births(row)) == cellRank(deaths(row))

    def bar(row: Int, representative: Chain[Cube, C]): PersistenceBar[Double, Chain[Cube, C]] =
      new PersistenceBar(
        dims(row).toInt,
        ClosedEndpoint(valueOf(births(row))),
        if deaths(row) < 0 then PositiveInfinity() else OpenEndpoint(valueOf(deaths(row))),
        Some(representative)
      )

  /** The steps of one reduction in order: the pivot cell reduced by, and the multiple of its column subtracted. */
  private final class Log(val pivots: Array[Int], val coefficients: Array[Any]):
    def isEmpty: Boolean = pivots.isEmpty
    def nonEmpty: Boolean = pivots.nonEmpty

  /** An array of one `Int` per cell of the doubled grid, all `value`. */
  private def filledWith(value: Int): Array[Int] =
    val a = new Array[Int](totalCells)
    java.util.Arrays.fill(a, value)
    a

  private val noLog = Log(Array.empty[Int], Array.empty[Any])

  /** Collects the steps of one reduction at a time; reused. */
  private final class LogBuilder:
    private var pivots = new Array[Int](16)
    private var coefficients = new Array[Any](16)
    private var count = 0

    def add(pivot: Int, c: C): Unit =
      if count == pivots.length then
        pivots = java.util.Arrays.copyOf(pivots, 2 * count)
        coefficients =
          java.util.Arrays.copyOf(coefficients.asInstanceOf[Array[AnyRef]], 2 * count).asInstanceOf[Array[Any]]
      pivots(count) = pivot
      coefficients(count) = c
      count += 1

    /** The steps so far, and the builder emptied. */
    def drain(): Log =
      if count == 0 then noLog
      else
        val out = Log(
          java.util.Arrays.copyOf(pivots, count),
          java.util.Arrays.copyOf(coefficients.asInstanceOf[Array[AnyRef]], count).asInstanceOf[Array[Any]]
        )
        count = 0
        out

  private def orderBug(pivot: Int): Nothing =
    throw new IllegalStateException(
      s"an engine bug: reducing by the column of ${cubeOf(pivot).show} did not remove it (a column and the order disagree)"
    )

  /** A V-column: each cell once, with its non-zero coefficient. */
  private final class SparseColumn(val cells: Array[Int], val coefficients: Array[Any])

  /** Coefficients keyed by cell, for one V-column at a time: open addressing over primitive keys, reused. */
  private final class CellSums:
    private var keys: Array[Int] = Array.fill(64)(-1)
    private var values: Array[Any] = new Array[Any](64)
    private var used: Array[Int] = new Array[Int](32) // occupied slots, in insertion order
    private var count: Int = 0

    private def slot(key: Int, ks: Array[Int]): Int =
      val mask = ks.length - 1
      var s = (key * 0x9e3779b9) >>> 7 & mask
      while ks(s) != -1 && ks(s) != key do s = (s + 1) & mask
      s

    /** `acc(key) = acc(key) - x`, an absent key reading as zero: the generic engine's V-column update. */
    def subtract(key: Int, x: C): Unit =
      if 2 * (count + 1) > keys.length then grow()
      val s = slot(key, keys)
      if keys(s) == -1 then
        keys(s) = key
        values(s) = fr.minus(fr.zero, x)
        if count == used.length then used = java.util.Arrays.copyOf(used, 2 * count)
        used(count) = s
        count += 1
      else values(s) = fr.minus(values(s).asInstanceOf[C], x)

    def set(key: Int, x: C): Unit =
      subtract(key, fr.zero)
      values(slot(key, keys)) = x

    /** The non-zero entries in insertion order; empties the table. */
    def drain(): SparseColumn =
      val outCells = new Array[Int](count)
      val outCoefficients = new Array[Any](count)
      var n = 0
      var i = 0
      while i < count do
        val s = used(i)
        val x = values(s).asInstanceOf[C]
        if !fr.isEqual(x, fr.zero) then
          outCells(n) = keys(s)
          outCoefficients(n) = x
          n += 1
        keys(s) = -1
        values(s) = null
        i += 1
      count = 0
      SparseColumn(
        java.util.Arrays.copyOf(outCells, n),
        java.util.Arrays.copyOf(outCoefficients.asInstanceOf[Array[AnyRef]], n).asInstanceOf[Array[Any]]
      )

    private def grow(): Unit =
      val oldKeys = keys
      val oldValues = values
      keys = Array.fill(oldKeys.length * 2)(-1)
      values = new Array[Any](oldKeys.length * 2)
      var i = 0
      while i < count do
        val s = slot(oldKeys(used(i)), keys)
        keys(s) = oldKeys(used(i))
        values(s) = oldValues(used(i))
        used(i) = s
        i += 1

  private val vSums = CellSums()

  /** A V-column `seed - sum c V(pivot)` over `log`, accumulated as the generic engine does (`acc(k) -= c * v`), from
    * the V-columns of earlier columns, each recorded as its seed and log and expanded only when needed, depth-first
    * without recursion, into `memo`.
    */
  private def expandV(
    seed: Int,
    log: Log,
    seedOf: Int => Int,
    logOf: Int => Log,
    memo: mutable.LongMap[SparseColumn]
  ): SparseColumn =
    def fold(seed: Int, log: Log): SparseColumn =
      if log.isEmpty then SparseColumn(Array(seed), Array(fr.one))
      else
        vSums.set(seed, fr.one)
        var i = 0
        while i < log.pivots.length do
          val v = memo(log.pivots(i).toLong)
          val coeff = log.coefficients(i).asInstanceOf[C]
          var k = 0
          while k < v.cells.length do
            vSums.subtract(v.cells(k), fr.times(coeff, v.coefficients(k).asInstanceOf[C]))
            k += 1
          i += 1
        vSums.drain()
    var stack = new Array[Int](64)
    var top = 0
    def push(pivot: Int): Unit =
      if top == stack.length then stack = java.util.Arrays.copyOf(stack, 2 * top)
      stack(top) = pivot
      top += 1
    var j = 0
    while j < log.pivots.length do
      push(log.pivots(j))
      j += 1
    while top > 0 do
      val s = stack(top - 1)
      if memo.contains(s.toLong) then top -= 1
      else
        val sLog = logOf(s)
        var missing = false
        var i = 0
        while i < sLog.pivots.length do
          if !memo.contains(sLog.pivots(i).toLong) then
            push(sLog.pivots(i))
            missing = true
          i += 1
        if !missing then
          memo(s.toLong) = fold(seedOf(s), sLog)
          top -= 1
    fold(seed, log)

  /** The chain of a reduced column of the cycles' reduction: its cells already pop in the chain's order (youngest
    * first), so the chain takes the column's arrays, which are never changed.
    */
  private def cycleChain(column: Column): Chain[Cube, C] =
    Chain.packed[Cube, C](column.keys, column.coefficients.asInstanceOf[Array[AnyRef]], cubes)(using
      youngestFirstOrder,
      fr
    )

  /** `v`'s terms (each cell once, non-zero) as a packed chain of cubes: oldest first (`olderFirst`, cocycles) or
    * youngest first (its reverse, cycles).
    */
  private def cubeChain(v: SparseColumn, youngestFirst: Boolean): Chain[Cube, C] =
    val n = v.cells.length
    val order = new Array[Long](n)
    var i = 0
    while i < n do
      order(i) = packed(v.cells(i))
      i += 1
    // The packed cells (rank, then index) sort as `olderFirst` orders the cubes.
    val sorted =
      if youngestFirst then SortIndices.sort(n, (a, b) => order(a) > order(b))
      else SortIndices.sort(n, (a, b) => order(a) < order(b))
    val keys = new Array[Int](n)
    val coefficients = new Array[AnyRef](n)
    i = 0
    while i < n do
      keys(i) = v.cells(sorted(i))
      coefficients(i) = v.coefficients(sorted(i)).asInstanceOf[AnyRef]
      i += 1
    Chain.packed[Cube, C](keys, coefficients, cubes)(using if youngestFirst then youngestFirstOrder else olderFirst, fr)

  /** The number of cells of dimension at most `k` in the doubled grid, those of a masked closure included: the most
    * rows a reduction up to degree `k` can have, each row having a cell of its own as its birth.
    */
  private def cellsUpTo(k: Int): Int =
    // The cells of dimension j: Σ over j of the d axes' odd positions (n of them) times the others' even ones (n + 1).
    val counts = new Array[Long](d + 1)
    counts(0) = 1
    for n <- shape do
      var j = d
      while j >= 0 do
        counts(j) = counts(j) * (n + 1) + (if j > 0 then counts(j - 1) * n else 0L)
        j -= 1
    math.min(counts.take(k + 1).sum, Int.MaxValue.toLong - 8).toInt

  /** The reduction: every pair of degree `0 .. lastDim` in report order. With `cocycles`, also the cocycle of every row
    * `wanted` accepts.
    */
  private def pairing(
    lastDim: Int,
    cocycles: Boolean,
    wanted: (Int, Int, Int) => Boolean
  ): (Rows, mutable.LongMap[Chain[Cube, C]]) =
    val rows = Rows(cellsUpTo(math.min(lastDim, topDim)))
    val cocycleOf = mutable.LongMap.empty[Chain[Cube, C]]
    val cleared = new java.util.BitSet(totalCells)
    val working = WorkingColumn(youngestFirst = false)
    // V-columns: a pivot's seed is the birth of the row it closes (an apparent row's is found again from the grid), by
    // cell: a cell is the death of at most one row, in one dimension. -1 for no row.
    val seedOfDeath = if cocycles then filledWith(-1) else Array.empty[Int]

    for k <- 0 to math.min(lastDim, topDim) do
      val cells = level(k)
      val hasCofacets = k < topDim
      val basis = new mutable.LongMap[Column]()
      // A pivot's log is kept only when not empty; essential rows' logs by row.
      val logs = mutable.LongMap.empty[Log]
      val pending = mutable.ArrayBuffer.empty[(Int, Log)]
      val steps = LogBuilder()

      def report(sigma: Int, death: Int, log: Log, isApparent: Boolean): Unit =
        val row = rows.add(k, sigma, death, isApparent)
        if cocycles then
          if death >= 0 && !isApparent then
            seedOfDeath(death) = sigma
            if log.nonEmpty then logs(death.toLong) = log
          if wanted(k, sigma, death) then pending += ((row, log))

      var i = cells.length - 1
      while i >= 0 do // youngest first
        val sigma = cells(i)
        if !cleared.get(sigma) then
          if !hasCofacets then report(sigma, -1, noLog, isApparent = false)
          else
            val tau = if useApparentPairs then zeroApparentCofacet(sigma) else -1
            if tau >= 0 then
              cleared.set(tau)
              report(sigma, tau, noLog, isApparent = true)
            else
              foreachCofacet(sigma)((cell, c) => working.push(packed(cell), c))
              var reducing = working.pivot()
              while reducing do
                val pivotCell = working.pivotCell
                val pivotKey = keyOf(pivotCell)
                var redCoeff = fr.zero
                var found = true
                val stored = basis.getOrNull(pivotKey.toLong)
                if stored != null then
                  redCoeff = fr.divide(working.pivotCoefficient, stored.leadingCoefficient)
                  working.addScaled(stored, fr.negate(redCoeff))
                else
                  // An apparent pair's column is not stored: it is the coboundary of its birth cell, which has pivot
                  // `pivotKey` and distinct cells with coefficients +-1, so it is added as it is, uncombined.
                  val sigma = if useApparentPairs then zeroApparentFacet(pivotKey) else -1
                  if sigma < 0 then found = false
                  else
                    redCoeff = fr.divide(working.pivotCoefficient, cofacetCoefficient(sigma, pivotKey))
                    val factor = fr.negate(redCoeff)
                    foreachCofacet(sigma)((cell, c) => working.push(packed(cell), fr.times(factor, c)))
                if !found then reducing = false
                else
                  if cocycles then steps.add(pivotKey, redCoeff)
                  reducing = working.pivot()
                  // Each step must move the pivot on (younger): otherwise a column and the order disagree, and the
                  // loop would never end.
                  if reducing && working.pivotCell <= pivotCell then orderBug(pivotKey)
              val reduced = working.drain()
              val reductionLog = steps.drain()
              if reduced.isEmpty then report(sigma, -1, reductionLog, isApparent = false)
              else
                val pivotKey = reduced.keys(0)
                basis(pivotKey.toLong) = reduced
                cleared.set(pivotKey)
                report(sigma, pivotKey, reductionLog, isApparent = false)
        i -= 1

      if cocycles then
        val memo = mutable.LongMap.empty[SparseColumn]
        def seedOf(pivot: Int): Int =
          if seedOfDeath(pivot) >= 0 then seedOfDeath(pivot)
          else
            val sigma = zeroApparentFacet(pivot)
            if sigma < 0 then throw new IllegalStateException(s"pivot $pivot has a basis entry but no V-column")
            sigma
        def logOf(pivot: Int): Log = logs.getOrElse(pivot.toLong, noLog)
        for (row, log) <- pending do
          val v = expandV(rows.births(row), log, seedOf, logOf, memo)
          cocycleOf(row.toLong) = cubeChain(v, youngestFirst = false)
    (rows, cocycleOf)

  /** Every bar with its cocycle, as `CellularCohomologyEngine.persistentCohomology` gives it on the stream. */
  def persistentCohomology(includeZeroLength: Boolean = false): List[PersistenceBar[Double, Chain[Cube, C]]] =
    val keep = (_: Int, birth: Int, death: Int) => includeZeroLength || death < 0 || cellRank(birth) != cellRank(death)
    val (rows, cocycleOf) = pairing(topDim, cocycles = true, keep)
    var bars: List[PersistenceBar[Double, Chain[Cube, C]]] = Nil
    var row = rows.size - 1
    while row >= 0 do
      if includeZeroLength || !rows.isZeroLength(row) then bars = rows.bar(row, cocycleOf(row.toLong)) :: bars
      row -= 1
    bars

  /** Every bar with its cycle, as `CellularCohomologyEngine.persistentHomology` gives it on the stream: degrees up to
    * `topDim - 1` for a truncated grid (its declared `homologyDegreeLimit`), all of them for the whole grid.
    */
  def persistentHomology(includeZeroLength: Boolean = false): List[PersistenceBar[Double, Chain[Cube, C]]] =
    val limit = if topDim < d then topDim - 1 else topDim
    val (rows, _) = pairing(limit, cocycles = false, (_, _, _) => false)
    val reported = (row: Int) => includeZeroLength || !rows.isZeroLength(row)
    val cycles = involutedCycles(rows, reported)
    var bars: List[PersistenceBar[Double, Chain[Cube, C]]] = Nil
    var row = rows.size - 1
    while row >= 0 do
      if reported(row) then bars = rows.bar(row, cycles(row.toLong)) :: bars
      row -= 1
    bars

  /** Pushes the boundary of `tau` onto `working`. */
  private def pushBoundary(tau: Int, working: WorkingColumn): Unit =
    foreachFacet(tau)((cell, c) => working.push(packed(cell), c))

  // `Involution.cycles` specialised to packed cells, as `PackedRipserCohomologyEngine.involutedCycles` does it: death
  // columns reduced youngest-first, oldest death first per dimension; a finite bar's cycle is its reduced column. Every
  // death column is reduced and every pivot checked, zero-length ones included (they are pivots for the others), but an
  // apparent pair's column is not kept (its boundary is already reduced, so it is rebuilt from the death cell when
  // another column needs it). V-columns are needed only for essential bars: only non-empty logs are kept.
  private def involutedCycles(rows: Rows, reported: Int => Boolean): mutable.LongMap[Chain[Cube, C]] =
    val result = mutable.LongMap.empty[Chain[Cube, C]]
    val working = WorkingColumn(youngestFirst = true)
    // Keyed by the birth (pivot) cell, which is unique across dimensions: the reduced column unless the row is an
    // apparent pair, the reduction log when it is not empty. The row of a cell is by cell as well, for the birth and the
    // death of the rows reduced so far (a cell is only one of the two), -1 for none.
    val basis = mutable.LongMap.empty[Column]
    val logs = mutable.LongMap.empty[Log]
    val rowOfCell = filledWith(-1)

    val steps = LogBuilder()
    def reduce(seed: Int): Log =
      pushBoundary(seed, working)
      var reducing = working.pivot()
      while reducing do
        val pivotCell = working.pivotCell
        val pivotKey = keyOf(pivotCell)
        var redCoeff = fr.zero
        var found = true
        val stored = basis.getOrNull(pivotKey.toLong)
        if stored != null then
          redCoeff = fr.divide(working.pivotCoefficient, stored.leadingCoefficient)
          working.addScaled(stored, fr.negate(redCoeff))
        else if rowOfCell(pivotKey) >= 0 && rows.apparent(rowOfCell(pivotKey)) then
          // An apparent pair's column is not stored: it is the boundary of its death cell, which has pivot `pivotKey`
          // and distinct cells with coefficients +-1, so it is added as it is, uncombined.
          val death = rows.deaths(rowOfCell(pivotKey))
          redCoeff = fr.divide(working.pivotCoefficient, facetCoefficient(death, pivotKey))
          val factor = fr.negate(redCoeff)
          foreachFacet(death)((cell, c) => working.push(packed(cell), fr.times(factor, c)))
        else found = false
        if !found then reducing = false
        else
          steps.add(pivotKey, redCoeff)
          reducing = working.pivot()
          if reducing && working.pivotCell >= pivotCell then orderBug(pivotKey)
      steps.drain()

    // Finite rows by death dimension, each dimension's oldest death first: the death cells' packed values sorted, the row
    // found again from its death cell.
    var deathDim = 1
    while deathDim <= d do
      var count = 0
      var row = 0
      while row < rows.size do
        if rows.deaths(row) >= 0 && rows.dims(row) + 1 == deathDim then count += 1
        row += 1
      val deathOrder = new Array[Long](count)
      var n = 0
      row = 0
      while row < rows.size do
        if rows.deaths(row) >= 0 && rows.dims(row) + 1 == deathDim then
          deathOrder(n) = packed(rows.deaths(row))
          rowOfCell(rows.deaths(row)) = row
          n += 1
        row += 1
      java.util.Arrays.sort(deathOrder)
      var di = 0
      while di < deathOrder.length do
        val death = deathOrder(di)
        di += 1
        val row = rowOfCell(keyOf(death))
        val sigma = rows.births(row)
        rowOfCell(sigma) = row
        if rows.apparent(row) then
          // The boundary of an apparent pair's death cell is already reduced: its youngest facet is the birth cell,
          // which has no column yet (each cell is born once), so the reduction would stop at once with an empty log.
          if reported(row) then
            pushBoundary(keyOf(death), working)
            result(row.toLong) = cycleChain(working.drain())
        else
          val log = reduce(keyOf(death))
          if !working.pivot() || keyOf(working.pivotCell) != sigma then
            throw new IllegalStateException(
              s"Involution: the boundary of ${cubeOf(keyOf(death)).show} does not reduce to its paired birth cell " +
                s"${cubeOf(sigma).show} (the pairing and the order disagree)"
            )
          if log.nonEmpty then logs(sigma.toLong) = log
          val column = working.drain()
          basis(sigma.toLong) = column
          if reported(row) then result(row.toLong) = cycleChain(column)
      deathDim += 1

    // Essential rows: the boundary of the birth cell reduces to zero against the death columns of its dimension; the
    // cycle is the V-column of that reduction.
    val memo = mutable.LongMap.empty[SparseColumn]
    for row <- 0 until rows.size if rows.deaths(row) < 0 && reported(row) do
      val sigma = rows.births(row)
      result(row.toLong) =
        if rows.dims(row) == 0 then cubeChain(SparseColumn(Array(sigma), Array(fr.one)), youngestFirst = true)
        else
          val log = reduce(sigma)
          if working.pivot() then
            throw new IllegalStateException(
              s"Involution: the boundary of the essential cell ${cubeOf(sigma).show} does not reduce to zero"
            )
          val v = expandV(
            sigma,
            log,
            s => rows.deaths(rowOfCell(s)),
            s =>
              if rowOfCell(s) < 0 then throw new IllegalStateException(s"pivot $s has a basis entry but no V-column")
              logs.getOrElse(s.toLong, noLog)
            ,
            memo
          )
          cubeChain(v, youngestFirst = true)
    result

object PackedCubicalCohomologyEngine:
  /** The engine on `grid`'s cells up to dimension `topDim` (the whole grid by default). */
  def apply[C: Field](grid: CubicalGridStream, topDim: Int): PackedCubicalCohomologyEngine[C] =
    new PackedCubicalCohomologyEngine[C](grid, topDim, GridRanks(grid.shape, grid.topCellValues))

/** The packed engine's order on the cubes of one grid, the generic engine's within a dimension: the rank of a cell's
  * value, then its doubled-grid index. Every representative keeps its order, so it holds only the rank table and the
  * strides, never the engine.
  */
open class GridCellOrder(cellRank: Array[Int], weight: Array[Int]) extends Ordering[Cube]:
  private def key(c: Cube): Int =
    val e = c.encoded
    var k = 0
    var i = 0
    while i < weight.length do
      k += e(i) * weight(i)
      i += 1
    k

  def compare(x: Cube, y: Cube): Int =
    val kx = key(x)
    val ky = key(y)
    val byRank = Integer.compare(cellRank(kx), cellRank(ky))
    if byRank != 0 then byRank else Integer.compare(kx, ky)
