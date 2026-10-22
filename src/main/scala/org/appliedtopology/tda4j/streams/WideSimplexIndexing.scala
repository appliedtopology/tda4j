package org.appliedtopology.tda4j

/** The combinatorial number system of [[SimplexIndexing]] with indices of up to 126 bits, for vertex counts and
  * dimensions whose indices do not fit in a `Long` ([[SimplexIndexing.fitsLong]]). An index is two words, both
  * non-negative: `low` holds its lowest 63 bits and `high` the bits above, so index order is the order of
  * `(high, low)`. The cursors find the same cofacets and facets as [[SimplexIndexing]]'s, in the same order.
  */
open class WideSimplexIndexing(val vertexCount: Int):
  import WideSimplexIndexing.Mask

  // C(n, k) for n in 0 .. vertexCount, row k built from row k - 1 by Pascal's rule when first needed.
  private var highRows: Array[Array[Long]] = Array.empty
  private var lowRows: Array[Array[Long]] = Array.empty

  private def row(k: Int): Int =
    if k >= lowRows.length then
      val grown = k + 1
      highRows = java.util.Arrays.copyOf(highRows, grown)
      lowRows = java.util.Arrays.copyOf(lowRows, grown)
    if lowRows(k) == null then
      val hs = new Array[Long](vertexCount + 1)
      val ls = new Array[Long](vertexCount + 1)
      if k == 0 then java.util.Arrays.fill(ls, 1L)
      else
        row(k - 1)
        val ph = highRows(k - 1)
        val pl = lowRows(k - 1)
        var n = 1
        while n <= vertexCount do
          // C(n, k) = C(n - 1, k) + C(n - 1, k - 1)
          var l = ls(n - 1) + pl(n - 1)
          var h = hs(n - 1) + ph(n - 1)
          if l < 0 then
            l &= Mask
            h += 1
          if h < 0 then
            throw new IllegalArgumentException(
              s"binomial($n, $k) needs more than 126 bits -- this complex is too large to index"
            )
          hs(n) = h
          ls(n) = l
          n += 1
      highRows(k) = hs
      lowRows(k) = ls
    k

  /** The high word of `C(n, k)`, `0` when `k < 0` or `n < k`. */
  // `row(k)` first: it may replace the arrays of rows.
  def chooseHigh(n: Int, k: Int): Long =
    if k < 0 || n < k then 0L
    else
      val r = row(k)
      highRows(r)(n)

  /** The low word of `C(n, k)`, `0` when `k < 0` or `n < k`. */
  def chooseLow(n: Int, k: Int): Long =
    if k < 0 || n < k then 0L
    else
      val r = row(k)
      lowRows(r)(n)

  // The largest vertex v in [d - 1, vertexCount - 1] with C(v, d) <= (high, low): C(v, d) increases in v.
  private def topVertex(d: Int, high: Long, low: Long): Int =
    val r = row(d)
    val hs = highRows(r)
    val ls = lowRows(r)
    var lo = d - 1
    var hi = vertexCount - 1
    while lo < hi do
      val mid = lo + (hi - lo + 1) / 2
      if hs(mid) < high || (hs(mid) == high && ls(mid) <= low) then lo = mid else hi = mid - 1
    lo

  /** The vertices, ascending, of the simplex with `size` vertices at index `(high, low)`. */
  def decodeToArray(high: Long, low: Long, size: Int): Array[Int] =
    val result = new Array[Int](size)
    var h = high
    var l = low
    var d = size
    while d > 0 do
      if h == 0L && l == 0L then
        // The remaining d vertices are 0 .. d - 1.
        var v = 0
        while v < d do
          result(d - 1 - v) = d - 1 - v
          v += 1
        d = 0
      else
        val v = topVertex(d, h, l)
        result(d - 1) = v
        l -= chooseLow(v, d)
        h -= chooseHigh(v, d)
        if l < 0 then
          l &= Mask
          h -= 1
        d -= 1
    result

  /** The index of a simplex from its vertices in ascending order, as `(high, low)`. */
  def index(vertices: Array[Int]): (Long, Long) =
    var h = 0L
    var l = 0L
    var i = 0
    while i < vertices.length do
      l += chooseLow(vertices(i), i + 1)
      h += chooseHigh(vertices(i), i + 1)
      if l < 0 then
        l &= Mask
        h += 1
      i += 1
    (h, l)

  /** A running two-word sum: `b` (the part of the start index from the vertices not yet passed) less what is
    * subtracted, `a` (the shifted part from the vertices passed) plus what is added; [[CofacetCursor]]'s `iB` and `iA`.
    */
  private class Sums(var bHigh: Long, var bLow: Long):
    var aHigh: Long = 0L
    var aLow: Long = 0L
    def subtractB(n: Int, k: Int): Unit =
      bLow -= chooseLow(n, k)
      bHigh -= chooseHigh(n, k)
      if bLow < 0 then
        bLow &= Mask
        bHigh -= 1
    def addA(n: Int, k: Int): Unit =
      aLow += chooseLow(n, k)
      aHigh += chooseHigh(n, k)
      if aLow < 0 then
        aLow &= Mask
        aHigh += 1
    // b + C(n, k) + a, into resultHigh/resultLow.
    var resultHigh: Long = 0L
    var resultLow: Long = 0L
    def total(n: Int, k: Int): Unit =
      var l = bLow + chooseLow(n, k)
      var h = bHigh + chooseHigh(n, k)
      if l < 0 then
        l &= Mask
        h += 1
      l += aLow
      h += aHigh
      if l < 0 then
        l &= Mask
        h += 1
      resultHigh = h
      resultLow = l

  /** [[SimplexIndexing.CofacetCursor]] with two-word indices: read `vertex` (the added vertex), `high` and `low` (the
    * cofacet's index), then `advance()`.
    */
  class CofacetCursor(startHigh: Long, startLow: Long, size: Int, allCofacets: Boolean):
    private val vertices: Array[Int] = decodeToArray(startHigh, startLow, size)
    private val sums = Sums(startHigh, startLow)
    private var k: Int = size
    private var j: Int = vertexCount - 1
    private var above: Int = size - 1 // the largest vertex of the simplex not yet passed
    private var _vertex: Int = -1
    private var havePending: Boolean = false
    private var done: Boolean = false

    private def step(): Unit =
      while !havePending && !done do
        if j < 0 then done = true
        else if above >= 0 && vertices(above) == j then
          if !allCofacets then done = true
          else
            sums.subtractB(j, k)
            sums.addA(j, k + 1)
            k -= 1
            above -= 1
            j -= 1
        else
          _vertex = j
          sums.total(j, k + 1)
          havePending = true
          j -= 1

    step()

    def hasNext: Boolean = havePending
    def vertex: Int = _vertex
    def high: Long = sums.resultHigh
    def low: Long = sums.resultLow
    def advance(): Unit =
      havePending = false
      step()

  def cofacetCursor(high: Long, low: Long, size: Int, allCofacets: Boolean = true): CofacetCursor =
    new CofacetCursor(high, low, size, allCofacets)

  /** [[SimplexIndexing.SparseCofacetCursor]] with two-word indices: the cofacets whose added vertex is a neighbour of
    * every vertex of the simplex, from the neighbour lists.
    */
  class SparseCofacetCursor(
    startHigh: Long,
    startLow: Long,
    vertices: Array[Int],
    allCofacets: Boolean,
    lists: NeighbourLists
  ):
    private val size = vertices.length
    private val pointers: Array[Int] = Array.tabulate(size)(a => lists.offsets(vertices(a) + 1) - 1)
    private val sums = Sums(startHigh, startLow)
    private var k: Int = size
    private var above: Int = size - 1
    private var _vertex: Int = -1
    private var _maxDistance: Double = 0.0
    private var havePending: Boolean = false
    private var done: Boolean = size == 0

    private def step(): Unit =
      havePending = false
      while !havePending && !done do
        val p0 = pointers(0)
        if p0 < lists.offsets(vertices(0)) then done = true
        else
          val candidate = lists.targets(p0)
          if !allCofacets && candidate < vertices(size - 1) then done = true
          else
            var common = true
            var a = 1
            while a < size && !done do
              val start = lists.offsets(vertices(a))
              var p = pointers(a)
              while p >= start && lists.targets(p) > candidate do p -= 1
              pointers(a) = p
              if p < start then done = true
              else if lists.targets(p) < candidate then common = false
              a += 1
            if !done then
              if common then
                var maxD = lists.distances(p0)
                a = 1
                while a < size do
                  val d = lists.distances(pointers(a))
                  if d > maxD then maxD = d
                  a += 1
                while above >= 0 && vertices(above) > candidate do
                  val j = vertices(above)
                  sums.subtractB(j, k)
                  sums.addA(j, k + 1)
                  k -= 1
                  above -= 1
                _vertex = candidate
                sums.total(candidate, k + 1)
                _maxDistance = maxD
                havePending = true
              pointers(0) = p0 - 1

    step()

    def hasNext: Boolean = havePending
    def vertex: Int = _vertex
    def high: Long = sums.resultHigh
    def low: Long = sums.resultLow
    def maxDistance: Double = _maxDistance
    def position: Int = k
    def advance(): Unit = step()

  def sparseCofacetCursor(
    high: Long,
    low: Long,
    vertices: Array[Int],
    allCofacets: Boolean,
    lists: NeighbourLists
  ): SparseCofacetCursor =
    new SparseCofacetCursor(high, low, vertices, allCofacets, lists)

  /** [[SimplexIndexing.FacetCursor]] with two-word indices: `vertex` is the vertex removed to reach the facet at
    * `(high, low)`; the indices increase.
    */
  class FacetCursor(startHigh: Long, startLow: Long, size: Int):
    private val vertices: Array[Int] = decodeToArray(startHigh, startLow, size)
    private val sums = Sums(startHigh, startLow)
    private var k: Int = size - 1
    private var _vertex: Int = -1
    private var havePending: Boolean = false

    private def step(): Unit =
      if k >= 0 then
        val v = vertices(k)
        sums.subtractB(v, k + 1)
        // The facet's index is b + a with a from before this vertex's own term.
        sums.total(0, 1) // C(0, 1) = 0
        _vertex = v
        sums.addA(v, k)
        k -= 1
        havePending = true
      else havePending = false

    step()

    def hasNext: Boolean = havePending
    def vertex: Int = _vertex
    def high: Long = sums.resultHigh
    def low: Long = sums.resultLow
    def advance(): Unit = step()

  def facetCursor(high: Long, low: Long, size: Int): FacetCursor =
    new FacetCursor(high, low, size)

object WideSimplexIndexing:
  /** The low word's 63 bits. */
  inline val Mask = 0x7fffffffffffffffL

  /** Whether the indices of simplices with up to `size` of `vertexCount` vertices fit in two words (126 bits). */
  def fits(vertexCount: Int, size: Int): Boolean =
    (0 to math.min(size, vertexCount)).forall(k => binomial(vertexCount, k).bitLength <= 126)

  /** `n` choose `k`, exactly; `0` when `k < 0`, `n < 0` or `k > n`. */
  def binomial(n: Int, k: Int): BigInt =
    if k < 0 || n < 0 || n < k then BigInt(0)
    else
      var result = BigInt(1)
      var i = 1
      while i <= k do
        result = result * (n - k + i) / i
        i += 1
      result

  /** A two-word index as a `BigInt`. */
  def toBigInt(high: Long, low: Long): BigInt = (BigInt(high) << 63) + low

  /** The high word of an index. */
  def highOf(index: BigInt): Long = (index >> 63).toLong

  /** The low word of an index. */
  def lowOf(index: BigInt): Long = (index & BigInt(Long.MaxValue)).toLong
