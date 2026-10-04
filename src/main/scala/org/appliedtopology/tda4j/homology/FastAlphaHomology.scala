package org.appliedtopology.tda4j

import scala.collection.mutable

/** Thrown by [[FastAlphaHomologyEngine]] when the Helix triangulation has a facet with more than two top-dimensional
  * cofaces, which its dual graph cannot represent. Not expected on ordinary input (Helix checks and repairs its own
  * triangulation), and never a problem with the data: the general engines handle the same points. The message says so.
  */
class FastAlphaTriangulationException(message: String) extends RuntimeException(message)

/** Persistent homology of a Helix alpha complex by union-find instead of matrix reduction: degree 0 on the vertices and
  * edges, the top degree `d - 1` on the dual graph of the top-dimensional simplices, as in
  * [[FastCubicalHomologyEngine]]. In the plane those cover everything; in dimension 3 and up the degrees in between are
  * computed by the cohomology engine on the complex without its top simplices. Works in any ambient dimension from 2,
  * with representatives for every bar.
  *
  * The dual graph needs every facet to have one or two top-dimensional cofaces. Unlike a grid, a triangulation does not
  * guarantee it, so it is checked first, throwing [[FastAlphaTriangulationException]] when it fails (rare on random
  * points). A facet's dual-edge value is its own filtration value, which can be smaller than its cofaces' circumradii
  * (a Gabriel edge).
  */
class FastAlphaHomologyEngine[CoefficientT: Field]:
  private val fr = summon[CoefficientT is Field]
  given Ordering[Simplex[Int]] = simplexOrdering[Int]

  /** Every bar of the alpha filtration, with representatives; zero-length bars only if `includeZeroLength`. */
  def persistentHomology(
    helix: HelixDelaunay,
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    require(
      helix.ambientDimension >= 2,
      s"FastAlphaHomologyEngine requires ambient dimension >= 2, got ${helix.ambientDimension}"
    )
    val bars =
      if helix.ambientDimension == 2 then
        computeH0(helix, includeZeroLength) ++ computeDualTopDimension(helix, includeZeroLength)
      else computeMiddleDimensions(helix, includeZeroLength) ++ computeDualTopDimension(helix, includeZeroLength)
    bars

  // -------------------------------------------------------------------------------------------------------------
  // d >= 3's "middle" degrees (1 <= k <= d-2): see FastCubicalHomologyEngine.computeMiddleDimensions, whose
  // structure this mirrors exactly (the cohomology engine on a view hiding the top-dimensional simplices, which
  // declares homologyDegreeLimit = d - 2; H_0 comes out of the same computation).
  // -------------------------------------------------------------------------------------------------------------
  private def computeMiddleDimensions(
    helix: HelixDelaunay,
    includeZeroLength: Boolean
  ): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    val truncated = LimitedAlphaShapesStream(helix, helix.ambientDimension - 1)
    CellularCohomologyEngine[Simplex[Int], CoefficientT, Double]()
      .persistentHomology(truncated, includeZeroLength)
      .filter(_.dim <= helix.ambientDimension - 2)

  private def endpoint(lower: Boolean)(v: Double): BarcodeEndpoint[Double] =
    if !lower && v == Double.PositiveInfinity then PositiveInfinity()
    else if lower then ClosedEndpoint(v)
    else OpenEndpoint(v)

  // -------------------------------------------------------------------------------------------------------------
  // H_0: ordinary primal union-find, ascending value order, elder rule -- identical in shape to
  // FastCubicalHomologyEngine.computeH0, just over Simplex[Int] vertices/edges instead of Cube ones.
  // -------------------------------------------------------------------------------------------------------------
  private def computeH0(
    helix: HelixDelaunay,
    includeZeroLength: Boolean
  ): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
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
        val (birth, death) = (helix.filtrationValue(dying), helix.filtrationValue(edge))
        if includeZeroLength || birth != death then
          bars += new PersistenceBar(0, endpoint(true)(birth), endpoint(false)(death), Some(Chain(dying)))
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
  // the derivation, and .claude/DESIGN-fast-cubical-engine.md/FastCubicalHomologyEngine for the shared mechanism
  // this ports term-for-term (facet enumeration and facet-value lookup are the two genuinely new pieces here --
  // everything downstream of `facetEvents` is identical to the cubical engine, deliberately, since it's the
  // SAME generic dual-union-find/representative-tracking algorithm regardless of cell type).
  // -------------------------------------------------------------------------------------------------------------
  private def computeDualTopDimension(
    helix: HelixDelaunay,
    includeZeroLength: Boolean
  ): List[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]] =
    val ambientDim = helix.ambientDimension
    val topSimplices: Vector[Simplex[Int]] = helix.iterateDimension(ambientDim).toVector
    val numTop = topSimplices.size
    val infinityId = numTop // one past the last real top-simplex id

    def topValue(id: Int): Double =
      if id == infinityId then Double.PositiveInfinity else helix.filtrationValue(topSimplices(id))

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

    FastAlphaHomologyEngine.requireDualGraph(facetToTopIds, helix.ambientDimension)

    // Value from helix.filtrationValue(facet) directly, NOT ids.map(topValue).min -- see the class doc's own
    // note on why these can genuinely differ for an alpha complex (unlike a cubical grid).
    case class FacetEvent(facet: Simplex[Int], value: Double, a: Int, b: Int)
    val facetEvents: Vector[FacetEvent] = facetToTopIds.map { case (facet, ids) =>
      val paddedIds = if ids.size == 2 then ids else ids :+ infinityId
      FacetEvent(facet, helix.filtrationValue(facet), paddedIds(0), paddedIds(1))
    }.toVector

    // ONE combined descending pass: (value DESCENDING, isVertex DESCENDING [vertices before edges at a tied
    // value], then a deterministic tie-break) -- identical structure to FastCubicalHomologyEngine's own.
    sealed trait DualEvent:
      def value: Double
    case class VertexEv(id: Int, value: Double) extends DualEvent
    case class EdgeEv(fe: FacetEvent) extends DualEvent:
      def value: Double = fe.value

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
          val xKind = x match
            case _: VertexEv => 0;
            case _: EdgeEv   => 1
          val yKind = y match
            case _: VertexEv => 0;
            case _: EdgeEv   => 1
          if xKind != yKind then Integer.compare(xKind, yKind)
          else
            val xKey = x match
              case VertexEv(id, _) => topSimplices(id).toSeq;
              case EdgeEv(fe)      => fe.facet.toSeq
            val yKey = y match
              case VertexEv(id, _) => topSimplices(id).toSeq;
              case EdgeEv(fe)      => fe.facet.toSeq
            lexCompare(xKey, yKey)
    val allEvents: Vector[DualEvent] = (vertexEvents ++ edgeEvents).sorted(using eventOrdering)

    // Union-find over `0 to numTop` (numTop itself = infinityId) -- identical mechanics to
    // FastCubicalHomologyEngine.computeDualTopDimension from here on, including both of that class's own
    // once-found bugs' fixes (the resolved-root vs. raw-id check for the old side, and the explicit `infinityId`
    // special-case in the young/old decision).
    val uf = SignedUnionFind[CoefficientT](numTop + 1)
    val birthOf: Array[Double] = Array.fill(numTop + 1)(Double.NaN)

    val bars = mutable.ArrayBuffer.empty[PersistenceBar[Double, Chain[Simplex[Int], CoefficientT]]]

    birthOf(infinityId) = Double.PositiveInfinity

    for ev <- allEvents do
      ev match
        case VertexEv(id, v) =>
          birthOf(id) = v
        case EdgeEv(FacetEvent(facet, v, a, b)) =>
          val ra = uf.find(a)
          val rb = uf.find(b)
          if ra != rb then
            val (youngRoot, oldRoot) =
              if ra == infinityId then (rb, ra)
              else if rb == infinityId then (ra, rb)
              else if birthOf(ra) <= birthOf(rb) then (ra, rb)
              else (rb, ra)
            // The coefficient of `facet` in the boundary of a top cell containing it -- a coboundary entry, read from the
            // top cell's boundary (the facet's own boundary holds only its faces, so looking a top cell up there gave 0).
            def coeffToward(top: Simplex[Int]): CoefficientT =
              top.boundary[CoefficientT].collectFirst { case (f, c) if f == facet => c }.getOrElse(fr.zero)
            val (youngTopId, oldTopId) = if youngRoot == ra then (a, b) else (b, a)
            require(
              youngTopId != infinityId,
              "an engine bug: the infinity dual vertex was treated as the younger side of a merge"
            )
            val youngCoeffAtTop = uf.orientation(youngTopId)
            val coeffTowardYoung = coeffToward(topSimplices(youngTopId))
            val flip: CoefficientT =
              if oldRoot == infinityId then fr.one
              else
                val oldCoeffAtTop = uf.orientation(oldTopId)
                val coeffTowardOld = coeffToward(topSimplices(oldTopId))
                fr.negate(
                  fr.times(fr.times(oldCoeffAtTop, coeffTowardOld), fr.times(youngCoeffAtTop, coeffTowardYoung))
                )
            if includeZeroLength || v != birthOf(youngRoot) then
              val rep: Chain[Simplex[Int], CoefficientT] =
                Chain.from(uf.members(youngRoot).toSeq.flatMap { (id, sign) =>
                  val c = fr.times(flip, sign)
                  topSimplices(id).boundary[CoefficientT].map((f, s) => (f, fr.times(c, s)))
                })
              bars += new PersistenceBar(
                ambientDim - 1,
                endpoint(true)(v),
                endpoint(false)(birthOf(youngRoot)),
                Some(rep)
              )
            uf.union(youngRoot, oldRoot, flip)
    bars.toList

object FastAlphaHomologyEngine:
  /** Throws [[FastAlphaTriangulationException]] unless every facet has one or two top-dimensional cofaces: the dual
    * graph the engine walks is not defined otherwise.
    */
  private[tda4j] def requireDualGraph(facetToTopIds: Map[Simplex[Int], Vector[Int]], ambientDimension: Int): Unit =
    // Unlike a cubical grid, this is a real precondition: HelixDelaunay does not guarantee it. With the frontier walk
    // before its minimal-centre candidate search, roughly 1 in 18700 random 2-D clouds and 1 in 1666 3-D clouds of
    // 20-30 points failed it (.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md); with the current walk none of
    // 20000 2-D, 3000 3-D and 500 4-D random clouds did (.claude/WORKLOG-helix-construction-speed.md). Fail loudly,
    // in language that doesn't assume the reader knows this engine's internals.
    val badFacets = facetToTopIds.filter { case (_, ids) => ids.size < 1 || ids.size > 2 }
    if badFacets.nonEmpty then
      throw new FastAlphaTriangulationException(
        "The fast alpha-complex engine (engine=\"fast-alpha\" / FastAlphaHomologyEngine) could not compute a " +
          "result for this specific set of points.\n\n" +
          "This is NOT an error in your data, and it does NOT mean this point cloud's persistent homology is " +
          "unusual or unsupported. It is a known limitation of HelixDelaunay, the Delaunay triangulation this " +
          s"engine's fast algorithm depends on, at ambient dimension ${ambientDimension}: on a fraction of " +
          "point sets, HelixDelaunay's own triangulation comes out subtly inconsistent in a way this engine can " +
          "detect but cannot safely work around. This is rare on random points.\n\n" +
          "TO GET YOUR RESULT: recompute the SAME point cloud with a different engine -- \"naive\", \"chunks\", " +
          "or \"cohomology\" all give the exact same, fully correct persistent homology, via a completely " +
          "different algorithm that this limitation does not affect at all. For example (MATLAB/Java):\n" +
          "  TDA4j.computeFromPoints(points, new String[]{\"complex\", \"alpha\", \"engine\", \"naive\"})\n" +
          "or on the command line: --complex alpha --engine naive\n\n" +
          "(Technical detail, for developers investigating this class itself: " +
          s"${badFacets.size} facet(s) had a containing-top-simplex count other than 1 or 2 " +
          s"(${badFacets.map { case (f, ids) => s"$f -> ${ids.size} cofaces" }.mkString("; ")}), meaning the dual " +
          "graph this engine needs is not well-defined for this triangulation.)"
      )
