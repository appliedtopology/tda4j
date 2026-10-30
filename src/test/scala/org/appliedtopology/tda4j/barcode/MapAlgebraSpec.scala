package org.appliedtopology.tda4j

import MapAlgebra.Interval

import org.specs2.mutable.Specification

/** Image, kernel and cokernel of maps of interval modules, checked against the ranks of the structure maps of the
  * modules: for a module `X` with bars `[b, d)`, the number of bars with `b <= s` and `t < d` is the rank of
  * `X_s -> X_t`, and these ranks, for all pairs of the critical values, determine the barcode. The ranks of the image,
  * kernel and cokernel are computed here from the matrices alone, by linear algebra over `Z/p` on the vector spaces at
  * the times `s` and `t`, so nothing is shared with the reductions under test.
  */
class MapAlgebraSpec extends Specification:

  /** A random module map: bars of the source and the target, and a coefficient in `1 until p` for some legal pairs. */
  private case class Instance(source: IndexedSeq[Interval], target: IndexedSeq[Interval], entries: Map[(Int, Int), Int])

  private def randomBars(rng: scala.util.Random, count: Int): IndexedSeq[Interval] =
    IndexedSeq.fill(count) {
      val birth = rng.nextInt(6).toDouble
      Interval(birth, if rng.nextInt(5) == 0 then Double.PositiveInfinity else birth + 1 + rng.nextInt(4))
    }

  private def randomInstance(seed: Long, p: Int): Instance =
    val rng = new scala.util.Random(seed)
    val (source, target) = (randomBars(rng, 1 + rng.nextInt(4)), randomBars(rng, 1 + rng.nextInt(4)))
    val entries = (for
      i <- source.indices
      j <- target.indices
      if MapAlgebra.isLegal(source(i), target(j)) && rng.nextInt(10) < 6
    yield (j, i) -> (1 + rng.nextInt(p - 1))).toMap
    Instance(source, target, entries)

  import ZpLinearAlgebra.{nullspace, rank}

  private def alive(bar: Interval, t: Double): Boolean = bar.birth <= t && t < bar.death

  /** The rank of the structure map from time `s` to time `t` of the image, kernel and cokernel of `inst`. */
  private def ranks(inst: Instance, p: Int, s: Double, t: Double): (Int, Int, Int) =
    val Instance(source, target, entries) = inst
    def phi(j: Int, i: Int) = entries.getOrElse((j, i), 0)
    val (srcS, srcT) =
      (source.indices.filter(i => alive(source(i), s)), source.indices.filter(i => alive(source(i), t)))
    val (tgtS, tgtT) =
      (target.indices.filter(j => alive(target(j), s)), target.indices.filter(j => alive(target(j), t)))
    // Image: the vectors phi(m_i), i alive at s, seen at time t (the components that are alive at both).
    val image = rank(srcS.map(i => tgtT.filter(tgtS.contains).map(phi(_, i))), p)
    // Kernel: the combinations of the source bars alive at s that phi_s sends to zero, restricted to those alive at t.
    val kernelBasis = nullspace(tgtS.map(j => srcS.map(phi(j, _))), srcS.length, p)
    val kernel = rank(kernelBasis.map(k => srcS.zip(k).collect { case (i, c) if srcT.contains(i) => c }), p)
    // Cokernel: (the target components alive at both, plus the image at t) modulo the image at t.
    val imageAtT = srcT.map(i => tgtT.map(phi(_, i)))
    val unit = tgtT.filter(tgtS.contains).map(j => tgtT.map(k => if k == j then 1 else 0))
    val cokernel = rank(imageAtT ++ unit, p) - rank(imageAtT, p)
    (image, kernel, cokernel)

  private def count(bars: Seq[Interval], s: Double, t: Double): Int = bars.count(b => b.birth <= s && t < b.death)

  /** The first disagreement between the barcodes and the ranks of the modules, over all pairs of critical times. */
  private def disagreement(
    inst: Instance,
    p: Int,
    image: Seq[Interval],
    kernel: Seq[Interval],
    cokernel: Seq[Interval]
  ): Option[String] =
    val critical =
      (inst.source ++ inst.target).flatMap(b => List(b.birth, b.death)).filterNot(_.isInfinite).distinct.sorted
    val times = critical :+ (critical.max + 1.0)
    (for
      s <- times
      t <- times
      if s <= t
      (expected, found, name) <-
        val (i, k, c) = ranks(inst, p, s, t)
        List(
          (i, count(image, s, t), "image"),
          (k, count(kernel, s, t), "kernel"),
          (c, count(cokernel, s, t), "cokernel")
        )
      if expected != found
    yield s"$name at s=$s, t=$t: the ranks say $expected, the bars $found").headOption

  private def run(inst: Instance, p: Int): Option[String] =
    val coefficients = Coefficients(p)
    import coefficients.given
    val columns = inst.source.indices.map { i =>
      inst.target.indices.collect {
        case j if inst.entries.contains((j, i)) => j -> coefficients.fromInt(inst.entries((j, i)))
      }.toMap
    }
    val image = MapAlgebra.image(inst.source, inst.target, columns).map(_._2)
    val kernel = MapAlgebra.kernel(inst.source, inst.target, columns).map(_._2)
    val cokernel = MapAlgebra.cokernel(inst.source, inst.target, columns).map(_._2)
    disagreement(inst, p, image, kernel, cokernel)

  "MapAlgebra" should {
    "have the ranks of the structure maps as its image, kernel and cokernel, over Z/2, Z/3 and Z/5" in {
      val failures = for
        p <- List(2, 3, 5)
        seed <- 0L until 150L
        inst = randomInstance(seed * 31 + p, p)
        problem <- run(inst, p)
      yield s"p = $p, seed $seed, $inst: $problem"
      failures.take(2) must beEmpty
    }

    "tell the field: the map that is 2 on a bar is an isomorphism over Z/3 and zero over Z/2" in {
      // [0, inf) -> [0, inf) by 2: an isomorphism unless 2 = 0.
      val bar = IndexedSeq(Interval(0.0, Double.PositiveInfinity))
      def barsOver(p: Int) =
        val coefficients = Coefficients(p)
        import coefficients.given
        val columns = IndexedSeq(Map(0 -> coefficients.fromInt(2)))
        (
          MapAlgebra.image(bar, bar, columns).size,
          MapAlgebra.kernel(bar, bar, columns).size,
          MapAlgebra.cokernel(bar, bar, columns).size
        )
      (barsOver(3) must beEqualTo((1, 0, 0))) and (barsOver(2) must beEqualTo((0, 1, 1)))
    }

    "reproduce the worked example of the user guide" in {
      given Double is Field = Field.DoubleApproximated(1e-9)
      val source = IndexedSeq(Interval(2.0, 5.0), Interval(3.0, 7.0))
      val target = IndexedSeq(Interval(1.0, 6.0), Interval(0.0, 4.0))
      val columns = IndexedSeq(Map(1 -> 1.0), Map(0 -> 1.0, 1 -> 1.0))
      (MapAlgebra.image(source, target, columns).map(_._2).toSet must beEqualTo(
        Set(Interval(3.0, 6.0), Interval(2.0, 4.0))
      )) and
        (MapAlgebra.kernel(source, target, columns).map(_._2).toSet must beEqualTo(
          Set(Interval(6.0, 7.0), Interval(4.0, 5.0))
        )) and
        (MapAlgebra.cokernel(source, target, columns).map(_._2).toSet must beEqualTo(
          Set(Interval(0.0, 2.0), Interval(1.0, 3.0))
        ))
    }
  }

  "Barcode.image, kernel and cokernel, on a matrix of real numbers" should {
    "have the ranks of the structure maps as well" in {
      // The reals, as Z/10007: a minor of a 0/1 matrix of at most 4 x 4 is at most 4 in size, so none is lost.
      val p = 10007
      def bar(b: Interval) = PersistenceBar[Double](0, b.birth, b.death)
      def interval(b: PersistenceBar[Double, Nothing]) = Interval(b.birth, b.death)
      val failures = for
        seed <- 0L until 150L
        inst = randomInstance(seed * 17 + 1, 2) // every coefficient 1
        matrix = DenseMatrix.tabulate(inst.target.length, inst.source.length)((j, i) =>
          inst.entries.getOrElse((j, i), 0).toDouble
        )
        (source, target) = (inst.source.map(bar).toList, inst.target.map(bar).toList)
        image = Barcode.image(source, target, matrix).map(interval)
        kernel = Barcode.kernel(source, target, matrix).map(interval)
        cokernel = Barcode.cokernel(source, target, matrix).map(interval)
        problem <- disagreement(inst, p, image, kernel, cokernel)
      yield s"seed $seed, $inst: $problem"
      failures.take(2) must beEmpty
    }
  }
