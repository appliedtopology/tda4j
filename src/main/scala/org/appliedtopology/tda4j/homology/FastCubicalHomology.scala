package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.{
  BarcodeEndpoint,
  ClosedEndpoint,
  OpenEndpoint,
  PersistenceBar,
  PositiveInfinity
}

import scala.collection.mutable

/** Flash Cubical's dual-graph union-find (Le Breton, Szustakowski, Piraud, arXiv:2606.04801) for `H_0` and the TOP
  * homological degree (`H_{d-1}`, `d` = ambient dimension) of a `CubicalGridStream`, generic over `Field` coefficients
  * and recording real representatives for every bar -- neither of which the source paper's own F2-only, barcode-only
  * treatment provides; both are this codebase's own extension, derived independently
  * (`.claude/DESIGN-fast-cubical-engine.md`'s 2026-09-25 update has the full derivation and a hand-verified worked
  * example -- this session could not reach the paper itself, network-blocked, and no reference implementation exists to
  * port the way `streams.EdgeCollapse` could port GUDHI's; this is original work built on Alexander duality, not a
  * translation).
  *
  * '''Currently ambient dimension 2 only''' (`require`d) -- `H_0` (ordinary primal union-find) plus `H_1` (`= H_{d-1}`
  * at `d=2`, via the dual union-find below) together account for every cell dimension a 2D grid has, with NO general
  * `Chain.reduceBy` reduction needed at all. Ambient dimension 3 needs an additional piece this class does not attempt
  * (`H_1` there needs general reduction on whichever cells are NOT already resolved by the `H_0` and `H_2` union-finds)
  * -- deferred, not half-implemented; see the design note.
  *
  * '''The dual construction''': top cells (`dim == ambientDim`, i.e. pixels) are dual vertices; codimension-1 cells
  * ("facets") are dual edges, each connecting the 1 or 2 top cells containing it as a face (always exactly 1 or 2 for a
  * grid), with a shared auxiliary vertex `∞` standing in for the missing side of a facet on the outer boundary of the
  * whole grid. `∞` is fixed at value `+Infinity` (not `-Infinity` -- a real, easy mistake caught while deriving this:
  * `∞` must belong to every SUPERLEVEL set `{value >= s}`, which requires the LARGEST possible value, not the
  * smallest). Primal `H_{d-1}` of the sublevel filtration equals ordinary `H_0` of this dual graph's own SUPERLEVEL
  * filtration (Alexander duality, `H_{d-1}(X) ~= H^0(S^d \ X)`), computed by the same elder-rule array union-find
  * `CellularPersistenceInChunksContext.unionFindDim01` already uses, just processing dual vertices/edges together in
  * DESCENDING order of their own primal value, with every resulting bar's endpoints SWAPPED (a dual merge at value `v`
  * absorbing a younger dual component born at value `b` becomes a primal bar `(birth = v, death = b)`) and `∞`'s own
  * component producing no bar at all (it is always the elder/surviving side of every merge it takes part in, by
  * construction, so it never "dies" -- nothing to explicitly filter out).
  *
  * '''Representatives''': each active dual component tracks its own running signed sum of top cells (a
  * `Map[Cube, CoefficientT]`, cheap to merge -- just a map union with one side's signs flipped as needed), oriented
  * COHERENTLY as unions happen so that shared internal facets cancel in the sum's own boundary; when a component dies
  * (is absorbed into an older one across some facet `f`), its `H_{d-1}` representative is `boundary(that running sum)`
  * -- the internal facets cancel by construction, leaving exactly the (d-1)-cycle bounding the dual component, per the
  * design note's own derivation. The orientation flip needed when merging two components across `f` is solved directly
  * from `f`'s own boundary coefficients toward its two top cells (both always `+-1`, from `cubeIsOrderedCell`'s
  * alternating-sign rule) and each side's own already-established sign for its half of `f`.
  */
class FastCubicalHomologyContext[CoefficientT: Field]:
  private val fr = summon[CoefficientT is Field]
  given Ordering[Cube] = cubeOrdering

  def persistentHomology(stream: CubicalGridStream): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    require(
      stream.ambientDim == 2,
      s"FastCubicalHomologyContext currently supports ambient dimension 2 only, got ${stream.ambientDim}"
    )
    computeH0(stream) ++ computeDualTopDimension(stream)

  private def endpoint(lower: Boolean)(v: Double): BarcodeEndpoint[Double] =
    if !lower && v == Double.PositiveInfinity then PositiveInfinity()
    else if lower then ClosedEndpoint(v)
    else OpenEndpoint(v)

  // -------------------------------------------------------------------------------------------------------------
  // H_0: ordinary primal union-find, ascending value order, elder rule -- the dimension-0-only portion of
  // CellularPersistenceInChunksContext.unionFindDim01's own already-validated pattern (no dimension-1
  // cycle-tracking needed here, since H_1 comes from the dual mechanism below instead).
  // -------------------------------------------------------------------------------------------------------------
  private def computeH0(stream: CubicalGridStream): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    val vertices: Vector[Cube] = stream.iterateDimension.applyOrElse(0, (_: Int) => Iterator.empty).toVector
    val vertexIndex: Map[Cube, Int] = vertices.zipWithIndex.toMap
    val parent: Array[Int] = Array.range(0, vertices.size)

    def find(i: Int): Int =
      var root = i
      while parent(root) != root do root = parent(root)
      var cur = i
      while parent(cur) != root do
        val next = parent(cur)
        parent(cur) = root
        cur = next
      root

    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Cube, CoefficientT]]]
    val edges: Vector[Cube] = stream.iterateDimension.applyOrElse(1, (_: Int) => Iterator.empty).toVector
    // Ascending order (oldest first) via the stream's own filtrationOrdering, exactly like unionFindDim01 relies
    // on the stream's own `.iterator`/`iterateDimension` ordering -- `filtrationOrdering.reverse` is "oldest
    // first" per this codebase's universal convention (smaller-under-filtrationOrdering = younger).
    val orderedEdges = edges.sorted(using stream.filtrationOrdering.reverse)
    for edge <- orderedEdges do
      val ends = edge.boundary[CoefficientT].map(_._1)
      val r0 = find(vertexIndex(ends(0)))
      val r1 = find(vertexIndex(ends(1)))
      if r0 != r1 then
        val v0Val = stream.filtrationValue(vertices(r0))
        val v1Val = stream.filtrationValue(vertices(r1))
        // Elder rule by ACTUAL value (ascending processing: smaller value = older): the larger-value root is
        // younger and dies here.
        val (youngRoot, oldRoot) = if v0Val <= v1Val then (r1, r0) else (r0, r1)
        parent(youngRoot) = oldRoot
        val dying = vertices(youngRoot)
        bars += new PersistenceBar(
          0,
          endpoint(true)(stream.filtrationValue(dying)),
          endpoint(false)(stream.filtrationValue(edge)),
          Some(Chain(dying))
        )
    vertices.indices.foreach { i =>
      if find(i) == i then
        bars += new PersistenceBar(
          0,
          endpoint(true)(stream.filtrationValue(vertices(i))),
          PositiveInfinity(),
          Some(Chain(vertices(i)))
        )
    }
    bars.toList

  // -------------------------------------------------------------------------------------------------------------
  // H_{d-1} (= H_1 at d=2): the dual union-find. See the class doc and .claude/DESIGN-fast-cubical-engine.md for
  // the derivation this implements term-for-term.
  // -------------------------------------------------------------------------------------------------------------
  private def computeDualTopDimension(
    stream: CubicalGridStream
  ): List[PersistenceBar[Double, Chain[Cube, CoefficientT]]] =
    val shape = stream.shape
    val ambientDim = stream.ambientDim

    def topCoordsIterator: Iterator[IndexedSeq[Int]] =
      shape.foldLeft(Iterator(IndexedSeq.empty[Int])) { (acc, n) =>
        for prefix <- acc; v <- (0 until n).iterator yield prefix :+ v
      }

    val topCoords: Vector[IndexedSeq[Int]] = topCoordsIterator.toVector
    val topIndex: Map[IndexedSeq[Int], Int] = topCoords.zipWithIndex.toMap
    val numTop = topCoords.size
    val infinityId = numTop // one past the last real top-cell id

    def topCube(coords: IndexedSeq[Int]): Cube = Cube.unitCube(coords)
    def topValue(id: Int): Double =
      if id == infinityId then Double.PositiveInfinity else stream.topCellValue(topCoords(id))

    // Every codimension-1 cube (exactly one degenerate axis), with the 1 or 2 top-cell ids it borders (the
    // missing side, for a boundary facet of the whole grid, is `infinityId`) -- computed directly from grid
    // coordinates (the same "regular grid" exploit CubicalGridStream.containingTopCells already uses), not a
    // generic coboundary walk.
    case class FacetEvent(facet: Cube, value: Double, a: Int, b: Int)
    val facetEvents: Vector[FacetEvent] =
      (0 until ambientDim).flatMap { degenAxis =>
        val axisRanges: IndexedSeq[Range] =
          (0 until ambientDim).map(i => if i == degenAxis then 0 to shape(i) else 0 until shape(i))
        axisRanges
          .foldLeft(Iterator(IndexedSeq.empty[Int]))((acc, r) => for prefix <- acc; v <- r.iterator yield prefix :+ v)
          .map { lower =>
            val facet = Cube(lower, (0 until ambientDim).toSet - degenAxis)
            val k = lower(degenAxis)
            val candidates = Seq(k - 1, k).filter(v => v >= 0 && v < shape(degenAxis))
            val topIds = candidates.map(v => topIndex(lower.updated(degenAxis, v)))
            val ids = if topIds.size == 2 then topIds else topIds :+ infinityId
            val value = ids.map(topValue).min
            FacetEvent(facet, value, ids(0), ids(1))
          }
      }.toVector

    // ONE combined descending pass: (value DESCENDING, isVertex DESCENDING [vertices before edges at a tied
    // value -- see the design note for why this specific tie-break is load-bearing, not stylistic], then a
    // deterministic tie-break so ties among same-kind-same-value items are still a total order).
    sealed trait DualEvent:
      def value: Double
    case class VertexEv(id: Int, value: Double) extends DualEvent
    case class EdgeEv(fe: FacetEvent) extends DualEvent:
      def value: Double = fe.value

    // Explicit comparator (not `.sortBy` into a tuple carrying an `IndexedSeq[Int]`, which drags in an unrelated
    // `OrderedCell`-derived given-search path) -- (value DESCENDING, isVertex DESCENDING [0 for vertex, 1 for
    // edge, so vertices sort first at a tied value], then lexicographic on the cell's own doubled-coordinate
    // encoding for a deterministic tie-break among same-kind-same-value items).
    def lexCompare(a: Seq[Int], b: Seq[Int]): Int =
      val n = math.min(a.size, b.size)
      var i = 0
      var result = 0
      while result == 0 && i < n do
        result = Integer.compare(a(i), b(i))
        i += 1
      if result != 0 then result else Integer.compare(a.size, b.size)

    val vertexEvents: Vector[VertexEv] = topCoords.indices.map(id => VertexEv(id, topValue(id))).toVector
    val edgeEvents: Vector[EdgeEv] = facetEvents.map(EdgeEv.apply)
    val eventOrdering: Ordering[DualEvent] = new Ordering[DualEvent]:
      def compare(x: DualEvent, y: DualEvent): Int =
        val byValue = -java.lang.Double.compare(x.value, y.value) // descending
        if byValue != 0 then byValue
        else
          val xKind = x match
            case _: VertexEv => 0;
            case _: EdgeEv   => 1
          val yKind = y match
            case _: VertexEv => 0;
            case _: EdgeEv   => 1
          if xKind != yKind then Integer.compare(xKind, yKind)
          else
            val xKey = x match
              case VertexEv(id, _) => topCoords(id);
              case EdgeEv(fe)      => fe.facet.encoded
            val yKey = y match
              case VertexEv(id, _) => topCoords(id);
              case EdgeEv(fe)      => fe.facet.encoded
            lexCompare(xKey, yKey)
    val allEvents: Vector[DualEvent] = (vertexEvents ++ edgeEvents).sorted(using eventOrdering)

    // Union-find over `0 to numTop` (numTop itself = infinityId). `birthOf(root)` is the value at which the
    // CURRENT root's own component was seeded (the root is always the OLDEST -- i.e. largest-value -- member,
    // by the same "always attach younger under older" invariant unionFindDim01's own doc explains for the
    // primal case). `chainOf(root)` is that component's own running signed top-cell sum.
    val parent: Array[Int] = Array.range(0, numTop + 1)
    val birthOf: Array[Double] = Array.fill(numTop + 1)(Double.NaN)
    val chainOf: mutable.Map[Int, Map[Cube, CoefficientT]] = mutable.Map.empty

    def find(i: Int): Int =
      var root = i
      while parent(root) != root do root = parent(root)
      var cur = i
      while parent(cur) != root do
        val next = parent(cur)
        parent(cur) = root
        cur = next
      root

    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Cube, CoefficientT]]]

    // `∞` is always active, born at +Infinity -- the unconditional elder of anything it ever merges with.
    birthOf(infinityId) = Double.PositiveInfinity
    chainOf(infinityId) = Map.empty // never used as a dying side's own chain, so its own content is irrelevant

    for ev <- allEvents do
      ev match
        case VertexEv(id, v) =>
          birthOf(id) = v
          chainOf(id) = Map(topCube(topCoords(id)) -> fr.one)
        case EdgeEv(FacetEvent(facet, v, a, b)) =>
          val ra = find(a)
          val rb = find(b)
          if ra != rb then
            // `infinityId` must be the unconditional elder of any merge it takes part in (its own chain is
            // deliberately never populated, see below) -- ordinarily guaranteed because birthOf(infinityId) =
            // +Infinity is the largest possible value, but a REAL top cell can ALSO have topValue = +Infinity
            // (a permanently-missing cell, e.g. this codebase's own "Perseus missing-pixel" convention -- see
            // CubicalImage/PerseusSpec), tying birthOf(ra) <= birthOf(rb) at +Infinity <= +Infinity and letting
            // infinityId lose the comparison and be picked as the YOUNG/dying side -- caught by
            // TDA4jSpec's own ring fixture (a permanently-missing center pixel), not the hand-derived finite-
            // valued fixtures this class was first validated against. Special-case infinityId explicitly rather
            // than relying on the birthOf comparison alone to break this specific tie correctly.
            val (youngRoot, oldRoot) =
              if ra == infinityId then (rb, ra)
              else if rb == infinityId then (ra, rb)
              else if birthOf(ra) <= birthOf(rb) then (ra, rb)
              else (rb, ra)
            // Orientation: `facet`'s own boundary gives its coefficient toward EACH of its (up to two) cofaces
            // directly (cubeIsOrderedCell's alternating-rank sign rule) -- look up its coefficient toward
            // whichever of a/b sits in the young (dying) component specifically, and toward whichever sits in
            // the old (surviving) one; solve for the flip that makes the two cancel once combined (see the
            // class doc's own derivation -- both coefficients are always +-1, so "divide" is "multiply").
            val facetBoundary: Map[Cube, CoefficientT] = facet.boundary[CoefficientT].toMap
            val (youngTopId, oldTopId) = if youngRoot == ra then (a, b) else (b, a)
            // `infinityId` never sits on the YOUNG side -- it is always the eldest of any merge it takes part
            // in (birthOf(infinityId) = +Infinity, the largest possible value), so it is never the smaller
            // side of the `birthOf(ra) <= birthOf(rb)` comparison above.
            require(
              youngTopId != infinityId,
              "an engine bug: the infinity dual vertex was treated as the younger side of a merge"
            )
            val youngTopCube: Cube = topCube(topCoords(youngTopId))
            // Test the RESOLVED root, not the raw id: `oldTopId` can be a real id whose component already
            // merged into infinity's via an earlier tied-value edge this same pass, in which case
            // `oldTopId == infinityId` is false but `chainOf(oldRoot) == chainOf(infinityId)`, which is
            // deliberately never populated (infinity never dies, so its own chain content is never read) --
            // looking up `oldCube` there throws. `oldRoot == infinityId` is the correct, order-independent test.
            val oldTopCube: Option[Cube] = if oldRoot == infinityId then None else Some(topCube(topCoords(oldTopId)))
            val youngChain = chainOf(youngRoot)
            val youngCoeffAtTop = youngChain.getOrElse(
              youngTopCube,
              throw new IllegalStateException(
                s"dual component missing its own boundary top cell $youngTopCube -- an engine bug"
              )
            )
            val coeffTowardYoung = facetBoundary.getOrElse(youngTopCube, fr.zero)
            val flip: CoefficientT =
              oldTopCube match
                case None          => fr.one // the surviving side is infinity -- nothing to cancel against
                case Some(oldCube) =>
                  val oldChain = chainOf(oldRoot)
                  val oldCoeffAtTop = oldChain.getOrElse(
                    oldCube,
                    throw new IllegalStateException(
                      s"dual component missing its own boundary top cell $oldCube -- an engine bug"
                    )
                  )
                  val coeffTowardOld = facetBoundary.getOrElse(oldCube, fr.zero)
                  // Want: flip * youngCoeffAtTop * coeffTowardYoung + oldCoeffAtTop * coeffTowardOld = 0, i.e.
                  // flip = -(oldCoeffAtTop * coeffTowardOld) / (youngCoeffAtTop * coeffTowardYoung); every factor
                  // is +-1 (facet coefficients from cubeIsOrderedCell; chain coefficients by this method's own
                  // invariant, propagated from +-1 seeds), so division is multiplication.
                  fr.negate(
                    fr.times(fr.times(oldCoeffAtTop, coeffTowardOld), fr.times(youngCoeffAtTop, coeffTowardYoung))
                  )
            val flippedYoung: Map[Cube, CoefficientT] = youngChain.view.mapValues(c => fr.times(flip, c)).toMap
            parent(youngRoot) = oldRoot
            if oldRoot != infinityId then
              val merged = mutable.Map.from(chainOf(oldRoot))
              flippedYoung.foreach { case (cube, c) =>
                merged.updateWith(cube) {
                  case Some(existing) => Some(fr.plus(existing, c))
                  case None           => Some(c)
                }
              }
              chainOf(oldRoot) = merged.toMap
            chainOf -= youngRoot

            val rep: Chain[Cube, CoefficientT] =
              Chain.from(
                flippedYoung.toSeq.flatMap((cube, c) => cube.boundary[CoefficientT].map((f, s) => (f, fr.times(c, s))))
              )
            bars += new PersistenceBar(
              ambientDim - 1,
              endpoint(true)(v),
              endpoint(false)(birthOf(youngRoot)),
              Some(rep)
            )
    bars.toList
