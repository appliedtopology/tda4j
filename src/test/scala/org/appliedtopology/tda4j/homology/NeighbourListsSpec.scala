package org.appliedtopology.tda4j

import org.specs2.mutable

/** `PackedRipserCohomologyEngine` with neighbour lists finds each simplex's cofacets by intersecting its vertices' lists
  * of neighbours within the threshold, instead of trying every vertex. It must be the same computation: the same bars,
  * in the same order, with the same cocycles and cycles term for term, as the all-vertex scan. Over F_3 and F_17, with
  * and without apparent pairs, at thresholds that keep few or most pairs, on generic clouds, on integer grids (diameters
  * tie everywhere), and on distances that are not exactly symmetric.
  */
class NeighbourListsSpec extends mutable.Specification:

  def entries[C](chain: Chain[?, C])(using fr: C is Field): List[(Double, Long, String)] =
    chain.rawEntries
      .groupMapReduce(e => cell(e._1))(_._2)(fr.plus)
      .toList
      .collect { case ((d, i), k) if !fr.isEqual(k, fr.zero) => (d, i, k.toString) }
      .sortBy(_._2)

  def cell(c: Any): (Double, Long) = c match
    case x: PackedRipserCohomologyEngine[?]#DiameterIndex => (x.diameter, x.index)
    case _                                                 => (Double.NaN, -1L)

  def clouds(seed: Long): Seq[FiniteMetricSpace[Int]] =
    val rng = new scala.util.Random(seed)
    val n = 10 + rng.nextInt(25)
    val dim = 2 + rng.nextInt(3)
    val points = Array.fill(n)(Array.fill(dim)(rng.nextDouble()))
    val grid = Array.fill(n)(Array.fill(dim)(rng.nextInt(3).toDouble))
    // Not symmetric by construction: d(i, j) and d(j, i) differ in the last bits.
    val skewed = new FiniteMetricSpace[Int]:
      private val base = EuclideanMetricSpace(points)
      def distance(x: Int, y: Int): Double = base.distance(x, y) * (if x > y then 1 + 1e-12 else 1.0)
      def size: Int = base.size
      def elements: Iterable[Int] = base.elements
      def contains(x: Int): Boolean = base.contains(x)
    // An explicit matrix whose two triangles differ (it reads the lower one).
    val matrix = ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => EuclideanMetricSpace(points).distance(i, j) * (if i < j then 1.01 else 1.0)))
    Seq(EuclideanMetricSpace(points), EuclideanMetricSpace(grid), skewed, matrix)

  def same(field: FiniteField)(space: FiniteMetricSpace[Int], apparent: Boolean, threshold: Option[Double]): Boolean =
    import field.given
    val scan = PackedRipserCohomologyEngine[field.Fp](space, 2, apparent, threshold, neighbourLists = Some(false))
    val lists = PackedRipserCohomologyEngine[field.Fp](space, 2, apparent, threshold, neighbourLists = Some(true))
    def flat[Cell](bars: List[PersistenceBar[Double, Chain[Cell, field.Fp]]]) =
      bars.map(b => (b.dim, b.lower.toString, b.upper.toString, entries(b.representative)))
    lists.usesNeighbourLists && !scan.usesNeighbourLists &&
    flat(lists.pairedCohomology().map(_._1)) == flat(scan.pairedCohomology().map(_._1)) &&
    flat(lists.persistentCohomology()) == flat(scan.persistentCohomology()) &&
    flat(lists.persistentHomology(true)) == flat(scan.persistentHomology(true)) &&
    lists.apparentPairCount == scan.apparentPairCount

  "Neighbour lists give the all-vertex scan's bars, cocycles and cycles, term for term" >> {
    val failures = for
      seed <- 0L until 10L
      (space, k) <- clouds(seed).zipWithIndex
      threshold <- Seq(Some(0.25), Some(0.5), None)
      field <- Seq(FiniteField(3), FiniteField(17))
      apparent <- Seq(true, false)
      if !same(field)(space, apparent, threshold)
    yield (seed, k, threshold, field.p, apparent)
    failures must beEmpty
  }

  "The engine builds them under a threshold that keeps few pairs, and not under the enclosing radius" >> {
    val rng = new scala.util.Random(5)
    val space = EuclideanMetricSpace(Array.fill(400)(Array.fill(3)(rng.nextDouble())))
    val f3 = FiniteField(3)
    import f3.given
    (PackedRipserCohomologyEngine[f3.Fp](space, 1, maxFiltrationValue = Some(0.1)).usesNeighbourLists must beTrue) and
      (PackedRipserCohomologyEngine[f3.Fp](space, 1).usesNeighbourLists must beFalse)
  }
