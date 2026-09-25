package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import org.apache.commons.numbers.combinatorics

import scala.annotation.tailrec

class SimplexIndexing(val vertexCount: Int):

  /** Lazily-memoized `binomial(d + s, s)`, indexed by `d`-row/`s`-column `Array`s rather than an eagerly-computed full
    * `(vertexCount+1) x (vertexCount+1)` table or a `Map[(Int, Int), Long]`. Eager: `d` is always bounded by the
    * SIMPLEX SIZE being encoded/decoded (small), never by `vertexCount` itself, so filling every row out to
    * `d = vertexCount` wastes work on astronomically large, never-read, potentially-overflowing entries (see
    * `binomial`'s own doc). Map-keyed: a `(d, s)` tuple key boxes on every lookup, including cache hits. Rows grow
    * lazily up to the largest `d` a real call reaches; `-1L` is the "not yet computed" sentinel (every real `binomial`
    * result is `>= 0`, so it can never collide with one).
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

  /** A SEPARATE lazily-memoized cache for `CofacetCursor`/`FacetCursor`'s own `binomial(n, k)` calls -- NOT a reuse of
    * `binomialEntry` above via the `binomial(n, k) = binomialEntry(n - k, k)` reindexing, even though that identity
    * holds: `binomialEntry`'s row axis (`d`) is only safe to grow lazily because every OTHER caller bounds `d` by
    * simplex size (small); `CofacetCursor`/`FacetCursor` call `binomial(j, k)` with `j` a vertex id ranging up to
    * `vertexCount - 1`, and reindexing that through `binomialEntry` would make ITS row axis scale with `vertexCount`
    * instead, reintroducing the same unbounded-row-growth problem `binomialEntry`'s own doc describes. Here the roles
    * are kept the right way round: `k` (bounded by simplex size) is the row index, `n` (bounded by `vertexCount`) is
    * the column.
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

  /** Binary search for the largest `s` in `[0, vertexCount]` with `binomialEntry(d, s) <= n` -- the same "`Found` or
    * `insertionPoint - 1`" result `scala.collection.Searching.search` used to give against the (now-removed)
    * eagerly-materialized table row, computed instead against the lazily-memoized entries above so the search never
    * forces evaluation of the wasteful, potentially-overflowing high-`s` region the eager table used to build
    * unconditionally. `binomialEntry(d, ·)` is strictly increasing in `s` for `d >= 0` (the standard
    * combinatorial-number-system property this class's whole encode/decode relies on), so ordinary binary search
    * applies.
    */
  private def searchRow(d: Int, n: Long): Int =
    var lo = 0
    var hi = vertexCount
    while lo < hi do
      val mid = lo + (hi - lo + 1) / 2
      if binomialEntry(d, mid) <= n then lo = mid else hi = mid - 1
    lo

  /** Uses the binomial numbering system to generate the `n`th simplex of dimension `d-1`, that is the `n`th subset of
    * size `d` of the vertices.
    *
    * If `n` is greater than (`vertexCount` choose `d`) the result will not be a subset of size `d`.
    *
    * `n` is `Long`, not `Int` -- see `binomial`'s own doc: a combinatorial index can be astronomically larger than
    * `vertexCount`/`d` themselves. The `d == 0` base case converts back to `Int` via `.toInt` safely: by this
    * algorithm's own invariant, the residual `n` at `d == 0` is always a single vertex id, never a combinatorial index
    * anymore.
    */
  @tailrec
  final def apply(n: Long, d: Int, upperAccum: Simplex[Int] = ∆()): Simplex[Int] =
    if d < 0 then return upperAccum
    if n <= 0 then return upperAccum ++ (0 until d).toSet
    if d == 0 then return upperAccum + n.toInt
    val id: Int = searchRow(d, n)
    apply(n - binomialEntry(d, id), d - 1, upperAccum + (id + d))

  /** Same decode as `apply(n, size)`, but returns the vertex set as a plain sorted `Array[Int]` instead of a
    * `Simplex[Int]`/`SortedSet[Int]` -- for callers (`PackedRipserCohomologyContext.sparseCofacets`/`coboundaryOf`/
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

  /** A `hasNext`/`vertex`/`index`/`advance()` cursor over `sigma`'s cofacets, factored out of what
    * `cofacetIteratorWithVertex` used to do directly as a hand-rolled `Iterator[(Int, Long)]`, so a caller can read
    * `vertex`/`index` as plain field accesses with zero per-step allocation -- not even the `(Int, Long)` tuple an
    * `Iterator[(Int, Long)]` contract forces on every `next()` call. `hasNext`/`advance()` are deliberately split from
    * a single `next()`: `vertex`/`index` stay valid to re-read as many times as a caller wants between one `advance()`
    * and the next (both `PackedRipserCohomology.scala`'s `coboundaryOf` and `Homology.scala` read both fields off one
    * candidate before advancing).
    *
    * Decodes `sigma` via `decodeToArray` (a plain sorted `Array[Int]`, binary-searched for membership), not `apply` (a
    * `Simplex[Int]`/`SortedSet[Int]`, `O(log d)` tree lookup per membership check) -- the same array-over-tree
    * substitution `decodeToArray`'s own doc motivates, folded in here since this cursor replaces
    * `cofacetIteratorWithVertex`'s body outright rather than wrapping it.
    */
  final class CofacetCursor(startIndex: Long, size: Int, allCofacets: Boolean):
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
  final class FacetCursor(startIndex: Long, size: Int):
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

  /** Hand-rolled `while` loop, not `simplex.toSeq.sorted.reverse.zipWithIndex.map(...).sum` -- five separate
    * allocating collection stages (a fresh `Seq`, a sort, a reverse, a `zipWithIndex` pairing, a `map`) for what's
    * structurally a single left-to-right reduction over an already-sorted set. `simplex.underlying` is already a
    * `SortedSet[Int]` in ascending order, so `.toArray` gives the same vertices with zero re-sort, walked from the
    * END (descending, matching the combinatorial-number-system convention every other encode/decode in this file
    * uses). Also switched to the CACHED `binomialChoose(n, k)` (private, same class) instead of the free-standing
    * `binomial`: this method's own access pattern -- `k` (the rank, `1..size`) small and bounded by simplex size,
    * `n` (the vertex value) large and bounded by `vertexCount` -- is exactly what `binomialChoose`'s row/column
    * layout was built for (see its own doc, added for `CofacetCursor`/`FacetCursor`'s stepping). Found via the
    * `o3_1024` compute-server JFR profile on `RipserCohomologyContext` (`.claude/WORKLOG-ripser-profiling.md`):
    * this chain (this method is the ONLY caller of the encode direction from any per-simplex hot path) accounted
    * for roughly 41% of that engine's remaining CPU time once the metric-space cache and the apparent-pairs
    * early-exit fix cleared away what had been dominating before, with the un-cached `binomial`'s own
    * `BinomialCoefficient`/`gcd` cost adding another ~18%.
    */
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
  /** Returns `Long`, not `Int`: a combinatorial index can be astronomically larger than `n`/`k` themselves, and
    * silently truncating it is a real bug, not a contrived edge case -- e.g. `C(229,5) = 5,022,337,545` truncates to
    * `727,370,249` via `Int`, well within a realistic point-cloud size. `Long` matches `ripser.cpp`'s own
    * `int64_t`/`long long` for this exact purpose and is dramatically cheaper than `BigInt`, which matters since this
    * backs `Ordering[Simplex[Int]]`'s comparator, consulted on every `SortedMap`/`PriorityQueue` operation during
    * reduction. `Long` is not infinite either, so this still asserts on overflow rather than repeating the same class
    * of bug one order of magnitude further out.
    *
    * Delegates to `commons.numbers.combinatorics.BinomialCoefficient.value`, a `long`-only, GCD-guarded-for-large-`n`
    * algorithm. `n < 0 || k < 0 || n < k` are special-cased to `0` BEFORE delegating: `BinomialCoefficient.value`
    * throws `IllegalArgumentException` for those inputs instead, which would be a real behavior change for callers
    * relying on the old "returns 0 outside the valid range" contract (`cofacetIteratorWithVertex`'s own `iA`/`iB`
    * bookkeeping does hit `k > n`-shaped calls at the boundary of its sweep). Overflow (`ArithmeticException`) is
    * caught and re-thrown as the same `IllegalArgumentException`-with-message shape `require` used to produce.
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
