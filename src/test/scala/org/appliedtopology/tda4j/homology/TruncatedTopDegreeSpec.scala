package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** A stream built for degrees `0..k` (`VietorisRips(.., maxDimension = k)`, `Truncated`, `LimitedCofaceSimplexStream`)
  * holds cells of degree `k + 1` only to close classes of degree `k`; the ones that close nothing are not homology.
  * Every way to a barcode of such a stream, with cycles or cocycles as representatives, must report no bar above `k`
  * and the same bars up to `k` as the same engine gives on the longer stream, so that Betti numbers and counts of
  * representatives are not the only witness. The ways are the engines behind `PersistenceEngine` (what the MATLAB
  * facade and the command line reach), the engines themselves, and `Persistence` with each engine.
  */
class TruncatedTopDegreeSpec extends Specification:
  private val ff = FiniteField(17)
  import ff.given

  private type Cell = Simplex[Int]
  private type Stream = StratifiedCellStream[Cell, Double]
  private type Bars = List[PersistenceBar[Double, Chain[Cell, ff.Fp]]]

  private def cloud(n: Int, dim: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  // Integer coordinates: many equal distances, so the tie-break decides which cells close nothing.
  private val grid =
    (for i <- 0 until 3; j <- 0 until 3; k <- 0 until 2 yield Array(i.toDouble, j.toDouble, k.toDouble)).toArray

  private val limit = 1
  private val spaces =
    Seq("a random cloud" -> EuclideanMetricSpace(cloud(13, 3, 7L)), "a grid" -> EuclideanMetricSpace(grid))

  private def truncated(s: FiniteMetricSpace[Int]): Stream = VietorisRips(s, maxDimension = limit)
  private def longer(s: FiniteMetricSpace[Int]): Stream = VietorisRips(s, maxDimension = limit + 1)

  private def triples(bars: Bars): List[(Int, Double, Double)] = bars.map(_.toTriple).sorted

  // The ways, each from a stream and the number of degrees to report (the engines that take a degree get it).
  private val ways: Seq[(String, (Stream, Int, Boolean) => Bars)] = Seq(
    "PersistenceEngine.naive" -> ((s, _, z) => PersistenceEngine.naive[Cell, ff.Fp].barcode(s, z)),
    "PersistenceEngine.naiveCocycles" -> ((s, _, z) => PersistenceEngine.naiveCocycles[Cell, ff.Fp].barcode(s, z)),
    "PersistenceEngine.chunks" -> ((s, k, z) => PersistenceEngine.chunks[Cell, ff.Fp](k).barcode(s, z)),
    "PersistenceEngine.chunksCocycles" -> ((s, k, z) => PersistenceEngine.chunksCocycles[Cell, ff.Fp](k).barcode(s, z)),
    "PersistenceEngine.cohomology" -> ((s, _, z) => PersistenceEngine.cohomology[Cell, ff.Fp].barcode(s, z)),
    "PersistenceEngine.cohomologyCycles" -> ((s, _, z) =>
      PersistenceEngine.cohomologyCycles[Cell, ff.Fp].barcode(s, z)
    ),
    // The engines' own defaults: chunks reads degrees 0..5 and the naive cursor the whole stream, so the stream's
    // limit is the only thing that stops them at degree 1.
    "PersistenceEngine.chunks(5)" -> ((s, _, z) => PersistenceEngine.chunks[Cell, ff.Fp](5).barcode(s, z)),
    "PersistenceEngine.chunksCocycles(5)" -> ((s, _, z) =>
      PersistenceEngine.chunksCocycles[Cell, ff.Fp](5).barcode(s, z)
    ),
    "CellularPersistenceInChunksEngine()" ->
      ((s, _, z) =>
        CellularPersistenceInChunksEngine[Cell, ff.Fp]().persistentHomology(s).barcodeAt(Double.PositiveInfinity, z)
      ),
    "CellularHomologyEngine, whole stream" ->
      ((s, _, z) =>
        val state = CellularHomologyEngine[Cell, ff.Fp, Double]().persistentHomology(s)
        state.advanceAll()
        state.barcodeAt(Double.PositiveInfinity, z)
      ),
    "CellularCohomologyEngine.persistentCohomology" ->
      ((s, _, z) => CellularCohomologyEngine[Cell, ff.Fp, Double]().persistentCohomology(s, z)),
    "CellularCohomologyEngine.persistentHomology" ->
      ((s, _, z) => CellularCohomologyEngine[Cell, ff.Fp, Double]().persistentHomology(s, z))
  )

  for (name, way) <- ways do
    s"$name" should {
      "report no bar above the stream's degree limit, and the longer stream's bars up to it" in {
        val checks = for
          (space, metric) <- spaces
          zeroLength <- Seq(false, true)
        yield
          val cut = way(truncated(metric), limit, zeroLength)
          val reference = way(longer(metric), limit, zeroLength).filter(_.dim <= limit)
          (
            s"$space, zero-length bars $zeroLength",
            cut.map(_.dim).maxOption.getOrElse(0) <= limit,
            triples(cut) == triples(reference)
          )
        checks.filterNot(c => c._2 && c._3) must beEmpty
      }
    }

  private val points = cloud(15, 3, 11L)
  private val image = Image(IndexedSeq.tabulate(6 * 6 * 6)(k => math.sin(k * 0.7) + (k % 5) * 0.1), IndexedSeq(6, 6, 6))

  private def compact(d: PersistenceDiagram[?]): List[(Int, Double, Double)] = d.triples.sorted

  "Persistence on inputs that are not streams" should {
    "report no bar above the degree asked for, and the bars of the larger degree up to it, for every engine" in {
      val cases: Seq[(String, Int => PersistenceDiagram[?])] =
        for
          engine <- Seq(Persistence.Engine.Ripser, Persistence.Engine.ParallelRipser, Persistence.Engine.Cohomology)
          representatives <- Representatives.values.toSeq
        yield (
          s"points, $engine, $representatives",
          (k: Int) =>
            Persistence(
              points,
              maxDimension = k,
              characteristic = 17,
              engine = engine,
              representatives = representatives
            )
        )
      val more: Seq[(String, Int => PersistenceDiagram[?])] = Seq(
        "points, alpha complex" -> ((k: Int) =>
          Persistence(points, maxDimension = k, complex = AlphaShapes, characteristic = 17)
        ),
        "an image, fast cubical" ->
          ((k: Int) =>
            Persistence(image, maxDimension = k, characteristic = 17, engine = Persistence.Engine.FastCubical)
          ),
        "an image, cohomology" ->
          ((k: Int) =>
            Persistence(image, maxDimension = k, characteristic = 17, engine = Persistence.Engine.Cohomology)
          ),
        "an image, chunks" ->
          ((k: Int) => Persistence(image, maxDimension = k, characteristic = 17, engine = Persistence.Engine.Chunks)),
        "an image, naive" ->
          ((k: Int) => Persistence(image, maxDimension = k, characteristic = 17, engine = Persistence.Engine.Naive))
      )
      val problems = for
        (name, run) <- cases ++ more
        cut = run(limit)
        reference = run(limit + 1)
        if cut.bars.exists(_.dim > limit) || compact(cut) != compact(reference).filter(_._1 <= limit)
      yield name
      problems must beEmpty
    }
  }

  private def keyed[C](bars: List[PersistenceBar[Double, Chain[Cube, C]]]) =
    bars.map(b => (b.toTriple, b.representative))

  "FastCubicalHomologyEngine" should {
    "give, for a degree cap, the uncapped bars and representatives up to it, and nothing above" in {
      val rng = new scala.util.Random(3)
      val problems = for
        shape <- Seq(IndexedSeq(7, 7), IndexedSeq(5, 5, 5), IndexedSeq(4, 4, 3, 3))
        values = Array.fill(shape.product)(math.round(rng.nextDouble() * 6).toDouble)
        grid = CubicalGridStream(shape, idx => values(idx.zip(shape).foldLeft(0) { case (acc, (i, n)) => acc * n + i }))
        engine = FastCubicalHomologyEngine[ff.Fp]()
        zeroLength <- Seq(false, true)
        full = engine.persistentHomology(grid, zeroLength)
        cap <- 0 until shape.size
        capped = engine.persistentHomology(grid, zeroLength, cap)
        if capped.exists(_.dim > cap) || keyed(capped) != keyed(full.filter(_.dim <= cap))
      yield s"shape $shape, cap $cap, zero-length bars $zeroLength"
      problems must beEmpty
    }
  }

  "Persistence" should {
    "give the same bars up to the degree from every engine that reads a stream, with either kind of representative" in {
      val problems = for
        (space, metric) <- spaces
        engine <- Seq(Persistence.Engine.Naive, Persistence.Engine.Chunks, Persistence.Engine.Cohomology)
        representatives <- Representatives.values.toSeq
        zeroLength <- Seq(false, true)
        diagram = Persistence(
          truncated(metric),
          maxDimension = limit,
          characteristic = 17,
          engine = engine,
          representatives = representatives,
          includeZeroLength = zeroLength
        )
        reference = Persistence(
          longer(metric),
          maxDimension = limit,
          characteristic = 17,
          engine = engine,
          representatives = representatives,
          includeZeroLength = zeroLength
        )
        if diagram.bars
          .exists(_.dim > limit) || diagram.bars.map(_.toTriple).sorted != reference.bars.map(_.toTriple).sorted
      yield s"$space, $engine, $representatives, zero-length bars $zeroLength"
      problems must beEmpty
    }
  }
