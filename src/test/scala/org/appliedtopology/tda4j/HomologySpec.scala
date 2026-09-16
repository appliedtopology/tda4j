package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.barcode.*
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable
import org.specs2.ScalaCheck

class HomologySpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-25)

  "Homology of a triangle" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List(1, 2, 3).map(i => (0.0, ∆(i))))
    streamBuilder.addAll(List((1.0, ∆(1, 2)), (2.0, ∆(1, 3)), (3.0, ∆(2, 3)), (4.0, ∆(1, 2, 3))))
    val stream = streamBuilder.result()
    val homology = persistentHomology(stream)
    homology.diagramAt(5.0) must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0), // one 0-component dies when 1-2 shows up
        (0, 0.0, 2.0), // one 0-component dies when 1-3 shows up
        (0, 0.0, Double.PositiveInfinity), // one 0-component lives forever
        (1, 3.0, 4.0) // one 1-component created from 2-3 and killed by 1-2-3.
      )
    )
  }

  // Regression test for a confirmed bug: chain arithmetic inside CellularHomologyContext must pick
  // pivots by filtration order (youngest cell), never by lexicographic vertex-label order. This
  // complex deliberately gives the two conventions DIFFERENT answers: vertex 1 (small label, born
  // EARLY) and vertex 9 (large label, born LATE) are connected by one edge. The "elder rule" says
  // the younger component (vertex 9, born at 10.0) must be the one that dies; the older component
  // (vertex 1, born at 0.0) must be the one that survives as an essential class. A lexicographic-
  // order bug would report the exact opposite pairing (vertex 1 dying, vertex 9 essential), since
  // 1 < 9 lexicographically. See WORKLOG-naive-homology.md for the full root-cause writeup.
  "Elder rule must follow filtration order, not lexicographic vertex order" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List((0.0, ∆(1)), (10.0, ∆(9))))
    streamBuilder.addOne((20.0, ∆(1, 9)))
    val stream = streamBuilder.result()
    val homology = persistentHomology(stream)
    homology.diagramAt(30.0) must containTheSameElementsAs(
      List(
        (0, 10.0, 20.0), // the younger component (vertex 9, born 10.0) dies when the edge connects it
        (0, 0.0, Double.PositiveInfinity) // the older component (vertex 1, born 0.0) survives
      )
    )
  }

  private def explicitStream(cells: Seq[(Double, Simplex[Int])]): ExplicitStream[Int, Double] =
    val builder = ExplicitStreamBuilder[Int, Double]
    builder.addAll(cells)
    builder.result()

  "Naive engine reproduces the hand-verified tetrahedron and torus barcodes" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    persistentHomology(explicitStream(HomologyFixtures.tetrahedronCells))
      .diagramAt(Double.PositiveInfinity) must containTheSameElementsAs(HomologyFixtures.tetrahedronExpected)

    persistentHomology(explicitStream(HomologyFixtures.torusCells))
      .diagramAt(Double.PositiveInfinity) must containTheSameElementsAs(HomologyFixtures.torusExpected)
  }

  "Naive engine satisfies the bars-account-for-cells invariant" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    val cases = List(
      HomologyFixtures.triangleCells,
      HomologyFixtures.tetrahedronCells,
      HomologyFixtures.torusCells,
      HomologyFixtures.elderRuleCells
    )
    forall(cases) { cells =>
      val dgm = persistentHomology(explicitStream(cells)).diagramAt(Double.PositiveInfinity)
      HomologyFixtures.totalBarsAccountForAllCells(dgm, cells.size) must beTrue
    }
  }

  "diagramAt uses a closed-birth/open-death convention at the exact query value" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    // Querying exactly at the death value of a bar must include that bar (both its birth 0.0 and
    // its death 1.0 have already "happened" by the time we ask about filtration value 1.0).
    persistentHomology(explicitStream(HomologyFixtures.triangleCells)).diagramAt(1.0) must contain((0, 0.0, 1.0))
  }

  "Representative cycles are genuine cycles (zero boundary) for every bar, finite and essential" >> {
    // Exact arithmetic (Fp), not Double: zero-detection during reduction must not be confused with
    // floating-point noise -- see WORKLOG-naive-homology.md.
    val f11 = new FiniteField(11)
    import f11.given
    given shc: SimplicialHomologyContext[Int, f11.Fp, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    val allCells = List(
      HomologyFixtures.triangleCells,
      HomologyFixtures.tetrahedronCells,
      HomologyFixtures.torusCells
    )
    forall(allCells) { cells =>
      val bars = persistentHomology(explicitStream(cells)).barcodeAt(Double.PositiveInfinity)
      forall(bars) { bar =>
        val rep = bar.annotation.get
        // A representative cycle is a chain over the complex's own cells, so it cannot legitimately
        // have more distinct entries than the complex has cells. `.items` reads the chain's raw,
        // uncollapsed entries, so this is also a deterministic, non-flaky proxy for the uncollapsed-
        // duplicate-entry performance bug fixed in this session (see WORKLOG-naive-homology.md) --
        // it fails hard under that regime instead of merely running slowly.
        (Chain.from(rep.boundary).isZero() must beTrue) and (rep.items.size <= cells.size must beTrue)
      }
    }
  }

  "Representative cycle for the elder-rule fixture is exactly the birthing vertex" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    val bars = persistentHomology(explicitStream(HomologyFixtures.elderRuleCells)).barcodeAt(Double.PositiveInfinity)
    val dying = bars.find(b => b.lower == ClosedEndpoint(10.0)).get
    val essential = bars.find(b => b.lower == ClosedEndpoint(0.0)).get
    (dying.annotation.get == Chain[Simplex[Int], Double](∆(9))) must beTrue
    (essential.annotation.get == Chain[Simplex[Int], Double](∆(1))) must beTrue
  }

  "Naive engine agrees with the clear-and-compress engine on the same complexes" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    def asStratified(cells: Seq[(Double, Simplex[Int])]): StratifiedCellStream[Simplex[Int], Double] =
      val raw = explicitStream(cells)
      val byDim: Map[Int, Seq[Simplex[Int]]] = raw.iterator.toSeq.groupBy(_.dim)
      new StratifiedCellStream[Simplex[Int], Double]:
        def filtrationValue = raw.filtrationValue
        def filtrationOrdering = raw.filtrationOrdering
        val smallest = Double.NegativeInfinity
        val largest = Double.PositiveInfinity
        override def iterator: Iterator[Simplex[Int]] = cells.sortBy(_._1).map(_._2).iterator
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case d if byDim.contains(d) => byDim(d).iterator
        }

    val cc = PersistenceInChunksContext[Int, Double](3)
    val cases = List(HomologyFixtures.triangleCells, HomologyFixtures.tetrahedronCells, HomologyFixtures.torusCells)
    forall(cases) { cells =>
      val naive = persistentHomology(explicitStream(cells)).diagramAt(Double.PositiveInfinity)
      val chunks = cc.persistentHomology(asStratified(cells)).diagramAt(Double.PositiveInfinity)
      naive must containTheSameElementsAs(chunks)
    }
  }

  "Barcode is independent of the coefficient field for these torsion-free complexes" >> {
    val f2 = new FiniteField(2)
    val f3 = new FiniteField(3)
    import f2.given
    import f3.given

    def barcodeOverField[CoefficientT: Field](cells: Seq[(Double, Simplex[Int])]): List[(Int, Double, Double)] =
      val ctx = SimplicialHomologyContext[Int, CoefficientT, Double]()
      ctx.persistentHomology(explicitStream(cells)).diagramAt(Double.PositiveInfinity)

    val cases = List(
      HomologyFixtures.triangleCells,
      HomologyFixtures.tetrahedronCells,
      HomologyFixtures.torusCells
    )
    forall(cases) { cells =>
      val overQ = barcodeOverField[Double](cells)
      val overF2 = barcodeOverField[f2.Fp](cells)
      val overF3 = barcodeOverField[f3.Fp](cells)
      (overF2 must containTheSameElementsAs(overQ)) and (overF3 must containTheSameElementsAs(overQ))
    }
  }

  private def flattenToCellStream(
    source: StratifiedSimplexStream[Int, Double],
    maxDim: Int
  ): CellStream[Simplex[Int], Double] = HomologyFixtures.flattenToCellStream(source, maxDim)

  private def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case NegativeInfinity() => Double.NegativeInfinity
    case PositiveInfinity() => Double.PositiveInfinity
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v

  private def barcodeEndpointLtEq(x: BarcodeEndpoint[Double], y: BarcodeEndpoint[Double]): Boolean =
    endpointValue(x) <= endpointValue(y)

  "Naive engine handles a real Vietoris-Rips stream: completes, and every bar has birth <= death" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      val vrStream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(EuclideanMetricSpace(points)), 2)
      val cellStream = flattenToCellStream(vrStream, 2)
      val totalCells = cellStream.iterator.size
      // One HomologyState, not two -- both checks must look at the exact same computation.
      val bars = persistentHomology(cellStream).barcodeAt(Double.PositiveInfinity)
      bars.forall(bar => barcodeEndpointLtEq(bar.lower, bar.upper)) &&
      // Structural bound doubling as the non-flaky proxy for the uncollapsed-entry blowup bug fixed
      // in this session: a representative cycle can't have more distinct cells than the complex does.
      bars.forall(bar => bar.annotation.get.items.size <= totalCells)
    }
  }

  // Random Euclidean point clouds essentially never produce exactly-tied pairwise distances in
  // floating point, so the previous test gives no real coverage of tied-filtration-value processing
  // order (a face and its coface can share a filtration value, e.g. two edges of equal length closing
  // a triangle at the same diameter). A square has two pairs of tied edge lengths and two tied
  // diagonals by construction -- a cheap, deterministic way to actually exercise that path.
  "Naive engine handles tied filtration values (VR complex on a square) without exception" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    // Points 0=(0,0), 1=(1,0), 2=(1,1), 3=(0,1): a unit square with diagonal 0-2 of length sqrt(2).
    val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
    val vrStream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(EuclideanMetricSpace(square)), 2)
    val cellStream = flattenToCellStream(vrStream, 2)
    val allCells: Vector[Simplex[Int]] = cellStream.iterator.toVector

    // Confirm the fixture actually exercises the hazard it's named for: a face and its own strictly
    // higher-dimensional coface sharing a filtration value, not merely two unrelated cells at
    // different dimensions happening to tie. Triangle {0,1,2}'s VR value is its longest edge, the
    // diagonal {0,2} (length sqrt(2)) -- so the diagonal and the triangle containing it tie exactly.
    (cellStream.filtrationValue(∆(0, 2)) == cellStream.filtrationValue(∆(0, 1, 2))) must beTrue

    // One HomologyState, not two -- both checks below must look at the exact same computation.
    val bars = persistentHomology(cellStream).barcodeAt(Double.PositiveInfinity)
    forall(bars) { bar =>
      (barcodeEndpointLtEq(bar.lower, bar.upper) must beTrue) and
        // Structural bound doubling as the non-flaky proxy for the uncollapsed-entry blowup bug fixed
        // in this session: a representative cycle can't have more distinct cells than the complex does.
        (bar.annotation.get.items.size <= allCells.size must beTrue)
    }
  }

class BarcodeRegressionSpec extends org.specs2.mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-25)

  val shc = PersistenceInChunksContext[Int, Double](3)

  val cases: Seq[(String, Array[Array[Double]] => StratifiedSimplexStream[Int, Double])] = Seq(
    ("Alpha DQP", (pts: Array[Array[Double]]) => Alpha(pts, "DQP")),
    ("Alpha Helix", (pts: Array[Array[Double]]) => Alpha(pts, "helix")),
    (
      "VR",
      (pts: Array[Array[Double]]) =>
        LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(EuclideanMetricSpace(pts)), 4)
    )
  )
  val points = matrixGen[Double](Gen.double, Gen.chooseNum(2, 10), Gen.chooseNum(25, 150)).sample.get
  for (name, streamBuilder) <- cases do
    s"$name complex should have births before deaths" >> {
      // matrixGen is defined in VietorisRipsSpec.scala
      // forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 10), Gen.chooseNum(25, 250))) { (points: Array[Array[Double]]) =>
      val vrstream = streamBuilder(points)
      val homology = shc.persistentHomology(vrstream)
      val dgm = homology.diagramAt(5.0)
      forall(dgm)((bar: (Int, Double, Double)) => bar._2 <= bar._3)
      // }
    }
