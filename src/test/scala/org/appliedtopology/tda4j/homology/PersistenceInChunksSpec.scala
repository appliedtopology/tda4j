package org.appliedtopology.tda4j

import org.specs2.mutable

class PersistenceInChunksSpec extends mutable.Specification:
  given Double is Field = Field.DoubleApproximated(1e-25)

  def explicitToStratifiedCellStream(
    streamBuilder: ExplicitStreamBuilder[Int, Double]
  ): StratifiedCellStream[Simplex[Int], Double] =
    val rawStream = streamBuilder.result()
    val byDim: Map[Int, Seq[Simplex[Int]]] =
      rawStream.iterator.toSeq.groupBy(_.dim)

    val stream: StratifiedCellStream[Simplex[Int], Double] =
      new StratifiedCellStream[Simplex[Int], Double]:
        def filtrationValue = rawStream.filtrationValue
        def filtrationOrdering = rawStream.filtrationOrdering
        val smallest = Double.NegativeInfinity
        var largest = Double.PositiveInfinity
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case d if byDim.contains(d) => byDim(d).iterator
        }
    stream

  /** The real projective plane as a CW complex, one cell per dimension: the loop `L` has boundary 0 (listed as no
    * entries at all, as a CW or Morse complex may), and the disc `D` is attached by `2L`.
    */
  enum ProjectivePlaneCell:
    case V, L, D

  "A CW complex whose loop lists no boundary (RP², one cell per dimension)" >> {
    import ProjectivePlaneCell.*
    given (ProjectivePlaneCell is OrderedCell) = new (ProjectivePlaneCell is OrderedCell):
      override lazy val ordering = Ordering.by[ProjectivePlaneCell, Int](_.ordinal)
      extension (c: ProjectivePlaneCell)
        override def dim = c.ordinal
        override def boundary[F: Field as fr]: Seq[(ProjectivePlaneCell, F)] =
          if c == D then Seq((L, fr.one + fr.one)) else Seq.empty
    val values = Map(V -> 0.0, L -> 1.0, D -> 2.0)
    val stream = new StratifiedCellStream[ProjectivePlaneCell, Double]:
      def filtrationValue = PartialFunction.fromFunction(values)
      def filtrationOrdering =
        FiltrationOrdering.canonical(filtrationValue, _.ordinal, Ordering.by[ProjectivePlaneCell, Int](_.ordinal))
      val smallest = Double.NegativeInfinity
      var largest = Double.PositiveInfinity
      def iterateDimension: PartialFunction[Int, Iterator[ProjectivePlaneCell]] = {
        case d if d >= 0 && d <= 2 => Iterator(ProjectivePlaneCell.fromOrdinal(d))
      }
    val inf = Double.PositiveInfinity
    def run(p: Int) =
      val field = FiniteField(p)
      import field.given
      val chunks = CellularPersistenceInChunksEngine[ProjectivePlaneCell, field.Fp](2).persistentHomology(stream)
      val naive = CellularHomologyEngine[ProjectivePlaneCell, field.Fp, Double]().persistentHomology(stream)
      val loopRepresentatives = chunks.barcodeAt(inf).filter(_.dim == 1).flatMap(_.annotation).map(_.cells)
      (chunks.diagramAt(inf).sorted, naive.diagramAt(inf).sorted, loopRepresentatives)
    // F_3: 2L kills the loop. F_2: 2L = 0, so the loop and the disc are both classes.
    (run(3) must beEqualTo(
      (List((0, 0.0, inf), (1, 1.0, 2.0)), List((0, 0.0, inf), (1, 1.0, 2.0)), List(Seq(L)))
    )).and(
      run(2) must beEqualTo(
        (
          List((0, 0.0, inf), (1, 1.0, inf), (2, 2.0, inf)),
          List((0, 0.0, inf), (1, 1.0, inf), (2, 2.0, inf)),
          List(Seq(L))
        )
      )
    )
  }

  /** A cell of a chain complex given by hand: integer boundary coefficients, read in the field. */
  final case class HandCell(name: String, dim: Int, value: Double, boundary: Seq[(HandCell, Int)] = Seq.empty)

  /** The barcode of a hand-given complex from the chunks and the naive engines, and whether every chunks bar's
    * representative is a cycle made of cells present at its birth.
    */
  def handBarcodes(cells: Seq[HandCell], p: Int, maxDim: Int = 2) =
    given (HandCell is OrderedCell) = new (HandCell is OrderedCell):
      override lazy val ordering = Ordering.by[HandCell, String](_.name)
      extension (c: HandCell)
        override def dim = c.dim
        override def boundary[F: Field as fr]: Seq[(HandCell, F)] = c.boundary.map { (f, k) =>
          val m = (1 to math.abs(k)).foldLeft(fr.zero)((acc, _) => acc + fr.one)
          (f, if k < 0 then -m else m)
        }
    val stream = new StratifiedCellStream[HandCell, Double]:
      def filtrationValue = PartialFunction.fromFunction((c: HandCell) => c.value)
      def filtrationOrdering =
        FiltrationOrdering.canonical(filtrationValue, _.dim, Ordering.by[HandCell, String](_.name))
      val smallest = Double.NegativeInfinity
      var largest = Double.PositiveInfinity
      def iterateDimension: PartialFunction[Int, Iterator[HandCell]] = {
        case d if d >= 0 && d <= cells.map(_.dim).max => cells.filter(_.dim == d).iterator
      }
    val field = FiniteField(p)
    import field.given
    val inf = Double.PositiveInfinity
    val chunks = CellularPersistenceInChunksEngine[HandCell, field.Fp](maxDim).persistentHomology(stream)
    val naive = CellularHomologyEngine[HandCell, field.Fp, Double]().persistentHomology(stream)
    given Ordering[HandCell] = Ordering.by[HandCell, String](_.name)
    val repsAreCycles = chunks.barcodeAt(inf).forall { bar =>
      bar.annotation.exists { rep =>
        !rep.isZero() && Chain.from(rep.boundary).terms.isEmpty && rep.cells.forall(_.value <= bar.birth)
      }
    }
    (chunks.diagramAt(inf).sorted, naive.diagramAt(inf).filter(_._1 <= maxDim).sorted, repsAreCycles)

  "Edges whose boundary is not 0 or c(v - u) with c a unit (algebraic complexes, mod-p coefficients)" >> {
    val inf = Double.PositiveInfinity
    val u = HandCell("u", 0, 0.0)
    val v = HandCell("v", 0, 0.0)
    // 2(v - u): zero over F_2 (so the edge is a cycle and u, v stay apart), u and v joined over F_3.
    val doubled = HandCell("e", 1, 1.0, Seq(v -> 2, u -> -2))
    // A disc on it (only a chain complex over F_2, where the edge's boundary vanishes): the cycle dies at 2.
    val disc = HandCell("f", 2, 2.0, Seq(doubled -> 1))
    val a = HandCell("a", 0, 0.0)
    val b = HandCell("b", 0, 0.0)
    val c = HandCell("c", 0, 1.0)
    // Three vertices: the youngest, c, is the pivot -- not a union of the first two listed.
    val threeEnds = HandCell("e", 1, 2.0, Seq(a -> 1, b -> 1, c -> 1))
    // One vertex with a nonzero coefficient over F_3 (zero over F_2).
    val oneEnd = HandCell("e", 1, 1.0, Seq(a -> 2))
    def agree(expected: List[(Int, Double, Double)]) = beEqualTo((expected, expected, true))

    "2(v - u) over F_2 is a cycle; u and v stay apart" >> {
      handBarcodes(Seq(u, v, doubled), 2) must agree(List((0, 0.0, inf), (0, 0.0, inf), (1, 1.0, inf)))
    }
    "2(v - u) over F_3 joins u and v" >> {
      handBarcodes(Seq(u, v, doubled), 3) must agree(List((0, 0.0, 1.0), (0, 0.0, inf)))
    }
    "2(v - u) over F_2 with a disc on it: the cycle dies" >> {
      handBarcodes(Seq(u, v, doubled, disc), 2) must agree(List((0, 0.0, inf), (0, 0.0, inf), (1, 1.0, 2.0)))
    }
    "a + b + c over F_3 kills the youngest vertex, c" >> {
      handBarcodes(Seq(a, b, c, threeEnds), 3) must agree(List((0, 0.0, inf), (0, 0.0, inf), (0, 1.0, 2.0)))
    }
    "2a over F_3 kills a" >> {
      handBarcodes(Seq(a, oneEnd), 3) must agree(List((0, 0.0, 1.0)))
    }
    "2a over F_2 is zero: a survives and the edge is a cycle" >> {
      handBarcodes(Seq(a, oneEnd), 2) must agree(List((0, 0.0, inf), (1, 1.0, inf)))
    }
  }

  "Homology of a triangle" >> {
    given shc: PersistenceInChunksEngine[Int, Double] = PersistenceInChunksEngine()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List(1, 2, 3).map(i => (0.0, ∆(i))))
    streamBuilder.addAll(List((1.0, ∆(1, 2)), (2.0, ∆(1, 3)), (3.0, ∆(2, 3)), (4.0, ∆(1, 2, 3))))
    val stream = explicitToStratifiedCellStream(streamBuilder)
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

  "Homology of a filled tetrahedron" >> {
    given shc: PersistenceInChunksEngine[Int, Double] = PersistenceInChunksEngine()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List(1, 2, 3, 4).map(i => (0.0, ∆(i))))
    streamBuilder.addAll(
      List(
        (1.0, ∆(1, 2)),
        (2.0, ∆(1, 3)),
        (3.0, ∆(1, 4)),
        (4.0, ∆(2, 3)),
        (5.0, ∆(2, 4)),
        (6.0, ∆(3, 4))
      )
    )
    streamBuilder.addAll(
      List(
        (7.0, ∆(1, 2, 3)),
        (8.0, ∆(1, 2, 4)),
        (9.0, ∆(1, 3, 4)),
        (10.0, ∆(2, 3, 4))
      )
    )
    streamBuilder.addOne((11.0, ∆(1, 2, 3, 4)))

    val stream = explicitToStratifiedCellStream(streamBuilder)
    val homology = persistentHomology(stream)
    homology.diagramAt(12.0) must containTheSameElementsAs(
      List(
        // H_0: 3 vertices die when connected to vertex 1; one lives forever
        (0, 0.0, 1.0), // killed by edge {1,2}
        (0, 0.0, 2.0), // killed by edge {1,3}
        (0, 0.0, 3.0), // killed by edge {1,4}
        (0, 0.0, Double.PositiveInfinity), // essential 0-class
        // H_1: 3 independent 1-cycles each filled by a triangle
        (1, 4.0, 7.0), // {2,3} born; {1,2,3} kills it
        (1, 5.0, 8.0), // {2,4} born; {1,2,4} kills it
        (1, 6.0, 9.0), // {3,4} born; {1,3,4} kills it
        // H_2: 2-sphere boundary born by {2,3,4}; filled by the tetrahedron
        (2, 10.0, 11.0)
      )
    )
  }

  "Homology of a triangulated torus" >> {
    given shc: PersistenceInChunksEngine[Int, Double] = PersistenceInChunksEngine()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List(0, 1, 2, 3, 4, 5, 6, 7, 8).map(i => (0.0, ∆(i))))
    streamBuilder.addAll(
      List(
        (1.0, ∆(0, 1)),
        (2.0, ∆(1, 2)),
        (3.0, ∆(2, 0)),
        (4.0, ∆(3, 4)),
        (5.0, ∆(4, 5)),
        (6.0, ∆(5, 3)),
        (7.0, ∆(6, 7)),
        (8.0, ∆(7, 8)),
        (9.0, ∆(8, 6)),
        (10.0, ∆(0, 3)),
        (11.0, ∆(1, 4)),
        (12.0, ∆(2, 5)),
        (13.0, ∆(3, 6)),
        (14.0, ∆(4, 7)),
        (15.0, ∆(5, 8)),
        (16.0, ∆(6, 0)),
        (17.0, ∆(7, 1)),
        (18.0, ∆(8, 2)),
        (19.0, ∆(0, 4)),
        (20.0, ∆(1, 5)),
        (21.0, ∆(2, 3)),
        (22.0, ∆(3, 7)),
        (23.0, ∆(4, 8)),
        (24.0, ∆(5, 6)),
        (25.0, ∆(6, 1)),
        (26.0, ∆(7, 2)),
        (27.0, ∆(8, 0))
      )
    )
    streamBuilder.addAll(
      List(
        (28.0, ∆(0, 1, 4)),
        (29.0, ∆(0, 4, 3)),
        (30.0, ∆(1, 2, 5)),
        (31.0, ∆(1, 5, 4)),
        (32.0, ∆(2, 0, 3)),
        (33.0, ∆(2, 3, 5)),
        (34.0, ∆(3, 4, 7)),
        (35.0, ∆(3, 7, 6)),
        (36.0, ∆(4, 5, 8)),
        (37.0, ∆(4, 8, 7)),
        (38.0, ∆(5, 3, 6)),
        (39.0, ∆(5, 6, 8)),
        (40.0, ∆(6, 7, 1)),
        (41.0, ∆(6, 1, 0)),
        (42.0, ∆(7, 8, 2)),
        (43.0, ∆(7, 2, 1)),
        (44.0, ∆(8, 6, 0)),
        (45.0, ∆(8, 0, 2))
      )
    )

    val rawStream = streamBuilder.result()
    val byDim: Map[Int, Seq[Simplex[Int]]] =
      rawStream.iterator.toSeq.groupBy(_.dim)
    val maxDim = 2
    // Filtration-order flattening (vertices, then edges, then triangles).
    // Vertices all have f=0 so dim-major equals filtration order here.
    val sortedAll: Vector[Simplex[Int]] =
      0.to(maxDim).iterator.flatMap(byDim).toVector

    val stream: StratifiedCellStream[Simplex[Int], Double] =
      new StratifiedCellStream[Simplex[Int], Double]:
        def filtrationValue = rawStream.filtrationValue
        def filtrationOrdering = rawStream.filtrationOrdering
        val smallest = Double.NegativeInfinity
        val largest = Double.PositiveInfinity
        // Bypass the buggy default StratifiedCellStream.iterator
        // (Iterator.from(0).filter(...).fold(...) infinite-loops).
        override def iterator: Iterator[Simplex[Int]] = sortedAll.iterator
        def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
          case d if byDim.contains(d) => byDim(d).iterator
        }

    val ccCtx: PersistenceInChunksEngine[Int, Double] =
      PersistenceInChunksEngine()
    val ccDiagram =
      ccCtx.persistentHomology(stream).diagramAt(Double.PositiveInfinity)

    ccDiagram must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0),
        (0, 0.0, 2.0),
        (0, 0.0, 4.0),
        (0, 0.0, 5.0),
        (0, 0.0, 7.0),
        (0, 0.0, 8.0),
        (0, 0.0, 10.0),
        (0, 0.0, 13.0),
        (0, 0.0, Double.PositiveInfinity),
        (1, 3.0, Double.PositiveInfinity),
        (1, 6.0, 33.0),
        (1, 9.0, 39.0),
        (1, 11.0, 29.0),
        (1, 12.0, 31.0),
        (1, 14.0, 35.0),
        (1, 15.0, 37.0),
        (1, 16.0, Double.PositiveInfinity),
        (1, 17.0, 41.0),
        (1, 18.0, 43.0),
        (1, 19.0, 28.0),
        (1, 20.0, 30.0),
        (1, 21.0, 32.0),
        (1, 22.0, 34.0),
        (1, 23.0, 36.0),
        (1, 24.0, 38.0),
        (1, 25.0, 40.0),
        (1, 26.0, 42.0),
        (1, 27.0, 44.0),
        (2, 45.0, Double.PositiveInfinity)
      )
    )
  }

  // Regression pin for a confirmed, now-fixed bug (see WORKLOG-benchmark-and-chunks-bug.md section 5):
  // compress/globalReduce's elimination loop could leave an already-`paired` cell as a chain's final
  // pivot when multiple rounds of substitution were needed to settle, corrupting the cleared/paired
  // invariant. This fixture -- the boundary of a tetrahedron (topologically S^2) with every cell tied at
  // the same filtration value -- is the minimal hand-verifiable case that actually exercises the tie
  // (HomologyFixtures.tetrahedronCells has the same shape but distinct values, and does NOT trigger it).
  // Checks PersistenceInChunksEngine directly against a hand-derived barcode, not against agreement with
  // another engine -- the two engines agreeing was exactly what this bug defeated for a while.
  "Homology of the tetrahedron boundary with every cell tied at the same value (degenerate S^2)" >> {
    given shc: PersistenceInChunksEngine[Int, Double] = PersistenceInChunksEngine(2)
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(HomologyFixtures.tetrahedronBoundaryDegenerateCells)
    val stream = explicitToStratifiedCellStream(streamBuilder)
    val homology = persistentHomology(stream)
    // All pairs, the zero-length ones included: how the tied cells pair off is the point of this fixture.
    (homology.diagramAt(Double.PositiveInfinity, includeZeroLength = true) must containTheSameElementsAs(
      HomologyFixtures.tetrahedronBoundaryDegenerateExpected
    )) and (homology.diagramAt(Double.PositiveInfinity) must containTheSameElementsAs(
      HomologyFixtures.tetrahedronBoundaryDegenerateExpected.filter((_, b, d) => b != d)
    ))
  }

  // Ground-truth checks reusing RipserCohomologySpec's hand-verified fixtures (threePointLine: 3 colinear
  // points at 0, 1, 3, so H_0's finite deaths are unambiguous MST edges, not tie-broken guesses) --
  // checked directly against a hand-derived barcode, the same reason as the fixture above: engines
  // agreeing with each other was exactly the check this session's bug defeated.
  "Homology of a 3-cycle graph (no filled triangle) has one essential H^1 class, not three" >> {
    given shc: PersistenceInChunksEngine[Int, Double] = PersistenceInChunksEngine(1)
    import shc.{*, given}

    // The graph on threePointLine's points (0, 1 and 3 on a line), edges at their lengths, as an explicit complex: a
    // `LimitedCofaceSimplexStream` of dimension 1 declares that degree 1 is a truncation artifact, which is not what
    // this test is about.
    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List(0, 1, 2).map(i => (0.0, ∆(i))))
    streamBuilder.addAll(List((1.0, ∆(0, 1)), (2.0, ∆(1, 2)), (3.0, ∆(0, 2))))
    val stream = explicitToStratifiedCellStream(streamBuilder)
    persistentHomology(stream).diagramAt(Double.PositiveInfinity) must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0),
        (0, 0.0, 2.0),
        (0, 0.0, Double.PositiveInfinity),
        (1, 3.0, Double.PositiveInfinity)
      )
    )
  }

  "Homology of the filled triangle has zero essential H^1 classes (contractible)" >> {
    given shc: PersistenceInChunksEngine[Int, Double] = PersistenceInChunksEngine(2)
    import shc.{*, given}

    val threePointLine = EuclideanMetricSpace(Array(Array(0.0), Array(1.0), Array(3.0)))
    // Explicit +Infinity -- see the "3-cycle graph" test above for why: {0,2} and the triangle are both born
    // at 3.0, past threePointLine's own minimumEnclosingRadius (2.0).
    val stream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(threePointLine, maxFiltrationValue = Some(Double.PositiveInfinity)),
      2
    )
    val h0 = List((0, 0.0, 1.0), (0, 0.0, 2.0), (0, 0.0, Double.PositiveInfinity))
    val state = persistentHomology(stream)
    (state.diagramAt(Double.PositiveInfinity) must containTheSameElementsAs(h0)) and
      (state.diagramAt(Double.PositiveInfinity, includeZeroLength = true) must containTheSameElementsAs(
        h0 :+ (1, 3.0, 3.0) // {0,2} paired with the triangle, both born at 3.0
      ))
  }

  "Homology of the elder-rule fixture picks the filtration-order pivot, not the lexicographic one" >> {
    given shc: PersistenceInChunksEngine[Int, Double] = PersistenceInChunksEngine()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(HomologyFixtures.elderRuleCells)
    val stream = explicitToStratifiedCellStream(streamBuilder)
    persistentHomology(stream).diagramAt(Double.PositiveInfinity) must containTheSameElementsAs(
      HomologyFixtures.elderRuleExpected
    )
  }

  // Regression pin for a real, pre-existing pairing bug found while validating dimension >= 2 representative
  // tracking (.claude/WORKLOG-chunks-pairing-bug.md): on a tie-heavy clique (every point at the same
  // filtration value, forcing heavy cross-chunk/cross-dimension cascading resolution), advanceAll's
  // local/global split could leave a cell "in limbo" (neither cleared nor paired) that a HIGHER dimension's
  // global pass would then wrongly claim as its own pivot, corrupting the cleared/paired invariant and
  // leaving an unrelated cell spuriously essential. Minimal repro was n=6, maxDim=3 (tetrahedron {1,2,4,5}
  // wrongly cleared by {0,1,2,3,4} instead of resolving as the killer of its own face {1,2,4}). Swept wider
  // here (n in [5,8] x maxDim in [2,3]) since the bug's own two root causes are both about cascade depth/
  // timing, not specific to the minimal case. Checks THREE independent things per case: diagramAt's own bar
  // counts by dimension against naive's (restricted to dim <= maxDim, since naive's own stream has no
  // maxDim concept -- a dimension-d cell can only ever affect a dimension d-1 pivot, so nothing above
  // maxDim+1 can change a dim <= maxDim bar, matching chunks' own internalMaxDim = maxDim + 1 convention);
  // that every barcodeAt representative at dimension >= 1 is a genuine cycle (this bug's fix reuses vcolOf,
  // so a regression here would likely also break representatives); and that cleared/paired never overlap.
  "Homology of tie-heavy cliques matches the naive engine exactly, with no cleared/paired overlap (pairing-bug regression)" >> {
    val results = for
      n <- 5 to 8
      maxDim <- 2 to 3
    yield
      val metricSpace = EuclideanMetricSpace(Array.fill(n)(Array(0.0)))

      val chunksCtx = PersistenceInChunksEngine[Int, Double](maxDim)
      val chunksState = chunksCtx.persistentHomology(
        EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(Double.PositiveInfinity))
      )
      val chunksBars = chunksState.diagramAt(Double.PositiveInfinity)
      val chunksReps = chunksState.barcodeAt(Double.PositiveInfinity)

      given shc: SimplicialHomologyEngine[Int, Double, Double] = SimplicialHomologyEngine()
      val naiveBars = shc
        .persistentHomology(
          EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(Double.PositiveInfinity))
        )
        .diagramAt(Double.PositiveInfinity)
        .filter(_._1 <= maxDim)

      def countsByDim(bars: List[(Int, Double, Double)]): Map[Int, Int] =
        bars.groupBy(_._1).view.mapValues(_.size).toMap

      val countsMatch = countsByDim(chunksBars) == countsByDim(naiveBars)
      val noOverlap = chunksState.cleared.intersect(chunksState.paired).isEmpty
      val repsAreCycles = chunksReps.filter(_.dim >= 1).forall { bar =>
        bar.annotation.exists(rep => Chain.from(rep.boundary).isZero())
      }
      (n, maxDim, countsMatch, noOverlap, repsAreCycles)

    val bad = results.filterNot { case (_, _, a, b, c) => a && b && c }
    bad must beEmpty
  }

  "Degree-1 representatives built on demand from union-find's spanning forest are cycles present at birth" >> {
    // Seeded random points over F_17 (signs matter), zero-length bars included, so every cycle-closing edge's class is
    // reported and every one of its tree-path representatives is built and checked, not only the long bars'.
    val field = FiniteField(17)
    import field.given
    val inf = Double.PositiveInfinity
    val rng = new scala.util.Random(5)
    val results = for n <- Seq(6, 12, 20) yield
      val vr = VietorisRips(EuclideanMetricSpace(Array.fill(n)(Array.fill(3)(rng.nextDouble()))), maxDimension = 1)
      val state = PersistenceInChunksEngine[Int, field.Fp](1).persistentHomology(vr)
      val degreeOne = state.barcodeAt(inf, includeZeroLength = true).filter(_.dim == 1)
      val valid = degreeOne.forall { bar =>
        bar.annotation.exists { rep =>
          !rep.isZero() && Chain.from(rep.boundary).isZero() &&
          rep.cells.forall(s => vr.filtrationValue(s) <= bar.birth)
        }
      }
      val naive = SimplicialHomologyEngine[Int, field.Fp, Double]().persistentHomology(vr)
      val sameDiagram = state.diagramAt(inf, includeZeroLength = true).sorted ==
        naive.diagramAt(inf, includeZeroLength = true).filter(_._1 <= 1).sorted
      (n, degreeOne.size, valid, sameDiagram)
    (results.map(_._2).sum must beGreaterThan(100)) and
      (results.filterNot((_, _, valid, same) => valid && same) must beEmpty)
  }

  "The reduction runs once: later queries on the same state add no field operations (#51)" >> {
    // A top cell that reduces to zero is neither cleared nor paired, so it used to be reduced again by every
    // `diagramAt`/`barcodeAt`/`pairing`. A field that counts its operations sees that directly.
    val ff = FiniteField(17)
    var operations = 0L
    given counting: (ff.Fp is Field) = new (ff.Fp is Field):
      private val base = ff.fpField
      def plus(x: ff.Fp, y: ff.Fp) =
        operations += 1; base.plus(x, y)
      def minus(x: ff.Fp, y: ff.Fp) =
        operations += 1; base.minus(x, y)
      def times(x: ff.Fp, y: ff.Fp) =
        operations += 1; base.times(x, y)
      def divide(x: ff.Fp, y: ff.Fp) =
        operations += 1; base.divide(x, y)
      def isEqual(x: ff.Fp, y: ff.Fp) = base.isEqual(x, y)
      def negate(x: ff.Fp) = base.negate(x)
      def invert(x: ff.Fp) = base.invert(x)
      def zero = base.zero
      def one = base.one
      def showForSelf = base.showForSelf
    val inf = Double.PositiveInfinity
    val rng = new scala.util.Random(11)
    // A random cloud (every class of degree 1 is closed by the end, so a second pass would find nothing to reduce) and a
    // ring of 16 points with a threshold below its closing value (one class stays open, so a second pass would reduce
    // every triangle again).
    val ring = Array.tabulate(16)(i => Array(math.cos(i * math.Pi / 8), math.sin(i * math.Pi / 8)))
    val inputs = Seq(
      ("cloud", VietorisRips(EuclideanMetricSpace(Array.fill(20)(Array.fill(3)(rng.nextDouble()))), maxDimension = 1)),
      ("ring", VietorisRips(EuclideanMetricSpace(ring), 1, 0.9))
    )
    val results = for (name, vr) <- inputs yield
      operations = 0L
      val state = PersistenceInChunksEngine[Int, ff.Fp](1).persistentHomology(vr)
      state.advanceAll()
      val afterFirst = operations
      val diagram = state.diagramAt(inf).sorted
      val pairing = state.pairing
      state.advanceAll()
      val again = state.diagramAt(inf).sorted
      (name, afterFirst, operations, again == diagram, state.pairing == pairing)
    (results.filter(_._2 <= 0L).map(_._1) must beEmpty) and
      (results.filter(r => r._3 != r._2).map(_._1) must beEmpty) and
      (results.filterNot(_._4).map(_._1) must beEmpty) and
      (results.filterNot(_._5).map(_._1) must beEmpty)
  }

  "The engine's cell ranking answers as the stream's ordering does, also for cells that are not the stream's own objects (#51)" >> {
    // The engine compares cells by a rank looked up by object identity. A stream that builds fresh equal cells on every
    // iteration defeats the lookup for every cell but those of the first pass; the fallback to the stream's own ordering
    // has to give the same pairing, bars and (valid) representatives. Tie-heavy input: a lattice of integer points.
    val field = FiniteField(17)
    import field.given
    val inf = Double.PositiveInfinity
    val grid = Array.tabulate(16)(i => Array((i % 4).toDouble, (i / 4).toDouble))
    val vr = VietorisRips(EuclideanMetricSpace(grid), maxDimension = 1)
    val fresh = new StratifiedCellStream[Simplex[Int], Double]:
      def filtrationValue = vr.filtrationValue
      def filtrationOrdering = vr.filtrationOrdering
      val smallest = vr.smallest
      var largest = vr.largest
      def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
        case d if vr.iterateDimension.isDefinedAt(d) => vr.iterateDimension(d).map(s => ∆(s.toSeq*))
      }
    val own = PersistenceInChunksEngine[Int, field.Fp](1).persistentHomology(vr)
    val copied = PersistenceInChunksEngine[Int, field.Fp](1).persistentHomology(fresh)
    val naive = SimplicialHomologyEngine[Int, field.Fp, Double]().persistentHomology(vr)
    val repsAreCycles = copied
      .barcodeAt(inf, includeZeroLength = true)
      .filter(_.dim == 1)
      .forall(bar => bar.annotation.exists(rep => !rep.isZero() && Chain.from(rep.boundary).isZero()))
    // The pairing is compared with the NAIVE engine's, cell for cell, not only with another run of this one: in the lattice
    // every vertex enters at the same value, so which vertex of a merging pair dies is decided by the ordering alone (the
    // vertices union-find reads are fresh objects, compared by the fallback), and the bars would be the same either way.
    (copied.pairing.toSet must beEqualTo(own.pairing.toSet)) and
      (copied.pairing.filter(_.dim <= 1).toSet must beEqualTo(naive.pairing.filter(_.dim <= 1).toSet)) and
      (own.pairing.filter(_.dim <= 1).toSet must beEqualTo(naive.pairing.filter(_.dim <= 1).toSet)) and
      (copied.diagramAt(inf, includeZeroLength = true).sorted must beEqualTo(
        naive.diagramAt(inf, includeZeroLength = true).filter(_._1 <= 1).sorted
      )) and
      (own.pairing.size must beGreaterThan(20)) and
      repsAreCycles
  }
