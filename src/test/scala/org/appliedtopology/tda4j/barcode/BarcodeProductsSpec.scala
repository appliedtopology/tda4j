package org.appliedtopology.tda4j

import ZpLinearAlgebra.{nullspace, rank}

import org.specs2.mutable.Specification

/** Tensor product, convolution, Tor, internal Hom and dual of barcodes. The formulas are checked against modules built
  * from matrices on the integer grid, where everything is linear algebra over `Z/p` on the vector spaces at each time:
  *
  *   - the pointwise tensor product by the ranks of the Kronecker products of the structure maps;
  *   - the internal Hom `[M, N]_t = Hom(M, N shifted by t)` by solving the equations of a natural transformation for
  *     each shift `t`, and the rank of the composition with the structure map of `N` from `t` to `t'`;
  *   - the adjunction `Hom(M ⊛ N, P) ≅ Hom(M, [N, P])` by counting maps of intervals (infinite bars included);
  *   - the product complexes themselves, in `ProductComplexSpec`.
  *
  * The grid modules are sums of intervals `[b, d)` with `0 <= b < d <= T`, zero from `T` on and before 0.
  */
class BarcodeProductsSpec extends Specification:

  private val prime = 101
  private val T = 5

  private def bar(dim: Int, birth: Double, death: Double): PersistenceBar[Double, Nothing] =
    if death.isPosInfinity then PersistenceBar[Double](dim, birth) else PersistenceBar[Double](dim, birth, death)

  private def intervals(bars: Seq[PersistenceBar[Double, ?]]) = bars.map(b => (b.dim, b.birth, b.death)).sorted

  private def randomBars(rng: scala.util.Random, count: Int, infinite: Boolean): List[PersistenceBar[Double, Nothing]] =
    List.fill(count) {
      val birth = rng.nextInt(4)
      val death =
        if infinite && rng.nextInt(4) == 0 then Double.PositiveInfinity
        else (birth + 1 + rng.nextInt(T - birth)).toDouble
      bar(rng.nextInt(2), birth.toDouble, death)
    }

  /** The number of bars alive from `s` to `t`: the rank of the structure map of a sum of intervals. */
  private def throughout(bars: Seq[PersistenceBar[Double, ?]], s: Int, t: Int): Int =
    bars.count(b => b.birth <= s && t < b.death)

  // ---- the module on the grid ----

  private def aliveAt(bars: Seq[PersistenceBar[Double, ?]], x: Int): IndexedSeq[Int] =
    bars.indices.filter(i => bars(i).birth <= x && x < bars(i).death)

  /** The ranks of the pointwise tensor product's structure maps: Kronecker products of the maps of the factors. */
  private def tensorRank(
    first: Seq[PersistenceBar[Double, ?]],
    second: Seq[PersistenceBar[Double, ?]],
    s: Int,
    t: Int
  ): Int =
    // the structure map of a sum of intervals from s to t: a 1 where a bar is alive at both, in the basis of the bars
    def structure(bars: Seq[PersistenceBar[Double, ?]]) =
      val (from, to) = (aliveAt(bars, s), aliveAt(bars, t))
      to.map(j => from.map(i => if i == j then 1 else 0))
    val (a, b) = (structure(first), structure(second))
    val kronecker = for
      ra <- a
      rb <- b
    yield
      for
        ca <- ra
        cb <- rb
      yield ca * cb
    rank(kronecker.map(_.toSeq), prime)

  /** The ranks of the maps `[M, N]_t -> [M, N]_t'` of the module of maps from `M` to `N` shifted by the amounts `t` and
    * `t'` (`t <= t'`): the natural transformations `M -> N(t)` are the solutions of one linear system, and composing
    * with the map `N(t) -> N(t')` sends them to those of `t'`.
    */
  private def homRank(m: Seq[PersistenceBar[Double, ?]], n: Seq[PersistenceBar[Double, ?]], t: Int, t2: Int): Int =
    // The unknowns of `t`: phi_s[j][i] for s in 0 until T, i alive in M at s, j alive in N at s + t.
    val variables =
      for
        s <- 0 until T
        i <- aliveAt(m, s)
        j <- aliveAt(n, s + t)
      yield (s, j, i)
    val index = variables.zipWithIndex.toMap
    // One equation per square s -> s + 1: (N map) phi_s = phi_{s+1} (M map), read at each (target bar j', source bar i).
    val equations = for
      s <- 0 until T
      i <- aliveAt(m, s)
      j2 <- aliveAt(n, s + 1 + t)
    yield
      val row = Array.fill(variables.length)(0)
      // (N map) phi_s: the N bar j2 is alive at s + t as well when it was born by then
      if aliveAt(n, s + t).contains(j2) then row(index((s, j2, i))) += 1
      // phi_{s+1} (M map): the M bar i is alive at s + 1 when it has not died
      if aliveAt(m, s + 1).contains(i) then row(index((s + 1, j2, i))) -= 1
      row.toSeq
    val homs = nullspace(equations, variables.length, prime)
    // The image under composition: phi_s[j][i] lands on N bar j at s + t', if it is still alive there.
    val variables2 =
      (for
        s <- 0 until T
        i <- aliveAt(m, s)
        j <- aliveAt(n, s + t2)
      yield (s, j, i)).zipWithIndex.toMap
    val images = homs.map { phi =>
      val image = Array.fill(variables2.size)(0)
      for ((s, j, i), k) <- variables.zipWithIndex if phi(k) != 0 && variables2.contains((s, j, i)) do
        image(variables2((s, j, i))) = phi(k)
      image.toSeq
    }
    rank(images, prime)

  "Barcode.tensor" should {
    "be the bars of M_t ⊗ N_t: the ranks of the Kronecker products of the structure maps, at every pair of times" in {
      val failures = for
        seed <- 0L until 120L
        rng = new scala.util.Random(seed)
        first = randomBars(rng, 1 + rng.nextInt(3), infinite = false)
        second = randomBars(rng, 1 + rng.nextInt(3), infinite = false)
        product = Barcode.tensor(first, second)
        s <- 0 until T
        t <- s until T
        expected = tensorRank(first, second, s, t)
        found = throughout(product, s, t)
        if expected != found
      yield s"seed $seed, $first ⊗ $second at ($s, $t): rank $expected, bars $found in $product"
      failures.take(2) must beEmpty
    }

    "add the degrees, take the later birth and the earlier death, and drop disjoint pairs" in {
      val product = Barcode.tensor(
        List(bar(1, 0.0, 3.0), bar(0, 0.0, Double.PositiveInfinity)),
        List(bar(2, 1.0, 5.0), bar(0, 4.0, 6.0))
      )
      intervals(product) must beEqualTo(
        List((0, 4.0, 6.0), (2, 1.0, 5.0), (3, 1.0, 3.0))
      )
    }
  }

  "Barcode.hom" should {
    "be the module of maps from M to N shifted by t: its structure maps have the ranks of the compositions" in {
      val failures = for
        seed <- 0L until 80L
        rng = new scala.util.Random(seed)
        m = randomBars(rng, 1 + rng.nextInt(3), infinite = false).map(b => bar(0, b.birth, b.death))
        n = randomBars(rng, 1 + rng.nextInt(3), infinite = false).map(b => bar(0, b.birth, b.death))
        internal = Barcode.hom(m, n)
        t <- -T to T
        t2 <- t to T
        expected = homRank(m, n, t, t2)
        found = throughout(internal, t, t2)
        if expected != found
      yield s"seed $seed, [$m, $n] from $t to $t2: rank $expected, bars $found in $internal"
      failures.take(2) must beEmpty
    }

    "have at time 0, in degree 0, the maps with no shift: homDimension" in {
      val failures = for
        seed <- 0L until 200L
        rng = new scala.util.Random(seed + 1000)
        m = randomBars(rng, 1 + rng.nextInt(4), infinite = true)
        n = randomBars(rng, 1 + rng.nextInt(4), infinite = true)
        if Barcode.homDimension(m, n) != throughout(Barcode.hom(m, n).filter(_.dim == 0), 0, 0)
      yield s"seed $seed: $m, $n"
      failures.take(2) must beEmpty
    }

    "treat bars that never die as the maps of a free module do" in {
      // Hom([0, inf), [c, d)) = [c, d); Hom([a, b), [c, inf)) = 0 (no map from a bar that dies to one that does not);
      // Hom([0, inf), [c, inf)) = [c, inf); Hom([1, 4), [0, 2)): b' <= b < d' <= d is 0 <= 1 < 2 <= 4, so one map,
      // at the shifts t with max(0 - 1, 2 - 4) <= t < 2 - 1.
      val free = bar(0, 0.0, Double.PositiveInfinity)
      (intervals(Barcode.hom(List(free), List(bar(0, 2.0, 5.0)))) must beEqualTo(List((0, 2.0, 5.0)))) and
        (Barcode.hom(List(bar(0, 0.0, 2.0)), List(bar(0, 1.0, Double.PositiveInfinity))) must beEmpty) and
        (intervals(Barcode.hom(List(free), List(bar(0, 3.0, Double.PositiveInfinity)))) must beEqualTo(
          List((0, 3.0, Double.PositiveInfinity))
        )) and
        (intervals(Barcode.hom(List(bar(0, 1.0, 4.0)), List(bar(0, 0.0, 2.0)))) must beEqualTo(List((0, -1.0, 1.0))))
    }

    "give the worked example [0, 3) to [1, 5) the bar [2, 5)" in {
      intervals(Barcode.hom(List(bar(0, 0.0, 3.0)), List(bar(0, 1.0, 5.0)))) must beEqualTo(List((0, 2.0, 5.0)))
    }

    "lower the degree by the degree of the source: a bar of degree p to one of degree q is a bar of degree q - p" in {
      intervals(Barcode.hom(List(bar(1, 0.0, 3.0)), List(bar(3, 1.0, 5.0)))) must beEqualTo(List((2, 2.0, 5.0)))
    }
  }

  "Barcode.convolve and Barcode.hom" should {
    "be adjoint: Hom(M ⊛ N, P) and Hom(M, [N, P]) have the same dimension, for bars that die and bars that do not" in {
      val failures = for
        seed <- 0L until 300L
        rng = new scala.util.Random(seed + 5000)
        m = randomBars(rng, 1 + rng.nextInt(3), infinite = true)
        n = randomBars(rng, 1 + rng.nextInt(3), infinite = true)
        pp = randomBars(rng, 1 + rng.nextInt(3), infinite = true)
        left = Barcode.homDimension(Barcode.convolve(m, n), pp)
        right = Barcode.homDimension(m, Barcode.hom(n, pp))
        if left != right
      yield s"seed $seed, M=$m, N=$n, P=$pp: $left against $right"
      failures.take(2) must beEmpty
    }
  }

  "Barcode.convolve and Barcode.tor" should {
    "give the worked example: [0, 3) ⊛ [1, 5) = [1, 4), and Tor of the two is [5, 8)" in {
      (intervals(Barcode.convolve(List(bar(0, 0.0, 3.0)), List(bar(0, 1.0, 5.0)))) must beEqualTo(
        List((0, 1.0, 4.0))
      )) and
        (intervals(Barcode.tor(List(bar(0, 0.0, 3.0)), List(bar(0, 1.0, 5.0)))) must beEqualTo(List((1, 5.0, 8.0))))
    }

    "have no Tor against a bar that never dies, and the convolution of two such bars is such a bar" in {
      val free = bar(0, 2.0, Double.PositiveInfinity)
      (Barcode.tor(List(free), List(bar(0, 0.0, 3.0))) must beEmpty) and
        (intervals(Barcode.convolve(List(free), List(bar(0, 1.0, Double.PositiveInfinity)))) must beEqualTo(
          List((0, 3.0, Double.PositiveInfinity))
        )) and
        (intervals(Barcode.convolve(List(free), List(bar(0, 1.0, 4.0)))) must beEqualTo(List((0, 3.0, 6.0))))
    }
  }

  "Barcode.dual" should {
    "reflect each bar: [b, d) becomes (-d, -b], and a bar that never dies starts at -Infinity" in {
      val dual = Barcode.dual(List(bar(1, 1.0, 4.0), bar(0, 2.0, Double.PositiveInfinity)))
      dual.map(b => (b.dim, b.lower, b.upper)).toSet must beEqualTo(
        Set(
          (1, OpenEndpoint(-4.0), ClosedEndpoint(-1.0)),
          (0, NegativeInfinity[Double](), ClosedEndpoint(-2.0))
        )
      )
    }

    "be an involution" in {
      val bars = List(bar(1, 1.0, 4.0), bar(0, 2.0, Double.PositiveInfinity), bar(2, -1.0, 0.5))
      Barcode.dual(Barcode.dual(bars)).map(b => (b.dim, b.lower, b.upper)).toSet must beEqualTo(
        bars.map(b => (b.dim, b.lower, b.upper)).toSet
      )
    }
  }
end BarcodeProductsSpec
