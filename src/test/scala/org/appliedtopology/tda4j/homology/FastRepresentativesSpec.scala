package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.SpecThrows.throwWith
import org.specs2.mutable

/** The fast engines' representatives come from a signed union-find (`SignedUnionFind`) instead of a coefficient map per
  * component. The outputs must not change at all: every bar, its endpoints, its representative term for term, and the
  * list order must equal what the eager implementations kept in the test tree (`EagerFastCubicalReference`,
  * `EagerFastAlphaReference`) return. Inputs cover:
  *   - ties everywhere (small integer values);
  *   - a bright blob on a dark background, the case that made the eager version quadratic;
  *   - pixels at `+Infinity`, which tie against `∞`, and masked (NaN) pixels, which are not in the complex;
  *   - 3-D grids (the chunks hybrid plus the dual union-find);
  *   - signed fields F_3 and F_17 as well as F_2, and zero-length bars on and off;
  *   - random Helix triangulations in 2-D and 3-D.
  */
class FastRepresentativesSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  val f2 = FiniteField(2)
  val f3 = FiniteField(3)
  val f17 = FiniteField(17)

  def images(seed: Long): Seq[CubicalGridStream] =
    val rng = new scala.util.Random(seed)
    def grid(shape: IndexedSeq[Int])(value: IndexedSeq[Int] => Double) = CubicalGridStream(shape, value)
    val (n, m) = (5 + rng.nextInt(8), 5 + rng.nextInt(8))
    val ties = IndexedSeq.fill(n * m)(rng.nextInt(4).toDouble)
    val noise = IndexedSeq.fill(n * m)(rng.nextDouble())
    val blob = IndexedSeq.tabulate(n * m) { k =>
      val (i, j) = (k / m, k % m)
      val r2 = math.pow(i - n / 2.0, 2) + math.pow(j - m / 2.0, 2)
      math.exp(-r2 / 8) + 0.1 * noise(k)
    }
    val missing = ties.map(v => if rng.nextInt(7) == 0 then Double.PositiveInfinity else v)
    val ties3d = IndexedSeq.fill(4 * 5 * 4)(rng.nextInt(3).toDouble)
    // Masks: scattered NaN pixels among ties (lone holes, masked runs, masked border pixels, +Infinity next to them),
    // and a masked 3-D grid.
    val masked = ties.map(v =>
      rng.nextInt(6) match
        case 0 => Double.NaN
        case 1 => Double.PositiveInfinity
        case _ => v
    )
    val masked3d = ties3d.map(v => if rng.nextInt(5) == 0 then Double.NaN else v)
    Seq(
      grid(IndexedSeq(n, m))(idx => ties(idx(0) * m + idx(1))),
      grid(IndexedSeq(n, m))(idx => noise(idx(0) * m + idx(1))),
      grid(IndexedSeq(n, m))(idx => blob(idx(0) * m + idx(1))),
      grid(IndexedSeq(n, m))(idx => missing(idx(0) * m + idx(1))),
      grid(IndexedSeq(4, 5, 4))(idx => ties3d(idx(0) * 20 + idx(1) * 4 + idx(2))),
      grid(IndexedSeq(n, m))(idx => masked(idx(0) * m + idx(1))),
      grid(IndexedSeq(4, 5, 4))(idx => masked3d(idx(0) * 20 + idx(1) * 4 + idx(2)))
    )

  def sameCubical(field: FiniteField)(stream: CubicalGridStream, zeroLength: Boolean): Boolean =
    import field.given
    FastCubicalHomologyEngine[field.Fp]().persistentHomology(stream, zeroLength) ==
      EagerFastCubicalReference[field.Fp]().persistentHomology(stream, zeroLength)

  /** Whether a chain's terms come in its own order, strictly ascending: what a packed chain must store. */
  def ascending[C](c: Chain[Cube, C]): Boolean =
    c.terms.map(_._1).sliding(2).forall(w => w.size < 2 || c.cellOrdering.lt(w(0), w(1)))

  // The representatives are stored packed (cells as grid indices, decoded when read), in their own order. Equality with
  // the reference is order-blind (formal sums) and would also pass on heap chains, so both are checked here.
  "The fast cubical engine stores every representative packed, in its order, 3-D middle degrees included" >> {
    import f3.given
    val unpacked = for
      seed <- 0L until 3L
      stream <- images(seed)
      zeroLength <- Seq(false, true)
      bar <- FastCubicalHomologyEngine[f3.Fp]().persistentHomology(stream, zeroLength)
      if !bar.representative.isPacked || !ascending(bar.representative)
    yield s"${stream.shape.mkString("x")} $bar"
    val verb = Persistence(Image(IndexedSeq.tabulate(30)(k => (k * 7 % 11).toDouble), IndexedSeq(5, 6)))
    (unpacked.take(3) must beEmpty) and (verb.bars.forall(_.representative.isPacked) must beTrue)
  }

  "The fast cubical engine's bars and representatives equal the eager reference's" >> {
    val checks = for
      seed <- 0L until 12L
      stream <- images(seed)
      field <- Seq(f2, f3, f17)
      zeroLength <- Seq(false, true)
    yield sameCubical(field)(stream, zeroLength)
    checks.forall(identity) must beTrue
  }

  // The flat-array engine walks the grid by index arithmetic: degenerate shapes (an axis of size 1, a single pixel),
  // non-cubic 3-D grids and a 4-D grid, with ties, +Infinity pixels, and both signed zeros (ordered -0.0 first, but
  // equal under the elder rule's comparisons).
  def oddShapes(seed: Long): Seq[CubicalGridStream] =
    val rng = new scala.util.Random(seed)
    val shapes = Seq(
      IndexedSeq(1, 1),
      IndexedSeq(1, 7),
      IndexedSeq(7, 1),
      IndexedSeq(2, 2),
      IndexedSeq(2, 9),
      IndexedSeq(1, 4, 3),
      IndexedSeq(3, 1, 4),
      IndexedSeq(2, 3, 4),
      IndexedSeq(2, 2, 2, 2)
    )
    def value(): Double = rng.nextInt(9) match
      case 0 => Double.PositiveInfinity
      case 1 => -0.0
      case 2 => 0.0
      case k => (k % 3).toDouble
    // Only signed zeros: the dual's elder rule then meets roots at -0.0 and 0.0 that it must treat as equal.
    def zero(): Double = if rng.nextBoolean() then -0.0 else 0.0
    shapes.map { shape =>
      CubicalImage.fromFlatArray(shape, IndexedSeq.fill(shape.product)(value()))
    } ++ Seq(IndexedSeq(4, 5), IndexedSeq(6, 6), IndexedSeq(3, 4, 3)).map { shape =>
      CubicalImage.fromFlatArray(shape, IndexedSeq.fill(shape.product)(zero()))
    } ++ shapes.map { shape =>
      // Masked (NaN) pixels among the same values: on degenerate shapes, whole rows and the single pixel get masked.
      CubicalImage.fromFlatArray(
        shape,
        IndexedSeq.fill(shape.product)(if rng.nextInt(3) == 0 then Double.NaN else value())
      )
    }

  // A 0.0 pixel whose first dual merge is with a lone -0.0 pixel to its right, everything else at -1.0: the elder rule
  // must see equal births (-0.0 == 0.0) and let the left side die, as the reference does; ordering the two by rank
  // (-0.0 first) would let the right side die instead. Random signed zeros rarely reach this merge before `∞` does.
  val signedZeroMerge: CubicalGridStream =
    CubicalImage.fromFlatArray(
      IndexedSeq(5, 5),
      IndexedSeq.tabulate(25)(k => if k == 12 then 0.0 else if k == 13 then -0.0 else -1.0)
    )

  "The fast cubical engine equals the eager reference on degenerate shapes, 3-D and 4-D grids, and signed zeros" >> {
    val checks = for
      stream <- (0L until 6L).flatMap(oddShapes) :+ signedZeroMerge
      field <- Seq(f2, f3, f17)
      zeroLength <- Seq(false, true)
    yield (stream.shape, sameCubical(field)(stream, zeroLength))
    checks.filterNot(_._2).map(_._1) must beEmpty
  }

  // Over the reals (`characteristic = 0`) the hybrid's middle degrees run the packed grid engine with `Double`
  // coefficients: the same bars as the eager reference, and every representative a cycle (checked approximately, as
  // the field does; `Chain ==` would compare coefficients exactly).
  "The fast cubical engine over the reals: the reference's bars, closed representatives" >> {
    given Double is Field = Field.DoubleApproximated(1e-9)
    given Ordering[Cube] = cubeOrdering
    val problems = for
      seed <- 0L until 4L
      stream <- images(seed) ++ oddShapes(seed).filter(_.ambientDim >= 3)
      zeroLength <- Seq(false, true)
      problem <-
        val fast = FastCubicalHomologyEngine[Double]().persistentHomology(stream, zeroLength)
        val eager = EagerFastCubicalReference[Double]().persistentHomology(stream, zeroLength)
        Seq(
          Option.when(fast.map(b => (b.dim, b.lower, b.upper)) != eager.map(b => (b.dim, b.lower, b.upper)))("bars"),
          Option.when(!fast.filter(_.dim > 0).forall(b => Chain.from(b.representative.boundary).isZero()))(
            "a representative that does not close"
          )
        ).flatten.map(p => s"$p: shape ${stream.shape.mkString("x")} zeroLength=$zeroLength")
    yield problem
    problems.take(5) must beEmpty
  }

  // A NaN pixel is not in the complex, in either direction (NaN has no sign), where an image is built from an array and
  // where a grid built from a function first reads its values. The wrong answers this rules out: a NaN negated with a
  // superlevel image (then it would enter first and fill the hole), carried to its faces by `min` (faces entering after
  // their cofaces), or treated as +Infinity (then the masked cells would enter at +Infinity: bars born there, and a
  // complex that is the whole grid).
  "A NaN pixel is not in the complex, sublevel or superlevel, under every engine" >> {
    val inf = Double.PositiveInfinity
    // A 4x5 image with two masked pixels: one inside a ring of 1s (a loop that never dies) and one in a corner.
    def image(mask: Double): IndexedSeq[Double] = IndexedSeq(
      IndexedSeq(1.0, 1.0, 1.0, 0.5, mask),
      IndexedSeq(1.0, mask, 1.0, 0.2, 0.3),
      IndexedSeq(1.0, 1.0, 1.0, 0.7, 0.1),
      IndexedSeq(0.4, 0.6, 0.8, 0.9, 0.0)
    ).flatten
    val shape = IndexedSeq(4, 5)
    val engines = Seq(
      Persistence.Engine.FastCubical,
      Persistence.Engine.Cohomology,
      Persistence.Engine.Chunks,
      Persistence.Engine.Naive
    )
    def triples(
      values: IndexedSeq[Double],
      sublevel: Boolean,
      engine: Persistence.Engine,
      zeroLength: Boolean = false
    ) =
      Persistence(
        Image(values, shape, sublevel),
        engine = engine,
        characteristic = 3,
        includeZeroLength = zeroLength
      ).triples.sorted
    def everyEngine(sublevel: Boolean, zeroLength: Boolean = false) =
      engines.map(triples(image(Double.NaN), sublevel, _, zeroLength)).distinct
    val masked = CubicalImage.fromFlatArray(shape, image(Double.NaN))
    val fromFunction = CubicalGridStream(shape, index => image(Double.NaN)(index(0) * 5 + index(1)))
    val allMasked = Image(IndexedSeq.fill(6)(Double.NaN), IndexedSeq(2, 3))
    // The 9x11 doubled grid has 99 cells; the inner masked pixel takes only itself away, the corner one itself, its two
    // outer edges and the corner vertex.
    def cells(stream: CubicalGridStream) = (0 to 2).map(stream.iterateDimension(_).size).sum
    (everyEngine(sublevel = true).size must beEqualTo(1)) and
      (everyEngine(sublevel = false).size must beEqualTo(1)) and
      (everyEngine(sublevel = true, zeroLength = true).size must beEqualTo(1)) and
      (everyEngine(sublevel = true).head.filter(t => t._1 == 1 && t._3 == inf) must beEqualTo(List((1, 1.0, inf)))) and
      (everyEngine(sublevel = false).head.filter(t => t._1 == 1 && t._3 == inf) must beEqualTo(
        List((1, -1.0, inf))
      )) and
      // Nothing is born at +Infinity, even with zero-length bars: a masked cell never enters. A +Infinity pixel does.
      (everyEngine(sublevel = true, zeroLength = true).head.exists(_._2 == inf) must beFalse) and
      (triples(image(inf), sublevel = true, Persistence.Engine.FastCubical, zeroLength = true).exists(_._2 == inf) must
        beTrue) and
      // At every finite value a masked pixel and a +Infinity one look the same.
      (triples(image(Double.NaN), sublevel = true, Persistence.Engine.FastCubical).filter(_._3 != inf) must beEqualTo(
        triples(image(inf), sublevel = true, Persistence.Engine.FastCubical).filter(_._3 != inf)
      )) and
      (cells(masked) must beEqualTo(94)) and (cells(fromFunction) must beEqualTo(94)) and
      (HomologyFixtures.respectsOrderingContract(masked) must beTrue) and
      (masked.topValues.count(_.isNaN) must beEqualTo(2)) and
      (Persistence(fromFunction, engine = Persistence.Engine.FastCubical).triples.sorted must beEqualTo(
        triples(image(Double.NaN), sublevel = true, Persistence.Engine.FastCubical)
      )) and
      (engines.map(e => Persistence(allMasked, engine = e).bars.size) must beEqualTo(engines.map(_ => 0)))
  }

  // Masks off (`nanMask = false`): a NaN pixel is an error naming the pixel and saying what to do, under every engine,
  // built from an array (refused at construction) or from a function (refused when an engine first reads the values);
  // without a NaN, nothing changes.
  "With nanMask = false a NaN pixel is an error that names it, under every engine" >> {
    import f3.given
    val shape = IndexedSeq(2, 3)
    val values = IndexedSeq(0.0, 1.0, Double.NaN, 2.0, 3.0, 4.0)
    val clean = values.updated(2, 5.0)
    val message = "NaN value at pixel \\(0, 2\\), and NaN masks are off.*nanMask.*masked, left out of the complex"
    val engines = Seq(
      Persistence.Engine.FastCubical,
      Persistence.Engine.Cohomology,
      Persistence.Engine.Chunks,
      Persistence.Engine.Naive
    )
    def fromFunction(vs: IndexedSeq[Double]) =
      CubicalGridStream(shape, index => vs(index(0) * 3 + index(1)), nanMask = false)
    (CubicalImage.fromFlatArray(shape, values, nanMask = false) must throwWith[IllegalArgumentException](message)) and
      (CubicalImage.fromFlatArray(shape, values, sublevel = false, nanMask = false) must
        throwWith[IllegalArgumentException](message)) and
      (CubicalImage.fromGrayscale2D(Array(values.take(3).toArray, values.drop(3).toArray), nanMask = false) must
        throwWith[IllegalArgumentException](message)) and
      (engines.map(e => scala.util.Try(Persistence(Image(values, shape, nanMask = false), engine = e)).isFailure) must
        beEqualTo(engines.map(_ => true))) and
      (Persistence(Image(values, shape, nanMask = false)) must throwWith[IllegalArgumentException](message)) and
      (FastCubicalHomologyEngine[f3.Fp]().persistentHomology(fromFunction(values)) must
        throwWith[IllegalArgumentException](message)) and
      (PackedCubicalCohomologyEngine[f3.Fp](fromFunction(values), 2).persistentHomology() must
        throwWith[IllegalArgumentException](message)) and
      (Persistence(fromFunction(values), engine = Persistence.Engine.Naive) must
        throwWith[IllegalArgumentException](message)) and
      (engines.map(e => Persistence(Image(clean, shape, nanMask = false), engine = e).triples) must beEqualTo(
        engines.map(e => Persistence(Image(clean, shape), engine = e).triples)
      )) and
      (Persistence(fromFunction(clean)).triples must beEqualTo(Persistence(Image(clean, shape)).triples))
  }

  /** `Some(equal)` when both engines produced bars, `None` when both rejected the triangulation. */
  def sameAlpha(field: FiniteField)(helix: HelixDelaunay, zeroLength: Boolean): Option[Boolean] =
    import field.given
    val fast = scala.util.Try(FastAlphaHomologyEngine[field.Fp]().persistentHomology(helix, zeroLength))
    val eager = scala.util.Try(EagerFastAlphaReference[field.Fp]().persistentHomology(helix, zeroLength))
    (fast.toOption, eager.toOption) match
      case (Some(a), Some(b)) => Some(a == b)
      case (None, None)       => None // both reject the same triangulation
      case _                  => Some(false)

  "The fast alpha engine's bars and representatives equal the eager reference's" >> {
    val checks = for
      seed <- 0L until 10L
      dim <- Seq(2, 3)
      field <- Seq(f3, f17)
      zeroLength <- Seq(false, true)
    yield
      val rng = new scala.util.Random(seed * 31 + dim)
      val pts = Array.fill(8 + rng.nextInt(10))(Array.fill(dim)(rng.nextDouble()))
      sameAlpha(field)(HelixDelaunay(pts), zeroLength)
    (checks.flatten.forall(identity) must beTrue) and (checks.flatten.size must beGreaterThan(checks.size / 2))
  }

  "Every top-degree representative is a cycle over F_3" >> {
    // Equality with the reference cannot catch a bug the reference shares; closedness over a signed field can.
    import f3.given
    given Ordering[Cube] = cubeOrdering
    val checks = for
      seed <- 0L until 6L
      stream <- images(seed)
      bar <- FastCubicalHomologyEngine[f3.Fp]().persistentHomology(stream)
      if bar.dim == stream.ambientDim - 1
    yield Chain.from(bar.representative.boundary).isZero()
    checks.forall(identity) must beTrue
  }

  // ---------------------------------------------------------------------------------------------------------
  // Validity, independent of the reference: a top-degree representative must be a NON-ZERO cycle, alive at the bar's
  // birth (every cell present by then, one entering exactly then), on images whose dual merges mostly happen away
  // from `∞` (a blob). Equality with the reference cannot catch a bug the reference shares: both once returned the
  // zero chain whenever neither merging dual component was `∞`'s (the flip was read from the facet's own boundary,
  // where no top cell occurs, so it was always zero).
  // ---------------------------------------------------------------------------------------------------------
  def validTopRepresentatives(field: FiniteField)(stream: CubicalGridStream): Seq[String] =
    import field.given
    given Ordering[Cube] = cubeOrdering
    val top = stream.ambientDim - 1
    FastCubicalHomologyEngine[field.Fp]().persistentHomology(stream).filter(_.dim == top).flatMap { bar =>
      val birth = bar.lower match
        case ClosedEndpoint(v) => v
        case other             => Double.NaN
      val rep = bar.representative
      val cells = rep.cells
      val problems = Seq(
        Option.when(rep.isZero())("zero representative"),
        Option.when(!Chain.from(rep.boundary).isZero())("not a cycle"),
        Option.when(cells.exists(c => stream.filtrationValue(c) > birth))("a cell enters after the birth"),
        Option.when(cells.nonEmpty && !cells.exists(c => stream.filtrationValue(c) == birth))("no cell enters at birth")
      ).flatten
      problems.map(p => s"$p: $bar")
    }

  "Every top-degree representative is a non-zero cycle born with its bar" >> {
    val problems = for
      seed <- 0L until 8L
      stream <- images(seed)
      field <- Seq(f3, f17)
      problem <- validTopRepresentatives(field)(stream)
    yield problem
    problems.take(5) must beEmpty
  }

  "Every top-degree alpha representative is a non-zero cycle born with its bar" >> {
    import f3.given
    given Ordering[Simplex[Int]] = simplexOrdering[Int]
    val problems = for
      seed <- 0L until 10L
      dim <- Seq(2, 3)
      rng = new scala.util.Random(seed * 17 + dim)
      helix = HelixDelaunay(Array.fill(12 + rng.nextInt(12))(Array.fill(dim)(rng.nextDouble())))
      bar <- scala.util.Try(FastAlphaHomologyEngine[f3.Fp]().persistentHomology(helix)).getOrElse(Nil)
      if bar.dim == dim - 1
      birth = bar.lower match
        case ClosedEndpoint(v) => v
        case _                 => Double.NaN
      rep = bar.representative
      problem <- Seq(
        Option.when(rep.isZero())("zero representative"),
        Option.when(!Chain.from(rep.boundary).isZero())("not a cycle"),
        Option.when(rep.cells.exists(c => helix.filtrationValue(c) > birth))("a cell enters after the birth")
      ).flatten
    yield s"$problem: $bar"
    problems.take(5) must beEmpty
  }
