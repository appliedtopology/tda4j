package org.appliedtopology.tda4j

import ZigzagStep.{Add, Remove}
import Persistence.Engine
import SpecThrows.throwWith

import org.specs2.mutable.Specification

import scala.collection.mutable
import scala.util.Random

/** Zigzag persistence. Oracles that share no code with the decomposition: the number of bars alive at each position is
  * the Betti number of that complex (rank and nullity of boundary matrices built here); the number of bars containing
  * `[i, j]` is the rank of the map from the limit to the colimit of the homology on that stretch (the generalized rank
  * invariant, computed from the chain complexes alone), and bars are recovered from it by inclusion and exclusion; a
  * zigzag with every arrow forward is an ordinary filtration, and the same complexes read backwards give the mirrored
  * barcode.
  */
class ZigzagPersistenceSpec extends Specification:

  import ZigzagOracle.*

  // every simplex on `n` vertices of dimension at most `top`, faces before cofaces
  private def universe(n: Int, top: Int): Vector[S] =
    (1 to top + 1).flatMap(size => (0 until n).toList.combinations(size).map(Simplex.from(_))).toVector

  /** A random zigzag: at each step a cell enters whose facets are there or a cell leaves that nothing there contains.
    */
  private def randomZigzag(cells: Vector[S], length: Int, seed: Long, start: Set[S] = Set.empty) =
    val rng = new Random(seed)
    val current = mutable.Set.from(start)
    val complexes = Vector.newBuilder[Set[S]]
    complexes += current.toSet
    val steps = Vector.newBuilder[ZigzagStep[S]]
    for _ <- 0 until length do
      val adds = cells.filter(s => !current(s) && facesOf(s).forall(current))
      val covered = current.flatMap(facesOf)
      val removes = current.toVector.filterNot(covered)
      if adds.nonEmpty && (removes.isEmpty || rng.nextDouble() < 0.55) then
        val s = adds(rng.nextInt(adds.size))
        current += s
        steps += Add(s)
      else
        val s = removes(rng.nextInt(removes.size))
        current -= s
        steps += Remove(s)
      complexes += current.toSet
    (steps.result(), complexes.result())

  /** The bars as (degree, birth, last position it is alive at) with `n` the last position. */
  private def intervals(diagram: PersistenceDiagram[?], n: Int): List[(Int, Int, Int)] =
    diagram.bars
      .map(b => (b.dim, b.birth.toInt, if b.death.isPosInfinity then n else b.death.toInt - 1))
      .sorted

  private val cells5 = universe(5, 3)

  "the number of bars alive at a position" should {
    "be the Betti number of the complex there, in every degree, over Z/2, Z/3 and Z/5, for random zigzags" in {
      val failures = for
        seed <- (1L to 12L).toList
        p <- List(2, 3, 5)
        (steps, complexes) = randomZigzag(cells5, 24, seed)
        diagram = ZigzagPersistence(steps, characteristic = p)
        bars = intervals(diagram, steps.size)
        (complex, position) <- complexes.zipWithIndex
        k <- 0 to 2
        alive = bars.count((dim, b, d) => dim == k && b <= position && position <= d)
        if alive != betti(complex, k, p)
      yield s"seed $seed p=$p position $position degree $k: $alive bars, Betti number ${betti(complex, k, p)}"
      failures.take(3) must beEmpty
    }
  }

  "the bars of a zigzag" should {
    "be those of the generalized rank invariant, by inclusion and exclusion, on random zigzags with arrows both ways" in {
      val failures = for
        seed <- (1L to 10L).toList
        p <- List(3)
        (steps, complexes) = randomZigzag(universe(4, 3), 7, 100 + seed)
        n = steps.size
        found = intervals(ZigzagPersistence(steps, characteristic = p), n)
        k <- 0 to 2
        expectedBars = multiplicities(complexes, k, p)
        b <- 0 to n
        d <- b to n
        expected = expectedBars((b, d))
        actual = found.count(_ == ((k, b, d)))
        if expected != actual
      yield s"seed $seed degree $k [$b, $d]: $actual bars, rank invariant says $expected"
      failures.take(3) must beEmpty
    }

    "be the ordinary barcode when every arrow goes forward" in {
      val failures = for
        seed <- (1L to 10L).toList
        p <- List(2, 3, 5)
        order = growOnly(cells5, seed)
        diagram = ZigzagPersistence(order.map(Add(_)), characteristic = p)
        builder = ExplicitStreamBuilder[Int, Double]()
        _ = order.zipWithIndex.foreach((s, idx) => builder += ((idx + 1.0, s)))
        ordinary = Persistence(builder.result(), maxDimension = 2, characteristic = p, engine = Engine.Naive)
        if intervals(diagram, order.size) != ordinary.bars
          .map(b => (b.dim, b.birth.toInt, if b.death.isPosInfinity then order.size else b.death.toInt - 1))
          .sorted
      yield s"seed $seed p=$p"
      failures must beEmpty
    }

    "be mirrored when the sequence of complexes is read backwards" in {
      val failures = for
        seed <- (20L to 34L).toList
        p <- List(2, 3)
        (steps, complexes) = randomZigzag(cells5, 20, seed)
        n = steps.size
        forwards = intervals(ZigzagPersistence(steps, characteristic = p), n)
        reversed = reverseSteps(steps)
        backwards = intervals(ZigzagPersistence(reversed, initial = complexes.last, characteristic = p), n)
        mirrored = forwards.map((k, b, d) => (k, n - d, n - b)).sorted
        if mirrored != backwards
      yield s"seed $seed p=$p"
      failures must beEmpty
    }

    "be the same for a list of nested complexes as for the steps that make them" in {
      val (steps, complexes) = randomZigzag(cells5, 18, 77L)
      intervals(ZigzagPersistence.ofComplexes(complexes, characteristic = 3), steps.size) must
        beEqualTo(intervals(ZigzagPersistence(steps, characteristic = 3), steps.size))
    }

    "have a representative at its birth that is a cycle of the complex there, and over the rationals the same bars" in {
      val problems = for
        seed <- (40L to 49L).toList
        (steps, complexes) = randomZigzag(cells5, 16, seed)
        problem <- representativeProblems(steps, complexes)
      yield s"seed $seed: $problem"
      val sameAsFp = for
        seed <- (40L to 44L).toList
        (steps, _) = randomZigzag(cells5, 16, seed)
        if intervals(ZigzagPersistence(steps, coefficients = Coefficients.Rationals), steps.size) !=
          intervals(ZigzagPersistence(steps, characteristic = 101), steps.size)
      yield seed
      (problems must beEmpty) and (sameAsFp must beEmpty)
    }
  }

  "a few zigzags worked by hand" should {
    val (v0, v1, v2) = (Simplex(0), Simplex(1), Simplex(2))
    val (e01, e12, e02) = (Simplex(0, 1), Simplex(1, 2), Simplex(0, 2))
    val triangle = Simplex(0, 1, 2)

    "close a loop and open it again by removing an edge" in {
      val steps = Seq(Add(v0), Add(v1), Add(v2), Add(e01), Add(e12), Add(e02), Remove(e02))
      // the younger vertex dies with each edge, the loop is born with the third edge and is gone with it
      intervals(ZigzagPersistence(steps), steps.size) must beEqualTo(
        List((0, 1, 7), (0, 2, 3), (0, 3, 4), (1, 6, 6))
      )
    }

    "give a class born by a map going backward: the loop that appears when a triangle's face is removed" in {
      val filled = Seq(v0, v1, v2, e01, e12, e02, triangle)
      val diagram = ZigzagPersistence(Seq(Remove(triangle)), initial = filled)
      // H_0: one class throughout; H_1: nothing at position 0, a loop at position 1
      intervals(diagram, 1) must beEqualTo(List((0, 0, 1), (1, 1, 1)))
    }

    "see the loop in the union of two paths, and nowhere else" in {
      val first = Seq(v0, v1, v2, e01, e12)
      val second = Seq(v0, v1, v2, e12, e02)
      val diagram = ZigzagPersistence.ofComplexes(Seq(first, second), link = ZigzagPersistence.Link.Union)
      // positions: the first path, the triangle outline, the second path
      (diagram.bars.filter(_.dim == 1).map(b => (b.birth, b.death)) must beEqualTo(List((1.0, 2.0)))) and
        (diagram.bars.count(_.dim == 0) must beEqualTo(1))
    }

    "refuse what is not a zigzag of complexes, and say what to do" in {
      val twoPaths = Seq(Seq(v0, v1, v2, e01), Seq(v0, v1, v2, e12))
      (ZigzagPersistence(Seq(Add(e01))) must throwAn[IllegalArgumentException]) and // facets missing
        (ZigzagPersistence(Seq(Remove(v0)), initial = Seq(v0, v1, e01)) must throwAn[IllegalArgumentException]) and
        (ZigzagPersistence(Seq(Add(v0), Add(v0))) must throwAn[IllegalArgumentException]) and
        (ZigzagPersistence.ofComplexes(twoPaths) must throwWith[IllegalArgumentException]("Link\\.Union"))
    }
  }

  private def reverseSteps(steps: Seq[ZigzagStep[S]]): Seq[ZigzagStep[S]] =
    steps.reverse.map:
      case Add(s)    => Remove(s)
      case Remove(s) => Add(s)

  /** What is wrong with the representatives of the bars over the rationals: not a cycle, not in the complex at the
    * birth.
    */
  private def representativeProblems(steps: Seq[ZigzagStep[S]], complexes: Vector[Set[S]]): List[String] =
    val diagram = ZigzagPersistence(steps, coefficients = Coefficients.Rationals)
    import diagram.given
    val fr = summon[diagram.Coefficient is Field]
    val simplices = summon[S is OrderedCell]
    for
      bar <- diagram.bars
      chain = bar.representative
      image = mutable.HashMap.empty[S, diagram.Coefficient]
      _ =
        for (cell, k) <- chain.terms; (face, k2) <- simplices.boundary(cell)[diagram.Coefficient] do
          image(face) = fr.plus(image.getOrElse(face, fr.zero), fr.times(k, k2))
      problem <- List(
        Option.when(chain.terms.isEmpty)("empty"),
        Option.when(image.values.exists(v => !fr.isEqual(v, fr.zero)))("not a cycle"),
        Option
          .when(!chain.terms.forall((cell, _) => complexes(bar.birth.toInt)(cell)))("outside the complex at its birth")
      ).flatten
    yield s"bar of degree ${bar.dim} from ${bar.birth}: $problem"

  /** A random order of the cells that is a filtration: each cell after its facets. */
  private def growOnly(cells: Vector[S], seed: Long): Vector[S] =
    val rng = new Random(seed)
    val current = mutable.Set.empty[S]
    val order = Vector.newBuilder[S]
    while current.size < cells.size do
      val ready = cells.filter(s => !current(s) && facesOf(s).forall(current))
      val s = ready(rng.nextInt(ready.size))
      current += s
      order += s
    order.result()
end ZigzagPersistenceSpec

/** The oracles, outside the specification: specs2 brings implicit conversions into a `Specification` that get in the
  * way of plain arithmetic on sequences.
  */
private object ZigzagOracle:
  type S = Simplex[Int]

  def facesOf(s: S): List[S] =
    if s.dim == 0 then Nil else s.toList.indices.map(i => Simplex.from(s.toList.patch(i, Nil, 1))).toList

  def key(s: S): Long = s.toList.foldLeft(0L)((a, v) => a * 10 + v) * 10 + s.dim

  def boundaryRows(cellsOfDimension: Seq[S], lower: Seq[S], p: Int): Seq[Seq[Int]] =
    val column = lower.zipWithIndex.toMap
    cellsOfDimension.map { s =>
      val row = Array.fill(lower.size)(0)
      for (f, i) <- facesOf(s).zipWithIndex do row(column(f)) = ((if i % 2 == 0 then 1 else -1) + p) % p
      row.toSeq
    }

  def betti(complex: Set[S], k: Int, p: Int): Int =
    def ofDim(d: Int) = complex.filter(_.dim == d).toVector.sortBy(key)
    def rankOfBoundary(d: Int) = if d == 0 then 0 else ZpLinearAlgebra.rank(boundaryRows(ofDim(d), ofDim(d - 1), p), p)
    ofDim(k).size - rankOfBoundary(k) - rankOfBoundary(k + 1)

  /** The number of bars of a zigzag of homology of degree `k` that contain the positions `i..j`: the rank of the map
    * from the limit to the colimit of the diagram `H_k(K_i), ..., H_k(K_j)`, worked out on cycles and boundaries as
    * subspaces of the k-chains of all the complexes together.
    */
  def rankInvariant(complexes: Vector[Set[S]], k: Int, i: Int, j: Int, p: Int): Int =
    val everything = complexes.flatten.distinct
    val kCells = everything.filter(_.dim == k).sortBy(key)
    val lowerCells = everything.filter(_.dim == k - 1).sortBy(key)
    val m = kCells.size
    val blocks = j - i + 1
    def embedded(x: Seq[Int], columns: Seq[S]): Seq[Int] =
      val v = Array.fill(m)(0)
      for (c, value) <- columns.zip(x) do v(kCells.indexOf(c)) = value
      v.toSeq
    def cycles(complex: Set[S]): Seq[Seq[Int]] =
      val columns = kCells.filter(complex)
      val matrix = boundaryRows(columns, lowerCells, p).transpose
      val rows = if matrix.isEmpty then Seq.empty[Seq[Int]] else matrix
      ZpLinearAlgebra.nullspace(rows, columns.size, p).map(embedded(_, columns))
    def boundaries(complex: Set[S]): Seq[Seq[Int]] =
      val column = kCells.zipWithIndex.toMap
      everything.filter(s => s.dim == k + 1 && complex(s)).map { s =>
        val v = Array.fill(m)(0)
        for (f, idx) <- facesOf(s).zipWithIndex do v(column(f)) = ((if idx % 2 == 0 then 1 else -1) + p) % p
        v.toSeq
      }
    def place(block: Int, x: Seq[Int]): Seq[Int] =
      Seq.fill((block - i) * m)(0) ++ x ++ Seq.fill((j - block) * m)(0)
    def negate(x: Seq[Int]) = x.map(v => (p - v) % p)
    val arrows = (i until j).map { l =>
      val (a, b) = (complexes(l), complexes(l + 1))
      val (small, large) = if a.subsetOf(b) then (a, b) else (b, a)
      (l, small, large)
    }
    val z = (i to j).map(l => cycles(complexes(l)))
    val bLarge = arrows.map((_, _, large) => boundaries(large))
    val columns = z.map(_.size) ++ bLarge.map(_.size)
    val width = columns.sum
    // unknowns: coefficients on a basis of the cycles at each position, then on a basis of the boundaries of the larger
    // complex of each arrow; the equations say that two neighbouring cycles agree in the larger complex's homology
    val alphaStart = z.scanLeft(0)(_ + _.size)
    val betaStart = bLarge.scanLeft(alphaStart.last)(_ + _.size)
    val equations = for
      ((l, _, _), a) <- arrows.zipWithIndex
      t <- 0 until m
    yield
      val row = Array.fill(width)(0)
      for (c, idx) <- z(l + 1 - i).zipWithIndex do row(alphaStart(l + 1 - i) + idx) = c(t)
      for (c, idx) <- z(l - i).zipWithIndex do row(alphaStart(l - i) + idx) = (p - c(t)) % p
      for (c, idx) <- bLarge(a).zipWithIndex do row(betaStart(a) + idx) = (p - c(t)) % p
      row.toSeq
    val solutions = ZpLinearAlgebra.nullspace(equations, width, p)
    // an element of the limit goes to the colimit through any one of its components; the first is as good as another
    val limit = solutions.map { x =>
      val parts = z(0).zipWithIndex.map((c, idx) => c.map(_ * x(alphaStart(0) + idx) % p))
      place(i, if parts.isEmpty then Seq.fill(m)(0) else parts.transpose.map(_.sum % p))
    }
    val relations =
      arrows.flatMap((l, small, _) =>
        cycles(small).map(x => place(l + 1, x).zip(negate(place(l, x))).map((u, v) => (u + v) % p))
      ) ++ (i to j).flatMap(l => boundaries(complexes(l)).map(place(l, _)))
    require(limit.forall(_.length == blocks * m) && relations.forall(_.length == blocks * m))
    ZpLinearAlgebra.rank(limit ++ relations, p) - ZpLinearAlgebra.rank(relations, p)

  /** For every `b <= d` the number of bars `[b, d]` of degree `k`, by inclusion and exclusion on the ranks. */
  def multiplicities(complexes: Vector[Set[S]], k: Int, p: Int): Map[(Int, Int), Int] =
    val n = complexes.size - 1
    val ranks = (for i <- 0 to n; j <- i to n yield (i, j) -> rankInvariant(complexes, k, i, j, p)).toMap
    def r(i: Int, j: Int) = if i < 0 || j > n then 0 else ranks((i, j))
    (for b <- 0 to n; d <- b to n yield (b, d) -> (r(b, d) - r(b - 1, d) - r(b, d + 1) + r(b - 1, d + 1))).toMap

end ZigzagOracle
