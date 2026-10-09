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
  * [[PackedRipserCohomologyEngine]] does it. Refuses an image with a NaN value (as [[GridRanks]] does).
  */
private[tda4j] final class PackedCubicalCohomologyEngine[C: Field](
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
  private val extent: Array[Int] = shape.map(n => 2 * n + 1)
  private val totalCells: Int = GridRanks.checkedProduct(extent, "PackedCubicalCohomologyEngine")
  private val weight: Array[Int] = GridRanks.strides(extent)

  /** Every cell's rank: the smallest rank of the pixels containing it (separable, as `CubicalGridStream`'s values). */
  private val cellRank: Array[Int] =
    val r = Array.fill(totalCells)(Int.MaxValue)
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

  private val boxes = CubeBoxes(shape)
  private def cubeOf(key: Int): Cube = boxes.cube(i => (key / weight(i)) % extent(i))
  private def keyOfCube(c: Cube): Int =
    val e = c.encoded
    var key = 0
    var i = 0
    while i < d do
      key += e(i) * weight(i)
      i += 1
    key

  /** The generic engine's order on the cells of one dimension; its reverse for cycles. */
  private val olderFirst: Ordering[Cube] = new Ordering[Cube]:
    def compare(x: Cube, y: Cube): Int = java.lang.Long.compare(packed(keyOfCube(x)), packed(keyOfCube(y)))

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

  /** Each cofacet of `sigma` with the coefficient of `sigma` in its boundary. Only for `sigma` of dimension below
    * `topDim`: the complex has no cell above it.
    */
  private inline def foreachCofacet(sigma: Int)(inline f: (Int, C) => Unit): Unit =
    decode(sigma)
    var oddBefore = 0
    var a = 0
    while a < d do
      val c = coords(a)
      if (c & 1) == 0 then
        if c > 0 then f(sigma - weight(a), if (oddBefore & 1) == 0 then upperEven else upperOdd)
        if c < extent(a) - 1 then f(sigma + weight(a), if (oddBefore & 1) == 0 then lowerEven else lowerOdd)
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

  /** The cells of dimension `k`, oldest first (rank, then index): a stable counting sort by rank of the cells taken in
    * ascending index.
    */
  private def level(k: Int): Array[Int] =
    val m = distinct.length
    val start = new Array[Int](m + 1)
    foreachCellAscending(k)(key => start(cellRank(key) + 1) += 1)
    var r = 1
    while r <= m do
      start(r) += start(r - 1)
      r += 1
    val out = new Array[Int](start(m))
    foreachCellAscending(k) { key =>
      val rank = cellRank(key)
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

  /** A reduced column: each cell once with its non-zero coefficient, the pivot first. */
  private final class Column(val cells: Array[Long], val coefficients: Array[Any]):
    def leadingCoefficient: C = coefficients(0).asInstanceOf[C]
    def isEmpty: Boolean = cells.isEmpty

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
      while k < column.cells.length do
        push(column.cells(k), fr.times(factor, column.coefficients(k).asInstanceOf[C]))
        k += 1

    // Combining here keeps repeats and cancelling pairs out of the stored columns, which are added again and again:
    // left in, they made the 3-D reductions superlinear (64³ noise: 40 s; .claude/WORKLOG-cubical-performance.md).
    /** Empties the column into a reduced column: every cell once, with its non-zero combined coefficient, in heap order
      * (the pivot first); empty if the column is zero.
      */
    def drain(): Column =
      val outCells = new Array[Long](size)
      val outCoefficients = new Array[Any](size)
      var n = 0
      while size > 0 do
        val cell = cells(0)
        var sum = coef(0).asInstanceOf[C]
        popTop()
        while size > 0 && cells(0) == cell do
          sum = fr.plus(sum, coef(0).asInstanceOf[C])
          popTop()
        if !fr.isEqual(sum, fr.zero) then
          outCells(n) = cell
          outCoefficients(n) = sum
          n += 1
      if n == outCells.length then Column(outCells, outCoefficients)
      else
        Column(
          java.util.Arrays.copyOf(outCells, n),
          java.util.Arrays.copyOf(outCoefficients.asInstanceOf[Array[AnyRef]], n).asInstanceOf[Array[Any]]
        )

  /** The pairing in report order (degree ascending, then the birth cell youngest first), one row per pair, zero-length
    * ones included.
    */
  private final class Rows:
    var size: Int = 0
    var dims: Array[Byte] = new Array[Byte](1024)
    var births: Array[Int] = new Array[Int](1024)
    var deaths: Array[Int] = new Array[Int](1024) // -1: essential
    var apparent: Array[Boolean] = new Array[Boolean](1024)

    def add(dim: Int, birth: Int, death: Int, isApparent: Boolean): Int =
      if size == births.length then
        val grown = math.min(2L * size, Int.MaxValue.toLong - 8).toInt
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

  private val noLog: Array[(Int, C)] = Array.empty

  private def orderBug(pivot: Int): Nothing =
    throw new IllegalStateException(
      s"an engine bug: reducing by the column of ${cubeOf(pivot).show} did not remove it (a column and the order disagree)"
    )

  /** A V-column: each cell once, with its non-zero coefficient. */
  private final class SparseColumn(val cells: Array[Int], val coefficients: Array[Any]):
    def iterator: Iterator[(Int, C)] = cells.indices.iterator.map(k => (cells(k), coefficients(k).asInstanceOf[C]))

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
    log: Array[(Int, C)],
    seedOf: Int => Int,
    logOf: Int => Array[(Int, C)],
    memo: mutable.LongMap[SparseColumn]
  ): SparseColumn =
    def fold(seed: Int, log: Array[(Int, C)]): SparseColumn =
      if log.isEmpty then SparseColumn(Array(seed), Array(fr.one))
      else
        vSums.set(seed, fr.one)
        for (pivot, coeff) <- log do
          val v = memo(pivot.toLong)
          var k = 0
          while k < v.cells.length do
            vSums.subtract(v.cells(k), fr.times(coeff, v.coefficients(k).asInstanceOf[C]))
            k += 1
        vSums.drain()
    val stack = mutable.Stack.empty[Int]
    log.foreach((pivot, _) => stack.push(pivot))
    while stack.nonEmpty do
      val s = stack.top
      if memo.contains(s.toLong) then stack.pop()
      else
        val sLog = logOf(s)
        val missing = sLog.iterator.map(_._1).filterNot(p => memo.contains(p.toLong)).toList
        if missing.nonEmpty then missing.foreach(stack.push)
        else
          memo(s.toLong) = fold(seedOf(s), sLog)
          stack.pop()
    fold(seed, log)

  /** `terms` (cells by index, possibly repeated) combined, without zeros, as a chain of cubes under `order`. */
  private def cubeChain(terms: Iterator[(Int, C)], order: Ordering[Cube]): Chain[Cube, C] =
    val sums = mutable.LongMap.empty[C]
    for (key, c) <- terms do
      sums.updateWith(key.toLong) {
        case Some(x) => Some(fr.plus(x, c))
        case None    => Some(c)
      }
    Chain.from(sums.iterator.collect { case (key, c) if !fr.isEqual(c, fr.zero) => (cubeOf(key.toInt), c) }.toSeq)(using
      order
    )

  /** The reduction: every pair of degree `0 .. lastDim` in report order. With `cocycles`, also the cocycle of every row
    * `wanted` accepts.
    */
  private def pairing(
    lastDim: Int,
    cocycles: Boolean,
    wanted: (Int, Int, Int) => Boolean
  ): (Rows, mutable.LongMap[Chain[Cube, C]]) =
    val rows = Rows()
    val cocycleOf = mutable.LongMap.empty[Chain[Cube, C]]
    val cleared = new java.util.BitSet(totalCells)
    val working = WorkingColumn(youngestFirst = false)
    val fallbackColumn = WorkingColumn(youngestFirst = false)

    for k <- 0 to math.min(lastDim, topDim) do
      val cells = level(k)
      val hasCofacets = k < topDim
      val basis = new mutable.LongMap[Column]()
      // V-columns: a pivot's seed is the birth of the row it closes (an apparent row's is found again from the grid),
      // its log kept only when not empty; essential rows' logs by row.
      val seedOfDeath = if cocycles then LongIntMap(cells.length / 4) else LongIntMap()
      val logs = mutable.LongMap.empty[Array[(Int, C)]]
      val pending = mutable.ArrayBuffer.empty[(Int, Array[(Int, C)])]

      def report(sigma: Int, death: Int, log: Array[(Int, C)], isApparent: Boolean): Unit =
        val row = rows.add(k, sigma, death, isApparent)
        if cocycles then
          if death >= 0 && !isApparent then
            seedOfDeath(death.toLong) = sigma
            if log.nonEmpty then logs(death.toLong) = log
          if wanted(k, sigma, death) then pending += ((row, log))

      def fallback(tau: Int): Option[Column] =
        if !useApparentPairs then None
        else
          val sigma = zeroApparentFacet(tau)
          if sigma < 0 then None
          else
            foreachCofacet(sigma)((cell, c) => fallbackColumn.push(packed(cell), c))
            Some(fallbackColumn.drain())

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
              val log = mutable.ArrayBuffer.empty[(Int, C)]
              var reducing = working.pivot()
              while reducing do
                val pivotCell = working.pivotCell
                val pivotKey = keyOf(pivotCell)
                basis.get(pivotKey.toLong).orElse(fallback(pivotKey)) match
                  case None         => reducing = false
                  case Some(column) =>
                    val redCoeff = fr.divide(working.pivotCoefficient, column.leadingCoefficient)
                    working.addScaled(column, fr.negate(redCoeff))
                    if cocycles then log += ((pivotKey, redCoeff))
                    reducing = working.pivot()
                    // Each step must move the pivot on (younger): otherwise a column and the order disagree, and the
                    // loop would never end.
                    if reducing && working.pivotCell <= pivotCell then orderBug(pivotKey)
              val reduced = working.drain()
              val reductionLog = if log.isEmpty then noLog else log.toArray
              if reduced.isEmpty then report(sigma, -1, reductionLog, isApparent = false)
              else
                val pivotKey = keyOf(reduced.cells(0))
                basis(pivotKey.toLong) = reduced
                cleared.set(pivotKey)
                report(sigma, pivotKey, reductionLog, isApparent = false)
        i -= 1

      if cocycles then
        val memo = mutable.LongMap.empty[SparseColumn]
        def seedOf(pivot: Int): Int =
          if seedOfDeath.contains(pivot.toLong) then seedOfDeath(pivot.toLong)
          else
            val sigma = zeroApparentFacet(pivot)
            if sigma < 0 then throw new IllegalStateException(s"pivot $pivot has a basis entry but no V-column")
            sigma
        def logOf(pivot: Int): Array[(Int, C)] = logs.getOrElse(pivot.toLong, noLog)
        for (row, log) <- pending do
          val v = expandV(rows.births(row), log, seedOf, logOf, memo)
          cocycleOf(row.toLong) = cubeChain(v.iterator, olderFirst)
    (rows, cocycleOf)

  /** Every bar with its cocycle, as `CellularCohomologyEngine.persistentCohomology` gives it on the stream. */
  def persistentCohomology(includeZeroLength: Boolean = false): List[PersistenceBar[Double, Chain[Cube, C]]] =
    val keep = (_: Int, birth: Int, death: Int) => includeZeroLength || death < 0 || cellRank(birth) != cellRank(death)
    val (rows, cocycleOf) = pairing(topDim, cocycles = true, keep)
    List.tabulate(rows.size)(identity).collect {
      case row if includeZeroLength || !rows.isZeroLength(row) => rows.bar(row, cocycleOf(row.toLong))
    }

  /** Every bar with its cycle, as `CellularCohomologyEngine.persistentHomology` gives it on the stream: degrees up to
    * `topDim - 1` for a truncated grid (its declared `homologyDegreeLimit`), all of them for the whole grid.
    */
  def persistentHomology(includeZeroLength: Boolean = false): List[PersistenceBar[Double, Chain[Cube, C]]] =
    val limit = if topDim < d then topDim - 1 else topDim
    val (rows, _) = pairing(limit, cocycles = false, (_, _, _) => false)
    val reported = (row: Int) => includeZeroLength || !rows.isZeroLength(row)
    val cycles = involutedCycles(rows, reported)
    List.tabulate(rows.size)(identity).collect {
      case row if reported(row) => rows.bar(row, cycles(row.toLong))
    }

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
    val youngestFirst = olderFirst.reverse
    val working = WorkingColumn(youngestFirst = true)
    val rebuilt = WorkingColumn(youngestFirst = true)
    // Keyed by the birth (pivot) cell, which is unique across dimensions: the reduced column unless the row is an
    // apparent pair, the reduction log when it is not empty, the row.
    val basis = mutable.LongMap.empty[Column]
    val logs = mutable.LongMap.empty[Array[(Int, C)]]
    val rowOfBirth = LongIntMap()

    def columnOf(pivot: Int): Option[Column] =
      basis.get(pivot.toLong).orElse {
        Option.when(rowOfBirth.contains(pivot.toLong) && rows.apparent(rowOfBirth(pivot.toLong))) {
          pushBoundary(rows.deaths(rowOfBirth(pivot.toLong)), rebuilt)
          rebuilt.drain()
        }
      }

    def reduce(seed: Int): Array[(Int, C)] =
      pushBoundary(seed, working)
      val log = mutable.ArrayBuffer.empty[(Int, C)]
      var reducing = working.pivot()
      while reducing do
        val pivotCell = working.pivotCell
        val pivotKey = keyOf(pivotCell)
        columnOf(pivotKey) match
          case None         => reducing = false
          case Some(column) =>
            val redCoeff = fr.divide(working.pivotCoefficient, column.leadingCoefficient)
            working.addScaled(column, fr.negate(redCoeff))
            log += ((pivotKey, redCoeff))
            reducing = working.pivot()
            if reducing && working.pivotCell >= pivotCell then orderBug(pivotKey)
      if log.isEmpty then noLog else log.toArray

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
      val rowOfDeath = LongIntMap(count)
      var n = 0
      row = 0
      while row < rows.size do
        if rows.deaths(row) >= 0 && rows.dims(row) + 1 == deathDim then
          deathOrder(n) = packed(rows.deaths(row))
          rowOfDeath(rows.deaths(row).toLong) = row
          n += 1
        row += 1
      java.util.Arrays.sort(deathOrder)
      for death <- deathOrder do
        val row = rowOfDeath(keyOf(death).toLong)
        val sigma = rows.births(row)
        rowOfBirth(sigma.toLong) = row
        if rows.apparent(row) then
          // The boundary of an apparent pair's death cell is already reduced: its youngest facet is the birth cell,
          // which has no column yet (each cell is born once), so the reduction would stop at once with an empty log.
          if reported(row) then
            pushBoundary(keyOf(death), working)
            val column = working.drain()
            result(row.toLong) = cubeChain(
              column.cells.indices.iterator.map(k => (keyOf(column.cells(k)), column.coefficients(k).asInstanceOf[C])),
              youngestFirst
            )
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
          if reported(row) then
            result(row.toLong) = cubeChain(
              column.cells.indices.iterator.map(k => (keyOf(column.cells(k)), column.coefficients(k).asInstanceOf[C])),
              youngestFirst
            )
      deathDim += 1

    // Essential rows: the boundary of the birth cell reduces to zero against the death columns of its dimension; the
    // cycle is the V-column of that reduction.
    val memo = mutable.LongMap.empty[SparseColumn]
    for row <- 0 until rows.size if rows.deaths(row) < 0 && reported(row) do
      val sigma = rows.births(row)
      result(row.toLong) =
        if rows.dims(row) == 0 then Chain.from(Seq((cubeOf(sigma), fr.one)))(using youngestFirst)
        else
          val log = reduce(sigma)
          if working.pivot() then
            throw new IllegalStateException(
              s"Involution: the boundary of the essential cell ${cubeOf(sigma).show} does not reduce to zero"
            )
          val v = expandV(
            sigma,
            log,
            s => rows.deaths(rowOfBirth(s.toLong)),
            s =>
              if !rowOfBirth.contains(s.toLong) then
                throw new IllegalStateException(s"pivot $s has a basis entry but no V-column")
              logs.getOrElse(s.toLong, noLog)
            ,
            memo
          )
          cubeChain(v.iterator, youngestFirst)
    result

private[tda4j] object PackedCubicalCohomologyEngine:
  /** The engine on `grid`'s cells up to dimension `topDim` (the whole grid by default). */
  def apply[C: Field](grid: CubicalGridStream, topDim: Int): PackedCubicalCohomologyEngine[C] =
    new PackedCubicalCohomologyEngine[C](grid, topDim, GridRanks(grid.shape, grid.topCellValues))
