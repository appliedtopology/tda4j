package org.appliedtopology.tda4j

import BruteForceComplexes.*
import SpecThrows.throwWith

import org.specs2.mutable.Specification

/** The persistent Laplacian of a simplicial map (Gülen, Mémoli, Wan and Wang). Oracles, none of which goes through the
  * code under test: the worked example of the paper (a map that merges two edges, so that the weights matter; the
  * matrices of its Examples 13 to 17 are written in the basis of the chains, ours in the orthonormal one, the two
  * related by the weights), the persistent Laplacian of an inclusion, and the rank of the map on homology from the
  * boundary matrices of both complexes over `Z/32003`, which the dimension of the kernel must equal.
  */
class PersistentLaplacianMapSpec extends Specification:

  private val root2 = math.sqrt(2.0)

  private def entries(m: DenseMatrix): Seq[Seq[Double]] = m.toArray.toSeq.map(_.toSeq)

  private def close(a: Seq[Seq[Double]], b: Seq[Seq[Double]], tolerance: Double = 1e-9): Boolean =
    a.size == b.size && a
      .zip(b)
      .forall((x, y) => x.size == y.size && x.zip(y).forall((u, v) => math.abs(u - v) <= tolerance))

  // The paper's Figure 2: K has the vertices a, b, c, d = 0, 1, 2, 3 and the edges ab, bc, ac, ad, bd; L has x, y, z =
  // 0, 1, 2 and the edges xy, yz, xz (in that order, as in the paper) with the triangle xyz; the map is a -> x,
  // b -> y, c -> z, d -> y, so that ab and ad both go to xy, and bd is killed. (The paper's text says d -> b; its
  // matrices need d -> y, which is also the only choice that sends ad into L.)
  private val sourceOfPaper: Seq[Simplex[Int]] =
    Seq(Simplex(0), Simplex(1), Simplex(2), Simplex(3)) ++
      Seq(Simplex(0, 1), Simplex(1, 2), Simplex(0, 2), Simplex(0, 3), Simplex(1, 3))
  private val hollow: Seq[Simplex[Int]] =
    Seq(Simplex(0), Simplex(1), Simplex(2), Simplex(0, 1), Simplex(1, 2), Simplex(0, 2))
  private val filled: Seq[Simplex[Int]] = hollow :+ Simplex(0, 1, 2)
  private val foldD: Int => Int = Map(0 -> 0, 1 -> 1, 2 -> 2, 3 -> 1)

  "the persistent Laplacian of a simplicial map" should {
    "reproduce the matrix of the paper's example: the down and up parts of a map that merges two edges" in {
      // Remark 16: [Delta_1] = [[7/2, 0, 1], [0, 5/2, 0], [1/2, 0, 3]] in the basis [xy], [yz], [xz], where [xy] has
      // the weight 2. In the orthonormal basis the entry (i, j) is multiplied by sqrt(w_j / w_i): symmetric.
      val laplacian = PersistentLaplacian.ofMap(sourceOfPaper, filled, dimension = 1)(foldD)
      val expected = Seq(Seq(3.5, 0.0, 1 / root2), Seq(0.0, 2.5, 0.0), Seq(1 / root2, 0.0, 3.0))
      (close(entries(laplacian.matrix), expected) must beTrue).setMessage(entries(laplacian.matrix).toString)
      (laplacian.weights must beEqualTo(Vector(2.0, 1.0, 1.0))) and
        (laplacian.cells must beEqualTo(Vector(Simplex(0, 1), Simplex(1, 2), Simplex(0, 2)))) and
        (laplacian.bettiNumber must beEqualTo(0)) and
        (laplacian.kernel must beEmpty) and
        // det of the paper's matrix is 25, the product of the eigenvalues
        (laplacian.eigenvalues.product must beCloseTo(25.0, 1e-7))
    }

    "reproduce the kernel of the paper's second example: the hollow triangle has the cycle [xy] + [yz] - [xz]" in {
      // Example 17: without the triangle in L the up part is zero, and the kernel of the down matrix is (1, 1, -1)
      val laplacian = PersistentLaplacian.ofMap(sourceOfPaper, hollow, dimension = 1)(foldD)
      val down = Seq(
        Seq(3.0, -1 / root2, root2),
        Seq(-1 / root2, 1.5, 1.0),
        Seq(root2, 1.0, 2.0)
      )
      (close(entries(laplacian.matrix), down) must beTrue).setMessage(entries(laplacian.matrix).toString)
      val kernel = laplacian.kernel
      (kernel.size must beEqualTo(1)) and
        (laplacian.bettiNumber must beEqualTo(1)) and {
          // the chain is (1, 1, -1) up to a factor; first entry positive
          val c = kernel.head.terms.toMap
          val (xy, yz, xz) = (c(Simplex(0, 1)), c(Simplex(1, 2)), c(Simplex(0, 2)))
          (xy must beGreaterThan(0.0)) and
            (yz must beCloseTo(xy, 1e-9)) and
            (xz must beCloseTo(-xy, 1e-9)) and
            // orthonormal in the weighted inner product: |c|^2 = sum of c(tau)^2 / w(tau)
            ((xy * xy / 2 + yz * yz + xz * xz) must beCloseTo(1.0, 1e-9))
        }
    }

    "send the cycle of the fold to the loop of the target and nothing else: the rank of the map on homology" in {
      // H_1(K) has rank 2 (five edges on four vertices), H_1(hollow) = 1, H_1(filled) = 0; the vertices are all connected
      val onFilled = PersistentLaplacian.ofMap(sourceOfPaper, filled, dimension = 0)(foldD)
      val onHollow = PersistentLaplacian.ofMap(sourceOfPaper, hollow, dimension = 0)(foldD)
      (onFilled.bettiNumber must beEqualTo(1)) and (onHollow.bettiNumber must beEqualTo(1))
    }

    "be the persistent Laplacian of the stream for an inclusion, entry for entry" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 6L, js = 2L)
        points = cloud(6, seed)
        rips = VietorisRips(EuclideanMetricSpace(points), maxDimension = 2, maxFiltrationValue = 100.0)
        k <- 0 to 1
        (s, t) <- Seq((0.3, 0.3), (0.3, 0.6), (0.5, 0.9), (0.2, 100.0))
        expected = PersistentLaplacian(rips, k, s, t)
        found = PersistentLaplacian.ofMap(rips, s, rips, t, k)(identity[Int])
        if !(found.cells == expected.cells && close(entries(found.matrix), entries(expected.matrix)))
      yield s"seed $seed, dimension $k, from $s to $t"
      failures.take(3) must beEmpty
    }

    "have a kernel as large as the rank of the map on homology, which the boundary matrices count over Z/32003" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 120L, js = 12L)
        q <- 0 to 1
        instance = randomMap(seed)
        found = PersistentLaplacian.ofMap(instance.source, instance.target, q)(instance.vertexMap)
        rank = homologyRank(instance, q)
        if found.bettiNumber != rank
      yield s"seed $seed, dimension $q: kernel ${found.bettiNumber}, rank of the map $rank"
      failures.take(3) must beEmpty
    }

    "have a kernel inside the image of the cycles, and no part of it outside the cells the map hits" in {
      val failures = for
        seed <- 0L until TestPlatform.perPlatform(jvm = 60L, js = 8L)
        instance = randomMap(seed)
        found = PersistentLaplacian.ofMap(instance.source, instance.target, 1)(instance.vertexMap)
        problem <- kernelProblem(instance, found).toSeq
      yield s"seed $seed: $problem"
      failures.take(3) must beEmpty
    }

    "refuse a map that is not simplicial, naming the simplex that has no image" in {
      // the edge ac of the paper's K goes to xz, but the target here lacks that edge
      val target = Seq(Simplex(0), Simplex(1), Simplex(2), Simplex(0, 1), Simplex(1, 2))
      PersistentLaplacian.ofMap(sourceOfPaper, target, dimension = 1)(foldD) must
        throwWith[IllegalArgumentException]("simplicial map")
    }

    "refuse a complex that lacks a face, and a negative dimension" in {
      val noVertices = Seq(Simplex(0, 1))
      (PersistentLaplacian.ofMap(noVertices, noVertices, dimension = 1)(identity[Int]) must
        throwWith[IllegalArgumentException]("face")) and
        (PersistentLaplacian.ofMap(hollow, hollow, dimension = -1)(identity[Int]) must
          throwWith[IllegalArgumentException]("negative"))
    }

    "have no cells when the map hits nothing of that dimension: a map of an edge onto a point has an empty Laplacian in degree 1" in {
      val point = Seq(Simplex(0))
      val edge = Seq(Simplex(0), Simplex(1), Simplex(0, 1))
      val laplacian = PersistentLaplacian.ofMap(edge, point, dimension = 1)(_ => 0)
      (laplacian.cells must beEmpty) and (laplacian.bettiNumber must beEqualTo(0)) and (laplacian.kernel must beEmpty)
    }

    "give the eigenvalues 0, 2, 3 for the square folded onto the triangle, as the user guide says" in {
      // the vertex 3 goes to 0: the side 3-0 is squeezed, the side 2-3 lands on 0-2 reversed (numpy, from the definition)
      val square = Seq(Simplex(0), Simplex(1), Simplex(2), Simplex(3)) ++
        Seq(Simplex(0, 1), Simplex(1, 2), Simplex(2, 3), Simplex(0, 3))
      val laplacian = PersistentLaplacian.ofMap(square, hollow, dimension = 1)(v => if v == 3 then 0 else v)
      (laplacian.eigenvalues.map(x => math.round(x * 1e9) / 1e9) must beEqualTo(Vector(0.0, 2.0, 3.0))) and
        (laplacian.bettiNumber must beEqualTo(1)) and
        (laplacian.cells must beEqualTo(Vector(Simplex(0, 1), Simplex(1, 2), Simplex(0, 2)))) and
        (laplacian.weights must beEqualTo(Vector(1.0, 1.0, 1.0)))
    }

    "not let a triangle count that no cycle of the source maps to: three separate edges onto the sides of a triangle" in {
      // The sides xy, yz, xz are images of three disjoint edges, so the chain xy + yz - xz is the image of a chain of
      // the source but of no cycle of it. Its boundary condition for the triangle is `d(xyz) in f(cycles)`, which fails,
      // so the up part is zero and the matrix is 2 on every side (an edge's down Laplacian). A construction that asked
      // only for the boundary to lie in the image of the chains would let the triangle add its own rank-one term.
      val disjoint = (0 until 6).map(Simplex(_)) ++ Seq(Simplex(0, 1), Simplex(2, 3), Simplex(4, 5))
      val laplacian =
        PersistentLaplacian.ofMap(disjoint, filled, dimension = 1)(Map(0 -> 0, 1 -> 1, 2 -> 1, 3 -> 2, 4 -> 0, 5 -> 2))
      val twoEachSide = Seq(Seq(2.0, 0.0, 0.0), Seq(0.0, 2.0, 0.0), Seq(0.0, 0.0, 2.0))
      (close(entries(laplacian.matrix), twoEachSide) must beTrue).setMessage(entries(laplacian.matrix).toString) and
        (laplacian.bettiNumber must beEqualTo(0))
    }

    "give the 1x1 matrix [3] for a path folded onto an edge: the squeezed chain and its reversed image count once" in {
      val path = Seq(Simplex(0), Simplex(1), Simplex(2), Simplex(0, 1), Simplex(1, 2))
      val edge = Seq(Simplex(0), Simplex(1), Simplex(0, 1))
      val laplacian = PersistentLaplacian.ofMap(path, edge, dimension = 1)(v => if v == 2 then 0 else v)
      (close(entries(laplacian.matrix), Seq(Seq(3.0))) must beTrue) and (laplacian.weights must beEqualTo(
        Vector(2.0)
      )) and
        (laplacian.bettiNumber must beEqualTo(0))
    }

    "give one class for a segment folded onto a point" in {
      val edge = Seq(Simplex(0), Simplex(1), Simplex(0, 1))
      val laplacian = PersistentLaplacian.ofMap(edge, Seq(Simplex(0)), dimension = 0)(_ => 0)
      (laplacian.weights must beEqualTo(Vector(2.0))) and
        (close(entries(laplacian.matrix), Seq(Seq(0.0))) must beTrue) and
        (laplacian.bettiNumber must beEqualTo(1))
    }
  }

  private def cloud(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble()))

  /** A simplicial map between complexes that the test builds by hand: the Vietoris-Rips complex of random points, a
    * random vertex map, and the target the closure of the images together with a few simplices of its own.
    */
  private case class Instance(source: Vector[Simplex[Int]], target: Vector[Simplex[Int]], images: Vector[Int]):
    def vertexMap: Int => Int = images(_)

  private def randomMap(seed: Long): Instance =
    val rng = new scala.util.Random(1000L + seed)
    val n = 5 + rng.nextInt(3)
    val points = cloud(n, 2000L + seed)
    val radius = 0.35 + 0.25 * rng.nextDouble()
    val source = rips(points, 3).filter((_, value) => value <= radius).keys.toVector
    val targetVertices = 3 + rng.nextInt(3)
    val images = Vector.fill(n)(rng.nextInt(targetVertices))
    val generators =
      source.map(simplex => simplex.toList.map(images).toSet) ++
        Seq.fill(2)(rng.shuffle((0 until targetVertices).toList).take(3).toSet)
    val faces = generators.flatMap(g => (1 to g.size).flatMap(g.subsets(_))).toSet
    val target = faces.map(vertices => Simplex.from(vertices.toList.sorted)).toVector.sortBy(_.toList.mkString(","))
    Instance(source.sortBy(_.toList.mkString(",")), target, images)

  private def signOfSorting(images: List[Int]): Int =
    // bubble sort: the parity of the number of swaps, counted by swapping, not by counting inversions
    val a = images.toArray
    var swaps = 0
    for i <- a.indices; j <- 0 until a.length - 1 - i if a(j) > a(j + 1) do
      val t = a(j); a(j) = a(j + 1); a(j + 1) = t
      swaps += 1
    if swaps % 2 == 0 then 1 else -1

  /** The chain map of the instance on the simplices of dimension `d`: image simplex and sign, none if degenerate. */
  private def chainMap(instance: Instance)(simplex: Simplex[Int]): Seq[(Simplex[Int], Int)] =
    val images = simplex.toList.map(instance.images)
    if images.distinct.size < images.size then Seq.empty
    else Seq((Simplex.from(images), signOfSorting(images)))

  private def dimension(vertices: Vector[Simplex[Int]], d: Int) = vertices.filter(_.dim == d)

  private val prime = 32003

  /** The rank of `H_q(source) -> H_q(target)` over `Z/p`: the rank of the boundaries of the target together with the
    * images of the cycles of the source, less the rank of the boundaries.
    */
  private def homologyRank(instance: Instance, q: Int): Int =
    val (sourceQ, sourceBelow) = (dimension(instance.source, q), dimension(instance.source, q - 1))
    val (targetQ, targetAbove) = (dimension(instance.target, q), dimension(instance.target, q + 1))
    val cycles = ZpLinearAlgebra.nullspace(matrixOf(sourceBelow, sourceQ)(boundary), sourceQ.size, prime)
    val chainMatrix = matrixOf(targetQ, sourceQ)(chainMap(instance))
    val imagesOfCycles: Seq[Seq[Int]] =
      cycles.map(z => chainMatrix.map(row => row.zip(z).map((a, b) => a * b).sum % prime))
    val boundaries: Seq[Seq[Int]] = matrixOf(targetQ, targetAbove)(boundary).transpose // one row per boundary
    ZpLinearAlgebra.rank(boundaries ++ imagesOfCycles, prime) - ZpLinearAlgebra.rank(boundaries, prime)

  /** None, or what is wrong with the kernel vectors: each has to be the image of a cycle of the source, with the
    * coefficients of the chain, and the matrix has to be symmetric.
    */
  private def kernelProblem(
    instance: Instance,
    found: PersistentLaplacian.MapResult[Simplex[Int]]
  ): Option[String] =
    val (sourceQ, sourceBelow) = (dimension(instance.source, 1), dimension(instance.source, 0))
    val targetQ = dimension(instance.target, 1)
    val cycles = ZpLinearAlgebra.nullspace(matrixOf(sourceBelow, sourceQ)(boundary), sourceQ.size, prime)
    // the images of integer cycles over Z/p are not those over the reals; use the real cycles, from the library's own
    // matrix, only to test membership: a chain v is in the span of the images iff v - P v is zero
    val chainMatrix = matrixOf(targetQ, sourceQ)(chainMap(instance))
    val boundaryMatrix = matrixOf(sourceBelow, sourceQ)(boundary)
    val symmetric = found.matrix.toArray.indices.forall(i =>
      found.matrix.toArray.indices.forall(j => math.abs(found.matrix(i, j) - found.matrix(j, i)) < 1e-9)
    )
    if !symmetric then Some("the matrix is not symmetric")
    else if cycles.isEmpty && found.kernel.nonEmpty then Some("a kernel with no cycles in the source")
    else
      val realCycles =
        val m = DenseMatrix.tabulate(boundaryMatrix.size, sourceQ.size)((i, j) => boundaryMatrix(i)(j).toDouble)
        val padded =
          if m.rows >= m.cols then m
          else DenseMatrix.tabulate(m.cols, m.cols)((i, j) => if i < m.rows then m(i, j) else 0.0)
        val svd = padded.svd
        (svd.rank until m.cols).map(k => (0 until m.cols).map(i => svd.v(i, k)))
      val images = realCycles.map(z => chainMatrix.map(row => row.zip(z).map((a, b) => a * b).sum))
      val span = images.filter(_.exists(math.abs(_) > 1e-9))
      val outside = found.kernel.flatMap { chain =>
        val terms = chain.terms.toMap
        val v = targetQ.map(terms.getOrElse(_, 0.0)) // the chain on every edge of the target
        val projected =
          if span.isEmpty then Seq.fill(v.size)(0.0)
          else
            val basis = DenseMatrix.tabulate(span.size, v.size)((i, j) => span(i)(j))
            val svd = basis.transpose.svd
            val u = DenseMatrix.tabulate(v.size, svd.rank)((i, j) => svd.u(i, j))
            val coefficients = (0 until svd.rank).map(k => (0 until v.size).map(i => u(i, k) * v(i)).sum)
            (0 until v.size).map(i => (0 until svd.rank).map(k => u(i, k) * coefficients(k)).sum)
        val distance = math.sqrt(v.zip(projected).map((a, b) => (a - b) * (a - b)).sum)
        if distance > 1e-7 then Some(distance) else None
      }
      outside.headOption.map(d => s"a kernel chain is $d away from the span of the images of the cycles")

end PersistentLaplacianMapSpec
