package org.appliedtopology.tda4j

import org.specs2.mutable

/** `PackedRipserCohomologyEngine` reduces with a heap of primitive entries (Ripser's working column) instead of
  * `Chain.reduceBy`'s `TreeMap`. It must be the same reduction: same pivots, same pairs, and the same cocycles term for
  * term, in the same list order, as the `Chain`-based engine kept in the test tree (`ChainPackedRipserReference`). The
  * cycles derived by the involution then agree as well. Over F_3 and F_17 (exact, signed; a sign slip shows), with and
  * without apparent pairs, on generic clouds and on integer grids where diameters tie everywhere.
  */
class PackedWorkingColumnSpec extends mutable.Specification:

  /** The chain's terms, equal cells combined and zeros dropped, by index. */
  def entries[C](chain: Chain[?, C], cell: Any => (Double, Long))(using fr: C is Field): List[(Double, Long, String)] =
    chain.rawEntries
      .groupMapReduce(e => cell(e._1))(_._2)(fr.plus)
      .toList
      .collect { case ((d, i), k) if !fr.isEqual(k, fr.zero) => (d, i, k.toString) }
      .sortBy(_._2)

  def clouds(seed: Long): Seq[EuclideanMetricSpace] =
    val rng = new scala.util.Random(seed)
    val n = 8 + rng.nextInt(18)
    val dim = 2 + rng.nextInt(3)
    Seq(
      EuclideanMetricSpace(Array.fill(n)(Array.fill(dim)(rng.nextDouble()))),
      EuclideanMetricSpace(Array.fill(n)(Array.fill(dim)(rng.nextInt(3).toDouble)))
    )

  def same(field: FiniteField)(
    space: FiniteMetricSpace[Int],
    apparent: Boolean,
    cycles: Boolean,
    threshold: Option[Double] = None,
    referenceSpace: Option[FiniteMetricSpace[Int]] = None
  ): Boolean =
    import field.given
    val heap = PackedRipserCohomologyEngine[field.Fp](space, 2, apparent, threshold)
    val chain = ChainPackedRipserReference[field.Fp](referenceSpace.getOrElse(space), 2, apparent, threshold)
    def heapCell(c: Any) = c match
      case x: heap.DiameterIndex => (x.diameter, x.index)
      case _                     => (Double.NaN, -1L)
    def chainCell(c: Any) = c match
      case x: chain.DiameterIndex => (x.diameter, x.index)
      case _                      => (Double.NaN, -1L)
    def flat[Cell](bars: List[PersistenceBar[Double, Chain[Cell, field.Fp]]], cell: Any => (Double, Long)) =
      bars.map(b => (b.dim, b.lower.toString, b.upper.toString, entries(b.representative, cell)))
    if cycles then
      flat(heap.persistentHomology(true), heapCell) == flat(chain.persistentHomology(true), chainCell) &&
      flat(heap.persistentHomology(), heapCell) == flat(chain.persistentHomology(), chainCell)
    else
      flat(heap.pairedCohomology().map(_._1), heapCell) == flat(chain.pairedCohomology().map(_._1), chainCell) &&
      flat(heap.persistentCohomology(), heapCell) == flat(chain.persistentCohomology(), chainCell)

  "The heap working column gives the Chain reduction's pairs and cocycles, term for term" >> {
    val failures = for
      seed <- 0L until 15L
      space <- clouds(seed)
      field <- Seq(FiniteField(3), FiniteField(17))
      apparent <- Seq(true, false)
      if !same(field)(space, apparent, cycles = false)
    yield (seed, field.p, apparent)
    failures must beEmpty
  }

  "... and the same cycles through the involution" >> {
    val failures = for
      seed <- 0L until 8L
      space <- clouds(seed)
      if !same(FiniteField(3))(space, apparent = true, cycles = true)
    yield seed
    failures must beEmpty
  }

  "... including essential cycles in degrees 1 and 2, below the enclosing radius" >> {
    // A low threshold leaves classes unkilled: their cycles are the only ones built from V-columns.
    val runs = for
      seed <- 0L until 8L
      space <- clouds(seed)
      threshold <- Seq(0.3, 0.6)
    yield
      val f3 = FiniteField(3)
      import f3.given
      val essentials = PackedRipserCohomologyEngine[f3.Fp](space, 2, maxFiltrationValue = Some(threshold))
        .persistentCohomology()
        .count(b => b.dim >= 1 && b.upper.isInstanceOf[PositiveInfinity[?]])
      (seed, threshold, essentials, same(FiniteField(3))(space, true, cycles = true, Some(threshold)))
    (runs.filterNot(_._4) must beEmpty) and (runs.map(_._3).sum[Int] must beGreaterThan(5))
  }

  "A distance that is not exactly symmetric is read symmetrically: same result as the symmetrized space" >> {
    // d(i, j) and d(j, i) differ in the last bits; read in both orders, one simplex would get two diameters, which the
    // heap working column never combines.
    def skewed(base: FiniteMetricSpace[Int]): FiniteMetricSpace[Int] = new FiniteMetricSpace[Int]:
      def distance(x: Int, y: Int): Double = base.distance(x, y) * (if x > y then 1 + 1e-12 else 1.0)
      def size: Int = base.size
      def elements: Iterable[Int] = base.elements
      def contains(x: Int): Boolean = base.contains(x)
    def symmetrized(s: FiniteMetricSpace[Int]): FiniteMetricSpace[Int] = new FiniteMetricSpace[Int]:
      def distance(x: Int, y: Int): Double = s.distance(math.min(x, y), math.max(x, y))
      def size: Int = s.size
      def elements: Iterable[Int] = s.elements
      def contains(x: Int): Boolean = s.contains(x)
    val failures = for
      seed <- 0L until 6L
      space <- clouds(seed)
      asym = skewed(space)
      threshold = Some(symmetrized(asym).minimumEnclosingRadius)
      cycles <- Seq(false, true)
      if !same(FiniteField(3))(asym, apparent = true, cycles, threshold, Some(symmetrized(asym)))
    yield (seed, cycles)
    failures must beEmpty
  }
