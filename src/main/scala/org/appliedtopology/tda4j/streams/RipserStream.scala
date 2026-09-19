package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import org.apache.commons.numbers.combinatorics
import org.appliedtopology.tda4j.barcode.{ClosedEndpoint, OpenEndpoint, PersistenceBar}

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.*

def binomialApache(n: Int, k: Int): Int =
  if n > 0 && k >= 0 && n >= k then combinatorics.BinomialCoefficient.value(n, k).toInt
  else 0

// see https://stackoverflow.com/questions/52795217/scala-tail-recursive-method-has-an-divide-and-remainder-error/65362753#65362753
def binomialBigint(n: Int, k: Int): BigInt =
  if k < 0 || n < k then 0
  else
    @tailrec
    def binomialtail(nIter: Int, kIter: Int, ac: BigInt): BigInt =
      if kIter > k then ac
      else binomialtail(nIter + 1, kIter + 1, (nIter * ac) / kIter)

    if k == 0 || k == n then 1
    else binomialtail(n - k + 1, 1, BigInt(1))

/** Returns `Long`, not `Int`: a combinatorial index (unlike `n`/`k` themselves, which stay realistically small --
  * vertex counts and dimension-derived sizes) can be astronomically larger than either input, and silently truncating
  * it is exactly the bug this signature change fixes -- see WORKLOG-simplexindexing-overflow.md. Confirmed directly:
  * `C(229,5) = 5,022,337,545` used to truncate to `727,370,249` via the old `Int`-returning `.intValue`, and
  * `C(229,6) = 187,500,601,680` truncated to `-1,477,959,344` (negative) -- both well within a realistic point-cloud
  * size, not a contrived edge case. `Long` matches `ripser.cpp`'s own `int64_t`/`long long` for this exact purpose, not
  * chosen arbitrarily -- and is dramatically cheaper than `BigInt` throughout, which matters here since this backs
  * `Ordering[Simplex[Int]]`'s comparator, consulted on every `SortedMap`/ `PriorityQueue` operation during reduction,
  * the hottest path in the engine. `Long` is not infinite either, so this still asserts rather than silently repeating
  * the same class of bug one order of magnitude further out -- a real, if astronomically unlikely for any complex
  * actually computable in practice, failure mode.
  *
  * '''Delegates to `commons.numbers.combinatorics.BinomialCoefficient.value`, not `binomialBigint`, as of
  * `.claude/WORKLOG-ripser-profiling.md`''': this function sits directly on the coboundary/cofacet/facet enumeration
  * hot path (`SimplexIndexing.cofacetIteratorWithVertex`/`facetIterator`/the encode direction of `apply(simplex)` call
  * it directly, unmemoized, `O(vertexCount)` times per simplex whose coboundary is enumerated -- NOT just the
  * `O(1)`-per-decode calls `binomialEntry` already memoizes). Profiling on a 48-point random cloud found
  * `binomialtail`/`binomialBigint` as the CURRENTLY-EXECUTING (leaf) frame in 11.6% of `RipserCohomologyContext`'s CPU
  * samples and 25.5% of `PackedRipserCohomologyContext`'s; a separate, cruder "`binomial` appears ANYWHERE in the call
  * stack" count (35.9%/44.1%) also double-counts every caller waiting on it and isn't the right number to quote as
  * "time spent computing" -- the leaf figures are. The first fix attempted was a memoizing `HashMap[(Int,Int),Long]`
  * wrapper around the old `binomialBigint`-backed `binomial` (kept as `SimplexIndexing`'s existing `binomialEntry`/
  * `binomialCache` pattern already does for its own, smaller usage) -- measured, via a controlled before/after at
  * matched problem sizes, to change wall-clock time by nothing outside noise (confirmed the JFR leaf-frame cost had
  * genuinely moved from `binomialBigint` into `HashMap$Node.findNode`/tuple-boxing, not disappeared): for the small `k`
  * these call sites always use (bounded by simplex dimension), a `BigInt` tail-recursion is already cheap enough per
  * call that a boxed-tuple `HashMap` lookup costs about the same as just recomputing it -- caching a fundamentally
  * expensive-for-its-shape operation doesn't help when the operation is only expensive because of ITS OWN
  * implementation choice (`BigInt`), not because it's being redundantly recomputed. Switching the computation itself to
  * `BinomialCoefficient.value` -- a `long`-only, non-`BigInt`, GCD-guarded-for-large-`n` algorithm already a dependency
  * of this exact file (`binomialApache` above already calls it, just truncated to `Int`) -- removes the `BigInt`
  * allocation/arithmetic entirely rather than caching around it, and needs no cache at all: see
  * `WORKLOG-ripser-profiling.md` for the measured before/after. `n < 0 || k < 0 || n < k` are special-cased to `0`
  * BEFORE delegating, matching `binomialBigint`'s own semantics -- `BinomialCoefficient.value` throws
  * `IllegalArgumentException` for `k > n`/negative inputs instead, which would be a real behavior change for any caller
  * relying on the old "returns 0 outside the valid range" contract (`cofacetIteratorWithVertex`'s own `iA`/`iB`
  * bookkeeping does hit `k > n`-shaped calls at the boundary of its sweep, confirmed by running the full existing
  * regression suite -- not merely reasoned through). Overflow (`ArithmeticException` from `BinomialCoefficient.value`)
  * is caught and re-thrown as the same `IllegalArgumentException`-with-message shape `require` used to produce, so any
  * caller (none currently do) depending on that exception TYPE stays correct.
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

class SimplexIndexing(val vertexCount: Int):

  /** Lazily-memoized `binomial(d + s, s)` lookup -- NOT an eagerly-computed full `(vertexCount+1) x (vertexCount+1)`
    * table (a prior version of this class had exactly that, computing every `(d, s)` pair up front regardless of
    * whether `d` values that large are ever used). In every real call, `d` is bounded by the SIMPLEX SIZE being
    * encoded/decoded (small -- `apply`'s own recursion only ever decreases `d`, starting from an actual simplex's
    * vertex count), never by `vertexCount` itself, so eagerly filling rows out to `d = vertexCount` computed
    * astronomically large, NEVER-READ entries for any nontrivial `vertexCount`. Confirmed directly, not hypothesized:
    * `SimplexIndexing(230)`'s eager construction failed on `binomial(207, 195)` (`d=12, s=195`) -- a `(d, s)` pair no
    * real cofacet/facet/encode/decode call for this codebase's actual dimension range would ever need, but which the
    * eager table computed anyway just by iterating `d` up to `vertexCount`. See `binomial`'s own doc and
    * WORKLOG-simplexindexing-overflow.md for the full account (this table itself is where the `Int`-truncation bug that
    * motivated the `Long` migration was silently swallowing out-of-range values before this same problem became
    * `Long`-overflow instead of a wrong answer). Memoizing per-`(d,s)`-pair on demand means only the pairs a real call
    * actually needs ever get computed, and those stay well within `Long`'s range for any complex actually computable in
    * practice.
    */
  /** Array-backed, not `mutable.Map[(Int, Int), Long]`, as of `.claude/WORKLOG-ripser-profiling.md`: allocation
    * profiling (`jdk.ObjectAllocationSample`, weighted by allocated bytes) found the `(d, s)` tuple key this used to
    * box on EVERY lookup -- including cache HITS, since a `HashMap` key still has to be constructed before it can be
    * hashed/compared -- was the single largest allocation source in both Ripser engines, 16.3% of all main-thread
    * allocation weight in `RipserCohomologyContext` and 19.1% in `PackedRipserCohomologyContext` on a 48-point random
    * cloud (`scala.Tuple2$mcII$sp`). `d` is always `>= 0` at both real call sites (`apply`'s own `d < 0` guard returns
    * before ever reaching `searchRow`/`binomialEntry`), so a `d`-indexed `Array` of per-`s` rows, each row sized
    * `vertexCount + 1` and grown/filled lazily, replaces the `(d, s)` tuple key with plain integer indexing -- zero
    * allocation per lookup after a row exists, and the row itself is only ever allocated for a `d` a real call actually
    * reaches (rows are grown one at a time up to the largest `d` seen, not pre-sized to some assumed maximum), so this
    * is NOT a reintroduction of the eagerly-computed-full-table bug the class doc below warns about -- that bug was
    * about eagerly COMPUTING every entry in a row up to `s = vertexCount` regardless of whether `apply`'s decode ever
    * asks for it; this still computes each entry lazily, on first access, exactly as before, just into array cells
    * instead of hashmap buckets. `-1L` is the "not yet computed" sentinel: every real `binomial(d + s, s)` value is
    * `>= 0`, so `-1L` can never collide with a genuine result.
    */
  private var binomialRows: Array[Array[Long]] = Array.empty
  private def binomialEntry(d: Int, s: Int): Long =
    if d >= binomialRows.length then
      val grown = Array.ofDim[Array[Long]](d + 1)
      Array.copy(binomialRows, 0, grown, 0, binomialRows.length)
      binomialRows = grown
    if binomialRows(d) == null then binomialRows(d) = Array.fill(vertexCount + 1)(-1L)
    val row = binomialRows(d)
    if row(s) == -1L then row(s) = binomial(d + s, s)
    row(s)

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
    * `n` is `Long`, not `Int` -- see `binomial`'s own doc and WORKLOG-simplexindexing-overflow.md: a combinatorial
    * index can be astronomically larger than `vertexCount`/`d` themselves. The `d == 0` base case converts back to
    * `Int` via `.toInt` -- safe there specifically, not a re-introduction of the same truncation bug: by this
    * algorithm's own invariant, the residual `n` at `d == 0` is always a single vertex id (bounded by
    * `vertexCount: Int`), never a combinatorial index anymore.
    *
    * @param n
    * @param d
    * @param upperAccum
    * @return
    */
  @tailrec
  final def apply(n: Long, d: Int, upperAccum: Simplex[Int] = ∆()): Simplex[Int] =
    if d < 0 then return upperAccum
    if n <= 0 then return upperAccum ++ (0 until d).toSet
    if d == 0 then return upperAccum + n.toInt
    val id: Int = searchRow(d, n)
    apply(n - binomialEntry(d, id), d - 1, upperAccum + (id + d))

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

  /** Same enumeration as `cofacetIterator`, but also yields the INSERTED vertex alongside each cofacet index -- needed
    * by a packed (index-only) reduction that has no materialized `Simplex[Int]` to recover it from afterward
    * (`(tau.underlying diff sigma.underlying).head`, `coboundaryOf`'s own approach, requires decoding `tau`). The
    * vertex is already present as `j`, the unfold's own loop state, at exactly the point a cofacet is emitted (`j`
    * values that are NOT already in `s` are candidates for insertion) -- so exposing it costs nothing beyond what this
    * method was already computing.
    */
  /** Hand-rolled `Iterator`, not `Iterator.unfold` + `.filter` + `.map`, as of `.claude/WORKLOG-ripser-profiling.md`:
    * allocation profiling found the `unfold`-based version allocated a fresh `Tuple5` state tuple AND an
    * `Option[(Int, Long)]` on EVERY candidate vertex `j` from `vertexCount - 1` down to `0` -- not just once per
    * cofacet actually found -- plus the `unfold`/`filter`/`map` step closures themselves, allocated once per call to
    * this method (`SimplexIndexing$$Lambda...` was the second-largest single allocation source measured, 13.5%-14.9% of
    * main-thread allocation weight, right behind `binomialEntry`'s tuple-keyed cache lookup fixed just above). This
    * version preserves the exact same per-step arithmetic and termination behavior (see the original `unfold` step
    * function, kept in git history for direct comparison) -- verified against it directly via `SimplexIndexingSpec`'s
    * exact-cofacet-set assertions and `RipserCohomologySpec`/`PackedRipserCohomologySpec`'s full-barcode
    * cross-validation, not merely reasoned through -- but allocates only the `(Int, Long)` result pair actually
    * returned by `next()`, never a throwaway state tuple or `Option` wrapper per skipped candidate.
    */
  def cofacetIteratorWithVertex(
    index: Long,
    size: Int,
    allCofacets: Boolean = true
  ): Iterator[(Int, Long)] =
    val sz = size // `size` also names `Iterator`'s own member; capture the parameter under a distinct name
    new Iterator[(Int, Long)]:
      private val s: Simplex[Int] = apply(index, sz)
      private var iB: Long = index
      private var iA: Long = 0L
      private var k: Int = sz
      private var j: Int = vertexCount - 1
      private var pendingVertex: Int = -1
      private var pendingIndex: Long = -1L
      private var havePending: Boolean = false
      private var done: Boolean = false

      private def advance(): Unit =
        while !havePending && !done do
          if j < 0 then done = true
          else if s.contains(j) then
            if !allCofacets then done = true
            else
              iB -= binomial(j, k)
              iA += binomial(j, k + 1)
              k -= 1
              j -= 1
          else
            pendingVertex = j
            pendingIndex = iB + binomial(j, k + 1) + iA
            havePending = true
            j -= 1

      advance()

      def hasNext: Boolean = havePending
      def next(): (Int, Long) =
        if !havePending then throw new NoSuchElementException("next on empty iterator")
        val result = (pendingVertex, pendingIndex)
        havePending = false
        advance()
        result

  /** Hand-rolled for the same reason as `cofacetIteratorWithVertex` above -- the `unfold`-based version allocated a
    * fresh `Tuple4` state (plus a defensive `.to(Vector)` re-copy of the already-immutable decoded simplex, EVERY step)
    * for what is always exactly `size` steps, no filtering or early termination. Preserves the original's exact
    * arithmetic, including yielding `iiB + iA` (the OLD `iA`, before this step's own update) rather than `iiB + iiA` --
    * a real, easy-to-invert-by-mistake detail of the original, kept byte-for-byte, not "corrected."
    */
  def facetIterator(index: Long, size: Int): Iterator[Long] =
    val sz = size // `size` also names `Iterator`'s own member; capture the parameter under a distinct name
    new Iterator[Long]:
      private val s: Seq[Int] = apply(index, sz).toSeq.sorted
      private var iB: Long = index
      private var iA: Long = 0L
      private var k: Int = sz - 1

      def hasNext: Boolean = k >= 0
      def next(): Long =
        if !hasNext then throw new NoSuchElementException("next on empty iterator")
        val j = s(k)
        val iiB = iB - binomial(j, k + 1)
        val iiA = iA + binomial(j, k)
        val result = iiB + iA
        iB = iiB
        iA = iiA
        k -= 1
        result

  def apply(simplex: Simplex[Int]): Long =
    simplex.toSeq.sorted.reverse.zipWithIndex.map { (v, i) =>
      binomial(v, simplex.size - i)
    }.sum

/** ****** Maybe @deprecate or outright everything below here? ******
  */

class RipserCliqueFinder:
  val className: String = "RipserCliqueFinder"

  def apply(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): Seq[Simplex[Int]] =
    RipserStream(metricSpace, maxFiltrationValue, maxDimension).iterator.toSeq

class RipserStreamSparse(
  val metricSpace: FiniteMetricSpace[Int],
  val maxFiltrationValue: Double,
  val maxDimension: Int = 2
) extends SimplexStream[Int, Double]:
  // given Ordering[Simplex] = Ordering.by(filtrationValue).orElse(sc.given_Ordering_Simplex)
  val doubleSimplexPairOrdering: Ordering[(Double, Simplex[Int])] =
    (x: (Double, Simplex[Int]), y: (Double, Simplex[Int])) =>
      Ordering.Double.TotalOrdering.compare(x._1, y._1) match
        case 0      => summon[Ordering[Simplex[Int]]].compare(x._2, y._2)
        case c: Int => c

  given Ordering[(Double, Simplex[Int])] = doubleSimplexPairOrdering

  val si = SimplexIndexing(metricSpace.size)

  override def filtrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  def zeroPivotCofacet(index: Long, size: Int): Option[Long] = (for
    cofacet <- si.cofacetIterator(index, size, false)
    if filtrationValue(si(cofacet, size + 1)) == filtrationValue(
      si(index, size)
    )
  yield cofacet).maxOption

  def zeroPivotFacet(index: Long, size: Int): Option[Long] = (for
    facet <- si.facetIterator(index, size)
    if filtrationValue(si(facet, size - 1)) == filtrationValue(si(index, size))
  yield facet).maxOption

  def zeroApparentCofacet(index: Long, size: Int): Option[Long] =
    for
      cofacet <- zeroPivotCofacet(index, size)
      facet <- zeroPivotFacet(cofacet, size + 1)
      if facet == index
    yield cofacet

  def zeroApparentFacet(index: Long, size: Int): Option[Long] =
    for
      facet <- zeroPivotFacet(index, size)
      cofacet <- zeroPivotCofacet(facet, size - 1)
      if facet == index
    yield cofacet

  lazy val kruskal = Kruskal(metricSpace)

  def zeroPersistence[CoefficientT](): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    kruskal.mstIterator.map { (b, d) =>
      PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]](
        0,
        ClosedEndpoint(0.0),
        OpenEndpoint(metricSpace.distance(b, d))
      )
    }.toList

  var simplexCacheContains: Option[Int] = None
  val simplexCache: mutable.SortedSet[(Double, Simplex[Int])] = mutable.SortedSet()

  def iteratorByDimension(d: Int): Iterator[Simplex[Int]] = d match
    case 0        => metricSpace.elements.iterator.map(Simplex(_))
    case 1        => kruskal.cyclesIterator.map((i, j) => Simplex(i, j))
    case dim: Int =>
      { // this is where the real work happens
        // This can get expensive if we are for any reason NOT traversing dimension by dimension
        if simplexCacheContains != Some(d - 1) then
          simplexCache.addAll(
            iteratorByDimension(d - 1).map(s => (filtrationValue(s), s))
          )
        for
          fV <- simplexCache.map(_._1).iterator
          previousSimplex <- simplexCache.filter(_._1 < fV).iterator.map(_._2)
          nextVertex <- metricSpace.elements
            .filter(!previousSimplex.contains(_))
            .filter(nV => previousSimplex.map(oV => metricSpace.distance(oV, nV)).max <= fV)
          simplex: Simplex[Int] = previousSimplex + nextVertex
          if zeroApparentCofacet(si(simplex), simplex.size).isEmpty
          if zeroApparentFacet(si(simplex), simplex.size).isEmpty
        // also check if this is cleared?
        yield simplex
      }.iterator

  override def iterator: Iterator[Simplex[Int]] =
    for
      d <- (0 until maxDimension).iterator
      s <- iteratorByDimension(d)
    yield s

abstract class RipserStreamBase(
  val metricSpace: FiniteMetricSpace[Int],
  val maxFiltrationValue: Double = Double.PositiveInfinity,
  val maxDimension: Int = 2
) extends SimplexStream[Int, Double]:
  def retain(index: Long, size: Int): Boolean = true
  def expand(filtrationValue: Double, index: Long, size: Int): Seq[Simplex[Int]] =
    Seq(si(index, size))

  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)

  override def iterator: Iterator[Simplex[Int]] =
    for
      d <- (0 to maxDimension).iterator
      s <- iteratorByDimension(d)
    yield s

  def iteratorByDimension(d: Int): Iterator[Simplex[Int]] = if d > metricSpace.size then Iterator()
  else
    (0L until binomial(metricSpace.size, d + 1)).iterator
      .filter(i => retain(i, d + 1))
      .map(i => (filtrationValue(si(i, d + 1)), i, d + 1))
      .toSeq
      .sortBy((f, i, s) => f)
      .flatMap(expand)
      .iterator

  override def filtrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)

  def zeroPivotCofacet(index: Long, size: Int): Option[Long] = (for
    cofacet <- si.cofacetIterator(index, size, false)
    if filtrationValue(si(cofacet, size + 1)) == filtrationValue(
      si(index, size)
    )
  yield cofacet).maxOption

  def zeroPivotFacet(index: Long, size: Int): Option[Long] = (for
    facet <- si.facetIterator(index, size)
    if filtrationValue(si(facet, size - 1)) == filtrationValue(si(index, size))
  yield facet).maxOption

  def zeroApparentCofacet(index: Long, size: Int): Option[Long] =
    for
      cofacet <- zeroPivotCofacet(index, size)
      facet <- zeroPivotFacet(cofacet, size + 1)
      if facet == index
    yield cofacet

  def zeroApparentFacet(index: Long, size: Int): Option[Long] =
    for
      facet <- zeroPivotFacet(index, size)
      cofacet <- zeroPivotCofacet(facet, size - 1)
      if facet == index
    yield cofacet

  def zeroPersistence[CoefficientT](): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    Kruskal(metricSpace).mstIterator.map { (b, d) =>
      PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]](
        0,
        ClosedEndpoint(0.0),
        OpenEndpoint(metricSpace.distance(b, d))
      )
    }.toList

class RipserStream(
  metricSpace: FiniteMetricSpace[Int],
  maxFiltrationValue: Double,
  maxDimension: Int
) extends RipserStreamBase(metricSpace, maxFiltrationValue, maxDimension):
  val className: String = "RipserStream"
object RipserStream {}

class RipserStreamOf[VertexT: Ordering](
  val metricSpace: FiniteMetricSpace[VertexT],
  val maxFiltrationValue: Double = Double.PositiveInfinity,
  val maxDimension: Int = 2
) extends SimplexStream[VertexT, Double]:
  protected val vertices: List[VertexT] = metricSpace.elements.toList
  protected val intMetricSpace = new FiniteMetricSpace[Int]:
    override def contains(x: Int): Boolean = metricSpace.contains(vertices(x))

    override def size: Int = metricSpace.size

    override def elements: Iterable[Int] = 0 until size

    override def distance(x: Int, y: Int): Double =
      metricSpace.distance(vertices(x), vertices(y))
  protected val rs: RipserStream =
    RipserStream(intMetricSpace, maxFiltrationValue, maxDimension)

  override def iterator: Iterator[Simplex[VertexT]] =
    rs.iterator.map(s => s.map(v => vertices(v)))

  override def filtrationValue: PartialFunction[Simplex[VertexT], Double] = spx =>
    val indices: Simplex[Int] = spx.map(vertices.indexOf)
    rs.filtrationValue(indices)

class SymmetricRipserCliqueFinder[KeyT](
  val symmetryGroup: SymmetryGroup[KeyT, Int]
):
  val className: String = "SymmetricRipserCliqueFinder"

  def apply(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): Seq[Simplex[Int]] =
    SymmetricRipserStream(
      metricSpace,
      maxFiltrationValue,
      maxDimension,
      symmetryGroup
    ).iterator.toSeq

class SymmetricRipserStream[KeyT](
  metricSpace: FiniteMetricSpace[Int],
  maxFiltrationValue: Double = Double.PositiveInfinity,
  maxDimension: Int = 2,
  val symmetryGroup: SymmetryGroup[KeyT, Int]
) extends RipserStream(metricSpace, maxFiltrationValue, maxDimension):
  override def retain(index: Long, size: Int): Boolean =
    symmetryGroup.isRepresentative(si(index, size))

  override def expand(
    filtrationValue: Double,
    index: Long,
    size: Int
  ): Seq[Simplex[Int]] =
    symmetryGroup.orbit(si(index, size)).toSeq

class MaskedSymmetricRipserVR[KeyT: Ordering](
  val symmetryGroup: SymmetryGroup[KeyT, Int]
):

  val className: String = "MaskedSymmetricRipserVR"

  def apply(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Double,
    maxDimension: Int
  ): Seq[Simplex[Int]] =
    MaskedSymmetricRipserStream[KeyT](
      metricSpace,
      maxFiltrationValue,
      maxDimension,
      symmetryGroup
    ).iterator.toSeq

class MaskedSymmetricRipserStream[KeyT](
  val metricSpace: FiniteMetricSpace[Int],
  val maxFiltrationValue: Double,
  val maxDimension: Int,
  val symmetryGroup: SymmetryGroup[KeyT, Int]
) extends SimplexStream[Int, Double]:
  val si: SimplexIndexing = SimplexIndexing(metricSpace.size)
  val distances: Set[Double] = Set.from(
    for
      x <- metricSpace.elements
      y <- metricSpace.elements
    yield metricSpace.distance(x, y)
  )

  override def iterator: Iterator[Simplex[Int]] =
    for
      d <- (0 to maxDimension).iterator
      s <- iteratorByDimension(d)
    yield s

  def iteratorByDimension(d: Int): Iterator[Simplex[Int]] = if d > metricSpace.size then Iterator()
  else
    val repmap: Map[Double, List[Simplex[Int]]] = List
      .from(
        for
          i <- (0L until binomial(metricSpace.size, d + 1)).iterator
          spx <- Seq(si(i, d + 1))
          if symmetryGroup.isRepresentative(spx)
        yield filtrationValue(spx) -> spx
      )
      .groupMap(_._1)(_._2)
    for
      dist <- repmap.keys.toSeq.sorted.iterator
      spx <- repmap(dist).iterator
      out <- symmetryGroup.orbit(spx).iterator
    yield out

  override def filtrationValue: PartialFunction[Simplex[Int], Double] =
    FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace)
