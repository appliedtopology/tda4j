package org.appliedtopology.tda4j

import BruteForceComplexes.*
import Persistence.Engine
import ZpLinearAlgebra.{nullspace, rank}

import org.specs2.mutable.Specification

/** Relative persistent homology: the stream of a pair `(X, A)`. Oracles: the pair (disc, circle) and the pair (X,
  * point) with known answers; and the rank of the structure maps of `H(X_s, A_s) -> H(X_t, A_t)`, computed from the
  * relative chain groups by linear algebra over `Z/p`, at every pair of critical values.
  */
class RelativeSpec extends Specification:

  private val infinity = Double.PositiveInfinity

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  private def triples(d: PersistenceDiagram[?]) = d.triples.sorted

  private val engines = List(Engine.Cohomology, Engine.Naive, Engine.Chunks)

  "the pair (disc, circle)" should {
    // A pentagon at value 0, and the cone over it (a vertex, five spokes, five triangles) at value 1.
    val vertices = (0 until 5).map(i => Simplex(i))
    val pentagon = (0 until 5).map(i => Simplex.from(List(i, (i + 1) % 5)))
    val disc =
      val builder = ExplicitStreamBuilder[Int, Double]()
      (vertices ++ pentagon).foreach(c => builder += ((0.0, c)))
      builder += ((1.0, Simplex(5)))
      (0 until 5).foreach(i => builder += ((1.0, Simplex(i, 5))))
      (0 until 5).foreach(i => builder += ((1.0, Simplex.from(List(i, (i + 1) % 5, 5)))))
      builder.result()
    val circle = (vertices ++ pentagon).toSet

    "have one class in degree 2 from the moment the disc enters, over every field, and nothing else" in {
      val failures = for
        p <- List(2, 3, 17)
        engine <- engines
        found = triples(Persistence(Relative(disc, circle), maxDimension = 2, characteristic = p, engine = engine))
        if found != List((2, 1.0, infinity))
      yield s"p=$p $engine: $found"
      failures must beEmpty
    }

    "have the homology of the disc relative to nothing when nothing is removed, and no cells when everything is" in {
      val all = triples(Persistence(Relative(disc, (_: Simplex[Int]) => false), maxDimension = 2, characteristic = 17))
      val none = Relative(disc, (_: Simplex[Int]) => true).iterator.toList
      (all must beEqualTo(triples(Persistence(disc, maxDimension = 2, characteristic = 17)))) and (none must beEmpty)
    }
  }

  "the pair (Vietoris-Rips complex, a vertex)" should {
    "be the reduced homology: the essential class of degree 0 is gone, and the finite bars and the higher degrees stay" in {
      // The classes of degree 0 are the components other than the vertex's, and all the points are born at 0, so the
      // dimension drops once at each edge of the minimum spanning tree: the same deaths as without the vertex.
      val rips = VietorisRips(EuclideanMetricSpace(cloud(11, 3L)), maxDimension = 2, maxFiltrationValue = 100.0)
      val failures = for
        engine <- engines
        p <- List(2, 3)
        relative = Persistence(Relative.ofVertices(rips, Set(4)), maxDimension = 2, characteristic = p, engine = engine)
        absolute = Persistence(rips, maxDimension = 2, characteristic = p, engine = engine)
        problem <- List(
          Option.when(triples(relative.dim(0)) != triples(absolute.dim(0)).filter(_._3 != infinity))("degree 0"),
          Option.when(relative.bettiNumbers.head != 0)("a degree 0 class survives"),
          Option.when((1 to 2).exists(k => triples(relative.dim(k)) != triples(absolute.dim(k))))("degrees 1, 2 differ")
        ).flatten
      yield s"$engine p=$p: $problem"
      failures must beEmpty
    }
  }

  "a pair of Rips complexes" should {
    "have the ranks of the structure maps as its barcode, over Z/2 and Z/3, in degrees 0, 1 and 2" in {
      val failures = for
        seed <- List(6L, 7L)
        p <- List(2, 3)
        engine <- engines
        problem <- disagreement(cloud(8, seed), Set(0, 3, 5), p, engine)
      yield s"seed $seed p=$p $engine: $problem"
      failures must beEmpty
    }
  }

  /** The first disagreement of the library's barcode of the pair (Rips complex of `points`, its subcomplex on `subset`)
    * with the ranks of `H(X_s, A_s) -> H(X_t, A_t)`, over every pair of filtration values of the complex.
    */
  private def disagreement(points: Array[Array[Double]], subset: Set[Int], p: Int, engine: Engine): Option[String] =
    val x = rips(points, 4)
    def inA(simplex: Simplex[Int]) = simplex.toList.forall(subset)
    def relativeCells(k: Int) = ofDim(x, k).filterNot(inA)
    def boundaryOutsideA(simplex: Simplex[Int]) = boundary(simplex).filterNot((face, _) => inA(face))
    val library = Persistence(
      Relative
        .ofVertices(VietorisRips(EuclideanMetricSpace(points), maxDimension = 2, maxFiltrationValue = 100.0), subset),
      maxDimension = 2,
      characteristic = p,
      engine = engine
    )
    val values = x.values.toList.distinct.sorted
    (for
      k <- 0 to 2
      cells = relativeCells(k)
      cycles = values.map { v =>
        val inside = cells.filter(x(_) <= v)
        v -> nullspace(matrixOf(relativeCells(k - 1), inside)(boundaryOutsideA), inside.size, p)
          .map(embed(_, inside, cells))
      }.toMap
      boundaries = values.map { v =>
        v -> relativeCells(k + 1)
          .filter(x(_) <= v)
          .map(tau => matrixOf(cells, Vector(tau))(boundaryOutsideA).map(_.head))
      }.toMap
      bars = library.dim(k).bars
      s <- values
      t <- values
      if s <= t
      expected = rank(cycles(s) ++ boundaries(t), p) - rank(boundaries(t), p)
      found = bars.count(b => b.birth <= s && t < b.death)
      if expected != found
    yield s"degree $k at s=$s, t=$t: the ranks say $expected, the bars $found").headOption
end RelativeSpec
