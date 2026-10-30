package org.appliedtopology.tda4j

import LayeredInorderSimplexStream.{Candidates, FlagGenerator, Generator}

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.execute.Result

/** `LayeredInorderSimplexStream.FlagGenerator` builds the flag complex of any weighted graph, in the order of the
  * Vietoris-Rips generator: ascending value, equal values colexicographically descending. Checked against a listing of
  * every vertex subset (a simplex when all its pairs are edges and its largest weight is within every vertex's
  * deadline), against the distance-based [[Generator]] on a complete graph, and against
  * `EnumeratingCofaceSimplexStream` on the metric with `Infinity` for the missing edges. Weights are drawn from three
  * values so that ties are the rule.
  */
class FlagGeneratorSpec extends Specification with ScalaCheck:
  private val topDimension = 4

  private type Listing = Vector[Vector[(Simplex[Int], Double)]]

  // The dimensions 1 .. topDimension of a generator, as `(simplex, value)` in its order.
  private def listing(g: FlagGenerator): Listing =
    Iterator
      .iterate(g.edges)(g.cofacets)
      .take(topDimension)
      .map(l => Vector.tabulate(l.size)(i => (l.simplex(i), g.diameter(l, i))))
      .toVector

  // Every subset of `0 until n` that is a simplex of the flag complex, by value and then colex descending.
  private def bruteForce(
    n: Int,
    weight: (Int, Int) => Double,
    threshold: Double,
    deadline: Int => Double
  ): Listing =
    // The larger top vertex first, then the next one down.
    def colexDescending(x: Simplex[Int], y: Simplex[Int]): Boolean =
      val (xs, ys) = (x.underlying.toArray, y.underlying.toArray)
      val at = (xs.length - 1 to 0 by -1).find(i => xs(i) != ys(i))
      at.exists(i => xs(i) > ys(i))
    val all = (0 until n).toList.toSet.subsets().filter(s => s.size >= 2 && s.size <= topDimension + 1).toVector
    val simplices = for
      s <- all
      pairs = for a <- s.toList; b <- s.toList if a < b yield weight(a, b)
      if pairs.forall(w => w.isFinite && w <= threshold)
      value = pairs.max
      if s.forall(v => deadline(v) >= value)
    yield (Simplex.from(s.toList.sorted), value)
    Vector.tabulate(topDimension) { d =>
      simplices
        .filter(_._1.dim == d + 1)
        .sortWith((x, y) => if x._2 != y._2 then x._2 < y._2 else colexDescending(x._1, y._1))
    }

  private def firstDifference(a: Listing, b: Listing): Option[String] =
    Option.when(a != b) {
      val d = a.indices.find(d => a(d) != b(d)).getOrElse(math.min(a.size, b.size))
      val (x, y) = (a.lift(d).getOrElse(Vector.empty), b.lift(d).getOrElse(Vector.empty))
      val i = x.indices.find(i => i >= y.size || x(i) != y(i)).getOrElse(y.size)
      s"dimension ${d + 1}: sizes ${x.size} and ${y.size}, first difference at $i: ${x.lift(i)} vs ${y.lift(i)}"
    }

  // A random graph on `n` vertices: each pair is an edge with probability one half, with a weight among 1, 2, 3.
  private val graphGen: Gen[(Int, Map[(Int, Int), Double])] =
    for
      n <- Gen.chooseNum(4, 9)
      pairs = for b <- 1 until n; a <- 0 until b yield (a, b)
      present <- Gen.listOfN(pairs.size, Gen.frequency(1 -> Gen.const(0.0), 1 -> Gen.oneOf(1.0, 2.0, 3.0)))
    yield (n, pairs.zip(present).filter(_._2 > 0.0).toMap)

  private def edgeArrays(graph: Map[(Int, Int), Double]): (Array[Int], Array[Int], Array[Double]) =
    val es = graph.toVector
    (es.map(_._1._1).toArray, es.map(_._1._2).toArray, es.map(_._2).toArray)

  private def weightOf(graph: Map[(Int, Int), Double])(a: Int, b: Int): Double =
    graph.getOrElse((math.min(a, b), math.max(a, b)), Double.PositiveInfinity)

  private def generator(n: Int, graph: Map[(Int, Int), Double], threshold: Double, c: Candidates): FlagGenerator =
    val (a, b, w) = edgeArrays(graph)
    FlagGenerator.ofEdges(n, a, b, w, threshold, c)

  "FlagGenerator" should {
    "list the flag complex of a weighted graph, cell for cell, value for value and in order" in
      forAll(graphGen, Gen.oneOf(Candidates.values.toSeq)) { case ((n, graph), c) =>
        Result.foreach(Seq(Double.PositiveInfinity, 2.0)) { threshold =>
          val expected = bruteForce(n, weightOf(graph), threshold, _ => Double.PositiveInfinity)
          firstDifference(listing(generator(n, graph, threshold, c)), expected) must beNone
        }
      }.set(minTestsOk = TestPlatform.propertyCases(60))

    "agree with the Vietoris-Rips generator on the complete graph, however the edges are listed" in
      forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(5, 10)), Gen.long) { (pts, seed) =>
        val space = EuclideanMetricSpace(pts)
        val n = pts.length
        val pairs = new scala.util.Random(seed).shuffle(for b <- 1 until n; a <- 0 until b yield (a, b))
        // Half of the pairs listed with their ends the other way round.
        val flipped = pairs.zipWithIndex.map((p, i) => if i % 2 == 0 then p else (p._2, p._1))
        val g = FlagGenerator.ofEdges(
          n,
          flipped.map(_._1).toArray,
          flipped.map(_._2).toArray,
          flipped.map((a, b) => space.distance(a, b)).toArray,
          Double.PositiveInfinity
        )
        firstDifference(listing(g), listing(Generator(n, space.distance(_, _), Double.PositiveInfinity))) must beNone
      }.set(minTestsOk = TestPlatform.propertyCases(30))

    "agree with EnumeratingCofaceSimplexStream on the metric that has infinity for a missing edge" in
      forAll(graphGen) { case (n, graph) =>
        val space = ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => if i == j then 0.0 else weightOf(graph)(i, j)))
        val reference = EnumeratingCofaceSimplexStream(space, maxFiltrationValue = Some(3.0))
        val g = generator(n, graph, 3.0, Candidates.Bitsets)
        val layers = listing(g)
        Result.foreach(1 to math.min(topDimension, n - 1)) { d =>
          val expected = reference.iterateDimension
            .applyOrElse(d, (_: Int) => Iterator.empty)
            .toVector
            .map(s => (s, reference.filtrationValue(s)))
          layers(d - 1) must beEqualTo(expected)
        }
      }.set(minTestsOk = TestPlatform.propertyCases(40))

    // Sheehy's sparse Rips complex: a vertex leaves the nerve after its deadline, a simplex of value `v` needs every
    // vertex alive at `v`, and its faces are not removed.
    "leave out a simplex whose value is past the deadline of one of its vertices" in
      forAll(
        graphGen,
        Gen.listOfN(9, Gen.oneOf(0.5, 1.0, 2.0, 3.0, Double.PositiveInfinity)),
        Gen.oneOf(Candidates.values.toSeq)
      ) { case ((n, graph), deadlines, c) =>
        val deadline = deadlines.take(n).toArray
        val (a, b, w) = edgeArrays(graph)
        val g = FlagGenerator.ofEdges(n, a, b, w, Double.PositiveInfinity, c, deadline)
        val expected = bruteForce(n, weightOf(graph), Double.PositiveInfinity, deadline(_))
        firstDifference(listing(g), expected) must beNone
      }.set(minTestsOk = TestPlatform.propertyCases(60))

    "refuse a loop, a repeated edge, an end out of range and a weight that is not a number" in {
      def build(a: Int, b: Int, w: Double, twice: Boolean = false) =
        val as = if twice then Array(a, b) else Array(a)
        val bs = if twice then Array(b, a) else Array(b)
        FlagGenerator.ofEdges(4, as, bs, Array.fill(as.length)(w), 1.0)
      (build(1, 1, 0.5) must throwAn[IllegalArgumentException])
        .and(build(0, 4, 0.5) must throwAn[IllegalArgumentException])
        .and(build(0, 1, 0.5, twice = true) must throwAn[IllegalArgumentException])
        .and(build(0, 1, Double.NaN) must throwAn[IllegalArgumentException])
    }
  }
