package org.appliedtopology.tda4j

import Persistence.Engine
import SpecThrows.throwWith
import ZpLinearAlgebra.{nullspace, rank}

import org.specs2.mutable.Specification

import scala.util.chaining.*

/** The map of persistent homology induced by a map of complexes. The oracle is the rank of the structure maps of the
  * image, kernel and cokernel modules, computed from the filtered complexes themselves by linear algebra over `Z/p`
  * (chains, boundaries and the chain map, nothing from the library): for `s <= t`, the number of bars of the image with
  * `birth <= s` and `t < death` is the rank of `f(H(X_s)) -> H(Y_t)`, and likewise for the kernel and the cokernel. The
  * ranks at all pairs of critical values determine the barcode.
  */
class PersistenceMapSpec extends Specification:

  import BruteForceComplexes.*

  /** A simplicial map by vertices: the image simplex with the sign of the permutation that sorts it, none if it
    * collapses. Written out again here, so that it is checked against the boundaries below rather than by the library.
    */
  private def push(vertexMap: Int => Int)(simplex: Simplex[Int]): Seq[(Simplex[Int], Int)] =
    val images = simplex.toList.map(vertexMap)
    if images.distinct.size < images.size then Seq.empty
    else
      val inversions = (for i <- images.indices; j <- i + 1 until images.size if images(i) > images(j) yield 1).size
      Seq((Simplex.from(images), if inversions % 2 == 0 then 1 else -1))

  /** The ranks of the structure maps `s -> t` of the image, kernel and cokernel of the map induced in degree `k`. */
  private def ranks(
    x: Complex,
    y: Complex,
    vertexMap: Int => Int,
    k: Int,
    p: Int
  )(s: Double, t: Double): (Int, Int, Int) =
    def upTo(c: Complex, dim: Int, v: Double) = ofDim(c, dim).filter(c(_) <= v)
    val (xk, yk) = (ofDim(x, k), ofDim(y, k))
    val xLower = ofDim(x, k - 1)
    val f = push(vertexMap)
    // f_# as a matrix in all the k-simplices; columns outside the level are left out by the caller.
    def sharp(from: Vector[Simplex[Int]]) = matrixOf(yk, from)(f)
    // The cycles of X at level v, as vectors over all the k-simplices of X.
    def xCycles(v: Double): Seq[Seq[Int]] =
      val inside = upTo(x, k, v)
      nullspace(matrixOf(xLower, inside)(boundary), inside.size, p).map(embed(_, inside, xk))
    // f_# of those, over all the k-simplices of Y.
    def pushedCycles(v: Double): Seq[Seq[Int]] =
      val pushed = sharp(xk)
      xCycles(v).map(z => pushed.map(row => row.zip(z).map((a, b) => a * b).sum))
    def boundariesOfY(v: Double): Seq[Seq[Int]] =
      upTo(y, k + 1, v).map(tau => matrixOf(yk, Vector(tau))(boundary).map(_.head))
    def boundariesOfX(v: Double): Seq[Seq[Int]] =
      upTo(x, k + 1, v).map(tau => matrixOf(xk, Vector(tau))(boundary).map(_.head))
    def yCycles(v: Double): Seq[Seq[Int]] =
      val inside = upTo(y, k, v)
      nullspace(matrixOf(ofDim(y, k - 1), inside)(boundary), inside.size, p).map(embed(_, inside, yk))

    val image = rank(pushedCycles(s) ++ boundariesOfY(t), p) - rank(boundariesOfY(t), p)
    // The kernel at level s: cycles z of X_s with f_# z a boundary of Y_s, from the nullspace of (boundary | f_# - boundary).
    val kernel =
      val xs = upTo(x, k, s)
      val ws = upTo(y, k + 1, s)
      val cycleRows = matrixOf(xLower, xs)(boundary).map(_ ++ Seq.fill(ws.size)(0))
      val pushRows = sharp(xs).zip(matrixOf(yk, ws)(boundary)).map((a, b) => a ++ b.map(c => (p - c % p) % p))
      val basis = nullspace(cycleRows ++ pushRows, xs.size + ws.size, p).map(_.take(xs.size)).map(embed(_, xs, xk))
      rank(basis ++ boundariesOfX(t), p) - rank(boundariesOfX(t), p)
    val killed = pushedCycles(t) ++ boundariesOfY(t)
    val cokernel = rank(yCycles(s) ++ killed, p) - rank(killed, p)
    (image, kernel, cokernel)

  private def countAlive(bars: Seq[PersistenceBar[Double, Nothing]], s: Double, t: Double): Int =
    bars.count(b => b.birth <= s && t < b.death)

  /** The first disagreement of the map's bars with the oracle's ranks, in degrees `0 to top`. */
  private def disagreement[S, T, C](
    map: PersistenceMap[S, T, C],
    x: Complex,
    y: Complex,
    vertexMap: Int => Int,
    p: Int,
    top: Int
  ): Option[String] =
    (for
      k <- 0 to top
      critical = (map.sourceBars(k) ++ map.targetBars(k)).flatMap(b => List(b.birth, b.death)).filterNot(_.isInfinite)
      times = if critical.isEmpty then Nil else critical.distinct.sorted :+ (critical.max + 1.0)
      s <- times
      t <- times
      if s <= t
      (expected, found, name) <-
        val (i, ker, cok) = ranks(x, y, vertexMap, k, p)(s, t)
        List(
          (i, countAlive(map.image(k), s, t), "image"),
          (ker, countAlive(map.kernel(k), s, t), "kernel"),
          (cok, countAlive(map.cokernel(k), s, t), "cokernel")
        )
      if expected != found
    yield s"degree $k, $name at s=$s, t=$t: the ranks say $expected, the bars $found").headOption

  private def noisyCircle(n: Int, seed: Long, radius: Double = 1.0, noise: Double = 0.15): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.tabulate(n)(i =>
      Array(
        radius * math.cos(2 * math.Pi * i / n) + noise * rng.nextGaussian(),
        radius * math.sin(2 * math.Pi * i / n) + noise * rng.nextGaussian()
      )
    )

  // The same `maxFiltrationValue` for the sample and its subsample, beyond every distance: the complexes are the whole
  // Vietoris-Rips complexes, which the oracle builds, and the subsample's is a subcomplex of the sample's at every value.
  private def diagramOf(points: Array[Array[Double]], p: Int, engine: Engine = Engine.Cohomology) =
    Persistence(
      points,
      maxDimension = 2,
      maxFiltrationValue = 100.0,
      engine = engine,
      characteristic = p,
      includeZeroLength = true
    )

  // The engines whose cycles die with their bars and which report zero-length bars (`ParallelRipser` does not).
  private val engines = List(Engine.Cohomology, Engine.Ripser)

  private val primes = List(2, 3, 17)

  "the map of an identity" should {
    "be the identity matrix, with the diagram itself as the image and nothing in the kernel and the cokernel" in {
      val points = noisyCircle(14, 1L)
      val failures = for
        p <- primes
        engine <- engines
        d = diagramOf(points, p, engine)
        map = PersistenceMap.inclusion(d, d)
        k <- 0 to 2
        problems = List(
          Option.when(map.entries(k).exists((i, j, c) => i != j))("not diagonal"),
          Option.when(map.entries(k).size != map.sourceBars(k).size)("not all ones"),
          Option.when(
            map.image(k).map(b => (b.birth, b.death)).sorted != map.sourceBars(k).map(b => (b.birth, b.death)).sorted
          )(
            "image is not the diagram"
          ),
          Option.when(map.kernel(k).nonEmpty)("kernel"),
          Option.when(map.cokernel(k).nonEmpty)("cokernel")
        ).flatten
        if problems.nonEmpty
      yield s"p=$p $engine degree $k: $problems"
      failures.take(3) must beEmpty
    }
  }

  "the inclusion of a boundary circle into a Mobius band" should {
    // The five-vertex Mobius band: the triangles i, i+1, i+2 (mod 5). Its boundary is the cycle of the edges {i, i+2},
    // which goes twice around the core circle of the edges {i, i+1}.
    def stream(cells: Iterable[Simplex[Int]]) =
      val builder = ExplicitStreamBuilder[Int, Double]()
      cells.foreach(c => builder += ((0.0, c)))
      builder.result()
    val vertices = (0 until 5).map(i => Simplex(i))
    val boundaryEdges = (0 until 5).map(i => Simplex(i, (i + 2) % 5))
    val allEdges = for a <- 0 until 5; b <- a + 1 until 5 yield Simplex(a, b)
    val triangles = (0 until 5).map(i => Simplex.from(List(i, (i + 1) % 5, (i + 2) % 5)))

    def run(p: Int) =
      val circle =
        Persistence(stream(vertices ++ boundaryEdges), maxDimension = 2, characteristic = p, includeZeroLength = true)
      val band = Persistence(
        stream(vertices ++ allEdges ++ triangles),
        maxDimension = 2,
        characteristic = p,
        includeZeroLength = true
      )
      PersistenceMap.inclusion(circle, band)

    "multiply the loop by 2: zero over Z/2, an isomorphism over Z/3 and Z/17" in {
      val (two, three, seventeen) = (run(2), run(3), run(17))
      val inf = Double.PositiveInfinity
      (two.entries(1) must beEmpty) and
        (two.kernel(1).map(_.toTriple) must beEqualTo(List((1, 0.0, inf)))) and
        (two.cokernel(1).map(_.toTriple) must beEqualTo(List((1, 0.0, inf)))) and
        (two.image(1) must beEmpty) and
        (three.entries(1).size must beEqualTo(1)) and
        (three.image(1).map(_.toTriple) must beEqualTo(List((1, 0.0, inf)))) and
        (three.kernel(1) must beEmpty) and
        (three.cokernel(1) must beEmpty) and
        (seventeen.entries(1).map((i, j, c) => math.abs(seventeen.target.coefficientField.toDouble(c))) must beEqualTo(
          List(2.0)
        )) and
        (seventeen.image(1).map(_.toTriple) must beEqualTo(List((1, 0.0, inf)))) and
        (seventeen.cokernel(1) must beEmpty)
    }

    "multiply the loop by exactly 2 over the rationals, an isomorphism" in {
      def over(stream: StratifiedCellStream[Simplex[Int], Double]) =
        Persistence(stream, maxDimension = 2, coefficients = Coefficients.Rationals, includeZeroLength = true)
      val map = PersistenceMap.inclusion(
        over(stream(vertices ++ boundaryEdges)),
        over(stream(vertices ++ allEdges ++ triangles))
      )
      val inf = Double.PositiveInfinity
      (map.entries(1).map((_, _, c) => c.toString).map(_.stripPrefix("-")) must beEqualTo(List("2"))) and
        (map.image(1).map(_.toTriple) must beEqualTo(List((1, 0.0, inf)))) and (map.kernel(1) must beEmpty) and
        (map.cokernel(1) must beEmpty)
    }

    "be the identity on the connected components over every field" in {
      primes.map(run).map(m => m.image(0).map(_.toTriple)) must beEqualTo(
        primes.map(_ => List((0, 0.0, Double.PositiveInfinity)))
      )
    }
  }

  "the inclusion of a subsample into the sample" should {
    "have the ranks of the structure maps as its image, kernel and cokernel, over Z/2, Z/3 and Z/17" in {
      val points = noisyCircle(12, 4L)
      val sub = points.take(8)
      val whole = rips(points, 3)
      val part = rips(sub, 3)
      val failures = for
        p <- primes
        engine <- engines
        map = PersistenceMap.inclusion(diagramOf(sub, p, engine), diagramOf(points, p, engine))
        problem <- disagreement(map, part, whole, identity, p, top = 1)
      yield s"p=$p $engine: $problem"
      failures must beEmpty
    }

    "have the ranks of the structure maps when the subsample is every other point, through a vertex map" in {
      val points = noisyCircle(12, 9L)
      val sub = Array.tabulate(6)(i => points(2 * i))
      val whole = rips(points, 3)
      val part = rips(sub, 3)
      val failures = for
        p <- primes
        engine <- engines
        map = PersistenceMap.simplicial(diagramOf(sub, p, engine), diagramOf(points, p, engine))(2 * _)
        problem <- disagreement(map, part, whole, 2 * _, p, top = 1)
      yield s"p=$p $engine: $problem"
      failures must beEmpty
    }

    "read the loop of the subsample as the image, and what the sample sees before it as the cokernel" in {
      val circle = noisyCircle(24, 2L, noise = 0.02)
      val sub = Array.tabulate(12)(i => circle(2 * i))
      val (subLoop, wholeLoop) = (diagramOf(sub, 17).dim(1).longest.get, diagramOf(circle, 17).dim(1).longest.get)
      val map = PersistenceMap.simplicial(diagramOf(sub, 17), diagramOf(circle, 17))(2 * _)
      // The loop of the sample is born at the chord of neighbours, half the subsample's, and both fill together.
      (wholeLoop.birth must beLessThan(subLoop.birth)) and
        (map.image(1).map(b => (b.birth, b.death)) must beEqualTo(List((subLoop.birth, wholeLoop.death)))) and
        (map.cokernel(1).map(b => (b.birth, b.death)) must beEqualTo(List((wholeLoop.birth, subLoop.birth)))) and
        (map.kernel(1).map(b => (b.birth, b.death)) must beEqualTo(
          if wholeLoop.death < subLoop.death then List((wholeLoop.death, subLoop.death)) else Nil
        ))
    }
  }

  "the inclusion of an octahedron into a larger sample" should {
    "have the ranks of the structure maps in degree 2, where the sphere is" in {
      val rng = new scala.util.Random(8L)
      val octahedron = for axis <- 0 until 3; sign <- List(-1.0, 1.0)
      yield Array.tabulate(3)(i => (if i == axis then sign else 0.0) + 0.05 * rng.nextGaussian())
      val extra =
        Array.fill(3)(Array.fill(3)(rng.nextGaussian()).pipe(v => v.map(_ / math.sqrt(v.map(x => x * x).sum))))
      val points = (octahedron ++ extra).toArray
      val sub = points.take(6)
      val (part, whole) = (rips(sub, 4), rips(points, 4))
      val failures = for
        p <- primes
        map = PersistenceMap.inclusion(diagramOf(sub, p), diagramOf(points, p))
        problem <- disagreement(map, part, whole, identity, p, top = 2)
      yield s"p=$p: $problem"
      (diagramOf(sub, 17).dim(2).bars.size must beGreaterThan(0)) and (failures must beEmpty)
    }
  }

  "a simplicial map that merges vertices" should {
    "have the ranks of the structure maps, for the projection of a ring with some points doubled to the plane, whichever way the target is numbered" in {
      // The projection forgets the height and does not increase distances; two points over the same place become one.
      // The target's vertices are numbered by a permutation, so the images of simplices are listed out of order and the
      // signs of the permutations that sort them are all different.
      val ring = List((0, 0), (1, 0), (2, 0), (2, 1), (2, 2), (1, 2), (0, 2), (0, 1))
      val rng = new scala.util.Random(5L)
      val above = ring.flatMap(c => List.fill(1 + rng.nextInt(2))(c))
      val points = above.map((a, b) => Array(a.toDouble, b.toDouble, 0.4 * rng.nextDouble())).toArray
      val x = rips(points, 3)
      val failures = for
        seed <- 0L until 3L
        number = new scala.util.Random(seed).shuffle(ring.indices.toList).toVector
        flat = Array.tabulate(ring.size)(v =>
          Array(ring(number.indexOf(v))._1.toDouble, ring(number.indexOf(v))._2.toDouble)
        )
        vertexMap = (i: Int) => number(ring.indexOf(above(i)))
        y = rips(flat, 3)
        p <- primes
        engine <- engines
        map = PersistenceMap.simplicial(diagramOf(points, p, engine), diagramOf(flat, p, engine))(vertexMap)
        problem <- disagreement(map, x, y, vertexMap, p, top = 1)
      yield s"numbering $seed, p=$p $engine: $problem"
      (above.size must beGreaterThan(ring.size)) and (failures must beEmpty)
    }

    "be a chain map in the oracle's own signs (the boundary of the image is the image of the boundary)" in {
      val ring = List((0, 0), (1, 0), (2, 0), (2, 1), (2, 2), (1, 2), (0, 2), (0, 1))
      val above = ring ++ ring.take(3)
      val x = rips(Array.tabulate(above.size)(i => Array(i.toDouble, 0.0)), 4)
      val number = new scala.util.Random(2L).shuffle(ring.indices.toList).toVector
      val vertexMap = (i: Int) => number(ring.indexOf(above(i)))
      val f = push(vertexMap)
      def imageOfBoundary(s: Simplex[Int]) = boundary(s).flatMap((face, sign) => f(face).map((c, n) => (c, sign * n)))
      def boundaryOfImage(s: Simplex[Int]) = f(s).flatMap((c, n) => boundary(c).map((face, sign) => (face, sign * n)))
      def total(chain: Seq[(Simplex[Int], Int)]) = chain.groupMapReduce(_._1)(_._2)(_ + _).filter(_._2 != 0)
      x.keys.filter(_.dim >= 1).filter(s => total(imageOfBoundary(s)) != total(boundaryOfImage(s))).toList must beEmpty
    }
  }

  "a map that is not a map of the diagrams" should {
    "be refused for the cycles of the Naive and Chunks engines, which stay classes after their bar ends" in {
      val points = noisyCircle(10, 3L)
      val refused =
        for engine <- List(Engine.Naive, Engine.Chunks)
        yield scala.util
          .Try(
            PersistenceMap.inclusion(diagramOf(points, 17, engine), diagramOf(points, 17, engine))
          )
          .failed
          .map(_.getMessage.contains("Engine.Cohomology"))
          .getOrElse(false)
      refused must beEqualTo(List(true, true))
    }

    "be refused for cocycles" in {
      val points = noisyCircle(10, 3L)
      val d =
        Persistence(points, maxDimension = 2, includeZeroLength = true, representatives = Representatives.Cocycles)
      PersistenceMap.inclusion(d, d) must throwWith[IllegalArgumentException]("carries cocycles")
    }

    "be refused when the two are over different fields, saying how to fix it" in {
      val points = noisyCircle(10, 3L)
      PersistenceMap.inclusion(diagramOf(points, 2), diagramOf(points, 3)) must throwWith[IllegalArgumentException](
        "same `characteristic`"
      )
    }

    "be refused when the cells of the source are not cells of the target" in {
      val points = noisyCircle(12, 4L)
      // The whole sample is not a subcomplex of its first eight points.
      PersistenceMap
        .inclusion(diagramOf(points, 17), diagramOf(points.take(8), 17)) must throwAn[IllegalArgumentException]
    }
  }

  "integer coefficients" should {
    "convert Int.MinValue correctly in a finite field" in {
      val points = Array(Array(0.0))
      val diagram = Persistence(points, maxDimension = 0, maxFiltrationValue = 2.0, characteristic = 17)
      val scaledIdentity = PersistenceMap(diagram, diagram)(cell => Seq((cell, Int.MinValue)))
      (scaledIdentity.entries(0).size must beEqualTo(1)) and
        (diagram.coefficientField.toDouble(scaledIdentity.entries(0).head._3) must beEqualTo(8.0))
    }
  }
end PersistenceMapSpec
