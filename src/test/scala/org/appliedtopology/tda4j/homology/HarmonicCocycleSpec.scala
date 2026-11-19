package org.appliedtopology.tda4j

import BruteForceComplexes.boundary as simplexBoundary
import SpecThrows.throwWith

import org.specs2.mutable.Specification

import scala.util.Random

/** Harmonic cocycles as the representatives of the ordinary bars (`Representatives.Cocycles`). The oracle is the dual
  * module written out in cochain coordinates over the rationals, sharing nothing with the library: the harmonic
  * cochains of `K_u` as the cocycles of `K_u` (rows `<∂τ, c> = 0` for the `(p+1)`-cells `τ` of `K_u`) that are
  * orthogonal to its coboundaries (rows `<c, δ e_f> = 0` for the `(p-1)`-cells `f`), the map from the level `u` to a
  * lower level `v` as the restriction of a cochain to the cells of `K_v` followed by the projection along the
  * coboundaries of `K_v` (normal equations), and Basu and Cox's `M`, `N`, `P` with the levels reversed: a bar `[s, t)`
  * lives at the levels `s .. top` (`top` the last value before `t`), is born at `top`, and the classes at `top` that
  * die at the level below `s` are those whose restriction there is the restriction of a class of the value `t`.
  *
  * Two hand-derived examples fix the values: a triangle `abc` at value 1 and a second loop `abd` at 2 closed by a
  * triangle at 3. The long bar's cycle is the plain loop `abc`, and its cocycle is that loop tilted away from `abd`;
  * for the short bar it is the other way round, so a spec that returned cycles for cocycles fails them.
  */
class HarmonicCocycleSpec extends Specification:

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

  private final class CoOracle(cells: Vector[(S, Double)], p: Int):
    private val value = cells.toMap
    private val pCells = cells.map(_._1).filter(_.dim == p)
    private val width = pCells.size
    val values: Vector[Double] = cells.map(_._2).distinct.sorted

    private def dot(a: Seq[Q], b: Seq[Q]): Q =
      a.zip(b).foldLeft(zero)((sum, xy) => exact.plus(sum, exact.times(xy._1, xy._2)))
    private def minus(a: Seq[Q], b: Seq[Q]): Vector[Q] = a.zip(b).map((x, y) => exact.minus(x, y)).toVector

    private def sign(sigma: S, f: S): Q =
      q(simplexBoundary(sigma).collectFirst { case (g, sg) if g == f => sg.toLong }.getOrElse(0L))

    /** `σ -> <∂τ, e_σ>`: the row that says the cochain `c` has `(δc)(τ) = 0`. */
    private def cocycleRow(tau: S): Vector[Q] = pCells.map(sigma => sign(tau, sigma)).toVector

    /** The coboundary `δ e_f` of the `(p-1)`-cell `f`, seen on the cells of `K_s`. */
    private def coboundary(f: S, s: Double): Vector[Q] =
      pCells.map(sigma => if value(sigma) <= s then sign(sigma, f) else zero).toVector

    private def lowerCells = cells.map(_._1).filter(_.dim == p - 1)

    private val harmonicCache = scala.collection.mutable.HashMap.empty[Double, Vector[Vector[Q]]]

    /** A basis of the harmonic cochains of `K_u`: cocycles of `K_u` orthogonal to its coboundaries. */
    def harmonicBasis(u: Double): Vector[Vector[Q]] = harmonicCache.getOrElseUpdate(
      u, {
        val cocycle = cells.filter(c => c._1.dim == p + 1 && c._2 <= u).map(c => cocycleRow(c._1))
        val orthogonal = lowerCells.filter(f => value(f) <= u).map(f => coboundary(f, u))
        val outside = pCells.indices
          .filter(i => value(pCells(i)) > u)
          .map(i => Vector.tabulate(width)(j => if i == j then exact.one else zero))
        if width == 0 then Vector.empty else LinearAlgebra.nullspace[Q](cocycle ++ orthogonal ++ outside, width)
      }
    )

    /** The class of `c` at the level `v`: `c` restricted to the cells of `K_v`, minus its projection onto the
      * coboundaries of `K_v` (`D^T D x = D^T c`).
      */
    private def restrict(v: Double)(c: Vector[Q]): Vector[Q] =
      val inside = pCells.indices.map(i => if value(pCells(i)) <= v then c(i) else zero).toVector
      val coboundaries = lowerCells.filter(f => value(f) <= v).map(f => coboundary(f, v))
      if coboundaries.isEmpty then inside
      else
        val rows = coboundaries.map(d => coboundaries.map(dot(d, _)) :+ dot(d, inside))
        val (reduced, pivots) = LinearAlgebra.rref[Q](rows)
        val x = scala.collection.mutable.Map.empty[Int, Q]
        for (row, pivot) <- reduced.zip(pivots) do x(pivot) = row.last
        val fitted = coboundaries.indices.foldLeft(Vector.fill(width)(zero))((sum, k) =>
          x.get(k).fold(sum)(a => sum.zip(coboundaries(k)).map((s, b) => exact.plus(s, exact.times(a, b))))
        )
        minus(inside, fitted)

    private def combine(coordinates: Vector[Q], basis: Vector[Vector[Q]]): Vector[Q] =
      coordinates
        .zip(basis)
        .foldLeft(Vector.fill(width)(zero))((sum, cb) =>
          sum.zip(cb._2).map((a, b) => exact.plus(a, exact.times(cb._1, b)))
        )

    private def rowSpace(vectors: Seq[Seq[Q]]): Vector[Vector[Q]] =
      if vectors.isEmpty then Vector.empty else LinearAlgebra.rref[Q](vectors)._1

    /** Coordinates in the harmonic basis at `top` of the classes whose image at the level `v` is the image of a class
      * of the harmonic cochains at `above` (none: of zero).
      */
    private def killedBy(top: Double, above: Option[Double], v: Double): Vector[Vector[Q]] =
      val ht = harmonicBasis(top)
      val later = above.fold(Vector.empty[Vector[Q]])(harmonicBasis)
      if ht.isEmpty then Vector.empty
      else
        val image = ht.map(restrict(v))
        val fromAbove = later.map(restrict(v))
        val rows = (0 until width).map(i => image.map(_(i)) ++ fromAbove.map(w => exact.negate(w(i))))
        rowSpace(LinearAlgebra.nullspace[Q](rows, ht.size + later.size).map(_.take(ht.size)))

    /** The harmonic cocycles of the bar `[s, t)`: born at `top`, the last value before `t`; `larger ∩ smaller^⊥`. */
    def bar(s: Double, t: Double): Vector[Vector[Q]] =
      val top = if t.isInfinity then values.last else values.filter(_ < t).last
      val above = if t.isInfinity then None else Some(t)
      val ht = harmonicBasis(top)
      val index = values.indexOf(s)
      val (larger, smaller) =
        if index == 0 then
          (
            Vector.tabulate(ht.size)(i => Vector.tabulate(ht.size)(j => if i == j then exact.one else zero)),
            killedBy(top, above, values.head)
          )
        else (killedBy(top, above, values(index - 1)), killedBy(top, above, s))
      val largerCochains = larger.map(combine(_, ht))
      val smallerCochains = smaller.map(combine(_, ht))
      if largerCochains.isEmpty then Vector.empty
      else
        val rows = smallerCochains.map(n => largerCochains.map(dot(_, n)))
        LinearAlgebra
          .nullspace[Q](rows, largerCochains.size)
          .map(c => combine(c.toVector, largerCochains))

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

  private def cocycles(
    stream: StratifiedCellStream[S, Double],
    maxDimension: Int
  ): PersistenceDiagram.Of[S, Q] =
    HarmonicRepresentatives(stream, maxDimension, Coefficients.Rationals, Representatives.Cocycles)
      .asInstanceOf[PersistenceDiagram.Of[S, Q]]

  private def cycles(stream: StratifiedCellStream[S, Double], maxDimension: Int): PersistenceDiagram.Of[S, Q] =
    HarmonicRepresentatives(stream, maxDimension, Coefficients.Rationals).asInstanceOf[PersistenceDiagram.Of[S, Q]]

  // Vertices a, b, c, d = 0..3 at 0; the loop abc at 1; the edges ad, bd (a second loop abd) at 2; the triangle abd at 3.
  private val twoLoops: Seq[(Double, S)] =
    List(0, 1, 2, 3).map(v => (0.0, Simplex(v))) ++ List(
      (1.0, Simplex(0, 1)),
      (1.0, Simplex(1, 2)),
      (1.0, Simplex(0, 2)),
      (2.0, Simplex(0, 3)),
      (2.0, Simplex(1, 3)),
      (3.0, Simplex(0, 1, 3))
    )

  private def representative(d: PersistenceDiagram.Of[S, Q], birth: Double): Map[S, Q] =
    d.bars.find(b => b.dim == 1 && b.birth == birth).get.representative.terms.toMap

  "the harmonic cocycles of two loops, the second closed by a triangle" should {
    // lazy: an example that breaks the construction fails alone, and the random ones still say whether they see it
    lazy val dual = cocycles(explicit(twoLoops*), 1)
    lazy val primal = cycles(explicit(twoLoops*), 1)

    "keep the ordinary bars [1, infinity) and [2, 3), and mark the diagram as cocycles" in {
      (dual.bars.filter(_.dim == 1).map(_.toTriple).sorted must beEqualTo(
        List((1, 1.0, Double.PositiveInfinity), (1, 2.0, 3.0))
      )) and (dual.representatives must beSome[Representatives](Representatives.Cocycles))
    }

    "give the long bar the loop abc tilted away from abd: the harmonic cochain of the last level" in {
      // abc - 1/3 abd, scaled to 1 on ab
      representative(dual, 1.0) must beEqualTo(
        Map(
          Simplex(0, 1) -> q(1),
          Simplex(1, 2) -> r(3, 2),
          Simplex(0, 2) -> r(-3, 2),
          Simplex(1, 3) -> r(-1, 2),
          Simplex(0, 3) -> r(1, 2)
        )
      )
    }

    "give the short bar the loop abd itself, the cycle of the long bar being the plain loop abc" in {
      (representative(dual, 2.0) must beEqualTo(
        Map(Simplex(0, 1) -> q(1), Simplex(1, 3) -> q(1), Simplex(0, 3) -> q(-1))
      )) and (representative(primal, 1.0) must beEqualTo(
        Map(Simplex(0, 1) -> q(1), Simplex(1, 2) -> q(1), Simplex(0, 2) -> q(-1))
      ))
    }
  }

  "the harmonic cocycles of a random filtration" should {
    def failuresOf(filtered: Vector[(S, Double)], maxDimension: Int, label: String): Seq[String] =
      val diagram = cocycles(explicit(filtered.map((s, v) => (v, s))*), maxDimension)
      for
        p <- 0 to maxDimension
        oracle = CoOracle(filtered, p)
        expected = oracle.bars
        mine = diagram.bars.filter(_.dim == p).groupBy(b => (b.birth, b.death))
        if expected.keySet != mine.keySet || expected.exists((key, subspace) =>
          mine(key).size != subspace.size || !oracle
            .spanEqual(mine(key).map(b => oracle.chain(b.representative)), subspace)
        )
      yield s"$label, degree $p: expected ${expected.map((k, v) => k -> v.size)}, found ${mine.map((k, v) => k -> v.size)}"

    "span the dual subspace P of every bar, which are the ordinary bars, in every degree" in {
      val cells = universe(5, 4)
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 10L, js = 2L)
        order = randomOrder(cells, 13 + (seed % 9).toInt, seed)
        filtered = order.zipWithIndex.map((s, i) => (s, i + 1.0))
        message <- failuresOf(filtered, 2, s"seed $seed")
      yield message
      failures.take(2) must beEmpty
    }

    "span the dual subspace of every bar when many cells enter at one value, whatever order the stream gives them" in {
      val cells = universe(5, 4)
      val failures = for
        seed <- 300L until 300L + TestPlatform.perPlatform(jvm = 12L, js = 2L)
        order = randomOrder(cells, 15 + (seed % 9).toInt, seed)
        filtered = order.zipWithIndex.map((s, i) => (s, (i / (3 + seed % 2)).toDouble + 1.0))
        message <- failuresOf(filtered, 2, s"seed $seed")
      yield message
      failures.take(2) must beEmpty
    }

    "do the same on a point cloud, whose vertices enter in ties" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 3L, js = 1L)
        rng = new Random(seed)
        points = Array.fill(6)(Array(rng.nextDouble(), rng.nextDouble()))
        rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
        filtered = HarmonicChainBarcode.insertionOrder(rips, 1).toVector
        diagram = cocycles(rips, 1)
        p <- 0 to 1
        oracle = CoOracle(filtered, p)
        expected = oracle.bars
        mine = diagram.bars.filter(_.dim == p).groupBy(b => (b.birth, b.death))
        if expected.keySet != mine.keySet || expected.exists((key, subspace) =>
          !oracle.spanEqual(mine(key).map(b => oracle.chain(b.representative)), subspace)
        )
      yield s"seed $seed, degree $p"
      failures.take(2) must beEmpty
    }

    "give every representative a cocycle orthogonal to the coboundaries, at the last value of its bar" in {
      val cells = universe(5, 4)
      val failures = for
        seed <- 50L until 50L + TestPlatform.perPlatform(jvm = 8L, js = 2L)
        order = randomOrder(cells, 14 + (seed % 8).toInt, seed)
        filtered = order.zipWithIndex.map((s, i) => (s, i + 1.0))
        diagram = cocycles(explicit(filtered.map((s, v) => (v, s))*), 2)
        bar <- diagram.bars
        top = if bar.death.isPosInfinity then filtered.last._2 else filtered.map(_._2).filter(_ < bar.death).max
        c = bar.representative.terms.toMap
        // (delta c)(tau) = sum over the faces of tau of the sign times c
        coboundary = filtered
          .filter(x => x._1.dim == bar.dim + 1 && x._2 <= top)
          .map(x =>
            simplexBoundary(x._1).flatMap((f, sign) => c.get(f).map(exact.times(_, q(sign)))).foldLeft(zero)(exact.plus)
          )
        // (boundary c)(f): c orthogonal to every coboundary of a cochain of K_top
        boundary = c.toSeq
          .flatMap((s, x) => simplexBoundary(s).map((f, sign) => f -> exact.times(x, q(sign))))
          .groupMapReduce(_._1)(_._2)(exact.plus)
        born = filtered.filter(_._2 <= top).map(_._1).toSet
        if !coboundary.forall(exact.isEqual(_, zero)) || !boundary.values.forall(exact.isEqual(_, zero)) ||
          !c.keys.forall(born)
      yield s"seed $seed: $bar"
      failures.take(2) must beEmpty
    }

    "agree between real and exact rational coefficients" in {
      val rng = new Random(11)
      val points = Array.fill(7)(Array(rng.nextDouble(), rng.nextDouble()))
      val rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 1, maxFiltrationValue = 100.0)
      val reals = HarmonicRepresentatives(rips, maxDimension = 1, representatives = Representatives.Cocycles)
        .asInstanceOf[PersistenceDiagram.Of[S, Double]]
      val rationals = cocycles(rips, 1)
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

  "the harmonic representatives of a diagram with a bar of length zero" should {
    "be refused with a message that says the bar is not a class of the module" in {
      val stream = explicit((0.0, Simplex(0)), (1.0, Simplex(1)), (1.0, Simplex(0, 1)))
      val diagram = Persistence(
        stream,
        maxDimension = 0,
        coefficients = Coefficients.Rationals,
        includeZeroLength = true
      )
      (diagram.bars.exists(_.isZeroLength) must beTrue) and
        (HarmonicRepresentatives.ofDiagram(stream, diagram, Coefficients.Rationals) must
          throwWith[IllegalArgumentException]("length zero")) and
        (HarmonicRepresentatives.ofDiagram(
          stream,
          diagram,
          Coefficients.Rationals,
          Representatives.Cocycles
        ) must throwWith[IllegalArgumentException]("length zero"))
    }
  }
