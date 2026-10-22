package org.appliedtopology.tda4j

import org.specs2.mutable

import scala.compiletime.asMatchable

/** `PackedRipserCohomologyEngine` with two-word simplex indices (`wideIndices = Some(true)`) must be the same
  * computation as with one `Long`, where the indices fit in both: the same bars in the same order, the same cocycles
  * and cycles term for term, the same apparent pairs. Over F_3 and F_17, with and without apparent pairs, with and
  * without neighbour lists, on generic clouds, on integer grids (diameters tie everywhere) and on distances that are
  * not exactly symmetric. The indices past 2^63, which only the two-word engine can reach, are checked in
  * `WideIndicesPastLongSpec`.
  */
class WideIndicesSpec extends mutable.Specification:

  def entries[C](chain: Chain[?, C])(using fr: C is Field): List[(Double, Long, Long, String)] =
    chain.rawEntries
      .groupMapReduce(e => cell(e._1))(_._2)(fr.plus)
      .toList
      .collect { case ((d, i, h), k) if !fr.isEqual(k, fr.zero) => (d, i, h, k.toString) }
      .sortBy(e => (e._3, e._2))

  def cell(c: Any): (Double, Long, Long) = c.asMatchable match
    case x: PackedRipserCohomologyEngine[?]#DiameterIndex => (x.diameter, x.index, x.high)
    case _                                                => (Double.NaN, -1L, -1L)

  def clouds(seed: Long): Seq[FiniteMetricSpace[Int]] =
    val rng = new scala.util.Random(seed)
    val n = 10 + rng.nextInt(20)
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
    Seq(EuclideanMetricSpace(points), EuclideanMetricSpace(grid), skewed)

  def same(field: FiniteField)(
    space: FiniteMetricSpace[Int],
    apparent: Boolean,
    threshold: Option[Double],
    lists: Boolean
  ): Boolean =
    import field.given
    def engine(wide: Boolean) =
      PackedRipserCohomologyEngine[field.Fp](
        space,
        2,
        apparent,
        threshold,
        neighbourLists = Some(lists),
        wideIndices = Some(wide)
      )
    val one = engine(false)
    val two = engine(true)
    def flat[Cell](bars: List[PersistenceBar[Double, Chain[Cell, field.Fp]]]) =
      bars.map(b => (b.dim, b.lower.toString, b.upper.toString, entries(b.representative)))
    two.usesWideIndices && !one.usesWideIndices &&
    flat(two.pairedCohomology().map(_._1)) == flat(one.pairedCohomology().map(_._1)) &&
    flat(two.persistentCohomology()) == flat(one.persistentCohomology()) &&
    flat(two.persistentHomology(true)) == flat(one.persistentHomology(true)) &&
    two.apparentPairCount == one.apparentPairCount &&
    two.totalSimplexCount == one.totalSimplexCount

  "Two-word indices give the one-word engine's bars, cocycles and cycles, term for term" >> {
    val failures = for
      seed <- 0L until 8L
      (space, k) <- clouds(seed).zipWithIndex
      threshold <- Seq(Some(0.25), Some(0.5), None)
      field <- Seq(FiniteField(3), FiniteField(17))
      apparent <- Seq(true, false)
      lists <- Seq(true, false)
      if !same(field)(space, apparent, threshold, lists)
    yield (seed, k, threshold, field.p, apparent, lists)
    failures must beEmpty
  }

  "A two-word key is its two words: keys equal in the low word and different in the high word are different" >> {
    val keys = WideKeys()
    val a = keys.add(1L, 5L)
    val b = keys.add(2L, 5L)
    val again = keys.add(1L, 5L)
    // Enough keys to grow the table, many of them with one low word.
    val many = (0 until 5000).map(i => (i.toLong / 7, i.toLong % 7))
    val numbered = many.map((h, l) => keys.add(h, l))
    (a must not(beEqualTo(b)))
      .and(again must beEqualTo(a))
      .and(keys.find(2L, 5L) must beEqualTo(b))
      .and(keys.find(99999L, 5L) must beEqualTo(WideKeys.Absent))
      .and(many.map((h, l) => keys.find(h, l)) must beEqualTo(numbered))
      .and(numbered.distinct.size must beEqualTo(many.size))
  }

  "Cells with the same low word and different high words are different cells, and the larger index is the older" >> {
    val f3 = FiniteField(3)
    import f3.given
    val engine = PackedRipserCohomologyEngine[f3.Fp](
      EuclideanMetricSpace(Array(Array(0.0), Array(1.0), Array(3.0))),
      1,
      wideIndices = Some(true)
    )
    val low = engine.DiameterIndex(1.0, 5L, 1L)
    val high = engine.DiameterIndex(1.0, 5L, 2L)
    val order = engine.packedOrdering
    (low must not(beEqualTo(high)))
      .and(low must beEqualTo(engine.DiameterIndex(1.0, 5L, 1L)))
      // Larger index first among equal diameters, the high word deciding before the low.
      .and(order.compare(high, low) must beLessThan(0))
      .and(order.compare(engine.DiameterIndex(1.0, 0L, 2L), engine.DiameterIndex(1.0, 999L, 1L)) must beLessThan(0))
  }

  "The engine chooses two-word indices exactly when one Long cannot hold the indices of its run" >> {
    val f3 = FiniteField(3)
    import f3.given
    def wide(n: Int, maxDimension: Int) =
      // A metric space is only asked its size here: a threshold is given, so nothing else is computed.
      val space = new FiniteMetricSpace[Int]:
        def distance(x: Int, y: Int): Double = 1.0
        def size: Int = n
        def elements: Iterable[Int] = 0 until n
        def contains(x: Int): Boolean = x >= 0 && x < n
      PackedRipserCohomologyEngine[f3.Fp](
        space,
        maxDimension,
        maxFiltrationValue = Some(0.0),
        neighbourLists = Some(false)
      ).usesWideIndices
    // C(n + 5, 5) passes 2^63 between 10,000 and 20,000 points; to degree 1 (4 vertices) not until much later.
    (wide(8000, 3) must beFalse)
      .and(wide(20000, 3) must beTrue)
      .and(wide(20000, 1) must beFalse)
      .and(wide(200, 11) must beTrue)
  }

  "The engine refuses a run whose indices need more than 126 bits, and says what to lower" >> {
    val f3 = FiniteField(3)
    import f3.given
    val space = new FiniteMetricSpace[Int]:
      def distance(x: Int, y: Int): Double = 1.0
      def size: Int = 1_000_000_000
      def elements: Iterable[Int] = Iterable.empty
      def contains(x: Int): Boolean = false
    // By substring: the regular-expression form of `throwAn` ("points are too many .* lower maxDimension") passed on the
    // JVM and failed on Scala.js against the same message (cause not investigated).
    val refused = scala.util.Try(
      PackedRipserCohomologyEngine[f3.Fp](space, 5, maxFiltrationValue = Some(0.0), neighbourLists = Some(false))
    )
    val message = refused.failed.toOption.collect { case e: IllegalArgumentException => e.getMessage }.getOrElse("")
    (refused.isFailure must beTrue)
      .and(message must contain("points are too many"))
      .and(message must contain("lower maxDimension"))
  }
