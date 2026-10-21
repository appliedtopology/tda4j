package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** `CellularCohomologyEngine` reduces simplices with `Int` vertices on flat vertex arrays. It must give exactly what it
  * gives reducing the cells themselves (`packSimplices = false`): the same pairs in the same order, the same cocycles
  * and cycles term for term, with zero-length bars and without. Inputs: alpha complexes in R², R³ and R⁴ and a
  * truncated one; Vietoris-Rips, Čech and witness complexes of a grid, where the stream's tie-break decides the pairs;
  * an explicit RP² (H₁ over F₂, not over F₃); streams that list their cells out of order. Over F₂, F₃, F₁₇ and the
  * reals. The engine must take the packed path on all of them (or the comparison is with itself), and must not take it
  * for vertices that are not `Int`s, for a vertex order other than `Int`'s, or for a cell instance of the user's own.
  */
class PackedSimplexCohomologySpec extends Specification:

  private def cloud(n: Int, dim: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  private val grid = (for i <- 0 until 3; j <- 0 until 4 yield Array(i.toDouble, j.toDouble)).toArray

  // The six-vertex RP².
  private val rp2Triangles =
    Seq((1, 2, 3), (1, 3, 4), (1, 4, 5), (1, 5, 6), (1, 2, 6), (2, 3, 5), (2, 4, 5), (2, 4, 6), (3, 4, 6), (3, 5, 6))
      .map((a, b, c) => Simplex(a, b, c))

  // The same cells listed in a random order (the engine must sort them itself).
  private def shuffled(s: CellStream[Simplex[Int], Double], seed: Long): CellStream[Simplex[Int], Double] =
    val cells = new scala.util.Random(seed).shuffle(s.iterator.toVector)
    new CellStream[Simplex[Int], Double] with DoubleFiltration[Simplex[Int]]:
      def filtrationValue = s.filtrationValue
      def filtrationOrdering = s.filtrationOrdering
      def iterator = cells.iterator

  private def streams(seed: Long): Seq[(String, CellStream[Simplex[Int], Double])] =
    val euclideanGrid = EuclideanMetricSpace(grid)
    Seq(
      "alpha in R^2" -> AlphaShapes(cloud(20, 2, seed)),
      "alpha in R^3" -> AlphaShapes(cloud(14, 3, seed)),
      "alpha in R^4" -> AlphaShapes(cloud(9, 4, seed)),
      "alpha in R^3 truncated to H_0..H_1" -> Truncated(AlphaShapes(cloud(14, 3, seed)), 1),
      "Vietoris-Rips of a grid" -> VietorisRips(euclideanGrid, maxDimension = 2),
      "Cech of a grid" -> Cech(euclideanGrid, maxDimension = 2),
      "witness complex of a grid" -> Witness(euclideanGrid, IndexedSeq(0, 2, 5, 7, 9, 11), maxDimension = 2),
      "RP^2, every cell at 0" -> ExplicitStreamBuilder.fromFacets(rp2Triangles),
      "RP^2, filtered" -> ExplicitStreamBuilder.fromFilteredFacets(
        rp2Triangles.zipWithIndex.map((t, i) => (i.toDouble, t))
      ),
      "alpha in R^3, shuffled" -> shuffled(AlphaShapes(cloud(14, 3, seed)), seed),
      "Vietoris-Rips of a grid, shuffled" -> shuffled(VietorisRips(euclideanGrid, maxDimension = 2), seed)
    )

  // Everything the engine returns for `stream`: the pairing with its cocycles, the order it was computed under, and the
  // bars of each kind with and without zero-length ones.
  private def results[C: Field](stream: CellStream[Simplex[Int], Double], packed: Boolean): Seq[Seq[Any]] =
    val engine = CellularCohomologyEngine[Simplex[Int], C, Double](packSimplices = packed)
    def bars(bs: List[PersistenceBar[Double, Chain[Simplex[Int], C]]]) =
      bs.map(b => (b.dim, b.lower, b.upper, b.representative.terms, b.representative.leadingCell))
    val (paired, order) = engine.pairedCohomology(stream)
    Seq(
      paired.map((b, p) => (b.dim, b.lower, b.upper, p, b.representative.terms, b.representative.leadingCell)),
      stream.iterator.toVector.sorted(using order),
      bars(engine.persistentCohomology(stream)),
      bars(engine.persistentCohomology(stream, includeZeroLength = true)),
      bars(engine.persistentHomology(stream)),
      bars(engine.persistentHomology(stream, includeZeroLength = true))
    )

  private val what = Seq("pairs", "order", "cocycles", "cocycles with zero-length", "cycles", "cycles with zero-length")

  // The first difference between the packed and the unpacked reduction of `stream`, if any.
  private def difference[C: Field](name: String, stream: CellStream[Simplex[Int], Double]): Option[String] =
    if !CellularCohomologyEngine[Simplex[Int], C, Double]().reducesPacked(stream) then Some(s"$name: not packed")
    else
      val (packed, cells) = (results[C](stream, packed = true), results[C](stream, packed = false))
      packed.indices.find(k => packed(k) != cells(k)).map { k =>
        val (a, b) = (packed(k), cells(k))
        val i = a.indices.find(i => i >= b.size || a(i) != b(i)).getOrElse(b.size)
        s"$name, ${what(k)}: sizes ${a.size} and ${b.size}, first difference at $i: ${a.lift(i)} vs ${b.lift(i)}"
      }

  "CellularCohomologyEngine on simplices with Int vertices" should {
    "give exactly the unpacked reduction's pairs and representatives over F_2, F_3 and F_17" in {
      val failures = for
        seed <- 0L until 3L
        ff <- Seq(FiniteField(2), FiniteField(3), FiniteField(17))
        (name, stream) <- streams(seed)
        failure <-
          import ff.given; difference[ff.Fp](s"$name (seed $seed, F_${ff.p})", stream)
      yield failure
      failures.take(3) must beEmpty
    }

    "and over the reals" in {
      given (Double is Field) = Field.DoubleApproximated(1e-9)
      streams(0L).flatMap((name, stream) => difference[Double](name, stream)).take(3) must beEmpty
    }

    "find H_1 of RP^2 over F_2 and not over F_3" in {
      def degrees(ff: FiniteField): Seq[Int] =
        import ff.given
        CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]()
          .persistentHomology(ExplicitStreamBuilder.fromFacets(rp2Triangles))
          .map(_.dim)
          .sorted
      (degrees(FiniteField(2)) must beEqualTo(Seq(0, 1, 2))).and(degrees(FiniteField(3)) must beEqualTo(Seq(0)))
    }

    "give cycles with zero boundary and cocycles with zero coboundary on an alpha complex over F_3" in {
      val ff = FiniteField(3)
      import ff.given
      val stream = AlphaShapes(cloud(14, 3, 5L))
      val engine = CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]()
      val cells = stream.iterator.toVector
      val cycles = engine.persistentHomology(stream)
      val essentialCocycles = engine.persistentCohomology(stream).filter(_.upper == PositiveInfinity[Double]())
      val fr = summon[ff.Fp is Field]
      def boundary(z: Chain[Simplex[Int], ff.Fp]) =
        Chain
          .from(z.terms.flatMap((s, c) => s.boundary[ff.Fp].map((f, b) => (f, fr.times(c, b)))))(using z.cellOrdering)
      val wrongCycles = cycles.filterNot(b => boundary(b.representative).isZero())
      val wrongCocycles = essentialCocycles.filterNot { b =>
        val z = b.representative
        engine.coboundaryOfChain(z, cells.filter(_.dim == b.dim + 1)).isZero()
      }
      (cycles.size must beGreaterThan(13)).and(wrongCycles.take(3) must beEmpty).and(wrongCocycles.take(3) must beEmpty)
    }

    "fall back to the cells for other vertex types, another vertex order, and a cell instance of the user's own" in {
      val ff = FiniteField(17)
      import ff.given
      val strings = ExplicitStreamBuilder.fromFacets(Seq(Simplex("a", "b", "c"), Simplex("b", "c", "d")))
      val backwards =
        given Ordering[Int] = Ordering.Int.reverse
        ExplicitStreamBuilder.fromFacets(Seq(Simplex(1, 2, 3), Simplex(2, 3, 4)))
      val base = simplexIsOrderedCell[Int]()
      def baseBoundary[F: Field](spx: Simplex[Int]): Seq[(Simplex[Int], F)] =
        import base.*
        spx.boundary[F]
      val own: Simplex[Int] is OrderedCell = new (Simplex[Int] is OrderedCell):
        override lazy val ordering = base.ordering
        extension (spx: Simplex[Int])
          override def dim = base.dim(spx)
          override def boundary[CoefficientT: Field]: Seq[(Simplex[Int], CoefficientT)] =
            baseBoundary[CoefficientT](spx)
      val square = ExplicitStreamBuilder.fromFacets(Seq(Simplex(0, 1, 2), Simplex(0, 2, 3)))
      val stringEngine = CellularCohomologyEngine[Simplex[String], ff.Fp, Double]()
      val backwardsEngine =
        given Ordering[Int] = Ordering.Int.reverse
        CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]()
      val ownEngine =
        given (Simplex[Int] is OrderedCell) = own
        CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]()
      (stringEngine.reducesPacked(strings) must beFalse)
        .and(stringEngine.persistentHomology(strings).map(_.dim) must beEqualTo(List(0)))
        .and(backwardsEngine.reducesPacked(backwards) must beFalse)
        .and(backwardsEngine.persistentHomology(backwards).map(_.dim) must beEqualTo(List(0)))
        .and(ownEngine.reducesPacked(square) must beFalse)
        .and(CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]().reducesPacked(square) must beTrue)
    }

    "reduce an empty stream to no bars" in {
      val ff = FiniteField(17)
      import ff.given
      val empty = ExplicitStreamBuilder.fromFacets(Seq.empty[Simplex[Int]])
      CellularCohomologyEngine[Simplex[Int], ff.Fp, Double]().persistentHomology(empty) must beEmpty
    }
  }
