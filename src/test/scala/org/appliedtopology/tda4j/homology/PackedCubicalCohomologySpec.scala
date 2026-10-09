package org.appliedtopology.tda4j

import org.specs2.mutable

/** The packed grid cohomology engine must give exactly what `CellularCohomologyEngine` gives on the same cells: the
  * same bars in the same order, the same cocycles (`persistentCohomology`) and the same cycles (`persistentHomology`,
  * essential ones included), term for term. On whole grids (`Engine.Cohomology` on an image) and on a grid's cells up
  * to a dimension (`LimitedCubicalGridStream`: the fast engine's middle degrees). Inputs: 1-D to 4-D grids, degenerate
  * shapes, tied values (zero-length pairs everywhere), +Infinity pixels, both signed zeros, and noise; F_2, F_3, F_17;
  * zero-length bars on and off; apparent pairs on and off (the shortcut must change nothing).
  */
class PackedCubicalCohomologySpec extends mutable.Specification:
  val fields: Seq[FiniteField] = Seq(FiniteField(2), FiniteField(3), FiniteField(17))

  def grids(seed: Long): Seq[CubicalGridStream] =
    val rng = new scala.util.Random(seed)
    def tied(): Double = rng.nextInt(10) match
      case 0 => Double.PositiveInfinity
      case 1 => -0.0
      case 2 => 0.0
      case k => (k % 4).toDouble
    def image(shape: IndexedSeq[Int], value: () => Double) =
      CubicalImage.fromFlatArray(shape, IndexedSeq.fill(shape.product)(value()))
    Seq(
      IndexedSeq(1),
      IndexedSeq(9),
      IndexedSeq(1, 1),
      IndexedSeq(1, 6),
      IndexedSeq(5, 1),
      IndexedSeq(4, 5),
      IndexedSeq(7, 6),
      IndexedSeq(3, 4, 3),
      IndexedSeq(1, 3, 4),
      IndexedSeq(2, 2, 2, 2)
    ).map(image(_, tied)) ++ Seq(IndexedSeq(6, 5), IndexedSeq(3, 3, 4)).map(image(_, () => rng.nextDouble()))

  // An essential class has no death cell; a pair whose death cell has value +Infinity (a missing pixel) also ends at
  // +Infinity, but its V-column is not a cocycle of the whole complex.
  def isEssential(bar: PersistenceBar[Double, ?]): Boolean = bar.upper match
    case PositiveInfinity() => true
    case _                  => false

  /** Whether the packed engine on `grid`'s cells up to `topDim` equals the generic engine on the same cells. */
  def same(ff: FiniteField, grid: CubicalGridStream, topDim: Int, zeroLength: Boolean, apparent: Boolean)(
    cocycles: Boolean
  ): Boolean =
    import ff.given
    val packed =
      new PackedCubicalCohomologyEngine[ff.Fp](grid, topDim, GridRanks(grid.shape, grid.topCellValues), apparent)
    val cells: StratifiedCellStream[Cube, Double] =
      if topDim == grid.ambientDim then grid else LimitedCubicalGridStream(grid, topDim)
    val generic = CellularCohomologyEngine[Cube, ff.Fp, Double]()
    if cocycles then packed.persistentCohomology(zeroLength) == generic.persistentCohomology(cells, zeroLength)
    else packed.persistentHomology(zeroLength) == generic.persistentHomology(cells, zeroLength)

  def failures(topDims: CubicalGridStream => Seq[Int]): Seq[String] =
    for
      seed <- 0L until 3L
      grid <- grids(seed)
      topDim <- topDims(grid)
      ff <- fields
      zeroLength <- Seq(false, true)
      apparent <- Seq(true, false)
      cocycles <- Seq(true, false)
      if !same(ff, grid, topDim, zeroLength, apparent)(cocycles)
    yield s"seed $seed shape ${grid.shape.mkString("x")} topDim $topDim F_${ff.p} zeroLength=$zeroLength " +
      s"apparent=$apparent ${if cocycles then "cocycles" else "cycles"}"

  "equals the generic cohomology engine on whole grids, cocycles and cycles" >> {
    failures(grid => Seq(grid.ambientDim)).take(5) must beEmpty
  }

  "equals it on a grid's cells up to each lower dimension, the fast engine's middle degrees among them" >> {
    failures(grid => 1 until grid.ambientDim).take(5) must beEmpty
  }

  // Characteristic 0 (`Engine.Cohomology` or `Representatives.Cocycles` on an image with `characteristic = 0`, MATLAB's
  // `field=R`) reaches the packed engine with `Double` coefficients, where zeros are tested approximately: the same
  // bars as the generic engine, and representatives that close. (`Chain ==` compares coefficients exactly, so the
  // representatives are checked for what they must be rather than term for term.)
  "agrees with the generic engine over the reals: the same bars, closed cycles and cocycles" >> {
    given Double is Field = Field.DoubleApproximated(1e-9)
    given Ordering[Cube] = cubeOrdering
    def endpoints(bars: List[PersistenceBar[Double, Chain[Cube, Double]]]) =
      bars.map(b => (b.dim, b.lower, b.upper))
    val problems = for
      seed <- 0L until 2L
      grid <- grids(seed)
      topDim <- (1 until grid.ambientDim) :+ grid.ambientDim
      zeroLength <- Seq(false, true)
      problem <-
        val packed = new PackedCubicalCohomologyEngine[Double](grid, topDim, GridRanks(grid.shape, grid.topCellValues))
        val cells: StratifiedCellStream[Cube, Double] =
          if topDim == grid.ambientDim then grid else LimitedCubicalGridStream(grid, topDim)
        val generic = CellularCohomologyEngine[Cube, Double, Double]()
        val cycles = packed.persistentHomology(zeroLength)
        val cocycles = packed.persistentCohomology(zeroLength)
        val cubesByDim = cells.iterator.toSeq.groupBy(_.dim)
        Seq(
          Option.when(endpoints(cycles) != endpoints(generic.persistentHomology(cells, zeroLength)))("cycle bars"),
          Option.when(endpoints(cocycles) != endpoints(generic.persistentCohomology(cells, zeroLength)))(
            "cocycle bars"
          ),
          Option.when(!cycles.filter(_.dim > 0).forall(b => Chain.from(b.representative.boundary).isZero()))(
            "a cycle that does not close"
          ),
          Option.when(!cocycles.filter(isEssential).forall { b =>
            generic.coboundaryOfChain(b.representative, cubesByDim.getOrElse(b.dim + 1, Nil)).isZero()
          })("an essential cocycle that does not close")
        ).flatten.map(p => s"$p: seed $seed shape ${grid.shape.mkString("x")} topDim $topDim zeroLength=$zeroLength")
    yield problem
    problems.take(5) must beEmpty
  }

  // Equality cannot catch a bug the reference shares; closedness over a signed field can.
  "reports cycles that are closed, and cocycles that are closed on a whole grid, over F_3" >> {
    val f3 = FiniteField(3)
    import f3.given
    given Ordering[Cube] = cubeOrdering
    val rng = new scala.util.Random(7)
    val volume = CubicalImage.fromFlatArray(IndexedSeq(7, 8, 6), IndexedSeq.fill(7 * 8 * 6)(rng.nextInt(5).toDouble))
    val engine = PackedCubicalCohomologyEngine[f3.Fp](volume, volume.ambientDim)
    val cycles = engine.persistentHomology().filter(_.dim > 0)
    val cubesByDim = volume.iterator.toSeq.groupBy(_.dim)
    val cocycles = engine.persistentCohomology().filter(isEssential)
    val generic = CellularCohomologyEngine[Cube, f3.Fp, Double]()
    (cycles must not(beEmpty)) and
      (cycles.forall(bar => Chain.from(bar.representative.boundary).isZero()) must beTrue) and
      (cocycles.forall(bar =>
        generic.coboundaryOfChain(bar.representative, cubesByDim.getOrElse(bar.dim + 1, Nil)).isZero()
      ) must beTrue)
  }
