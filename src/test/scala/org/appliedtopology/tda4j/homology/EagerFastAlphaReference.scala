package org.appliedtopology.tda4j

import scala.collection.mutable

/** Test oracle: the fast alpha engine as it was before its representative bookkeeping moved to a signed union-find
  * (`.claude/WORKLOG-fast-cubical-representatives.md`), kept as it was apart from the class name and the
  * coboundary-coefficient fix (the merge flip was read from the facet's own boundary, which made it zero). It keeps a
  * running coefficient map per dual component and copies the surviving component's map on every merge -- quadratic, but
  * obviously right. `FastRepresentativesSpec` checks that the production engine returns exactly the same bars and
  * representatives, in the same order.
  */
class EagerFastAlphaReference[CoefficientT: Field]:
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
  // d >= 3's "middle" dimensions (1 <= k <= d-2): see FastCubicalHomologyEngine.computeMiddleDimensions, whose
  // structure this mirrors exactly (a stream truncated to hide the real top-dimensional cells, chunks's own
  // maxDim = d-2 semantics discarding the resulting incomplete top-dimension bars for free, H_0 coming along as
  // a side effect of chunks's own unionFindDim01). PersistenceInChunksEngine[Int, CoefficientT] is the
  // Simplex[Int]-over-Ordering[Int] convenience wrapper for CellularPersistenceInChunksEngine -- the same class
  // this codebase's naive/chunks/cohomology engines already use for alpha complexes elsewhere.
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

    // See the class doc's own note: unlike a cubical grid, this is a real precondition that can genuinely fail --
    // measured at roughly 1-in-18700 on random points at ambient dimension 2, but NOTICEABLY MORE LIKELY at
    // higher ambient dimension and with more points (roughly 1-in-1666 measured at ambient dimension 3 with
    // 20-30 points -- see .claude/DESIGN-fast-engines-hybrid-middle-dimensions.md's own measurement) -- fail
    // loudly and specifically, and (per the class's own doc) in language that doesn't assume the reader knows
    // this engine's internals.
    val badFacets = facetToTopIds.filter { case (_, ids) => ids.size < 1 || ids.size > 2 }
    if badFacets.nonEmpty then
      throw new FastAlphaTriangulationException(
        "The fast alpha-complex engine (engine=\"fast-alpha\" / FastAlphaHomologyEngine) could not compute a " +
          "result for this specific set of points.\n\n" +
          "This is NOT an error in your data, and it does NOT mean this point cloud's persistent homology is " +
          "unusual or unsupported. It is a known limitation of HelixDelaunay, the Delaunay triangulation this " +
          s"engine's fast algorithm depends on, at ambient dimension ${helix.ambientDimension}: on a fraction of " +
          "point sets, HelixDelaunay's own triangulation comes out subtly inconsistent in a way this engine can " +
          "detect but cannot safely work around. This is rare at ambient dimension 2 (roughly 1-in-18700 on " +
          "random points) but noticeably more likely at higher ambient dimension and with more points (roughly " +
          "1-in-1666 measured at ambient dimension 3 with 20-30 points).\n\n" +
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
            // The coefficient of `facet` in the boundary of a top cell containing it -- a coboundary entry, read from the
            // top cell's boundary (the facet's own boundary holds only its faces, so looking a top cell up there gave 0).
            def coeffToward(top: Simplex[Int]): CoefficientT =
              top.boundary[CoefficientT].collectFirst { case (f, c) if f == facet => c }.getOrElse(fr.zero)
            val (youngTopId, oldTopId) = if youngRoot == ra then (a, b) else (b, a)
            require(
              youngTopId != infinityId,
              "an engine bug: the infinity dual vertex was treated as the younger side of a merge"
            )
            val youngTop: Simplex[Int] = topSimplices(youngTopId)
            val oldTop: Option[Simplex[Int]] = if oldRoot == infinityId then None else Some(topSimplices(oldTopId))
            val youngChain = chainOf(youngRoot)
            val youngCoeffAtTop = youngChain.getOrElse(
              youngTop,
              throw new IllegalStateException(
                s"dual component missing its own boundary top simplex $youngTop -- an engine bug"
              )
            )
            val coeffTowardYoung = coeffToward(youngTop)
            val flip: CoefficientT =
              oldTop match
                case None             => fr.one
                case Some(oldSimplex) =>
                  val oldChain = chainOf(oldRoot)
                  val oldCoeffAtTop = oldChain.getOrElse(
                    oldSimplex,
                    throw new IllegalStateException(
                      s"dual component missing its own boundary top simplex $oldSimplex -- an engine bug"
                    )
                  )
                  val coeffTowardOld = coeffToward(oldSimplex)
                  fr.negate(
                    fr.times(fr.times(oldCoeffAtTop, coeffTowardOld), fr.times(youngCoeffAtTop, coeffTowardYoung))
                  )
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
              Chain.from(
                flippedYoung.toSeq.flatMap((simplex, c) =>
                  simplex.boundary[CoefficientT].map((f, s) => (f, fr.times(c, s)))
                )
              )
            if includeZeroLength || v != birthOf(youngRoot) then
              bars += new PersistenceBar(
                ambientDim - 1,
                endpoint(true)(v),
                endpoint(false)(birthOf(youngRoot)),
                Some(rep)
              )
    bars.toList
