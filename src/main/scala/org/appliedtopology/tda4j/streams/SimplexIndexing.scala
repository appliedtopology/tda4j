package org.appliedtopology.tda4j

import org.apache.commons.numbers.combinatorics

import scala.annotation.tailrec

open class SimplexIndexing(val vertexCount: Int):

  /** Memoized `binomial(d + s, s)` by row `d`, filled on demand: `d` is bounded by the size of a simplex, so rows stay
    * short. `-1L` marks an entry not yet computed.
    */
  private var binomialRows: Array[Array[Long]] = Array.empty
  private def binomialEntry(d: Int, s: Int): Long =
    if d >= binomialRows.length then
      val grown = Array.ofDim[Array[Long]](d + 1)
      Array.copy(binomialRows, 0, grown, 0, binomialRows.length)
      binomialRows = grown
    if binomialRows(d) == null then binomialRows(d) = Array.fill(vertexCount + 1)(-1L)
    val row = binomialRows(d)
    if row(s) == -1L then row(s) = SimplexIndexing.binomial(d + s, s)
    row(s)

  /** Memoized `binomial(n, k)` with the small `k` (at most the size of a simplex) as the row and `n` (a vertex) as the
    * column, for [[CofacetCursor]], [[FacetCursor]] and `apply`.
    */
  private var binomialChooseRows: Array[Array[Long]] = Array.empty
  private def binomialChoose(n: Int, k: Int): Long =
    if k < 0 || n < k then 0L
    else
      if k >= binomialChooseRows.length then
        val grown = Array.ofDim[Array[Long]](k + 1)
        Array.copy(binomialChooseRows, 0, grown, 0, binomialChooseRows.length)
        binomialChooseRows = grown
      if binomialChooseRows(k) == null then binomialChooseRows(k) = Array.fill(vertexCount + 1)(-1L)
      val row = binomialChooseRows(k)
      if row(n) == -1L then row(n) = SimplexIndexing.binomial(n, k)
      row(n)

  /** The largest `s` in `[0, vertexCount]` with `binomialEntry(d, s) <= n`, by binary search (the entries increase in
    * `s`), computing only the entries it visits.
    */
  private def searchRow(d: Int, n: Long): Int =
    var lo = 0
    var hi = vertexCount
    while lo < hi do
      val mid = lo + (hi - lo + 1) / 2
      if binomialEntry(d, mid) <= n then lo = mid else hi = mid - 1
    lo

  /** The `n`th subset of size `d` of the vertices (a `(d-1)`-simplex) in the combinatorial number system. For `n`
    * beyond `vertexCount` choose `d` the result is not a subset of size `d`.
    */
  @tailrec
  final def apply(n: Long, d: Int, upperAccum: Simplex[Int] = ∆()): Simplex[Int] =
    if d < 0 then return upperAccum
    if n <= 0 then return upperAccum ++ (0 until d).toSet
    if d == 0 then return upperAccum + n.toInt
    val id: Int = searchRow(d, n)
    apply(n - binomialEntry(d, id), d - 1, upperAccum + (id + d))

  /** Same decode as `apply(n, size)`, but returns the vertex set as a plain sorted `Array[Int]` instead of a
    * `Simplex[Int]`/`SortedSet[Int]` -- for callers (`PackedRipserCohomologyEngine.sparseCofacets`/`coboundaryOf`/
    * `zeroPivotCofacet`) that only ever wanted `si(index, size).underlying.toArray` and threw the decoded `Simplex`
    * away immediately afterward. `apply`'s own `upperAccum + (id + d)` builds the result via `size` separate
    * persistent-tree insertions (each an O(log size) allocation this throwaway value never needs). This method performs
    * the identical `searchRow`/`binomialEntry` arithmetic as `apply` -- a direct transcription of the same three base
    * cases and recursive step -- but writes each vertex into a pre-sized `Array[Int]` and sorts once at the end
    * (`java.util.Arrays.sort`, in-place, zero allocation) instead. Verified against `apply` directly (same vertex set,
    * for every `(index, size)` pair the recursion can reach) in `SimplexIndexingSpec`'s property test; `apply` itself
    * is left unchanged, so any future divergence between the two shows up as a test failure.
    *
    * `apply`'s own `Simplex[Int]`-returning callers are NOT switched to this method: building a `SortedSet[Int]` from
    * an already-sorted array is not obviously cheaper than `apply`'s own incremental construction (`TreeSet` has no
    * exposed O(n) bulk-build-from-sorted-input path), so there's no clear win, only a different allocation shape --
    * left alone rather than "fixed" without a measurement to justify it.
    */
  def decodeToArray(n0: Long, size: Int): Array[Int] =
    val result = new Array[Int](size)
    var cursor = 0
    var n = n0
    var d = size
    var continue = true
    while continue do
      if d < 0 then
        // Unreachable in practice (mirrors `apply`'s own dead `d < 0` guard -- `d == 0` always short-circuits
        // below before `d` could ever go negative), kept only for exact parity with `apply`'s branch structure.
        continue = false
      else if n <= 0 then
        // Remaining `d` vertices are exactly {0, ..., d-1} -- `apply`'s `upperAccum ++ (0 until d).toSet`.
        var v = 0
        while v < d do
          result(cursor) = v
          cursor += 1
          v += 1
        continue = false
      else if d == 0 then
        // `apply`'s `upperAccum + n.toInt` base case.
        result(cursor) = n.toInt
        cursor += 1
        continue = false
      else
        val id: Int = searchRow(d, n)
        result(cursor) = id + d
        cursor += 1
        n -= binomialEntry(d, id)
        d -= 1
    java.util.Arrays.sort(result)
    result

  def cofacetIterator(simplex: Simplex[Int]): Iterator[Long] =
    cofacetIterator(apply(simplex), simplex.size, true)
  def topCofacetIterator(simplex: Simplex[Int]): Iterator[Long] =
    cofacetIterator(apply(simplex), simplex.size, false)
  def cofacetIterator(
    index: Long,
    size: Int,
    allCofacets: Boolean = true
  ): Iterator[Long] =
    cofacetIteratorWithVertex(index, size, allCofacets).map((_, idx) => idx)

  /** A cursor over the cofacets of a simplex that allocates nothing per step: read `vertex` (the added vertex) and
    * `index` (the cofacet's index) as often as needed, then `advance()`.
    */
  class CofacetCursor(startIndex: Long, size: Int, allCofacets: Boolean):
    private val vertices: Array[Int] = decodeToArray(startIndex, size)
    private var iB: Long = startIndex
    private var iA: Long = 0L
    private var k: Int = size
    private var j: Int = vertexCount - 1
    private var _vertex: Int = -1
    private var _index: Long = -1L
    private var havePending: Boolean = false
    private var done: Boolean = false

    private def containsVertex(v: Int): Boolean =
      var lo = 0
      var hi = vertices.length - 1
      var found = false
      while lo <= hi && !found do
        val mid = (lo + hi) >>> 1
        val mv = vertices(mid)
        if mv == v then found = true
        else if mv < v then lo = mid + 1
        else hi = mid - 1
      found

    private def step(): Unit =
      while !havePending && !done do
        if j < 0 then done = true
        else if containsVertex(j) then
          if !allCofacets then done = true
          else
            iB -= binomialChoose(j, k)
            iA += binomialChoose(j, k + 1)
            k -= 1
            j -= 1
        else
          _vertex = j
          _index = iB + binomialChoose(j, k + 1) + iA
          havePending = true
          j -= 1

    step()

    def hasNext: Boolean = havePending
    def vertex: Int = _vertex
    def index: Long = _index
    def advance(): Unit =
      havePending = false
      step()

  def cofacetCursor(index: Long, size: Int, allCofacets: Boolean = true): CofacetCursor =
    new CofacetCursor(index, size, allCofacets)

  /** The cofacets of a simplex whose added vertex is a neighbour of every one of its vertices: the same cofacets, in
    * the same order (vertex, and so index, strictly decreasing) and with the same indices as `CofacetCursor`, but found
    * by intersecting the vertices' neighbour lists instead of trying every vertex. `maxDistance` is the largest
    * distance from the added vertex to the simplex's vertices, read from the lists; `position` is the number of the
    * simplex's vertices below the added one.
    */
  class SparseCofacetCursor(
    startIndex: Long,
    vertices: Array[Int],
    allCofacets: Boolean,
    lists: NeighbourLists
  ):
    private val size = vertices.length
    // One pointer per vertex into its neighbour list, from the largest neighbour down.
    private val pointers: Array[Int] = Array.tabulate(size)(a => lists.offsets(vertices(a) + 1) - 1)
    private var iB: Long = startIndex
    private var iA: Long = 0L
    private var k: Int = size
    private var above: Int = size - 1 // the largest vertex of the simplex not yet passed
    private var _vertex: Int = -1
    private var _index: Long = -1L
    private var _maxDistance: Double = 0.0
    private var havePending: Boolean = false
    private var done: Boolean = size == 0

    private def step(): Unit =
      havePending = false
      while !havePending && !done do
        // The candidate: the current entry of the first vertex's list.
        val p0 = pointers(0)
        if p0 < lists.offsets(vertices(0)) then done = true
        else
          var candidate = lists.targets(p0)
          if !allCofacets && candidate < vertices(size - 1) then done = true
          else
            // Move every other list down to the candidate; a list whose next entry is below it rules it out, and
            // the largest such entry becomes the next candidate to try.
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
                // Pass the simplex's vertices above the candidate, exactly as CofacetCursor does.
                while above >= 0 && vertices(above) > candidate do
                  val j = vertices(above)
                  iB -= binomialChoose(j, k)
                  iA += binomialChoose(j, k + 1)
                  k -= 1
                  above -= 1
                _vertex = candidate
                _index = iB + binomialChoose(candidate, k + 1) + iA
                _maxDistance = maxD
                havePending = true
              pointers(0) = p0 - 1

    step()

    def hasNext: Boolean = havePending
    def vertex: Int = _vertex
    def index: Long = _index
    def maxDistance: Double = _maxDistance
    def position: Int = k
    def advance(): Unit = step()

  def sparseCofacetCursor(
    index: Long,
    vertices: Array[Int],
    allCofacets: Boolean,
    lists: NeighbourLists
  ): SparseCofacetCursor =
    new SparseCofacetCursor(index, vertices, allCofacets, lists)

  /** Same enumeration as `cofacetIterator`, but also yields the INSERTED vertex alongside each cofacet index -- needed
    * by a packed (index-only) reduction that has no materialized `Simplex[Int]` to recover it from afterward
    * (`(tau.underlying diff sigma.underlying).head`, `coboundaryOf`'s own approach, requires decoding `tau`). Now a
    * thin `Iterator[(Int, Long)]` wrapper over `CofacetCursor` above, kept because `cofacetIterator` below and
    * `SimplexIndexingSpec`'s own `Iterator`-based assertions still go through it -- new call sites use `cofacetCursor`
    * directly.
    */
  def cofacetIteratorWithVertex(
    index: Long,
    size: Int,
    allCofacets: Boolean = true
  ): Iterator[(Int, Long)] =
    val cur = cofacetCursor(index, size, allCofacets)
    new Iterator[(Int, Long)]:
      def hasNext: Boolean = cur.hasNext
      def next(): (Int, Long) =
        if !cur.hasNext then throw new NoSuchElementException("next on empty iterator")
        val result = (cur.vertex, cur.index)
        cur.advance()
        result

  /** A `hasNext`/`vertex`/`index`/`advance()` cursor over `tau`'s facets, mirroring `CofacetCursor` above -- `vertex`
    * is the vertex REMOVED to produce the facet at `index`. `_index` is `iiB + iA` (the OLD `iA`, before this step's
    * own update), not `iiB + iiA` -- easy to invert by mistake, so kept exactly. Decodes `tau` via `decodeToArray`, not
    * `apply(...).toSeq.sorted` (a `Simplex[Int]` decode followed by a redundant re-sort of an already-sorted
    * `SortedSet`).
    */
  class FacetCursor(startIndex: Long, size: Int):
    private val vertices: Array[Int] = decodeToArray(startIndex, size)
    private var iB: Long = startIndex
    private var iA: Long = 0L
    private var k: Int = size - 1
    private var _vertex: Int = -1
    private var _index: Long = -1L
    private var havePending: Boolean = false

    private def step(): Unit =
      if k >= 0 then
        val v = vertices(k)
        val iiB = iB - binomialChoose(v, k + 1)
        val iiA = iA + binomialChoose(v, k)
        _vertex = v
        _index = iiB + iA
        iB = iiB
        iA = iiA
        k -= 1
        havePending = true
      else havePending = false

    step()

    def hasNext: Boolean = havePending
    def vertex: Int = _vertex
    def index: Long = _index
    def advance(): Unit = step()

  def facetCursor(index: Long, size: Int): FacetCursor =
    new FacetCursor(index, size)

  /** Now a thin `Iterator[Long]` wrapper over `FacetCursor` above, kept for `SimplexIndexingSpec`'s own
    * `Iterator`-based assertions -- new call sites use `facetCursor` directly.
    */
  def facetIterator(index: Long, size: Int): Iterator[Long] =
    val cur = facetCursor(index, size)
    new Iterator[Long]:
      def hasNext: Boolean = cur.hasNext
      def next(): Long =
        if !cur.hasNext then throw new NoSuchElementException("next on empty iterator")
        val result = cur.index
        cur.advance()
        result

  /** The index of `simplex` in the combinatorial number system. */
  def apply(simplex: Simplex[Int]): Long =
    val vertices = simplex.underlying.toArray
    val size = vertices.length
    var acc: Long = 0L
    var i = 0
    while i < size do
      acc += binomialChoose(vertices(size - 1 - i), size - i)
      i += 1
    acc

object SimplexIndexing:
  /** `n` choose `k` as a `Long` (an index can exceed `Int` long before `n` does: `C(229, 5) > 5·10⁹`), `0` when
    * `k < 0`, `n < 0` or `k > n`. Throws `IllegalArgumentException` on `Long` overflow.
    */
  def binomial(n: Int, k: Int): Long =
    if k < 0 || n < 0 || n < k then 0L
    else
      try combinatorics.BinomialCoefficient.value(n, k)
      catch
        case e: ArithmeticException =>
          throw new IllegalArgumentException(
            s"binomial($n, $k) overflows Long -- this complex is too large to index",
            e
          )

/** Each vertex's neighbours within a threshold, ascending, with their distances, in compressed rows: vertex `v`'s are
  * `targets(offsets(v) until offsets(v + 1))`. Built by [[NeighbourLists.within]].
  */
open class NeighbourLists(
  val offsets: Array[Int],
  val targets: Array[Int],
  val distances: Array[Double]
):
  def entryCount: Int = targets.length

object NeighbourLists:
  /** The pairs at distance at most `threshold`, each distance computed once (as `distance(i, j)`, `i < j`) and stored
    * for both vertices. `None` when there are more than `maxEntries` directed pairs (capped at `Int.MaxValue`): the
    * lists would not be sparse, or would not fit in arrays.
    */
  def within(n: Int, threshold: Double, maxEntries: Long)(distance: (Int, Int) => Double): Option[NeighbourLists] =
    val limit = math.min(maxEntries, Int.MaxValue.toLong - 8)
    // One pass over the pairs, i < j ascending, collecting the close ones with their distance.
    var lo = new Array[Int](1024)
    var hi = new Array[Int](1024)
    var ds = new Array[Double](1024)
    var pairs = 0
    val counts = new Array[Int](n)
    var tooMany = false
    var i = 0
    while i < n && !tooMany do
      var j = i + 1
      while j < n && !tooMany do
        val d = distance(i, j)
        if d <= threshold then
          if 2L * (pairs + 1) > limit then tooMany = true
          else
            if pairs == lo.length then
              val grown = math.min(lo.length.toLong * 2, Int.MaxValue.toLong - 8).toInt
              lo = java.util.Arrays.copyOf(lo, grown)
              hi = java.util.Arrays.copyOf(hi, grown)
              ds = java.util.Arrays.copyOf(ds, grown)
            lo(pairs) = i
            hi(pairs) = j
            ds(pairs) = d
            pairs += 1
            counts(i) += 1
            counts(j) += 1
        j += 1
      i += 1
    if tooMany then None
    else
      val offsets = new Array[Int](n + 1)
      i = 0
      while i < n do
        offsets(i + 1) = offsets(i) + counts(i)
        i += 1
      val targets = new Array[Int](2 * pairs)
      val distances = new Array[Double](2 * pairs)
      val fill = java.util.Arrays.copyOf(offsets, n)
      // Pairs come in increasing (i, j): vertex u receives first the i < u (as the larger end, i increasing), then the
      // j > u (as the smaller end, j increasing), so every list comes out ascending.
      var k = 0
      while k < pairs do
        val a = lo(k)
        val b = hi(k)
        targets(fill(a)) = b
        distances(fill(a)) = ds(k)
        fill(a) += 1
        targets(fill(b)) = a
        distances(fill(b)) = ds(k)
        fill(b) += 1
        k += 1
      Some(NeighbourLists(offsets, targets, distances))
