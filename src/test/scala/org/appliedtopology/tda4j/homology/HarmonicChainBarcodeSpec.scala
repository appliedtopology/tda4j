package org.appliedtopology.tda4j

import BruteForceComplexes.boundary as simplexBoundary
import SpecThrows.throwWith

import org.specs2.mutable.Specification

import scala.util.Random

/** The harmonic chain barcode (Hou, Parsa and Wang). The bars are checked against the DEFINITION, with nothing of the
  * algorithm in the oracle:
  *
  *   - the harmonic chain groups `H_p(K_i)` of a simplex-wise filtration are subspaces of one chain space, joined by
  *     inclusions in either direction; for such a zigzag the number of bars containing `[i, j]` is the dimension of
  *     `H_p(K_i) ∩ ... ∩ H_p(K_j)` (the limit maps into the colimit injectively), and the bars come out of those
  *     dimensions by inclusion and exclusion. The intersections are nullities of exact rational matrices built here;
  *   - every representative is checked against the definition of a harmonic representative (Definition 11 of the
  *     paper): a cycle that is harmonic at every complex of its bar, born at the cell that opens the bar and not
  *     harmonic once the cell that closes it has entered;
  *   - the hand-worked example of the paper's Figure 1, whose harmonic barcode is not the ordinary one.
  */
class HarmonicChainBarcodeSpec extends Specification:

  private type S = Simplex[Int]
  private type Q = Coefficients.Rational

  private def q(n: Long): Q = Coefficients.Rational(n)
  private val exact = summon[Q is Field]

  private def explicit(items: (Double, S)*): StratifiedCellStream[S, Double] =
    val builder = ExplicitStreamBuilder[Int, Double]()
    items.foreach(builder += _)
    builder.result()

  // Every simplex on `n` vertices of at most `size` vertices.
  private def universe(n: Int, size: Int): Vector[S] =
    (1 to size).flatMap(k => (0 until n).toList.combinations(k).map(Simplex.from(_))).toVector

  private def faces(s: S): Seq[S] = simplexBoundary(s).map(_._1)

  /** A random prefix of a random linear extension of the face order of `cells`: a simplex-wise filtration. */
  private def randomOrder(cells: Vector[S], length: Int, seed: Long): Vector[S] =
    val rng = new Random(seed)
    val inserted = scala.collection.mutable.LinkedHashSet.empty[S]
    while inserted.size < length do
      val ready = cells.filter(c => !inserted(c) && faces(c).forall(inserted))
      inserted += ready(rng.nextInt(ready.size))
    inserted.toVector

  /** The order with the values `1, 2, ..., m`: the complex `K_t` of the paper is the first `t` cells. */
  private def indexed(order: Vector[S]): Seq[(S, Double)] = order.zipWithIndex.map((s, i) => (s, i + 1.0))

  // --- the oracle: dimensions of intersections of harmonic chain groups, as exact nullities ---

  private def row(entries: Map[Int, Long], width: Int): Seq[Q] = (0 until width).map(c => q(entries.getOrElse(c, 0L)))

  /** `dim { z in C_p(K_i) : dz = 0 and <z, d tau> = 0 for every (p+1)-cell tau of K_j }`: the intersection of the
    * harmonic chain groups of `K_i, ..., K_j`, whose chains live in `K_i` and are orthogonal to the boundaries of
    * `K_j`.
    */
  private def intersectionDimension(order: Vector[S], p: Int, i: Int, j: Int): Int =
    if i < 1 || j > order.size || i > j then 0
    else
      val columns = order.take(i).filter(_.dim == p)
      val index = columns.zipWithIndex.toMap
      val width = columns.size
      val boundaries = columns.map(c => simplexBoundary(c).map((f, sign) => f -> sign.toLong).toMap)
      // dz = 0: a row for each (p-1)-cell, whose entries are the coefficients of that cell in the boundaries of the columns
      val cycleRows =
        order.take(i).filter(_.dim == p - 1).map(f => (0 until width).map(k => q(boundaries(k).getOrElse(f, 0L))))
      // <z, d tau> = 0: a row for each (p+1)-cell tau of K_j, restricted to the columns
      val harmonicRows = order
        .take(j)
        .filter(_.dim == p + 1)
        .map(t => row(simplexBoundary(t).flatMap((f, sign) => index.get(f).map(_ -> sign.toLong)).toMap, width))
      width - LinearAlgebra.rank[Q](cycleRows ++ harmonicRows)

  /** The bars `(p, b, d)` of the harmonic chain barcode as closed index intervals `[b, d]` of the complexes `K_b..K_d`,
    * each repeated by its multiplicity.
    */
  private def oracleBars(order: Vector[S], maxDimension: Int): List[(Int, Int, Int)] =
    val m = order.size
    (for
      p <- 0 to maxDimension
      b <- 1 to m
      d <- b to m
      r = (i: Int, j: Int) => intersectionDimension(order, p, i, j)
      multiplicity = r(b, d) - r(b - 1, d) - r(b, d + 1) + r(b - 1, d + 1)
      _ <- 0 until multiplicity
    yield (p, b, d)).toList.sorted

  /** The diagram's bars of a filtration valued `1..m` as closed index intervals: a bar `[b, e)` was harmonic in
    * `K_b, ..., K_{e-1}`.
    */
  private def intervals(diagram: PersistenceDiagram[?], m: Int): List[(Int, Int, Int)] =
    diagram.bars
      .map(bar => (bar.dim, bar.birth.toInt, if bar.death.isPosInfinity then m else bar.death.toInt - 1))
      .sorted

  private def exactDiagram(order: Vector[S], maxDimension: Int): PersistenceDiagram.Of[S, Q] =
    HarmonicChainBarcode
      .ofOrder(indexed(order), maxDimension, Coefficients.Rationals, includeZeroLength = true)
      .asInstanceOf[PersistenceDiagram.Of[S, Q]]

  private def dot(a: Map[S, Q], b: Map[S, Q]): Q =
    a.foldLeft(exact.zero) { case (sum, (s, x)) => b.get(s).fold(sum)(y => exact.plus(sum, exact.times(x, y))) }

  /** Definition 11 for one bar `[b, d]` (closed indices) whose cycle is `z`; `None` if it holds, else what fails. */
  private def violation(order: Vector[S], p: Int, b: Int, d: Int, m: Int, z: Map[S, Q]): Option[String] =
    val inFirst = order.take(b).toSet
    val last = order.take(d)
    val birthCell = order(b - 1)
    val boundaryOfZ = z.toSeq.flatMap((s, x) => simplexBoundary(s).map((f, sign) => f -> exact.times(x, q(sign))))
    val net = boundaryOfZ.groupMapReduce(_._1)(_._2)(exact.plus)
    def inner(t: S): Q = dot(z, simplexBoundary(t).map((f, sign) => f -> q(sign)).toMap)
    if !z.keys.forall(s => s.dim == p && inFirst(s)) then Some(s"z of [$b,$d] is not a p-chain of K_$b")
    else if !net.values.forall(_.isZero) then Some(s"z of [$b,$d] is not a cycle")
    else if z.get(birthCell).forall(_.isZero) then
      Some(s"z of [$b,$d] does not use the cell $birthCell that opens the bar")
    else if !last.filter(_.dim == p + 1).forall(t => inner(t).isZero) then
      Some(s"z of [$b,$d] is not orthogonal to the boundaries of K_$d")
    else if d < m && inner(order(d)).isZero then Some(s"z of [$b,$d] is still harmonic once ${order(d)} has entered")
    else None

  "the harmonic chain barcode of the paper's Figure 1" should {
    // Vertices a..e are 0..4; the edges enter at 1..7 (ac, ad, bd, ab, bc, ae, be) and the triangles abe, abd, abc at 8, 9, 10.
    val figure = explicit(
      List(0, 1, 2, 3, 4).map(v => (0.0, Simplex(v))) ++ List(
        (1.0, Simplex(0, 2)),
        (2.0, Simplex(0, 3)),
        (3.0, Simplex(1, 3)),
        (4.0, Simplex(0, 1)),
        (5.0, Simplex(1, 2)),
        (6.0, Simplex(0, 4)),
        (7.0, Simplex(1, 4)),
        (8.0, Simplex(0, 1, 4)),
        (9.0, Simplex(0, 1, 3)),
        (10.0, Simplex(0, 1, 2))
      )*
    )

    "have the bars [4,8), [5,9), [7,10) in degree 1, where the ordinary barcode has [4,9), [5,10), [7,8)" in {
      val harmonic = HarmonicChainBarcode(figure, maxDimension = 1).bars.filter(_.dim == 1).map(_.toTriple).sorted
      val ordinary = Persistence(figure, maxDimension = 1).bars.filter(_.dim == 1).map(_.toTriple).sorted
      (harmonic must beEqualTo(List((1, 4.0, 8.0), (1, 5.0, 9.0), (1, 7.0, 10.0)))) and
        (ordinary must beEqualTo(List((1, 4.0, 9.0), (1, 5.0, 10.0), (1, 7.0, 8.0))))
    }

    "carry a harmonic cycle for each bar: the oldest bar's cycle is the loop abd with coefficients 1, 1, -1" in {
      val diagram = HarmonicChainBarcode(figure, maxDimension = 1, coefficients = Coefficients.Rationals)
        .asInstanceOf[PersistenceDiagram.Of[S, Q]]
      val oldest = diagram.bars.find(b => b.dim == 1 && b.birth == 4.0).get
      oldest.representative.terms.toMap must beEqualTo(
        Map(Simplex(0, 1) -> q(1), Simplex(1, 3) -> q(1), Simplex(0, 3) -> q(-1))
      )
    }

    "have the Betti numbers of the complex among its essential bars" in {
      HarmonicChainBarcode(figure, maxDimension = 1).bettiNumbers must beEqualTo(Vector(1, 0))
    }
  }

  "the harmonic chain barcode of a small complex" should {
    "have the loop of a hollow triangle, born at its last edge and gone at the face: bar [3, 4), the cycle with coefficient 1 on that edge" in {
      val triangle = explicit(
        (0.0, Simplex(0)),
        (0.0, Simplex(1)),
        (0.0, Simplex(2)),
        (1.0, Simplex(0, 1)),
        (2.0, Simplex(1, 2)),
        (3.0, Simplex(0, 2)),
        (4.0, Simplex(0, 1, 2))
      )
      val diagram = HarmonicChainBarcode(triangle, maxDimension = 1, coefficients = Coefficients.Rationals)
        .asInstanceOf[PersistenceDiagram.Of[S, Q]]
      val loop = diagram.bars.filter(_.dim == 1)
      (loop.map(_.toTriple) must beEqualTo(List((1, 3.0, 4.0)))) and
        (loop.head.representative.terms.toMap must beEqualTo(
          Map(Simplex(0, 1) -> q(-1), Simplex(1, 2) -> q(-1), Simplex(0, 2) -> q(1))
        ))
    }

    "have, in degree 0, the all-ones chain on a component as the cycle of its essential bar, and the older vertex's own chain for the bar that ends" in {
      // two vertices and the edge between them, entering in this order (the edge ends the older vertex's bar: the opposite of the ordinary barcode)
      val order = Seq((Simplex(0), 0.0), (Simplex(1), 0.0), (Simplex(0, 1), 1.0))
      val diagram = HarmonicChainBarcode
        .ofOrder(order, maxDimension = 0, coefficients = Coefficients.Rationals)
        .asInstanceOf[PersistenceDiagram.Of[S, Q]]
      val essential = diagram.bars.find(_.death.isPosInfinity).get
      val finite = diagram.bars.find(!_.death.isPosInfinity).get
      (essential.representative.terms.toMap must beEqualTo(Map(Simplex(0) -> q(1), Simplex(1) -> q(1)))) and
        (finite.representative.terms.toMap must beEqualTo(Map(Simplex(0) -> q(1)))) and
        (finite.toTriple must beEqualTo((0, 0.0, 1.0)))
    }

    "name the cell that opens a bar and the one that closes it" in {
      val triangle = explicit(
        (0.0, Simplex(0)),
        (0.0, Simplex(1)),
        (0.0, Simplex(2)),
        (1.0, Simplex(0, 1)),
        (2.0, Simplex(1, 2)),
        (3.0, Simplex(0, 2)),
        (4.0, Simplex(0, 1, 2))
      )
      val loop = HarmonicChainBarcode(triangle, maxDimension = 1).bars.find(_.dim == 1).get
      (loop.birthCell must beSome(Simplex(0, 2))) and (loop.deathCell must beSome(Simplex(0, 1, 2)))
    }
  }

  "the harmonic chain barcode of a random simplex-wise filtration" should {
    "be the barcode of the zigzag of harmonic chain groups, in every degree, for exact rational coefficients" in {
      val cells = universe(5, 4)
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 12L, js = 3L)
        order = randomOrder(cells, 14 + (seed % 10).toInt, seed)
        expected = oracleBars(order, 2)
        found = intervals(exactDiagram(order, 2), order.size)
        if found != expected
      yield s"seed $seed: expected $expected, found $found"
      failures.take(2) must beEmpty
    }

    "give every bar a cycle that satisfies Definition 11 of the paper" in {
      val cells = universe(5, 4)
      val failures = for
        seed <- 100L until 100L + TestPlatform.perPlatform(jvm = 20L, js = 4L)
        order = randomOrder(cells, 12 + (seed % 14).toInt, seed)
        bar <- exactDiagram(order, 2).bars
        z = bar.representative.terms.toMap
        why <- violation(
          order,
          bar.dim,
          bar.birth.toInt,
          if bar.death.isPosInfinity then order.size else bar.death.toInt - 1,
          order.size,
          z
        )
      yield s"seed $seed: $why"
      failures.take(2) must beEmpty
    }

    "have as many bars alive at each value as the complex has Betti number, which the ordinary barcode also counts" in {
      val cells = universe(6, 3)
      val failures = for
        seed <- 200L until 200L + TestPlatform.perPlatform(jvm = 8L, js = 2L)
        order = randomOrder(cells, 18 + (seed % 8).toInt, seed)
        stream = explicit(order.zipWithIndex.map((s, i) => (i.toDouble + 1.0, s))*)
        harmonic = HarmonicChainBarcode(stream, maxDimension = 2, includeZeroLength = true).bars
        ordinary = Persistence(stream, maxDimension = 2, includeZeroLength = true).bars
        t <- 1 to order.size
        p <- 0 to 2
        alive = (bars: List[PersistenceBar[Double, ?]]) => bars.count(b => b.dim == p && b.birth <= t && t < b.death)
        if alive(harmonic) != alive(ordinary)
      yield s"seed $seed, t = $t, degree $p: ${alive(harmonic)} against ${alive(ordinary)}"
      failures.take(2) must beEmpty
    }
  }

  "the harmonic chain barcode of a point cloud, whose cells enter in ties" should {
    "have its bars and cycles agree with the definition on the order the stream gives" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 4L, js = 1L)
        rng = new Random(seed)
        points = Array.fill(7)(Array(rng.nextDouble(), rng.nextDouble()))
        rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
        order = HarmonicChainBarcode.insertionOrder(rips, maxDimension = 1)
        cellsInOrder = order.map(_._1).toVector
        diagram = HarmonicChainBarcode
          .ofOrder(indexed(cellsInOrder), 1, Coefficients.Rationals, includeZeroLength = true)
          .asInstanceOf[PersistenceDiagram.Of[S, Q]]
        expected = oracleBars(cellsInOrder, 1)
        found = intervals(diagram, cellsInOrder.size)
        if found != expected
      yield s"seed $seed: expected ${expected.take(6)}, found ${found.take(6)}"
      failures.take(2) must beEmpty
    }

    "agree between real and exact rational coefficients" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 6L, js = 2L)
        rng = new Random(seed)
        points = Array.fill(9)(Array(rng.nextDouble(), rng.nextDouble()))
        rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
        reals = HarmonicChainBarcode(rips, maxDimension = 1, includeZeroLength = true).triples.sorted
        rationals = HarmonicChainBarcode(
          rips,
          maxDimension = 1,
          coefficients = Coefficients.Rationals,
          includeZeroLength = true
        ).triples.sorted
        if reals != rationals
      yield s"seed $seed: ${reals.size} bars against ${rationals.size}"
      failures.take(2) must beEmpty
    }

    "have the births and the deaths of the ordinary barcode, paired differently" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 8L, js = 2L)
        rng = new Random(seed)
        points = Array.fill(9)(Array(rng.nextDouble(), rng.nextDouble()))
        rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
        harmonic = HarmonicChainBarcode(
          rips,
          maxDimension = 1,
          coefficients = Coefficients.Rationals,
          includeZeroLength = true
        ).triples
        ordinary = Persistence(rips, maxDimension = 1, characteristic = 17, includeZeroLength = true).triples
        births = (bars: List[(Int, Double, Double)]) => bars.map(b => (b._1, b._2)).sorted
        deaths = (bars: List[(Int, Double, Double)]) => bars.filter(_._3.isFinite).map(b => (b._1, b._3)).sorted
        if births(harmonic) != births(ordinary) || deaths(harmonic) != deaths(ordinary)
      yield s"seed $seed"
      failures must beEmpty
    }

    "cut the long bar of a Vietoris-Rips complex where a triangle enters with its longest edge, which the Basu-Cox bars do not" in {
      // seed 2: one loop in the ordinary barcode; at four values an edge starts a new loop in the same step as a
      // triangle with that edge as its longest, the triangle ends the older loop, and the loop is four consecutive bars
      val rng = new Random(2)
      val points = Array.fill(10)(Array(rng.nextDouble(), rng.nextDouble()))
      val rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
      val ordinary = Persistence(rips, maxDimension = 1, characteristic = 17).dim(1).triples
      val cut =
        HarmonicChainBarcode(rips, maxDimension = 1, coefficients = Coefficients.Rationals).dim(1).triples.sorted
      val representatives = HarmonicRepresentatives(rips, maxDimension = 1, coefficients = Coefficients.Rationals)
      (ordinary.size must beEqualTo(1)) and (cut.size must beEqualTo(4)) and
        (cut.head._2 must beEqualTo(ordinary.head._2)) and (cut.last._3 must beEqualTo(ordinary.head._3)) and
        (cut.zip(cut.tail).forall((a, b) => a._3 == b._2) must beTrue) and
        (representatives.dim(1).triples must beEqualTo(ordinary))
    }

    "leave out the bars that are born and gone at one value, unless asked" in {
      val rng = new Random(3)
      val points = Array.fill(8)(Array(rng.nextDouble(), rng.nextDouble()))
      val rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
      val all = HarmonicChainBarcode(rips, maxDimension = 1, includeZeroLength = true).bars
      val some = HarmonicChainBarcode(rips, maxDimension = 1).bars
      (all.exists(_.isZeroLength) must beTrue) and
        (some.exists(_.isZeroLength) must beFalse) and
        (some.size must beEqualTo(all.count(!_.isZeroLength)))
    }
  }

  "the harmonic chains at a value" should {
    // Figure 1 again: K_7 has no triangle, so its three loops are all harmonic; abe enters at 8 and abd at 9
    val figure = explicit(
      List(0, 1, 2, 3, 4).map(v => (0.0, Simplex(v))) ++ List(
        (1.0, Simplex(0, 2)),
        (2.0, Simplex(0, 3)),
        (3.0, Simplex(1, 3)),
        (4.0, Simplex(0, 1)),
        (5.0, Simplex(1, 2)),
        (6.0, Simplex(0, 4)),
        (7.0, Simplex(1, 4)),
        (8.0, Simplex(0, 1, 4)),
        (9.0, Simplex(0, 1, 3)),
        (10.0, Simplex(0, 1, 2))
      )*
    )

    "be as many as the Betti number there and cycles orthogonal to every boundary, over the rationals" in {
      val triangles = List(Simplex(0, 1, 4) -> 8.0, Simplex(0, 1, 3) -> 9.0, Simplex(0, 1, 2) -> 10.0)
      val failures = for
        (value, betti) <- List(3.5 -> 0, 4.0 -> 1, 7.0 -> 3, 8.0 -> 2, 9.0 -> 1, 10.0 -> 0)
        chains = HarmonicChainBarcode.harmonicChains(figure, 1, value, Coefficients.Rationals)
        if chains.size != betti || chains.exists { chain =>
          val z = chain.terms.toMap
          val net = z.toSeq
            .flatMap((cell, x) => simplexBoundary(cell).map((f, sign) => f -> exact.times(x, q(sign))))
            .groupMapReduce(_._1)(_._2)(exact.plus)
          val orthogonal = triangles
            .filter(_._2 <= value)
            .map((t, _) =>
              simplexBoundary(t)
                .flatMap((f, sign) => z.get(f).map(exact.times(_, q(sign))))
                .foldLeft(exact.zero)(exact.plus)
            )
          !net.values.forall(_.isZero) || !orthogonal.forall(_.isZero)
        }
      yield s"at $value: ${chains.size} chains, expected $betti"
      failures must beEmpty
    }

    "be none before any cell has entered" in {
      HarmonicChainBarcode.harmonicChains(figure, 1, -1.0, Coefficients.Rationals) must beEmpty
    }
  }

  "the harmonic chain barcode" should {
    "refuse coefficients in a finite field, whose chains have no inner product" in {
      val edge = explicit((0.0, Simplex(0)), (0.0, Simplex(1)), (1.0, Simplex(0, 1)))
      HarmonicChainBarcode(edge, maxDimension = 0, coefficients = Coefficients(17)) must
        throwWith[IllegalArgumentException]("inner product")
    }

    "refuse a degree the stream was not built for" in {
      val rips =
        VietorisRips(EuclideanMetricSpace(Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.0, 1.0))), maxDimension = 1)
      HarmonicChainBarcode(rips, maxDimension = 2) must throwWith[IllegalArgumentException]("maxDimension")
    }

    "refuse an order in which a cell comes before its face" in {
      HarmonicChainBarcode.ofOrder(
        Seq((Simplex(0, 1), 0.0), (Simplex(0), 0.0), (Simplex(1), 0.0)),
        maxDimension = 1
      ) must throwWith[IllegalArgumentException]("face")
    }
  }
