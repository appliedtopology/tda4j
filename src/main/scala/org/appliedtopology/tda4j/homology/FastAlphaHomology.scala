package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}
import org.appliedtopology.tda4j.barcode.{BarcodeEndpoint, ClosedEndpoint, OpenEndpoint, PersistenceBar, PositiveInfinity}

import scala.collection.mutable

/** The `homology.FastCubicalHomologyContext` dual-graph union-find, ported to a `HelixDelaunay` alpha complex
  * (`.claude/DESIGN-alpha-dual-unionfind.md`, item 7 of `.claude/WORKLOG-mainstream-feature-gap-analysis.md`,
  * a follow-on to item 6's cubical engine). `HelixDelaunay` specifically, not `AlphaComplexDQP`/`AlphaShapeDQP`
  * -- the dual graph needs the FULL, untruncated triangulation and "every facet has <= 2 cofaces," which
  * `AlphaShapeDQP`'s own documented cospherical-degeneracy hazard can violate directly (see the design note).
  *
  * '''Currently ambient dimension 2 only''' (`require`d), for the identical reason as the cubical engine: `H_0`
  * (ordinary primal union-find) plus `H_1` (`= H_{d-1}` at `d=2`, via the dual union-find below) together account
  * for every cell dimension a 2D triangulation has, with no general `Chain.reduceBy` reduction needed at all.
  * Ambient dimension 3 needs the same additional, harder piece the cubical engine deferred (`H_1` there needs
  * general reduction on whichever cells are NOT already resolved by the `H_0`/`H_2` union-finds) -- not attempted
  * here either.
  *
  * '''Unlike the cubical grid, "every facet has 1 or 2 cofaces" is not guaranteed by construction''' -- validated
  * explicitly up front, throwing a specific, actionable exception on violation rather than silently building a
  * wrong dual graph. Measured (this session, not previously known) at roughly 1-in-3000 on random points at this
  * exact ambient dimension -- rare, but real, a genuine `HelixDelaunay` limitation (a cospherical tiling choice
  * or its own documented frontier-walk incompleteness bug), not a flaw in this construction. See the design
  * note's "new finding" section.
  *
  * '''A facet's own dual-edge value is `helix.filtrationValue(facet)` directly, never recomputed as `min` over
  * its containing top simplices''' -- unlike a cubical grid (where those two quantities are the same by
  * construction), `HelixDelaunay.computeFVal`'s own `edgeIsDelaunay` shortcut can give a genuinely SMALLER value
  * than either containing triangle's own circumradius; using anything else silently shifts some bars' birth
  * values (see the design note's own worked example for a concrete case where this matters).
  *
  * See `FastCubicalHomologyContext`'s own doc for the shared parts of the construction (the dual graph itself,
  * the `∞` sentinel and why it must be `+Infinity`, the birth/death swap, and the representative-tracking
  * orientation-flip scheme) -- identical here, `Simplex[Int]`'s alternating-sign boundary rule
  * (`simplexIsOrderedCell`) standing in for `Cube`'s rank-among-non-degenerate-axes rule.
  */
class FastAlphaHomologyContext[CoefficientT: Field]:
  private val fr = summon[CoefficientT is Field]
  given Ordering[Simplex[Int]] = simplexOrdering[Int]

  def persistentHomology(helix: HelixDelaunay): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    require(
      helix.ambientDimension == 2,
      s"FastAlphaHomologyContext currently supports ambient dimension 2 only, got ${helix.ambientDimension}"
    )
    computeH0(helix) ++ computeDualTopDimension(helix)

  private def endpoint(lower: Boolean)(v: Double): BarcodeEndpoint[Double] =
    if !lower && v == Double.PositiveInfinity then PositiveInfinity() else if lower then ClosedEndpoint(v) else OpenEndpoint(v)

  // -------------------------------------------------------------------------------------------------------------
  // H_0: ordinary primal union-find, ascending value order, elder rule -- identical in shape to
  // FastCubicalHomologyContext.computeH0, just over Simplex[Int] vertices/edges instead of Cube ones.
  // -------------------------------------------------------------------------------------------------------------
  private def computeH0(helix: HelixDelaunay): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    val vertices: Vector[Simplex[Int]] = helix.iterateDimension.applyOrElse(0, (_: Int) => Iterator.empty).toVector
    val vertexIndex: Map[Simplex[Int], Int] = vertices.zipWithIndex.toMap
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

    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]]
    val edges: Vector[Simplex[Int]] = helix.iterateDimension.applyOrElse(1, (_: Int) => Iterator.empty).toVector
    val orderedEdges = edges.sorted(using helix.filtrationOrdering.reverse)
    for edge <- orderedEdges do
      val ends = edge.boundary[CoefficientT].map(_._1)
      val r0 = find(vertexIndex(ends(0)))
      val r1 = find(vertexIndex(ends(1)))
      if r0 != r1 then
        val v0Val = helix.filtrationValue(vertices(r0))
        val v1Val = helix.filtrationValue(vertices(r1))
        val (youngRoot, oldRoot) = if v0Val <= v1Val then (r1, r0) else (r0, r1)
        parent(youngRoot) = oldRoot
        val dying = vertices(youngRoot)
        bars += new PersistenceBar(
          0,
          endpoint(true)(helix.filtrationValue(dying)),
          endpoint(false)(helix.filtrationValue(edge)),
          Some(Chain(dying))
        )
    vertices.indices.foreach { i =>
      if find(i) == i then
        bars += new PersistenceBar(
          0,
          endpoint(true)(helix.filtrationValue(vertices(i))),
          PositiveInfinity(),
          Some(Chain(vertices(i)))
        )
    }
    bars.toList

  // -------------------------------------------------------------------------------------------------------------
  // H_{d-1} (= H_1 at d=2): the dual union-find. See the class doc and .claude/DESIGN-alpha-dual-unionfind.md for
  // the derivation, and .claude/DESIGN-fast-cubical-engine.md/FastCubicalHomologyContext for the shared mechanism
  // this ports term-for-term (facet enumeration and facet-value lookup are the two genuinely new pieces here --
  // everything downstream of `facetEvents` is identical to the cubical engine, deliberately, since it's the
  // SAME generic dual-union-find/representative-tracking algorithm regardless of cell type).
  // -------------------------------------------------------------------------------------------------------------
  private def computeDualTopDimension(helix: HelixDelaunay): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    val ambientDim = helix.ambientDimension
    val topSimplices: Vector[Simplex[Int]] = helix.iterateDimension(ambientDim).toVector
    val numTop = topSimplices.size
    val infinityId = numTop // one past the last real top-simplex id

    def topValue(id: Int): Double = if id == infinityId then Double.PositiveInfinity else helix.filtrationValue(topSimplices(id))

    // Facet (codimension-1 simplex) -> containing top-simplex ids, built by brute force: a Delaunay
    // triangulation has no regular-grid structure to derive this from coordinates the way CubicalGridStream
    // does, so this is one pass over every top simplex's own ambientDim+1 facets.
    val facetToTopIds: Map[Simplex[Int], Vector[Int]] =
      topSimplices.zipWithIndex
        .flatMap { case (top, id) => top.toSeq.map(v => (top - v, id)) }
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toVector)
        .toMap

    // See the class doc's own note: unlike a cubical grid, this is a real precondition that can genuinely fail
    // (measured ~1-in-3000 on random points at this exact ambient dimension) -- fail loudly and specifically.
    val badFacets = facetToTopIds.filter { case (_, ids) => ids.size < 1 || ids.size > 2 }
    if badFacets.nonEmpty then
      throw new IllegalStateException(
        s"FastAlphaHomologyContext: ${badFacets.size} facet(s) have a containing-top-simplex count other than " +
          s"1 or 2 (${badFacets.map { case (f, ids) => s"$f -> ${ids.size} cofaces" }.mkString("; ")}) -- the dual " +
          "graph this engine relies on is not well-defined for this triangulation. This is a known, rare " +
          "HelixDelaunay limitation (a cospherical tiling choice, or its own documented frontier-walk " +
          "incompleteness bug), not a bug in this class -- see .claude/DESIGN-alpha-dual-unionfind.md's 'new " +
          "finding' section. Use CellularHomologyContext/CellularPersistenceInChunksContext on this same " +
          "HelixDelaunay stream instead."
      )

    // Value from helix.filtrationValue(facet) directly, NOT ids.map(topValue).min -- see the class doc's own
    // note on why these can genuinely differ for an alpha complex (unlike a cubical grid).
    case class FacetEvent(facet: Simplex[Int], value: Double, a: Int, b: Int)
    val facetEvents: Vector[FacetEvent] = facetToTopIds.map { case (facet, ids) =>
      val paddedIds = if ids.size == 2 then ids else ids :+ infinityId
      FacetEvent(facet, helix.filtrationValue(facet), paddedIds(0), paddedIds(1))
    }.toVector

    // ONE combined descending pass: (value DESCENDING, isVertex DESCENDING [vertices before edges at a tied
    // value], then a deterministic tie-break) -- identical structure to FastCubicalHomologyContext's own.
    sealed trait DualEvent { def value: Double }
    case class VertexEv(id: Int, value: Double) extends DualEvent
    case class EdgeEv(fe: FacetEvent) extends DualEvent { def value: Double = fe.value }

    def lexCompare(a: Seq[Int], b: Seq[Int]): Int =
      val n = math.min(a.size, b.size)
      var i = 0
      var result = 0
      while result == 0 && i < n do
        result = Integer.compare(a(i), b(i))
        i += 1
      if result != 0 then result else Integer.compare(a.size, b.size)

    val vertexEvents: Vector[VertexEv] = topSimplices.indices.map(id => VertexEv(id, topValue(id))).toVector
    val edgeEvents: Vector[EdgeEv] = facetEvents.map(EdgeEv.apply)
    val eventOrdering: Ordering[DualEvent] = new Ordering[DualEvent]:
      def compare(x: DualEvent, y: DualEvent): Int =
        val byValue = -java.lang.Double.compare(x.value, y.value) // descending
        if byValue != 0 then byValue
        else
          val xKind = x match { case _: VertexEv => 0; case _: EdgeEv => 1 }
          val yKind = y match { case _: VertexEv => 0; case _: EdgeEv => 1 }
          if xKind != yKind then Integer.compare(xKind, yKind)
          else
            val xKey = x match { case VertexEv(id, _) => topSimplices(id).toSeq; case EdgeEv(fe) => fe.facet.toSeq }
            val yKey = y match { case VertexEv(id, _) => topSimplices(id).toSeq; case EdgeEv(fe) => fe.facet.toSeq }
            lexCompare(xKey, yKey)
    val allEvents: Vector[DualEvent] = (vertexEvents ++ edgeEvents).sorted(using eventOrdering)

    // Union-find over `0 to numTop` (numTop itself = infinityId) -- identical mechanics to
    // FastCubicalHomologyContext.computeDualTopDimension from here on, including both of that class's own
    // once-found bugs' fixes (the resolved-root vs. raw-id check for `oldTopCube`, and the explicit `infinityId`
    // special-case in the young/old decision) -- ported directly rather than risking rediscovering either.
    val parent: Array[Int] = Array.range(0, numTop + 1)
    val birthOf: Array[Double] = Array.fill(numTop + 1)(Double.NaN)
    val chainOf: mutable.Map[Int, Map[Simplex[Int], CoefficientT]] = mutable.Map.empty

    def find(i: Int): Int =
      var root = i
      while parent(root) != root do root = parent(root)
      var cur = i
      while parent(cur) != root do
        val next = parent(cur)
        parent(cur) = root
        cur = next
      root

    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]]

    birthOf(infinityId) = Double.PositiveInfinity
    chainOf(infinityId) = Map.empty

    for ev <- allEvents do
      ev match
        case VertexEv(id, v) =>
          birthOf(id) = v
          chainOf(id) = Map(topSimplices(id) -> fr.one)
        case EdgeEv(FacetEvent(facet, v, a, b)) =>
          val ra = find(a)
          val rb = find(b)
          if ra != rb then
            val (youngRoot, oldRoot) =
              if ra == infinityId then (rb, ra)
              else if rb == infinityId then (ra, rb)
              else if birthOf(ra) <= birthOf(rb) then (ra, rb)
              else (rb, ra)
            val facetBoundary: Map[Simplex[Int], CoefficientT] = facet.boundary[CoefficientT].toMap
            val (youngTopId, oldTopId) = if youngRoot == ra then (a, b) else (b, a)
            require(youngTopId != infinityId, "an engine bug: the infinity dual vertex was treated as the younger side of a merge")
            val youngTop: Simplex[Int] = topSimplices(youngTopId)
            val oldTop: Option[Simplex[Int]] = if oldRoot == infinityId then None else Some(topSimplices(oldTopId))
            val youngChain = chainOf(youngRoot)
            val youngCoeffAtTop = youngChain.getOrElse(
              youngTop,
              throw new IllegalStateException(s"dual component missing its own boundary top simplex $youngTop -- an engine bug")
            )
            val coeffTowardYoung = facetBoundary.getOrElse(youngTop, fr.zero)
            val flip: CoefficientT =
              oldTop match
                case None => fr.one
                case Some(oldSimplex) =>
                  val oldChain = chainOf(oldRoot)
                  val oldCoeffAtTop = oldChain.getOrElse(
                    oldSimplex,
                    throw new IllegalStateException(s"dual component missing its own boundary top simplex $oldSimplex -- an engine bug")
                  )
                  val coeffTowardOld = facetBoundary.getOrElse(oldSimplex, fr.zero)
                  fr.negate(fr.times(fr.times(oldCoeffAtTop, coeffTowardOld), fr.times(youngCoeffAtTop, coeffTowardYoung)))
            val flippedYoung: Map[Simplex[Int], CoefficientT] = youngChain.view.mapValues(c => fr.times(flip, c)).toMap
            parent(youngRoot) = oldRoot
            if oldRoot != infinityId then
              val merged = mutable.Map.from(chainOf(oldRoot))
              flippedYoung.foreach { case (simplex, c) =>
                merged.updateWith(simplex) {
                  case Some(existing) => Some(fr.plus(existing, c))
                  case None           => Some(c)
                }
              }
              chainOf(oldRoot) = merged.toMap
            chainOf -= youngRoot

            val rep: Chain[Simplex[Int], CoefficientT] =
              Chain.from(flippedYoung.toSeq.flatMap((simplex, c) => simplex.boundary[CoefficientT].map((f, s) => (f, fr.times(c, s)))))
            bars += new PersistenceBar(ambientDim - 1, endpoint(true)(v), endpoint(false)(birthOf(youngRoot)), Some(rep))
    bars.toList
