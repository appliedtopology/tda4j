package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** `CellularCohomologyEngine` pairs degree 0 by union-find when every edge is `c·(v - u)` or has no boundary, and by
  * reducing the vertices' coboundaries otherwise. Both give what the cell-keyed reduction gives (the oracle, which
  * reduces every degree): the same bars, pairs and cocycles term for term, on random hand-built complexes whose edges
  * are every shape an algebraic chain complex allows: `u - v`, `c·(v - u)` with `c` another unit, a loop (listed as
  * nothing or as `(v, 1), (v, -1)`), a boundary of three vertices or of one, and several edges between two vertices.
  * Values tie, so the tie-break decides the pivots.
  */
class CohomologyUnionFindSpec extends Specification:

  /** A cell of a chain complex given by hand: integer boundary coefficients, read in the field. */
  final case class HandCell(name: String, dim: Int, value: Double, boundary: Seq[(HandCell, Int)] = Seq.empty)

  given (HandCell is OrderedCell) = new (HandCell is OrderedCell):
    override lazy val ordering = Ordering.by[HandCell, String](_.name)
    extension (c: HandCell)
      override def dim = c.dim
      override def boundary[F: Field as fr]: Seq[(HandCell, F)] = c.boundary.map { (f, k) =>
        val m = (1 to math.abs(k)).foldLeft(fr.zero)((acc, _) => acc + fr.one)
        (f, if k < 0 then fr.negate(m) else m)
      }

  private def streamOf(cells: Seq[HandCell]): StratifiedCellStream[HandCell, Double] =
    new StratifiedCellStream[HandCell, Double]:
      def filtrationValue = PartialFunction.fromFunction((c: HandCell) => c.value)
      def filtrationOrdering =
        FiltrationOrdering.canonical(filtrationValue, _.dim, Ordering.by[HandCell, String](_.name))
      val smallest = Double.NegativeInfinity
      val largest = Double.PositiveInfinity
      private val top = cells.map(_.dim).max
      def iterateDimension: PartialFunction[Int, Iterator[HandCell]] =
        case d if d >= 0 && d <= top => cells.filter(_.dim == d).sortBy(c => (c.value, c.name)).iterator

  /** The edge shapes, by what degree 0 can do with them. */
  private enum Shape:
    case Plain, Scaled, Loop, Irregular

  private case class Complex(cells: Seq[HandCell], shapes: Set[Shape])

  /** Vertices of value 0 or 1, edges and triangles chosen by `seed`; every boundary names cells that exist. */
  private def complexOf(seed: Long): Complex =
    val rng = new scala.util.Random(seed)
    val n = 2 + rng.nextInt(7)
    val vertices = Vector.tabulate(n)(i => HandCell(f"v$i%02d", 0, rng.nextInt(2).toDouble))
    val shapes = scala.collection.mutable.Set.empty[Shape]
    val edges = Vector.tabulate(1 + rng.nextInt(2 * n)) { k =>
      val u = vertices(rng.nextInt(n))
      val v = vertices(rng.nextInt(n))
      val value = math.max(u.value, v.value) + rng.nextInt(3)
      val (shape, boundary) = rng.nextInt(10) match
        case 0 => (Shape.Loop, Seq.empty[(HandCell, Int)])
        case 1 => (Shape.Loop, Seq(v -> 1, v -> -1))
        case 2 =>
          val w = vertices(rng.nextInt(n))
          (Shape.Irregular, Seq(u -> 1, v -> 1, w -> -2))
        case 3     => (Shape.Irregular, Seq(u -> (1 + rng.nextInt(3))))
        case 4 | 5 =>
          val c = 2 + rng.nextInt(3)
          (Shape.Scaled, Seq(v -> c, u -> -c))
        case _ => (Shape.Plain, Seq(v -> 1, u -> -1))
      shapes += shape
      HandCell(f"e$k%02d", 1, value, boundary)
    }
    // A triangle on three vertices, with edges of its own so that its boundary is a cycle: the complex stays a chain
    // complex whatever the other edges are.
    val triangles: Vector[HandCell] = Vector
      .tabulate[Vector[HandCell]](if n >= 3 then rng.nextInt(4) else 0) { k =>
        val Seq(u, v, w) = rng.shuffle(vertices).take(3)
        val sides = Seq((u, v), (v, w), (u, w)).zipWithIndex.map { case ((a, b), i) =>
          HandCell(f"t$k%02d$i", 1, math.max(a.value, b.value) + rng.nextInt(2), Seq(b -> 1, a -> -1))
        }
        val face = HandCell(
          f"f$k%02d",
          2,
          sides.map(_.value).max + rng.nextInt(2),
          Seq(sides(0) -> 1, sides(1) -> 1, sides(2) -> -1)
        )
        sides.toVector :+ face
      }
      .flatten
    Complex(vertices ++ edges ++ triangles, shapes.toSet)

  private def agrees(field: FiniteField)(complex: Complex): Boolean =
    import field.given
    val stream = streamOf(complex.cells)
    val (fast, order) = CellularCohomologyEngine[HandCell, field.Fp, Double]().pairedCohomology(stream)
    val (oracle, _) = CellKeyedCohomologyReference[HandCell, field.Fp, Double]().pairedCohomology(stream)
    given Ordering[HandCell] = order
    def key(entry: (PersistenceBar[Double, Chain[HandCell, field.Fp]], Involution.Pair[HandCell])) =
      val (bar, pair) = entry
      (bar.dim, bar.lower, bar.upper, pair, bar.representative.terms.toSet, bar.representative.leadingCell)
    val reduced = CellularCohomologyEngine[HandCell, field.Fp, Double](useUnionFind = false)
    def flat(bars: List[PersistenceBar[Double, Chain[HandCell, field.Fp]]]) =
      bars.map(b => (b.dim, b.lower, b.upper, b.representative.terms.toSet, b.representative.leadingCell))
    fast.map(key) == oracle.map(key) &&
    reduced.pairedCohomology(stream)._1.map(key) == oracle.map(key) &&
    flat(CellularCohomologyEngine[HandCell, field.Fp, Double]().persistentHomology(stream, true)) ==
      flat(reduced.persistentHomology(stream, true))

  "Degree 0 by union-find on hand-built complexes" should {
    "give the cell-keyed reduction's bars, pairs and cocycles, and the same cycles as reducing the vertices" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(120L, 25L)
        p <- Seq(2, 3, 5, 17)
        complex = complexOf(seed)
        if !agrees(FiniteField(p))(complex)
      yield (seed, p)
      failures.take(3) must beEmpty
    }
    "see every kind of edge: unions, scalings, loops and edges union-find cannot express" in {
      val seen = (0L until 120L).flatMap(complexOf(_).shapes).toSet
      seen must beEqualTo(Shape.values.toSet)
    }
  }
