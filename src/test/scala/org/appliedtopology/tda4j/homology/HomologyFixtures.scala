package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

/** Hand-verified simplicial complexes with known barcodes, shared across homology-engine test suites so that different
  * persistence algorithms (naive reduction, clear&compress, ...) can be checked against the exact same input and are
  * directly comparable to each other, not just each internally self-consistent.
  */
object HomologyFixtures:

  val triangleCells: Seq[(Double, Simplex[Int])] =
    List(1, 2, 3).map(i => (0.0, ∆(i))) ++
      List((1.0, ∆(1, 2)), (2.0, ∆(1, 3)), (3.0, ∆(2, 3)), (4.0, ∆(1, 2, 3)))

  val triangleExpected: List[(Int, Double, Double)] = List(
    (0, 0.0, 1.0), // one 0-component dies when 1-2 shows up
    (0, 0.0, 2.0), // one 0-component dies when 1-3 shows up
    (0, 0.0, Double.PositiveInfinity), // one 0-component lives forever
    (1, 3.0, 4.0) // one 1-component created from 2-3 and killed by 1-2-3
  )

  val tetrahedronCells: Seq[(Double, Simplex[Int])] =
    List(1, 2, 3, 4).map(i => (0.0, ∆(i))) ++
      List(
        (1.0, ∆(1, 2)),
        (2.0, ∆(1, 3)),
        (3.0, ∆(1, 4)),
        (4.0, ∆(2, 3)),
        (5.0, ∆(2, 4)),
        (6.0, ∆(3, 4))
      ) ++
      List(
        (7.0, ∆(1, 2, 3)),
        (8.0, ∆(1, 2, 4)),
        (9.0, ∆(1, 3, 4)),
        (10.0, ∆(2, 3, 4))
      ) ++
      List((11.0, ∆(1, 2, 3, 4)))

  val tetrahedronExpected: List[(Int, Double, Double)] = List(
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

  val torusCells: Seq[(Double, Simplex[Int])] =
    List(0, 1, 2, 3, 4, 5, 6, 7, 8).map(i => (0.0, ∆(i))) ++
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
      ) ++
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

  val torusExpected: List[(Int, Double, Double)] = List(
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

  // Elder-rule regression fixture: two vertices at deliberately DIFFERENT, non-tied filtration
  // values, connected by one edge. Discriminates "pivot by filtration order" (correct) from "pivot
  // by lexicographic vertex order" (a confirmed historical bug in CellularHomologyContext -- see
  // WORKLOG-naive-homology.md). The vertex with the SMALLER label is born LATER, so a lexicographic-
  // order bug and a filtration-order-correct implementation disagree about which component dies.
  val elderRuleCells: Seq[(Double, Simplex[Int])] =
    List((0.0, ∆(1)), (10.0, ∆(9)), (20.0, ∆(1, 9)))

  val elderRuleExpected: List[(Int, Double, Double)] = List(
    (0, 10.0, 20.0), // younger component (vertex 9, born 10.0) dies when the edge connects it
    (0, 0.0, Double.PositiveInfinity) // older component (vertex 1, born 0.0) survives
  )

  // Regression fixture for a confirmed PersistenceInChunksContext bug (see WORKLOG-benchmark-and-chunks-bug.md
  // section 5): the full 2-skeleton of a tetrahedron -- its own boundary, i.e. deliberately NOT including
  // the solid 3-simplex -- topologically S^2, with every cell tied at the SAME filtration value. The tie is
  // what actually exercises the bug: compress/globalReduce's elimination loop needing more than one round to
  // settle only matters when multiple cells could plausibly serve as each other's pivot, which ties make
  // possible. tetrahedronCells above (all-distinct values) has the same combinatorial shape but does NOT
  // exercise this path -- confirmed by hand-tracing the original bug, which only reproduced once every
  // filtration value coincided.
  val tetrahedronBoundaryDegenerateCells: Seq[(Double, Simplex[Int])] =
    List(1, 2, 3, 4).map(i => (0.0, ∆(i))) ++
      List((0.0, ∆(1, 2)), (0.0, ∆(1, 3)), (0.0, ∆(1, 4)), (0.0, ∆(2, 3)), (0.0, ∆(2, 4)), (0.0, ∆(3, 4))) ++
      List((0.0, ∆(1, 2, 3)), (0.0, ∆(1, 2, 4)), (0.0, ∆(1, 3, 4)), (0.0, ∆(2, 3, 4)))

  // By hand: connected (H_0 = Z, one essential class -- all 4 vertices tie at 0.0, 3 of them merge
  // immediately at value 0.0 too). No 1-cycle survives (H_1 = 0): with all 6 edges present and only 3
  // needed to connect 4 vertices, the other 3 each get filled by some triangle, all at value 0.0 --
  // WHICH specific edge pairs with which triangle is legitimately tie-order-dependent, but since every
  // value here is 0.0, every such pairing is the same zero-length bar (0.0, 0.0) regardless. One
  // 2-dimensional void (H_2 = Z, essential): the tetrahedron's own boundary, with no 3-simplex to fill it
  // in (deliberately excluded from this fixture).
  val tetrahedronBoundaryDegenerateExpected: List[(Int, Double, Double)] = List(
    (0, 0.0, 0.0),
    (0, 0.0, 0.0),
    (0, 0.0, 0.0),
    (0, 0.0, Double.PositiveInfinity),
    (1, 0.0, 0.0),
    (1, 0.0, 0.0),
    (1, 0.0, 0.0),
    (2, 0.0, Double.PositiveInfinity)
  )

  /** For every cell in a stream, it either opens exactly one bar (as a birth, whether finite or essential) or closes
    * exactly one bar (as a death); a finite bar accounts for 2 cells, an essential bar for 1. This is a cheap
    * structural invariant that catches most reduction bugs without needing an external oracle.
    *
    * `topDimension` (default `Int.MaxValue`, i.e. no special case -- every other caller of this helper feeds a
    * complete, untruncated complex) accounts for one specific, legitimate exception: an engine like
    * `RipserCohomologyContext` that reports homology only up to some requested top dimension `maxDimension` can have a
    * genuine finite bar BORN at `dim == maxDimension` whose death cell lives at `maxDimension + 1` -- a real simplex,
    * needed to correctly resolve that pairing, but never itself one of the `totalCells` counted (it was never
    * independently considered as its own reduction column; see `RipserCohomologyContext.coboundaryOf`'s doc and
    * `.claude/WORKLOG-maxdim-semantics-fix.md`). Such a bar consumes only its birth cell for this invariant's purposes,
    * same as an essential bar -- not 2, since its death cell isn't part of the counted set.
    */
  def totalBarsAccountForAllCells(
    barcode: List[(Int, Double, Double)],
    totalCells: Int,
    topDimension: Int = Int.MaxValue
  ): Boolean =
    val (finite, essential) = barcode.partition((_, _, upper) => upper.isFinite)
    val (finiteAtTop, finiteBelowTop) = finite.partition((dim, _, _) => dim == topDimension)
    finiteBelowTop.size * 2 + finiteAtTop.size + essential.size == totalCells
