package org.appliedtopology.tda4j

import org.specs2.mutable

import scala.compiletime.asMatchable

/** Emergent pairs (Bauer, Def. 3.11) in `PackedRipserCohomologyEngine`: a column whose oldest same-diameter cofacet is
  * no earlier column's pivot is paired with it without being reduced. The shortcut is the first step of the reduction
  * (the pivot of the unreduced coboundary, found not to be taken), so it must change nothing: the same bars, the same
  * pairs and the same cocycles and cycles term for term with `useEmergentPairs = false`, and the bars of the reference
  * engine, which has no shortcut beyond apparent pairs. Over F_2, F_3 and F_17, with and without apparent pairs, with
  * and without neighbour lists and two-word indices, on generic clouds and on integer grids where diameters tie
  * everywhere (the emergent pairs there are many, and so are the cofacets that an earlier column has taken).
  */
class EmergentPairsSpec extends mutable.Specification:

  def entries[C](chain: Chain[?, C])(using fr: C is Field): List[(Double, Long, Long, String)] =
    chain.rawEntries
      .groupMapReduce(e => cell(e._1))(_._2)(fr.plus)
      .toList
      .collect { case ((d, i, h), k) if !fr.isEqual(k, fr.zero) => (d, i, h, k.toString) }
      .sortBy(e => (e._3, e._2))

  def cell(c: Any): (Double, Long, Long) = c.asMatchable match
    case x: PackedRipserCohomologyEngine[?]#DiameterIndex => (x.diameter, x.index, x.high)
    case _                                                => (Double.NaN, -1L, -1L)

  /** Points with coordinates in `0 until values` (some coincide): diameters tie almost everywhere. */
  def tieGrid(seed: Long): EuclideanMetricSpace =
    val rng = new scala.util.Random(seed)
    val n = 8 + rng.nextInt(24)
    val dim = 2 + rng.nextInt(3)
    val values = 2 + rng.nextInt(3)
    EuclideanMetricSpace(Array.fill(n)(Array.fill(dim)(rng.nextInt(values).toDouble)))

  // Seeds of `tieGrid` where a column's oldest same-diameter cofacet is the pivot of an earlier column that was
  // reduced and stored (so a missing check pairs two columns with one cofacet), found by searching 300 seeds.
  val collisionSeeds: Seq[Long] = Seq(9L, 36L, 58L, 83L, 115L)

  def clouds(seed: Long): Seq[FiniteMetricSpace[Int]] =
    val rng = new scala.util.Random(seed)
    val n = 10 + rng.nextInt(20)
    val dim = 2 + rng.nextInt(3)
    Seq(
      EuclideanMetricSpace(Array.fill(n)(Array.fill(dim)(rng.nextDouble()))),
      EuclideanMetricSpace(Array.fill(n)(Array.fill(dim)(rng.nextInt(3).toDouble))),
      tieGrid(seed)
    )

  /** The seeds of a run: a few of each kind, and the collisions. */
  def seeds(jvm: Long, js: Long): Seq[Long] = (0L until TestPlatform.perPlatform(jvm, js)) ++ collisionSeeds

  /** The runs with and without the shortcut agree; the number of emergent pairs of the run with it (0 if it is off). */
  def emergentPairs(field: FiniteField)(
    space: FiniteMetricSpace[Int],
    apparent: Boolean,
    threshold: Option[Double],
    lists: Boolean,
    wide: Boolean
  ): Option[Int] =
    import field.given
    def engine(emergent: Boolean) =
      PackedRipserCohomologyEngine[field.Fp](
        space,
        2,
        apparent,
        threshold,
        neighbourLists = Some(lists),
        wideIndices = Some(wide),
        useEmergentPairs = emergent
      )
    val with_ = engine(true)
    val without = engine(false)
    def flat[Cell](bars: List[PersistenceBar[Double, Chain[Cell, field.Fp]]]) =
      bars.map(b => (b.dim, b.lower.toString, b.upper.toString, entries(b.representative)))
    def pairs(e: PackedRipserCohomologyEngine[field.Fp]) =
      e.pairedCohomology().map { case (_, p) => (p.dim, cell(p.birth), p.death.map(cell)) }
    val agree =
      flat(with_.pairedCohomology().map(_._1)) == flat(without.pairedCohomology().map(_._1)) &&
        pairs(with_) == pairs(without) &&
        flat(with_.persistentCohomology()) == flat(without.persistentCohomology()) &&
        flat(with_.persistentHomology(true)) == flat(without.persistentHomology(true)) &&
        with_.apparentPairCount == without.apparentPairCount &&
        with_.totalSimplexCount == without.totalSimplexCount &&
        without.emergentPairCount == 0
    Option.when(agree)(with_.emergentPairCount)

  "Emergent pairs change no bar, pair, cocycle or cycle, term for term" >> {
    val runs = for
      seed <- seeds(jvm = 10L, js = 2L)
      space <- clouds(seed)
      threshold <- Seq(Some(0.3), None)
      field <- Seq(FiniteField(2), FiniteField(3), FiniteField(17))
      apparent <- Seq(true, false)
      lists <- Seq(true, false)
      wide <- Seq(false, true)
    yield (seed, threshold, field.p, apparent, lists, wide) -> emergentPairs(field)(
      space,
      apparent,
      threshold,
      lists,
      wide
    )
    (runs.collect { case (run, None) => run }.take(3) must beEmpty) and
      // The shortcut has to be taken for this to say anything, with and without apparent pairs.
      (runs.collect { case ((_, _, _, true, _, _), Some(n)) => n }.sum must beGreaterThan(0)) and
      (runs.collect { case ((_, _, _, false, _, _), Some(n)) => n }.sum must beGreaterThan(0))
  }

  /** The bars of the packed engine (zero-length ones included) and of the reference engine, as sorted triples. */
  def barsOfBoth(field: FiniteField)(
    space: FiniteMetricSpace[Int]
  ): (List[(Int, String, String)], List[(Int, String, String)]) =
    import field.given
    def triples[A](bars: List[PersistenceBar[Double, A]]) =
      bars.map(b => (b.dim, b.lower.toString, b.upper.toString)).sorted
    (
      triples(PackedRipserCohomologyEngine[field.Fp](space, 2).persistentCohomology(true)),
      triples(RipserCohomologyEngine[field.Fp](space, 2).persistentCohomology(true))
    )

  "The shortcut finds the same bars as the reference engine, bar for bar, over F_2 and F_17" >> {
    val failures = for
      seed <- seeds(jvm = 12L, js = 3L)
      space <- clouds(seed)
      field <- Seq(FiniteField(2), FiniteField(17))
      (packed, reference) = barsOfBoth(field)(space)
      if packed != reference
    yield (seed, field.p)
    failures.take(3) must beEmpty
  }

  /** Whether the simplices of dimension at most 2 are the bars (zero-length ones included) plus the finite bars of
    * degrees 0 and 1: each simplex is the birth of a bar or the death of a bar of the degree below (its column is
    * cleared).
    */
  def accountsForEverySimplex(space: FiniteMetricSpace[Int], emergent: Boolean): Boolean =
    val field = FiniteField(17)
    import field.given
    val engine = PackedRipserCohomologyEngine[field.Fp](space, 2, useEmergentPairs = emergent)
    val bars = engine.persistentCohomology(true)
    engine.totalSimplexCount == bars.size + bars.count(b => b.dim <= 1 && b.upper.isInstanceOf[OpenEndpoint[?]])

  "The bars account for every simplex of dimension at most 2, with and without the shortcut" >> {
    val failures = for
      seed <- seeds(jvm = 10L, js = 2L)
      space <- clouds(seed)
      emergent <- Seq(true, false)
      if !accountsForEverySimplex(space, emergent)
    yield (seed, emergent)
    failures.take(3) must beEmpty
  }
