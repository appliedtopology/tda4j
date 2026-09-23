package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.appliedtopology.tda4j.streams.StreamFixtures.explicitStream
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

  /** Wraps `explicitStream`'s output as a `StratifiedCellStream` with each dimension's bucket sorted by
    * `filtrationOrdering.reverse` -- the stream contract's own rule 2 (CLAUDE.md), needed here because
    * `PersistenceInChunksContext` (unlike the naive engine) reads `iterateDimension` bucket order directly rather than
    * re-sorting internally.
    */
  private def asStratified(cells: Seq[(Double, Simplex[Int])]): StratifiedCellStream[Simplex[Int], Double] =
    val raw = explicitStream(cells)
    val byDim: Map[Int, Seq[Simplex[Int]]] =
      raw.iterator.toSeq.groupBy(_.dim).view.mapValues(_.sorted(using raw.filtrationOrdering.reverse)).toMap
    new StratifiedCellStream[Simplex[Int], Double]:
      def filtrationValue = raw.filtrationValue
      def filtrationOrdering = raw.filtrationOrdering
      val smallest = Double.NegativeInfinity
      val largest = Double.PositiveInfinity
      override def iterator: Iterator[Simplex[Int]] = cells.sortBy(_._1).map(_._2).iterator
      def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
        case d if byDim.contains(d) => byDim(d).iterator
      }

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
        // have more distinct entries than the complex has cells. `.rawEntries` reads the chain's raw,
        // uncollapsed entries, so this is also a deterministic, non-flaky proxy for the uncollapsed-
        // duplicate-entry performance bug fixed in this session (see WORKLOG-naive-homology.md) --
        // it fails hard under that regime instead of merely running slowly.
        (Chain.from(rep.boundary).isZero() must beTrue) and (rep.rawEntries.size <= cells.size must beTrue)
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

    val cc = PersistenceInChunksContext[Int, Double](3)
    val cases = List(HomologyFixtures.triangleCells, HomologyFixtures.tetrahedronCells, HomologyFixtures.torusCells)
    forall(cases) { cells =>
      val naive = persistentHomology(explicitStream(cells)).diagramAt(Double.PositiveInfinity)
      val chunks = cc.persistentHomology(asStratified(cells)).diagramAt(Double.PositiveInfinity)
      naive must containTheSameElementsAs(chunks)
    }
  }

  // Per .claude/CLAUDE.md's coefficients-and-representatives design principle: chunks' representatives must be
  // genuine cycles matching the naive engine's EXACTLY (not merely homologous), at EVERY dimension -- the
  // earlier, narrower dimension-0-only version of this test is superseded now that barcodeAt closes that gap.
  // barcodeAt no longer delegates to a second CellularHomologyContext run (see
  // .claude/WORKLOG-chunks-representatives-incremental.md: that design was tried, then rejected by the project
  // lead as "nowhere near a reasonable request," and replaced with `vcolOf`, which reconstructs each
  // representative incrementally from chunks' own already-computed boundaries/cleared/paired/killer state).
  // Exact match still holds under the new design too, for the same reason it held under the old one: a fixed
  // total order over a fixed cell set determines a unique reduced boundary matrix regardless of which
  // algorithm computes it, and `vcolOf`'s fold-over-reduction-log logic is derived term-for-term from
  // CellularHomologyContext.advanceOne's own audited V-column formula (see vcolOf's own doc).
  "The clear-and-compress engine's representatives match the naive engine's exactly, at every dimension" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    val cases =
      List(HomologyFixtures.elderRuleCells, HomologyFixtures.triangleCells, HomologyFixtures.tetrahedronCells)
    forall(cases) { cells =>
      val fvByCell: Map[Simplex[Int], Double] = cells.map((f, c) => c -> f).toMap
      val naiveBars = persistentHomology(explicitStream(cells)).barcodeAt(Double.PositiveInfinity)
      val chunksState = PersistenceInChunksContext[Int, Double](3).persistentHomology(asStratified(cells))
      val chunksBars = chunksState.barcodeAt(Double.PositiveInfinity)
      val chunksDiagram = chunksState.diagramAt(Double.PositiveInfinity)

      val naiveReps = naiveBars.map(b => (b.dim, b.lower, b.upper, b.annotation.get))
      val chunksReps = chunksBars.map(b => (b.dim, b.lower, b.upper, b.annotation.get))

      // (1) no bar with dim <= maxDim is left with annotation = None -- a silent None is exactly how a
      // disagreement between barcodeAt's delegate and diagramAt's own chunked computation would hide.
      val noneMissing = chunksBars.forall(_.annotation.isDefined)
      // (2) exact representative match against the naive engine, at every dimension -- multiset equality via
      // Chain's own overridden `equals` (item order inside a Chain isn't significant, and Chain has no
      // matching `hashCode` override, so plain `.toSet`/`.toMap` equality on tuples containing a Chain is NOT
      // reliable here: two `equals`-equal Chains can land in different hash buckets). Bipartite-match instead.
      val matchesNaive =
        val remaining = scala.collection.mutable.ArrayBuffer.from(naiveReps)
        chunksReps.forall { case (dim, lower, upper, chain) =>
          val idx = remaining.indexWhere { case (d, l, u, c) => d == dim && l == lower && u == upper && c == chain }
          idx >= 0 && { remaining.remove(idx); true }
        } && remaining.isEmpty
      // (3) independent agreement check: barcodeAt's own (dim, lower, upper) triples (stripped of
      // annotation) match diagramAt's -- barcodeAt's pairing comes from the same advanceAll the state
      // was already built from (diagramAt just reads it back without representatives), so this mainly
      // guards against barcodeAt's own assembly logic silently reordering or dropping a bar, not a
      // second independent computation the way it was when barcodeAt delegated elsewhere.
      val agreesWithDiagramAt =
        chunksBars.map(b => (b.dim, endpointValue(b.lower), endpointValue(b.upper))).toSet ==
          chunksDiagram.toSet
      // (4) every representative is a genuine cycle, over a field where a sign error can't hide (Double,
      // not F2).
      val allCycles = chunksBars.forall(b => Chain.from(b.annotation.get.boundary).isZero())
      // (5) the representative's own leading (youngest) cell is exactly the bar's birth cell -- checked via
      // filtration value, and every term in the representative existed by the time the bar was born.
      val leadingCellMatchesBirth = chunksBars.forall { b =>
        val rep = b.annotation.get
        val leadingFv = fvByCell(rep.leadingCell.get)
        (leadingFv == endpointValue(b.lower)) &&
        rep.rawEntries.forall((cell, _) => fvByCell(cell) <= endpointValue(b.lower))
      }

      (noneMissing must beTrue) and (matchesNaive must beTrue) and (agreesWithDiagramAt must beTrue) and
        (allCycles must beTrue) and (leadingCellMatchesBirth must beTrue)
    }
  }

  // Broader fuzz for the dimension-0/1 raw-union-find fast path added to CellularPersistenceInChunksContext
  // (.claude/WORKLOG-unionfind-in-chunks.md) -- the fixed-fixture check above (triangle/tetrahedron/torus) is
  // real coverage but far narrower than SimplicialHomologyByDimensionSpec's own 100+-random-cloud property for
  // the same kind of change, and this is exactly the class the new fast path was added to. Mirrors that
  // property's shape directly: naive engine (unaffected by this change, a genuinely different algorithm) is
  // the oracle.
  "The clear-and-compress engine's dimension-0/1 union-find agrees with the naive engine on random Vietoris-Rips complexes" >> {
    val boundedMaxDim = 3
    "agreement" ==> forAll(matrixGen[Double](Gen.double, Gen.chooseNum(1, 4), Gen.chooseNum(4, 12))) {
      (points: Array[Array[Double]]) =>
        val metricSpace = EuclideanMetricSpace(points)
        val naiveStream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace), boundedMaxDim)
        val naive = SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(naiveStream)
          .diagramAt(Double.PositiveInfinity)

        val chunksStream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace), boundedMaxDim)
        val chunks = PersistenceInChunksContext[Int, Double](boundedMaxDim)
          .persistentHomology(chunksStream)
          .diagramAt(Double.PositiveInfinity)

        "chunks (union-find dim 0/1) and naive engines agree" ==> (chunks must containTheSameElementsAs(naive))
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
      val totalCells = vrStream.iterator.size
      // One HomologyState, not two -- both checks must look at the exact same computation.
      val bars = persistentHomology(vrStream).barcodeAt(Double.PositiveInfinity)
      bars.forall(bar => barcodeEndpointLtEq(bar.lower, bar.upper)) &&
      // Structural bound doubling as the non-flaky proxy for the uncollapsed-entry blowup bug fixed
      // in this session: a representative cycle can't have more distinct cells than the complex does.
      bars.forall(bar => bar.annotation.get.rawEntries.size <= totalCells)
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
    val allCells: Vector[Simplex[Int]] = vrStream.iterator.toVector

    // Confirm the fixture actually exercises the hazard it's named for: a face and its own strictly
    // higher-dimensional coface sharing a filtration value, not merely two unrelated cells at
    // different dimensions happening to tie. Triangle {0,1,2}'s VR value is its longest edge, the
    // diagonal {0,2} (length sqrt(2)) -- so the diagonal and the triangle containing it tie exactly.
    (vrStream.filtrationValue(∆(0, 2)) == vrStream.filtrationValue(∆(0, 1, 2))) must beTrue

    // One HomologyState, not two -- both checks below must look at the exact same computation.
    val bars = persistentHomology(vrStream).barcodeAt(Double.PositiveInfinity)
    forall(bars) { bar =>
      (barcodeEndpointLtEq(bar.lower, bar.upper) must beTrue) and
        // Structural bound doubling as the non-flaky proxy for the uncollapsed-entry blowup bug fixed
        // in this session: a representative cycle can't have more distinct cells than the complex does.
        (bar.annotation.get.rawEntries.size <= allCells.size must beTrue)
    }
  }

class BarcodeRegressionSpec extends org.specs2.mutable.Specification with ScalaCheck:
  // Re-skipped after a correctness-fix session initially un-skipped this on the strength of one fast,
  // small-sample run -- wrong: matrixGen's own range here (25-150 points, dimension 2-10) can and does
  // also sample large/high-dimensional clouds, and on at least one of those this OOMs with severe GC
  // thrashing (up to 515% GC time observed) well before completing, not a quick pass. Whether that's the
  // same pre-existing "stalls out" performance issue this skip always documented, or something this
  // session's PersistenceInChunksContext fix made worse by doing more substitution work per cell, is NOT
  // yet determined -- don't re-attempt un-skipping without measuring across the actual generator range,
  // not one sample. See WORKLOG-benchmark-and-chunks-bug.md.
  skipAll // currently stalls out - we need to figure out the speed issues here.
  given Double is Field = Field.DoubleApproximated(1e-25)

  val shc = PersistenceInChunksContext[Int, Double](3)

  val cases: Seq[(String, Array[Array[Double]] => StratifiedSimplexStream[Int, Double])] = Seq(
    ("Alpha DQP", (pts: Array[Array[Double]]) => AlphaShapes(pts.toIndexedSeq, "DQP")),
    ("Alpha Helix", (pts: Array[Array[Double]]) => AlphaShapes(pts.toIndexedSeq, "helix")),
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
