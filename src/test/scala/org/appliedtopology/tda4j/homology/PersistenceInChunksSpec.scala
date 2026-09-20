package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

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

  "Homology of a triangle" >> {
    given shc: PersistenceInChunksContext[Int, Double] = PersistenceInChunksContext()
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
    given shc: PersistenceInChunksContext[Int, Double] = PersistenceInChunksContext()
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
    given shc: PersistenceInChunksContext[Int, Double] = PersistenceInChunksContext()
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

    val ccCtx: PersistenceInChunksContext[Int, Double] =
      PersistenceInChunksContext()
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
  // Checks PersistenceInChunksContext directly against a hand-derived barcode, not against agreement with
  // another engine -- the two engines agreeing was exactly what this bug defeated for a while.
  "Homology of the tetrahedron boundary with every cell tied at the same value (degenerate S^2)" >> {
    given shc: PersistenceInChunksContext[Int, Double] = PersistenceInChunksContext(2)
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(HomologyFixtures.tetrahedronBoundaryDegenerateCells)
    val stream = explicitToStratifiedCellStream(streamBuilder)
    val homology = persistentHomology(stream)
    homology.diagramAt(Double.PositiveInfinity) must containTheSameElementsAs(
      HomologyFixtures.tetrahedronBoundaryDegenerateExpected
    )
  }

  // Ground-truth checks reusing RipserCohomologySpec's hand-verified fixtures (threePointLine: 3 colinear
  // points at 0, 1, 3, so H_0's finite deaths are unambiguous MST edges, not tie-broken guesses) --
  // checked directly against a hand-derived barcode, the same reason as the fixture above: engines
  // agreeing with each other was exactly the check this session's bug defeated.
  "Homology of a 3-cycle graph (no filled triangle) has one essential H^1 class, not three" >> {
    given shc: PersistenceInChunksContext[Int, Double] = PersistenceInChunksContext(1)
    import shc.{*, given}

    val threePointLine = EuclideanMetricSpace(Array(Array(0.0), Array(1.0), Array(3.0)))
    // Explicit +Infinity: this test wants the raw, unthresholded graph's own cycle structure. threePointLine's
    // own minimumEnclosingRadius (2.0, from the middle point) is less than the longest edge {0,2} (3.0) needed
    // for the 3-cycle here, and EnumeratingCofaceSimplexStream now defaults to that radius (see
    // CLAUDE.md/WORKLOG-mst-and-perf.md).
    val stream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(threePointLine, maxFiltrationValue = Some(Double.PositiveInfinity)),
      1
    )
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
    given shc: PersistenceInChunksContext[Int, Double] = PersistenceInChunksContext(2)
    import shc.{*, given}

    val threePointLine = EuclideanMetricSpace(Array(Array(0.0), Array(1.0), Array(3.0)))
    // Explicit +Infinity -- see the "3-cycle graph" test above for why: {0,2} and the triangle are both born
    // at 3.0, past threePointLine's own minimumEnclosingRadius (2.0).
    val stream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(threePointLine, maxFiltrationValue = Some(Double.PositiveInfinity)),
      2
    )
    persistentHomology(stream).diagramAt(Double.PositiveInfinity) must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0),
        (0, 0.0, 2.0),
        (0, 0.0, Double.PositiveInfinity),
        (1, 3.0, 3.0) // zero-length: {0,2} paired with the triangle, both born at 3.0
      )
    )
  }

  "Homology of the elder-rule fixture picks the filtration-order pivot, not the lexicographic one" >> {
    given shc: PersistenceInChunksContext[Int, Double] = PersistenceInChunksContext()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(HomologyFixtures.elderRuleCells)
    val stream = explicitToStratifiedCellStream(streamBuilder)
    persistentHomology(stream).diagramAt(Double.PositiveInfinity) must containTheSameElementsAs(
      HomologyFixtures.elderRuleExpected
    )
  }
