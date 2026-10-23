package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** The coface streams behind [[Cech]], [[Witness]], [[Dowker]], [[DtmRips]] and [[SparseRips]] build their dimensions
  * on flat arrays ([[FlatLayerStream]]), with an array-level value function per complex. Each must give exactly what
  * the same stream gives built from `Simplex` cells (its `flatFiltration` turned off): the same cells, with the same
  * values bit for bit, in the same order, so that Betti numbers are not the only witness; the same bars with the same
  * representatives term for term; and the engine must take the flat path (a stream that cannot list its cells is
  * reduced). Inputs are chosen to be tie-heavy (a grid, integer relations), with thresholds, with a `keepCriterion` of
  * the user's, and with the values computed in parallel.
  */
class FlatLayersSpec extends Specification:

  private def cloud(n: Int, dim: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  private val grid = (for i <- 0 until 3; j <- 0 until 4 yield Array(i.toDouble, j.toDouble)).toArray

  // Integer entries, so that many simplices share a value; `Infinity` entries make a classical Dowker complex.
  private def relation(rows: Int, columns: Int, seed: Long, infinite: Boolean): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(rows, columns)(if infinite then if rng.nextInt(3) == 0 then 0.0 else Double.PositiveInfinity
    else rng.nextInt(4).toDouble)

  // A criterion of the user's (not `keepAll`): the edges of every simplex it keeps are kept.
  private val evenOrSmall: PartialFunction[Simplex[Int], Boolean] = { case s => s.size <= 2 || s.min % 2 == 0 }

  private type Stream = LevelwiseSimplexStream[Int, Double]

  /** `(flat, cells)`: the same complex built both ways, each a fresh instance (coface streams carry cache state). */
  private def pairs: Seq[(String, () => (Stream, Stream))] =
    val metrics = Seq("grid" -> EuclideanMetricSpace(grid), "cloud" -> EuclideanMetricSpace(cloud(11, 3, 3L)))
    val thresholds = Seq(None, Some(1.6))
    val criteria = Seq(CofaceSimplexStream.keepAll, evenOrSmall)

    val vietorisRips = for
      (name, metric) <- metrics
      max <- thresholds
      keep <- criteria
    yield s"Vietoris-Rips of the $name, threshold $max, ${if keep eq evenOrSmall then "criterion" else "keep all"}" ->
      (() =>
        (
          Truncated.ofCofaces(new RipserCofaceSimplexStream(metric, keep, max), 2),
          Truncated.ofCofaces(
            new RipserCofaceSimplexStream(metric, keep, max):
              override protected def flatFiltration = None
            ,
            2
          )
        )
      )

    val cech = for
      (name, metric) <- metrics
      max <- thresholds
      keep <- criteria
    yield s"Cech of the $name, threshold $max, ${if keep eq evenOrSmall then "criterion" else "keep all"}" ->
      (() =>
        (
          Truncated.ofCofaces(new CechCofaceSimplexStream(metric, keep, max), 2),
          Truncated.ofCofaces(
            new CechCofaceSimplexStream(metric, keep, max):
              override protected def flatFiltration = None
            ,
            2
          )
        )
      )

    // More than a chunk of candidates is not needed; four parallel parts of them are.
    val cechParallel = "Cech of 25 points, in parallel" -> (() =>
      val metric = EuclideanMetricSpace(cloud(25, 3, 9L))
      (
        Truncated.ofCofaces(new CechCofaceSimplexStream(metric, parallelFiltrationValue = true), 2),
        Truncated.ofCofaces(
          new CechCofaceSimplexStream(metric):
            override protected def flatFiltration = None
          ,
          2
        )
      )
    )

    val dtm = for
      (name, metric) <- metrics
      p <- Seq(1.0, 2.0)
      max <- Seq(None, Some(2.4))
      keep <- criteria
    yield s"DTM-Rips (p = $p) of the $name, threshold $max, ${
        if keep eq evenOrSmall then "criterion" else "keep all"
      }" ->
      (() =>
        val reified = DtmRipsSimplexStream.DtmMetricSpace(metric, DistanceToMeasure(metric, 3, 2.0), p)
        (
          Truncated.ofCofaces(new DtmRipsSimplexStream(reified, keep, max), 2),
          Truncated.ofCofaces(
            new DtmRipsSimplexStream(reified, keep, max):
              override protected def flatFiltration = None
            ,
            2
          )
        )
      )

    val dowker = for
      infinite <- Seq(false, true)
      seed <- 0L until 3L
      max <- Seq(Double.PositiveInfinity, 2.0)
      keep <- criteria
    yield s"Dowker, ${if infinite then "boolean" else "integer"} relation $seed, threshold $max, ${
        if keep eq evenOrSmall then "criterion" else "keep all"
      }" ->
      (() =>
        val geometry = DowkerGeometry(relation(9, 5, seed, infinite))
        (
          Truncated.ofCofaces(new DowkerCofaceSimplexStream(geometry, max, keep), 2),
          Truncated.ofCofaces(
            new DowkerCofaceSimplexStream(geometry, max, keep):
              override protected def flatFiltration = None
            ,
            2
          )
        )
      )

    val sparse = for
      (name, metric) <- metrics
      epsilon <- Seq(0.3, 0.8)
      max <- thresholds
      keep <- criteria
    yield s"sparse Rips (epsilon $epsilon) of the $name, threshold $max, ${
        if keep eq evenOrSmall then "criterion" else "keep all"
      }" ->
      (() =>
        val selection = LandmarkSelector.maxmin(metric, metric.size, 0)
        val permutation = GreedyPermutation(selection.landmarks, selection.insertionRadius)
        (
          Truncated.ofCofaces(new SheehyRipsSimplexStream(metric, permutation, epsilon, keep, max), 2),
          Truncated.ofCofaces(
            new SheehyRipsSimplexStream(metric, permutation, epsilon, keep, max):
              override protected def flatFiltration = None
            ,
            2
          )
        )
      )

    val witness = for
      (name, metric) <- metrics
      nu <- 0 to 2
    yield s"lazy witness (nu = $nu) of the $name" -> (() =>
      val landmarks = IndexedSeq(0, 2, 3, 5, 7, 8, 10)
      (
        Truncated.ofCofaces(new LazyWitnessSimplexStream(metric, landmarks, nu), 2),
        Truncated.ofCofaces(
          new LazyWitnessSimplexStream(metric, landmarks, nu):
            override protected def flatFiltration = None
          ,
          2
        )
      )
    )

    val generalWitness =
      for (name, metric) <- metrics
      yield s"general witness of the $name" -> (() =>
        val geometry = WitnessGeometry(metric, IndexedSeq(0, 2, 3, 5, 7, 8))
        (
          Truncated.ofCofaces(new WitnessCofaceSimplexStream(geometry, 2.5), 2),
          Truncated.ofCofaces(
            new WitnessCofaceSimplexStream(geometry, 2.5):
              override protected def flatFiltration = None
            ,
            2
          )
        )
      )

    vietorisRips ++ cech ++ Seq(cechParallel) ++ dtm ++ dowker ++ sparse ++ witness ++ generalWitness

  private def bits(x: Double): Long = java.lang.Double.doubleToLongBits(x)

  // The first way `flat` differs from `cells` in the layers it gives, the listing it gives and the engine's bars.
  private def difference(name: String, flat: Stream, cells: Stream): Option[String] =
    val layers = flat.asInstanceOf[FlatLayerStream].flatLayers
    if layers.isEmpty then Some(s"$name: no flat layers")
    else
      val flatLayers = layers.get.toList
      val expected = Iterator
        .from(0)
        .takeWhile(cells.iterateDimension.isDefinedAt)
        .map(d => cells.iterateDimension(d).toList)
        .takeWhile(_.nonEmpty)
        .toList
      if flatLayers.size != expected.size then Some(s"$name: ${flatLayers.size} layers, ${expected.size} dimensions")
      else
        flatLayers
          .zip(expected)
          .zipWithIndex
          .collectFirst {
            case ((layer, cellsOfDim), d) if layer.dim != d => s"$name: layer $d says dimension ${layer.dim}"
            case ((layer, cellsOfDim), d) if layer.size != cellsOfDim.size =>
              s"$name, dimension $d: ${layer.size} cells, ${cellsOfDim.size}"
            // Dimension 0 is listed unsorted by streams that give every vertex the value 0; the layer is sorted.
            case ((layer, cellsOfDim), d) if d > 0 && layer.simplicesInStreamOrder.toList != cellsOfDim =>
              s"$name, dimension $d: another order, ${layer.simplicesInStreamOrder.toList.zip(cellsOfDim).indexWhere(_ != _)}"
            case ((layer, cellsOfDim), d) if d == 0 && layer.simplicesInStreamOrder.toSet != cellsOfDim.toSet =>
              s"$name, dimension 0: other vertices"
            case ((layer, cellsOfDim), d)
                if (0 until layer.size)
                  .exists(i => bits(layer.values(i)) != bits(cells.filtrationValue(layer.simplex(i)))) =>
              val i = (0 until layer.size)
                .find(i => bits(layer.values(i)) != bits(cells.filtrationValue(layer.simplex(i))))
                .get
              s"$name, dimension $d, cell ${layer.simplex(i)}: ${layer.values(i)} vs ${cells.filtrationValue(layer.simplex(i))}"
          }
          .orElse:
            // The listing a consumer of cells sees.
            val listed = flat.iterator.toList
            if listed != cells.iterator.toList then Some(s"$name: another listing")
            else None

  // A stream with the cells of `flat` taken away: the engine can only read its layers.
  private def layersOnly(flat: Stream): Stream =
    new LimitedCofaceSimplexStream(flat.asInstanceOf[CofaceSimplexStream[Int, Double]], 3):
      override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
        case d if false => Iterator.empty
      }
      override def iterator: Iterator[Simplex[Int]] = throw new IllegalStateException("the cells were listed")

  private def barsOf[C: Field](stream: Stream, cohomology: Boolean) =
    val engine = CellularCohomologyEngine[Simplex[Int], C, Double]()
    (if cohomology then engine.persistentCohomology(stream) else engine.persistentHomology(stream))
      .map(b => (b.dim, b.lower, b.upper, b.representative.terms))

  private def barDifferences[C: Field](label: String): Seq[String] =
    pairs.flatMap { (name, make) =>
      val (flat, cells) = make()
      if flat.asInstanceOf[FlatLayerStream].flatLayers.exists(!_.hasNext) then Seq.empty // an empty complex has no bars
      else if !CellularCohomologyEngine[Simplex[Int], C, Double]().reducesPacked(layersOnly(flat)) then
        Seq(s"$name: not packed")
      else
        Seq(true, false).flatMap { cohomology =>
          Option.when(barsOf[C](layersOnly(make()._1), cohomology) != barsOf[C](cells, cohomology))(
            s"$name, $label, ${if cohomology then "cocycles" else "cycles"}: other bars"
          )
        }
    }

  "a coface stream that builds its dimensions on arrays" should {
    "give the layers, values and listing of the stream built from cells" in {
      val failures = pairs.flatMap { (name, make) =>
        val (flat, cells) = make()
        difference(name, flat, cells)
      }
      failures.take(3) must beEmpty
    }

    "give them in the same way when a dimension is asked for first" in {
      val failures = pairs.flatMap { (name, make) =>
        val (flat, cells) = make()
        val d = 2
        val asked = flat.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toList
        val expected = cells.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toList
        Option.when(asked != expected)(s"$name: dimension $d asked first differs")
      }
      failures.take(3) must beEmpty
    }

    "be reduced by the cohomology engine from its layers, without listing a cell, to the bars of the stream built from cells over F_3 and F_17" in {
      val failures = Seq(FiniteField(3), FiniteField(17)).flatMap { ff =>
        import ff.given
        barDifferences[ff.Fp](s"F_${ff.p}")
      }
      failures.take(3) must beEmpty
    }

    "and over the reals" in {
      given (Double is Field) = Field.DoubleApproximated(1e-9)
      barDifferences[Double]("the reals").take(3) must beEmpty
    }

    "report the largest value it read" in {
      given (Double is Field) = Field.DoubleApproximated(1e-9)
      val failures = pairs.flatMap { (name, make) =>
        val (flat, cells) = make()
        val engine = CellularCohomologyEngine[Simplex[Int], Double, Double]()
        engine.persistentCohomology(layersOnly(flat))
        val fromLayers = engine.largestFiltrationValue
        engine.persistentCohomology(cells)
        Option.when(fromLayers != engine.largestFiltrationValue)(
          s"$name: $fromLayers, ${engine.largestFiltrationValue}"
        )
      }
      failures.take(3) must beEmpty
    }
  }
