package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** Cycles from a cohomology pairing ([[Involution]]). The oracle is validity, which holds whichever order the pairing
  * was computed under and whichever valid cycle is reported. For a finite bar `(σ, τ)` the cycle `z` is closed, its
  * youngest cell is `σ`, and `z = ∂c` with `c`'s youngest cell `τ`; for an essential bar `z` is closed with youngest
  * cell `σ`. The inputs are tie-heavy (equal filtration values everywhere), where an order mismatch between pairing and
  * reduction would show.
  */
class InvolutionSpec extends Specification:
  private val ff = FiniteField(5)
  import ff.given

  private def boundaryOf(z: Chain[Simplex[Int], ff.Fp])(using Ordering[Simplex[Int]]) =
    Chain.from(z.terms.flatMap((cell, c) => cell.boundary[ff.Fp].map((f, s) => (f, c * s))))

  /** Checks every pair's cycle against the validity conditions; returns the number of pairs checked. */
  private def checkAll[CellT](
    pairs: IndexedSeq[Involution.Pair[CellT]],
    youngestFirst: Ordering[CellT],
    boundary: (CellT, Int) => Seq[(CellT, ff.Fp)]
  ): Int =
    given Ordering[CellT] = youngestFirst
    def d(z: Chain[CellT, ff.Fp], dim: Int) =
      val out = Chain.from(z.terms.flatMap((cell, c) => boundary(cell, dim).map((f, s) => (f, c * s))))
      out.collapseAll()
      out
    val cycles = Involution.cycles[CellT, ff.Fp](pairs, youngestFirst, boundary)
    for (Involution.Pair(dim, sigma, death), (z, bounding)) <- pairs.zip(cycles) do
      require(d(z, dim).isZero(), s"not closed: $z")
      require(z.leadingCell.exists(youngestFirst.equiv(_, sigma)), s"youngest cell of $z is not the birth cell $sigma")
      death.foreach { tau =>
        val c = bounding.get
        require(c.leadingCell.exists(youngestFirst.equiv(_, tau)), s"$c does not end at the death cell $tau")
        require((d(c, dim + 1) - z).isZero(), s"$z is not the boundary of $c")
      }
    pairs.size

  private def valid(stream: StratifiedCellStream[Simplex[Int], Double]): Int =
    val (paired, olderFirst) = CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]().pairedCohomology(stream)
    checkAll(paired.map(_._2).toIndexedSeq, olderFirst.reverse, (cell, _) => cell.boundary[ff.Fp])

  private def validPacked(points: Array[Array[Double]], maxDimension: Int, threshold: Double): Int =
    val engine = PackedRipserCohomologyEngine[ff.Fp](
      EuclideanMetricSpace(points),
      maxDimension,
      maxFiltrationValue = Some(threshold)
    )
    checkAll(engine.pairedCohomology().map(_._2).toIndexedSeq, engine.packedOrdering.reverse, engine.boundaryOf)

  /** Checks every pair's cocycle: oldest cell `σ`; `δz` starts at the death cell, or is zero for an essential bar. */
  private def checkCocycles(
    stream: StratifiedCellStream[Simplex[Int], Double],
    pairs: IndexedSeq[Involution.Pair[Simplex[Int]]],
    olderFirst: Ordering[Simplex[Int]]
  ): IndexedSeq[Chain[Simplex[Int], ff.Fp]] =
    given Ordering[Simplex[Int]] = olderFirst
    val delta = Involution.coboundariesOf[Simplex[Int], ff.Fp](stream)
    val cocycles = Involution.cocycles[Simplex[Int], ff.Fp](pairs, olderFirst, delta)
    for (Involution.Pair(dim, sigma, death), z) <- pairs.zip(cocycles) do
      val dz = Chain.from(z.terms.flatMap((cell, c) => delta(cell, dim).map((f, s) => (f, c * s))))
      dz.collapseAll()
      require(z.leadingCell.exists(olderFirst.equiv(_, sigma)), s"oldest cell of $z is not the birth cell $sigma")
      death match
        case Some(tau) => require(dz.leadingCell.exists(olderFirst.equiv(_, tau)), s"δ$z does not start at $tau")
        case None      => require(dz.isZero(), s"$z is not a cocycle")
    cocycles

  "Involution" should {
    "give valid cycles on a triangulated torus with every cell at value 0" in {
      // The 7-vertex torus, all cells tied: the order is decided by the tie-break alone.
      val triangles = for i <- 0 until 7; (a, b) <- Seq((1, 3), (3, 2)) yield Simplex(i, (i + a) % 7, (i + b) % 7)
      val torus = ExplicitStreamBuilder.fromFacets(triangles)
      valid(torus) must beGreaterThan(20)
    }
    "give valid cycles on a Vietoris-Rips complex of integer grid points (many equal distances)" in {
      val grid = for i <- 0 until 4; j <- 0 until 3 yield Array(i.toDouble, j.toDouble)
      val vr = VietorisRips(EuclideanMetricSpace(grid.toArray), maxDimension = 2, maxFiltrationValue = 2.5)
      valid(vr) must beGreaterThan(100)
    }
    "give valid cycles from the packed Ripser pairing on integer grid points, in degrees 0 to 2" in {
      val grid = for i <- 0 until 4; j <- 0 until 3; k <- 0 until 2 yield Array(i.toDouble, j.toDouble, k.toDouble)
      validPacked(grid.toArray, 2, 2.0) must beGreaterThan(100)
    }
    "leave out the degree above a truncated stream's limit (its top cells are not essential classes)" in {
      val grid = for i <- 0 until 4; j <- 0 until 3 yield Array(i.toDouble, j.toDouble)
      val vr = VietorisRips(EuclideanMetricSpace(grid.toArray), maxDimension = 1, maxFiltrationValue = 2.5)
      val engine = CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]()
      (engine.persistentCohomology(vr).exists(_.dim == 2) must beTrue)
        .and(engine.persistentHomology(vr).exists(_.dim == 2) must beFalse)
    }
    "reproduce the cohomology engine's own cocycles term for term from its pairing (same columns, same order)" in {
      val grid = for i <- 0 until 4; j <- 0 until 3 yield Array(i.toDouble, j.toDouble)
      val vr = VietorisRips(EuclideanMetricSpace(grid.toArray), maxDimension = 1, maxFiltrationValue = 2.5)
      val (paired, olderFirst) = CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]().pairedCohomology(vr)
      val kept = paired.filter(_._2.dim <= 1)
      val cocycles = checkCocycles(vr, kept.map(_._2).toIndexedSeq, olderFirst)
      given Ordering[Simplex[Int]] = olderFirst
      kept.zip(cocycles).forall { case ((bar, _), z) => (bar.representative - z).isZero() } must beTrue
    }
    "give valid cocycles on the all-zero torus" in {
      val triangles = for i <- 0 until 7; (a, b) <- Seq((1, 3), (3, 2)) yield Simplex(i, (i + a) % 7, (i + b) % 7)
      val torus = ExplicitStreamBuilder.fromFacets(triangles)
      val (paired, olderFirst) = CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]().pairedCohomology(torus)
      checkCocycles(torus, paired.map(_._2).toIndexedSeq, olderFirst).size must beGreaterThan(20)
    }
    "give valid cocycles from the chunks and naive pairings, which agree with each other, on tie-heavy inputs" in {
      val grid = for i <- 0 until 4; j <- 0 until 3 yield Array(i.toDouble, j.toDouble)
      val vr = VietorisRips(EuclideanMetricSpace(grid.toArray), maxDimension = 1, maxFiltrationValue = 2.5)
      val triangles = for i <- 0 until 7; (a, b) <- Seq((1, 3), (3, 2)) yield Simplex(i, (i + a) % 7, (i + b) % 7)
      val torus = ExplicitStreamBuilder.fromFacets(triangles)
      val checks = for (stream, maxDim) <- Seq((vr, 1), (torus, 2)) yield
        val chunks = CellularPersistenceInChunksEngine[Simplex[Int], ff.Fp](maxDim).persistentHomology(stream).pairing
        val naive = CellularHomologyEngine[Simplex[Int], ff.Fp, Double]()
          .persistentHomology(stream)
          .pairing
          .filter(_.dim <= maxDim)
        val olderFirst = stream.filtrationOrdering.reverse
        checkCocycles(stream, chunks.filter(_.dim <= maxDim), olderFirst)
        checkCocycles(stream, naive, olderFirst)
        chunks.filter(_.dim <= maxDim).toSet == naive.toSet
      checks must contain(allOf(true))
    }
    "refuse a pairing computed under a different order than the reduction (the pivot check)" in {
      val grid = for i <- 0 until 3; j <- 0 until 3 yield Array(i.toDouble, j.toDouble)
      val vr = VietorisRips(EuclideanMetricSpace(grid.toArray), maxDimension = 1, maxFiltrationValue = 2.5)
      val (paired, olderFirst) = CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]().pairedCohomology(vr)
      Involution.cycles[Simplex[Int], ff.Fp](
        paired.map(_._2).toIndexedSeq,
        olderFirst, // oldest first: the wrong way round
        (cell, _) => cell.boundary[ff.Fp]
      ) must throwAn[IllegalStateException]
    }
    "agree with the chunks engine bar for bar, with closed representatives, through the engine's own method" in {
      val grid = for i <- 0 until 4; j <- 0 until 3 yield Array(i.toDouble, j.toDouble)
      val vr = VietorisRips(EuclideanMetricSpace(grid.toArray), maxDimension = 1, maxFiltrationValue = 2.5)
      val cycles = CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]().persistentHomology(vr)
      val chunks = CellularPersistenceInChunksEngine[Simplex[Int], ff.Fp](1)
        .persistentHomology(vr)
        .barcodeAt(Double.PositiveInfinity)
      given Ordering[Simplex[Int]] = vr.filtrationOrdering
      (cycles.filter(_.dim <= 1).map(_.toTriple).sorted must beEqualTo(chunks.map(_.toTriple).sorted))
        .and(cycles.forall(b => boundaryOf(b.representative).isZero()) must beTrue)
    }
  }
