package org.appliedtopology.tda4j

/** Hand-verified simplicial complexes with known barcodes, shared across homology-engine test suites so that different
  * persistence algorithms (naive reduction, clear&compress, ...) can be checked against the exact same input and are
  * directly comparable to each other, not just each internally self-consistent.
  */
object HomologyFixtures:

  /** `StratifiedCellStream`'s default `.iterator` (`Iterator.from(0).filter(isDefinedAt).fold(...)`) hangs forever for
    * a coface stream (documented in `PersistenceInChunksContext`/`PersistenceInChunksSpec`). Engines that call
    * `stream.iterator` directly (`CellularHomologyContext.HomologyState`) would hang too. Sidestep by flattening
    * `iterateDimension` over a bounded range into a finite `Vector` up front and wrapping that as a plain `CellStream`
    * -- the engine only needs a correctly-ordered finite iterator, not this specific stream implementation's own
    * (buggy) default one. Shared across homology-engine specs so a naive-engine test and a cohomology-engine test can
    * build comparable streams from the same helper.
    *
    * This dimension-major cell vector is also what makes `flattenToCellStream` safe regardless of `source
    * .filtrationOrdering`'s own tie-break: `CellularHomologyContext`'s reduction only ever touches a cell's own
    * transitive faces, so faces-before-cofaces (all it actually needs from iteration order) holds unconditionally here
    * -- a proper face always has strictly smaller dimension, and dimension-major processing does all of dimension `d-1`
    * before any of dimension `d`, regardless of any filtration-value tie.
    *
    * (`source.filtrationOrdering` itself -- used for PIVOT selection, a separate concern from iteration order -- was
    * for a while a real hazard here: `EnumeratingCofaceSimplexStream.filtrationOrdering` used to be `Ordering.by
    * (filtrationValue)` with no tie-break at all, so two DIFFERENT simplices tied at the same filtration value compared
    * as *equal*, corrupting `Chain.reduceBy`'s `SortedMap`-keyed pivot table on any Vietoris-Rips complex with
    * `maxDimension >= 2` (a triangle always ties with its own longest edge). Fixed directly at the source -- see
    * `EnumeratingCofaceSimplexStream.filtrationOrdering`'s own doc comment and WORKLOG-cohomology.md for the full repro
    * and derivation -- so this helper no longer needs a workaround for it.)
    */
  def flattenToCellStream(
    source: StratifiedSimplexStream[Int, Double],
    maxDim: Int
  ): CellStream[Simplex[Int], Double] =
    val cells: Vector[Simplex[Int]] =
      (0 to maxDim).iterator.flatMap(d => source.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty)).toVector
    new CellStream[Simplex[Int], Double]:
      def filtrationValue = source.filtrationValue
      def filtrationOrdering = source.filtrationOrdering
      val smallest = Double.NegativeInfinity
      val largest = Double.PositiveInfinity
      def iterator: Iterator[Simplex[Int]] = cells.iterator

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

  /** For every cell in a stream, it either opens exactly one bar (as a birth, whether finite or essential) or closes
    * exactly one bar (as a death); a finite bar accounts for 2 cells, an essential bar for 1. This is a cheap
    * structural invariant that catches most reduction bugs without needing an external oracle.
    */
  def totalBarsAccountForAllCells(
    barcode: List[(Int, Double, Double)],
    totalCells: Int
  ): Boolean =
    val (finite, essential) = barcode.partition((_, _, upper) => upper.isFinite)
    finite.size * 2 + essential.size == totalCells
