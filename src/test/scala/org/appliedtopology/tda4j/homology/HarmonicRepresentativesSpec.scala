package org.appliedtopology.tda4j

import BruteForceComplexes.boundary as simplexBoundary
import SpecThrows.throwWith

import org.specs2.mutable.Specification

import scala.util.Random

/** Harmonic representatives of the ordinary bars (Basu and Cox). The oracle writes the definition out in chain
  * coordinates over the rationals and shares nothing with the library: the harmonic chains `H_p(K_s)` as the kernel of
  * the stacked cycle and orthogonality conditions, the map `H_p(K_s) -> H_p(K_t)` as the projection along the
  * boundaries of `K_t` (normal equations), the subspaces `M^{s,t}`, `N^{s,t} = M^{s,t'}` (`t'` the value before `t`)
  * and `P^{s,t} = M^{s,t} ∩ (N^{s,t})^⊥` of Definition 3.10, for every pair of values; the bars are the pairs with a
  * nonzero `P`, and a bar's representatives must span exactly its `P`. The hand-derived expectations are the Figure 1
  * filtration of the harmonic chain barcode paper, whose ordinary bars are `[4, 9), [5, 10), [7, 8)`.
  */
class HarmonicRepresentativesSpec extends Specification:

  private type S = Simplex[Int]
  private type Q = Coefficients.Rational

  private def q(n: Long): Q = Coefficients.Rational(n)
  private def r(n: Long, d: Long): Q = Coefficients.Rational(n, d)
  private val exact = summon[Q is Field]
  private val zero = exact.zero

  private def explicit(items: (Double, S)*): StratifiedCellStream[S, Double] =
    val builder = ExplicitStreamBuilder[Int, Double]()
    items.foreach(builder += _)
    builder.result()

  private def universe(n: Int, size: Int): Vector[S] =
    (1 to size).flatMap(k => (0 until n).toList.combinations(k).map(Simplex.from(_))).toVector

  private def randomOrder(cells: Vector[S], length: Int, seed: Long): Vector[S] =
    val rng = new Random(seed)
    val inserted = scala.collection.mutable.LinkedHashSet.empty[S]
    while inserted.size < length do
      val ready = cells.filter(c => !inserted(c) && simplexBoundary(c).forall((f, _) => inserted(f)))
      inserted += ready(rng.nextInt(ready.size))
    inserted.toVector

  // --- the oracle ---

  private final class Oracle(cells: Vector[(S, Double)], p: Int):
    private val value = cells.toMap
    private val pCells = cells.map(_._1).filter(_.dim == p)
    private val width = pCells.size
    private val column = pCells.zipWithIndex.toMap
    val values: Vector[Double] = cells.map(_._2).distinct.sorted

    private def dot(a: Seq[Q], b: Seq[Q]): Q =
      a.zip(b).foldLeft(zero)((sum, xy) => exact.plus(sum, exact.times(xy._1, xy._2)))
    private def scale(c: Q, v: Seq[Q]): Vector[Q] = v.map(exact.times(c, _)).toVector
    private def minus(a: Seq[Q], b: Seq[Q]): Vector[Q] = a.zip(b).map((x, y) => exact.minus(x, y)).toVector
    private def isZero(v: Seq[Q]): Boolean = v.forall(exact.isEqual(_, zero))

    private def boundaryVector(t: S): Vector[Q] =
      val entries = simplexBoundary(t).flatMap((f, sign) => column.get(f).map(_ -> q(sign))).toMap
      Vector.tabulate(width)(i => entries.getOrElse(i, zero))

    private def cycleRows(v: Double): Seq[Seq[Q]] =
      val faces = cells.map(_._1).filter(_.dim == p - 1)
      val rowsOfCycle = faces.map(f =>
        pCells.map(c => q(simplexBoundary(c).collectFirst { case (g, sign) if g == f => sign.toLong }.getOrElse(0L)))
      )
      val outside = pCells.indices
        .filter(i => value(pCells(i)) > v)
        .map(i => Vector.tabulate(width)(j => if i == j then exact.one else zero))
      rowsOfCycle ++ outside

    private val harmonicCache = scala.collection.mutable.HashMap.empty[Double, Vector[Vector[Q]]]
    def harmonicBasis(v: Double): Vector[Vector[Q]] = harmonicCache.getOrElseUpdate(
      v, {
        val orthogonal = cells.filter(c => c._1.dim == p + 1 && c._2 <= v).map(c => boundaryVector(c._1))
        if width == 0 then Vector.empty else LinearAlgebra.nullspace[Q](cycleRows(v) ++ orthogonal, width)
      }
    )

    /** Projection of the chain `h` along the boundaries of `K_t`: `h - D x` with `D^T D x = D^T h`. */
    private def projectAway(t: Double)(h: Vector[Q]): Vector[Q] =
      val boundaries = cells.filter(c => c._1.dim == p + 1 && c._2 <= t).map(c => boundaryVector(c._1))
      if boundaries.isEmpty then h
      else
        val rows = boundaries.map(d => boundaries.map(dot(d, _)) :+ dot(d, h))
        val (reduced, pivots) = LinearAlgebra.rref[Q](rows)
        val x = scala.collection.mutable.Map.empty[Int, Q]
        for (row, pivot) <- reduced.zip(pivots) do x(pivot) = row.last
        val fitted = boundaries.indices.foldLeft(Vector.fill(width)(zero))((sum, k) =>
          x.get(k).fold(sum)(c => sum.zip(boundaries(k)).map((a, b) => exact.plus(a, exact.times(c, b))))
        )
        minus(h, fitted)

    private def combine(coordinates: Vector[Q], basis: Vector[Vector[Q]]): Vector[Q] =
      coordinates
        .zip(basis)
        .foldLeft(Vector.fill(width)(zero))((sum, cb) =>
          sum.zip(cb._2).map((a, b) => exact.plus(a, exact.times(cb._1, b)))
        )

    private def rowSpace(vectors: Seq[Seq[Q]]): Vector[Vector[Q]] =
      if vectors.isEmpty then Vector.empty else LinearAlgebra.rref[Q](vectors)._1

    /** `M^{s,t}`: coordinates in the harmonic basis at `s` of the chains whose image at `t` is the image of the
      * harmonic chains of the value before `s`.
      */
    def killedBy(s: Double, t: Double): Vector[Vector[Q]] =
      val hs = harmonicBasis(s)
      val before = values.filter(_ < s).lastOption.fold(Vector.empty[Vector[Q]])(harmonicBasis)
      if hs.isEmpty then Vector.empty
      else
        val image = hs.map(projectAway(t))
        val earlier = before.map(projectAway(t))
        val rows = (0 until width).map(i => image.map(_(i)) ++ earlier.map(v => exact.negate(v(i))))
        rowSpace(LinearAlgebra.nullspace[Q](rows, hs.size + before.size).map(_.take(hs.size)))

    /** `P^{s,t}` as chains: `M ∩ N^⊥` (`t = infinity`: the harmonic chains at `s` orthogonal to `M^{s,last}`). */
    def bar(s: Double, t: Double): Vector[Vector[Q]] =
      val hs = harmonicBasis(s)
      val (larger, smaller) =
        if t.isInfinity then
          (
            Vector.tabulate(hs.size)(i => Vector.tabulate(hs.size)(j => if i == j then exact.one else zero)),
            killedBy(s, values.last)
          )
        else (killedBy(s, t), killedBy(s, values.filter(_ < t).last))
      val largerChains = larger.map(combine(_, hs))
      val smallerChains = smaller.map(combine(_, hs))
      if largerChains.isEmpty then Vector.empty
      else
        val rows = smallerChains.map(n => largerChains.map(dot(_, n)))
        val combinations = LinearAlgebra.nullspace[Q](rows, largerChains.size)
        combinations.map(c => combine(c.toVector, largerChains))

    /** The bars of degree `p`: every pair `s < t` (or `t` infinite) and the dimension of its `P`. */
    def bars: Map[(Double, Double), Vector[Vector[Q]]] =
      (for
        s <- values
        t <- values.filter(_ > s) :+ Double.PositiveInfinity
        subspace = bar(s, t)
        if subspace.nonEmpty
      yield (s, t) -> subspace).toMap

    def spanEqual(a: Seq[Seq[Q]], b: Seq[Seq[Q]]): Boolean =
      def rank(vs: Seq[Seq[Q]]) = if vs.isEmpty then 0 else LinearAlgebra.rank[Q](vs)
      rank(a) == rank(b) && rank(a ++ b) == rank(a)

    def chain(representative: Chain[S, Q]): Vector[Q] =
      val entries = representative.terms.toMap
      Vector.tabulate(width)(i => entries.getOrElse(pCells(i), zero))

  private def diagramOf(stream: StratifiedCellStream[S, Double], maxDimension: Int): PersistenceDiagram.Of[S, Q] =
    HarmonicRepresentatives(stream, maxDimension, Coefficients.Rationals).asInstanceOf[PersistenceDiagram.Of[S, Q]]

  // The filtration of Figure 1: vertices a..e are 0..4; edges ac ad bd ab bc ae be enter at 1..7; abe, abd, abc at 8, 9, 10.
  private val figureCells: Seq[(Double, S)] =
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
    )

  "the harmonic representatives of the bars of Figure 1" should {
    val diagram = diagramOf(explicit(figureCells*), 1)
    def representative(birth: Double): Map[S, Q] =
      diagram.bars.find(b => b.dim == 1 && b.birth == birth).get.representative.terms.toMap

    "keep the ordinary bars [4,9), [5,10), [7,8)" in {
      diagram.bars.filter(_.dim == 1).map(_.toTriple).sorted must beEqualTo(
        List((1, 4.0, 9.0), (1, 5.0, 10.0), (1, 7.0, 8.0))
      )
    }

    "give the bar born at 4 the loop abd, the only cycle there" in {
      representative(4.0) must beEqualTo(Map(Simplex(0, 1) -> q(1), Simplex(1, 3) -> q(1), Simplex(0, 3) -> q(-1)))
    }

    "give the bar born at 5 the harmonic cycle of K_5 orthogonal to the loop abd, not the loop abc" in {
      // (ab + bc - ac) minus its projection onto abd, 1/3 of (ab + bd - ad), scaled to 1 on ab
      representative(5.0) must beEqualTo(
        Map(
          Simplex(0, 1) -> q(1),
          Simplex(1, 2) -> r(3, 2),
          Simplex(0, 2) -> r(-3, 2),
          Simplex(1, 3) -> r(-1, 2),
          Simplex(0, 3) -> r(1, 2)
        )
      )
    }

    "give the bar born at 7 the harmonic cycle of K_7 orthogonal to every cycle of K_6" in {
      // the loop abe minus its projection onto the span of abd and abc, which is 1/4 of their sum, scaled to 1 on ab
      representative(7.0) must beEqualTo(
        Map(
          Simplex(0, 1) -> q(1),
          Simplex(1, 4) -> q(2),
          Simplex(0, 4) -> q(-2),
          Simplex(1, 3) -> r(-1, 2),
          Simplex(0, 3) -> r(1, 2),
          Simplex(1, 2) -> r(-1, 2),
          Simplex(0, 2) -> r(1, 2)
        )
      )
    }
  }

  "the harmonic representatives of a random filtration" should {
    "span the subspace P^{s,t} of Definition 3.10 for every bar, which are the ordinary bars, in every degree" in {
      val cells = universe(5, 4)
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 10L, js = 2L)
        order = randomOrder(cells, 13 + (seed % 9).toInt, seed)
        filtered = order.zipWithIndex.map((s, i) => (s, i + 1.0))
        diagram = diagramOf(explicit(filtered.map((s, v) => (v, s))*), 2)
        p <- 0 to 2
        oracle = Oracle(filtered, p)
        expected = oracle.bars
        mine = diagram.bars.filter(_.dim == p).groupBy(b => (b.birth, b.death))
        if expected.keySet != mine.keySet || expected.exists((key, subspace) =>
          mine(key).size != subspace.size || !oracle
            .spanEqual(mine(key).map(b => oracle.chain(b.representative)), subspace)
        )
      yield s"seed $seed, degree $p: expected ${expected.map((k, v) => k -> v.size)}, found ${mine.map((k, v) =>
          k -> v.size
        )}"
      failures.take(2) must beEmpty
    }

    "span P^{s,t} for every bar when many cells enter at one value, whatever order the stream gives them" in {
      val cells = universe(5, 4)
      val failures = for
        seed <- 300L until 300L + TestPlatform.perPlatform(jvm = 12L, js = 2L)
        order = randomOrder(cells, 15 + (seed % 9).toInt, seed)
        // groups of three or four cells share a value: faces are in the same group or an earlier one
        filtered = order.zipWithIndex.map((s, i) => (s, (i / (3 + seed % 2)).toDouble + 1.0))
        diagram = diagramOf(explicit(filtered.map((s, v) => (v, s))*), 2)
        p <- 0 to 2
        oracle = Oracle(filtered, p)
        expected = oracle.bars
        mine = diagram.bars.filter(_.dim == p).groupBy(b => (b.birth, b.death))
        if expected.keySet != mine.keySet || expected.exists((key, subspace) =>
          mine(key).size != subspace.size || !oracle
            .spanEqual(mine(key).map(b => oracle.chain(b.representative)), subspace)
        )
      yield s"seed $seed, degree $p: expected ${expected.map((k, v) => k -> v.size)}, found ${mine.map((k, v) =>
          k -> v.size
        )}"
      failures.take(2) must beEmpty
    }

    "do the same on a point cloud, whose vertices enter in ties" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 3L, js = 1L)
        rng = new Random(seed)
        points = Array.fill(6)(Array(rng.nextDouble(), rng.nextDouble()))
        rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
        filtered = HarmonicChainBarcode.insertionOrder(rips, 1)
        diagram = diagramOf(rips, 1)
        p <- 0 to 1
        oracle = Oracle(filtered.toVector, p)
        expected = oracle.bars
        mine = diagram.bars.filter(_.dim == p).groupBy(b => (b.birth, b.death))
        if expected.keySet != mine.keySet || expected.exists((key, subspace) =>
          !oracle.spanEqual(mine(key).map(b => oracle.chain(b.representative)), subspace)
        )
      yield s"seed $seed, degree $p"
      failures.take(2) must beEmpty
    }

    "give every representative a cycle that is orthogonal to the boundaries at its bar's birth" in {
      val cells = universe(5, 4)
      val failures = for
        seed <- 50L until 50L + TestPlatform.perPlatform(jvm = 8L, js = 2L)
        order = randomOrder(cells, 14 + (seed % 8).toInt, seed)
        filtered = order.zipWithIndex.map((s, i) => (s, i + 1.0))
        diagram = diagramOf(explicit(filtered.map((s, v) => (v, s))*), 1)
        bar <- diagram.bars
        z = bar.representative.terms.toMap
        boundary = z.toSeq
          .flatMap((s, x) => simplexBoundary(s).map((f, sign) => f -> exact.times(x, q(sign))))
          .groupMapReduce(_._1)(_._2)(exact.plus)
        born = filtered.filter(_._2 <= bar.birth).map(_._1).toSet
        orthogonal = filtered
          .filter(c => c._1.dim == bar.dim + 1 && c._2 <= bar.birth)
          .map(c =>
            simplexBoundary(c._1).flatMap((f, sign) => z.get(f).map(exact.times(_, q(sign)))).foldLeft(zero)(exact.plus)
          )
        if !boundary.values.forall(exact.isEqual(_, zero)) || !z.keys.forall(born) || !orthogonal.forall(
          exact.isEqual(_, zero)
        )
      yield s"seed $seed: $bar"
      failures.take(2) must beEmpty
    }

    "agree between real and exact rational coefficients" in {
      val rng = new Random(11)
      val points = Array.fill(7)(Array(rng.nextDouble(), rng.nextDouble()))
      val rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
      val reals = HarmonicRepresentatives(rips, maxDimension = 1).asInstanceOf[PersistenceDiagram.Of[S, Double]]
      val rationals = HarmonicRepresentatives(rips, maxDimension = 1, coefficients = Coefficients.Rationals)
        .asInstanceOf[PersistenceDiagram.Of[S, Q]]
      val a = reals.bars
        .sortBy(b => (b.dim, b.birth, b.death))
        .map(b => (b.toTriple, b.representative.terms.toMap))
      val b = rationals.bars
        .sortBy(b => (b.dim, b.birth, b.death))
        .map(b => (b.toTriple, b.representative.terms.map((cell, x) => cell -> x.toDouble).toMap))
      (a.map(_._1) must beEqualTo(b.map(_._1))) and
        (a.zip(b)
          .forall((x, y) =>
            x._2.keySet == y._2.keySet && x._2.forall((k, v) => math.abs(v - y._2(k)) < 1e-6)
          ) must beTrue)
    }
  }

  "the harmonic representatives" should {
    "refuse coefficients in a finite field" in {
      val edge = explicit((0.0, Simplex(0)), (0.0, Simplex(1)), (1.0, Simplex(0, 1)))
      HarmonicRepresentatives(edge, maxDimension = 0, coefficients = Coefficients(17)) must
        throwWith[IllegalArgumentException]("inner product")
    }

    "refuse a diagram that is not the barcode of the stream" in {
      val edge = explicit((0.0, Simplex(0)), (0.0, Simplex(1)), (1.0, Simplex(0, 1)))
      val other = explicit((0.0, Simplex(0)), (0.0, Simplex(1)), (2.0, Simplex(0, 1)))
      val diagram = Persistence(other, maxDimension = 0, coefficients = Coefficients.Rationals)
      HarmonicRepresentatives.ofDiagram(edge, diagram, Coefficients.Rationals) must
        throwWith[IllegalArgumentException]("not a filtration value")
    }
  }
