package org.appliedtopology.tda4j

import scala.collection.mutable

/** The Delaunay triangulation of a point cloud by Bowyer–Watson insertion, filtered as an alpha complex: for point
  * clouds in 2 to 4 dimensions (of their affine span), the fast way to the whole alpha complex.
  *
  * Points are inserted one at a time in a randomized, spatially sorted order; each insertion removes the simplices
  * whose circumspheres contain the new point and fills the hole with simplices through it. The hull is handled with a
  * vertex at infinity. Every geometric decision is exact ([[DelaunayPredicates]]), and points exactly on a circumsphere
  * are decided by a perturbation that depends only on the points' order in the input, so the result is a valid
  * triangulation for any input, degenerate ones (grids, cospherical points) included, and the same for every `seed`.
  * Points that coincide exactly with an earlier one are joined to it by an edge of value 0.
  *
  * @param pts
  *   the points; their affine span must have dimension 1 to 4
  * @param seed
  *   seeds the insertion order, which affects only the running time
  */
class BowyerWatsonDelaunay(pts: Array[Array[Double]], seed: Long = 0L)(using epsilon: Epsilon)
    extends DelaunayAlphaShapes:
  // A single point (or none) spans nothing: zero coordinates, a lone vertex.
  private val coordinates: Array[Array[Double]] =
    if pts.length <= 1 then pts.map(_ => Array.empty[Double]) else HelixDelaunay.projectToAffineRank(pts)
  val ambientDimension: Int = coordinates.headOption.map(_.length).getOrElse(0)
  require(
    ambientDimension <= 4,
    s"BowyerWatsonDelaunay: the points span $ambientDimension dimensions; it handles at most 4. Use " +
      "AlphaBackend.Helix, or AlphaBackend.DQP with a maxRadius, for higher dimensions."
  )
  val points: Seq[AlphaShapes.Point] = coordinates.map(AlphaShapes.Point(_)).toIndexedSeq
  override val metricSpace: EuclideanMetricSpace = EuclideanMetricSpace(coordinates)

  private val triangulation = BowyerWatsonTriangulation(coordinates, seed)
  protected def topSimplices: Iterable[Simplex[Int]] = triangulation.topSimplices
  protected def topValue(s: Simplex[Int]): Double = smallestCircumsphere(s)._2
  override protected def duplicates: Map[Int, Int] = triangulation.duplicates

/** The triangulation behind [[BowyerWatsonDelaunay]], on flat arrays: cell `c` has vertices `vertex(c, 0 .. d)` and,
  * for each position `i`, the neighbouring cell across the facet opposite `vertex(c, i)`. Finite cells are positively
  * oriented. The vertex at infinity is the index `n`; a cell containing it (an "infinite" cell, one per hull facet) is
  * stored so that replacing the vertex at infinity by a point beyond its hull facet gives a positive orientation, which
  * makes "copy the cell and put the new point where the removed vertex was" correct for every cell.
  */
private[tda4j] final class BowyerWatsonTriangulation(
  coordinates: Array[Array[Double]],
  seed: Long,
  checkEveryStep: Boolean = false
):
  val n: Int = coordinates.length
  val d: Int = coordinates.headOption.map(_.length).getOrElse(0)
  private val infinite = n
  private val width = d + 1
  private lazy val predicates = DelaunayPredicates(coordinates)

  private var vertices = new Array[Int](64 * width)
  private var neighbours = new Array[Int](64 * width)
  private var alive = new Array[Boolean](64)
  private var stamp = new Array[Int](64) // conflict marks: = 2*insertion + 1 in conflict, 2*insertion + 2 checked clear
  private var cellCount = 0
  private val freeCells = mutable.Stack.empty[Int]
  private var lastCell = -1
  private val duplicateOf = mutable.LinkedHashMap.empty[Int, Int]
  private val rng = new scala.util.Random(seed)

  private[tda4j] var insertionErrors: List[String] = Nil

  private def vertex(c: Int, i: Int): Int = vertices(c * width + i)
  private def neighbour(c: Int, i: Int): Int = neighbours(c * width + i)
  private def isInfinite(c: Int): Boolean =
    var i = 0
    var found = false
    while i < width && !found do
      found = vertex(c, i) == infinite
      i += 1
    found
  private def cellVertices(c: Int): Array[Int] = java.util.Arrays.copyOfRange(vertices, c * width, c * width + width)

  private def newCell(): Int =
    if freeCells.nonEmpty then freeCells.pop()
    else
      if cellCount == alive.length then
        val cap = alive.length * 2
        vertices = java.util.Arrays.copyOf(vertices, cap * width)
        neighbours = java.util.Arrays.copyOf(neighbours, cap * width)
        alive = java.util.Arrays.copyOf(alive, cap)
        stamp = java.util.Arrays.copyOf(stamp, cap)
      cellCount += 1
      cellCount - 1

  // ---- construction ---------------------------------------------------------------------------------------------------

  /** Insertion order: random rounds of doubling size (BRIO), each sorted along a Z-order curve, so consecutive points
    * are close (short walks) while the order stays random enough for the expected running time.
    */
  private def insertionOrder(): Array[Int] =
    val shuffled = rng.shuffle((0 until n).toVector).toArray
    if n < 64 || d == 0 then shuffled
    else
      val lo = Array.tabulate(d)(j => coordinates.iterator.map(_(j)).min)
      val hi = Array.tabulate(d)(j => coordinates.iterator.map(_(j)).max)
      val bits = 63 / d
      def morton(i: Int): Long =
        val q = Array.tabulate(d) { j =>
          val span = hi(j) - lo(j)
          if span <= 0 then 0L else (((coordinates(i)(j) - lo(j)) / span) * ((1L << bits) - 1)).toLong
        }
        var key = 0L
        for b <- bits - 1 to 0 by -1; j <- 0 until d do key = (key << 1) | ((q(j) >> b) & 1L)
        key
      val result = mutable.ArrayBuffer.empty[Int]
      var start = 0
      var size = math.max(64, n >> 6)
      while start < n do
        val end = if n - start < 2 * size then n else start + size
        result ++= shuffled.slice(start, end).sortBy(morton)
        start = end
        size *= 2
      result.toArray

  /** `d + 1` affinely independent points, positively oriented, chosen early in `order`. */
  private def initialSimplex(order: Array[Int]): Array[Int] =
    val chosen = mutable.ArrayBuffer(order(0))
    // Grow by points that raise the affine rank, judged in floating point with a small relative tolerance, then
    // confirm with the exact orientation (and search exactly if that ever disagrees).
    def rankWith(candidate: Int): Int =
      val base = coordinates(chosen(0))
      val rows = (chosen.tail :+ candidate).map(i => Array.tabulate(d)(j => coordinates(i)(j) - base(j))).toArray
      if rows.isEmpty then 0
      else
        val scale = rows.map(r => math.sqrt(r.map(x => x * x).sum)).max
        if scale == 0 then 0
        else
          val svd = org.apache.commons.math3.linear.SingularValueDecomposition(
            org.apache.commons.math3.linear.MatrixUtils.createRealMatrix(rows)
          )
          svd.getSingularValues.count(_ > 1e-10 * scale)
    var k = 1
    while chosen.size < width && k < order.length do
      if rankWith(order(k)) == chosen.size then chosen += order(k)
      k += 1
    var simplex = chosen.toArray
    if simplex.length < width || predicates.orientation(simplex) == 0 then
      simplex = order.combinations(width).map(_.toArray).find(s => predicates.orientation(s) != 0).get
    if predicates.orientation(simplex) < 0 then
      val t = simplex(0); simplex(0) = simplex(1); simplex(1) = t
    simplex

  private def build(): Unit =
    if d == 0 then return
    // Exactly coincident points: the smallest index of each group is triangulated, the others recorded, so the result
    // does not depend on the insertion order.
    val firstAt = mutable.HashMap.empty[Seq[Double], Int]
    for i <- 0 until n do
      firstAt.get(coordinates(i).toSeq) match
        case Some(kept) => duplicateOf(i) = kept
        case None       => firstAt(coordinates(i).toSeq) = i
    val order = insertionOrder().filterNot(duplicateOf.contains)
    val start = initialSimplex(order)
    val finite = newCell()
    for i <- 0 until width do vertices(finite * width + i) = start(i)
    alive(finite) = true
    val hull = Array.tabulate(width) { i =>
      val c = newCell()
      val vs = start.clone()
      vs(i) = infinite
      // One transposition, so that replacing the vertex at infinity by a point beyond the facet (the opposite side
      // from start(i)) gives a positive orientation: two finite vertices, or in 1-D the two vertices there are.
      val (a, b) = if d == 1 then (0, 1) else if i == 0 then (1, 2) else if i == 1 then (0, 2) else (0, 1)
      val t = vs(a); vs(a) = vs(b); vs(b) = t
      for j <- 0 until width do vertices(c * width + j) = vs(j)
      alive(c) = true
      c
    }
    linkByFacets(finite +: hull.toSeq)
    lastCell = finite
    val startSet = start.toSet
    var step = 0
    for p <- order if !startSet.contains(p) do
      insert(p, step)
      step += 1
      if checkEveryStep then
        val problems = invariantProblems()
        if problems.nonEmpty then
          insertionErrors = s"after inserting point $p: ${problems.take(3).mkString("; ")}" :: insertionErrors

  /** Links every pair of the given cells that share a facet (used once, for the initial cells). */
  private def linkByFacets(cells: Seq[Int]): Unit =
    val byFacet = mutable.HashMap.empty[Seq[Int], (Int, Int)]
    for c <- cells; i <- 0 until width do
      val key = (0 until width).filter(_ != i).map(vertex(c, _)).sorted
      byFacet.remove(key) match
        case Some((o, oi)) =>
          neighbours(c * width + i) = o
          neighbours(o * width + oi) = c
        case None => byFacet(key) = (c, i)

  // ---- insertion ------------------------------------------------------------------------------------------------------

  private def withVertex(c: Int, i: Int, p: Int): Array[Int] =
    val vs = cellVertices(c)
    vs(i) = p
    vs

  /** A cell in conflict with `p` (containing it, or an infinite cell whose hull facet it lies beyond), or `-1 - v` if
    * `p` coincides with vertex `v`.
    */
  private def locate(p: Int): Int =
    var c = if lastCell >= 0 && alive(lastCell) then lastCell else (0 until cellCount).find(alive(_)).get
    var located = -1
    var done = false // `located` is negative for a duplicate, so it cannot double as the loop flag
    var steps = 0L
    while !done do
      steps += 1
      if steps > 10L * cellCount + 1000 then
        throw new IllegalStateException(s"BowyerWatsonTriangulation: the walk to point $p did not terminate")
      if isInfinite(c) then
        // Walking out of the hull: step to a finite neighbour unless p lies beyond this hull facet.
        val inf = (0 until width).find(vertex(c, _) == infinite).get
        if predicates.orientation(withVertex(c, inf, p)) > 0 then
          located = c
          done = true
        else c = neighbour(c, inf)
      else
        val offset = rng.nextInt(width)
        var moved = false
        var k = 0
        while !moved && k < width do
          val i = (offset + k) % width
          if predicates.orientation(withVertex(c, i, p)) < 0 then
            c = neighbour(c, i)
            moved = true
          k += 1
        if !moved then
          val same =
            (0 until width).map(vertex(c, _)).find(v => java.util.Arrays.equals(coordinates(v), coordinates(p)))
          located = same.fold(c)(v => -1 - v)
          done = true
    located

  private def inConflict(c: Int, p: Int): Boolean =
    val inf = (0 until width).find(vertex(c, _) == infinite)
    inf match
      case None    => predicates.inSphere(cellVertices(c), p) > 0
      case Some(i) =>
        val o = predicates.orientation(withVertex(c, i, p))
        if o != 0 then o > 0
        else predicates.inSphere(cellVertices(neighbour(c, i)), p) > 0 // p on the hull facet's hyperplane

  private def insert(p: Int, step: Int): Unit =
    val first = locate(p)
    if first < 0 then
      duplicateOf(p) = -1 - first
      return
    val conflictMark = 2 * step + 1
    val clearMark = 2 * step + 2
    val conflict = mutable.ArrayBuffer(first)
    stamp(first) = conflictMark
    val boundary = mutable.ArrayBuffer.empty[(Int, Int, Int)] // (conflict cell, position, outside cell)
    var head = 0
    while head < conflict.length do
      val c = conflict(head)
      head += 1
      for i <- 0 until width do
        val o = neighbour(c, i)
        if stamp(o) == conflictMark then ()
        else if stamp(o) == clearMark then boundary += ((c, i, o))
        else if inConflict(o, p) then
          stamp(o) = conflictMark
          conflict += o
        else
          stamp(o) = clearMark
          boundary += ((c, i, o))
    // One new cell per boundary facet: the conflict cell with p in place of the vertex across that facet.
    val ridges = mutable.HashMap.empty[Long, (Int, Int)]
    val generic = mutable.HashMap.empty[Seq[Int], (Int, Int)]
    val packable = d <= 4 && (d <= 1 || n + 1 < (1L << (63 / math.max(1, d - 1))))
    for (c, j, o) <- boundary do
      val nc = newCell()
      alive(nc) = true
      stamp(nc) = 0
      for i <- 0 until width do vertices(nc * width + i) = if i == j then p else vertex(c, i)
      neighbours(nc * width + j) = o
      var k = 0
      while neighbour(o, k) != c do k += 1
      neighbours(o * width + k) = nc
      for i <- 0 until width if i != j do
        // The facet opposite position i contains p; it is shared with the new cell built on the same ridge.
        val ridge = (0 until width).filter(m => m != i && m != j).map(vertex(nc, _)).sorted
        if packable then
          val bits = 63 / math.max(1, d - 1)
          val key = ridge.foldLeft(0L)((acc, v) => (acc << bits) | v.toLong)
          ridges.remove(key) match
            case Some((other, oi)) =>
              neighbours(nc * width + i) = other
              neighbours(other * width + oi) = nc
            case None => ridges(key) = (nc, i)
        else
          generic.remove(ridge) match
            case Some((other, oi)) =>
              neighbours(nc * width + i) = other
              neighbours(other * width + oi) = nc
            case None => generic(ridge) = (nc, i)
      if !isInfinite(nc) then lastCell = nc
    for c <- conflict do
      alive(c) = false
      freeCells.push(c)

  build()

  // ---- results and checks ---------------------------------------------------------------------------------------------

  /** The finite cells, as simplices. */
  lazy val topSimplices: Seq[Simplex[Int]] =
    if d == 0 then if n > 0 then Seq(Simplex(0)) else Nil
    else (0 until cellCount).filter(c => alive(c) && !isInfinite(c)).map(c => Simplex.from(cellVertices(c).toSeq))

  lazy val duplicates: Map[Int, Int] =
    if d == 0 then (1 until n).map(_ -> 0).toMap else duplicateOf.toMap

  /** Structural problems of the current triangulation (empty when it is valid): orientation of finite cells, symmetric
    * neighbours sharing a facet, the hull orientation convention, and the empty-sphere property across every facet.
    */
  private[tda4j] def invariantProblems(): Seq[String] =
    val problems = mutable.ArrayBuffer.empty[String]
    for c <- 0 until cellCount if alive(c) do
      val vs = cellVertices(c)
      if !isInfinite(c) && predicates.orientation(vs) <= 0 then problems += s"cell ${vs.mkString(",")} not positive"
      for i <- 0 until width do
        val o = neighbour(c, i)
        if !alive(o) then problems += s"cell ${vs.mkString(",")} has a dead neighbour"
        else
          val back = (0 until width).filter(neighbour(o, _) == c)
          if back.size != 1 then problems += s"neighbours of ${vs.mkString(",")} not symmetric"
          else
            val shared = vs.toSet - vs(i)
            val opposite = vertex(o, back.head)
            if (cellVertices(o).toSet - opposite) != shared then problems += s"facet mismatch at ${vs.mkString(",")}"
            else if !isInfinite(c) && opposite != infinite && predicates.inSphere(vs, opposite) > 0 then
              problems += s"cell ${vs.mkString(",")} not locally Delaunay"
    problems.toSeq
