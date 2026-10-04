package org.appliedtopology.tda4j

import org.specs2.mutable

/** The fast engines' representatives come from a signed union-find (`SignedUnionFind`) instead of a coefficient map per
  * component. The outputs must not change at all: every bar, its endpoints, its representative term for term, and the
  * list order must equal what the eager implementations kept in the test tree (`EagerFastCubicalReference`,
  * `EagerFastAlphaReference`) return. Inputs cover:
  *   - ties everywhere (small integer values);
  *   - a bright blob on a dark background, the case that made the eager version quadratic;
  *   - missing pixels at `+Infinity`, which tie against `∞`;
  *   - 3-D grids (the chunks hybrid plus the dual union-find);
  *   - signed fields F_3 and F_17 as well as F_2, and zero-length bars on and off;
  *   - random Helix triangulations in 2-D and 3-D.
  */
class FastRepresentativesSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  val f2 = FiniteField(2)
  val f3 = FiniteField(3)
  val f17 = FiniteField(17)

  def images(seed: Long): Seq[CubicalGridStream] =
    val rng = new scala.util.Random(seed)
    def grid(shape: IndexedSeq[Int])(value: IndexedSeq[Int] => Double) = CubicalGridStream(shape, value)
    val (n, m) = (5 + rng.nextInt(8), 5 + rng.nextInt(8))
    val ties = IndexedSeq.fill(n * m)(rng.nextInt(4).toDouble)
    val noise = IndexedSeq.fill(n * m)(rng.nextDouble())
    val blob = IndexedSeq.tabulate(n * m) { k =>
      val (i, j) = (k / m, k % m)
      val r2 = math.pow(i - n / 2.0, 2) + math.pow(j - m / 2.0, 2)
      math.exp(-r2 / 8) + 0.1 * noise(k)
    }
    val missing = ties.map(v => if rng.nextInt(7) == 0 then Double.PositiveInfinity else v)
    val ties3d = IndexedSeq.fill(4 * 5 * 4)(rng.nextInt(3).toDouble)
    Seq(
      grid(IndexedSeq(n, m))(idx => ties(idx(0) * m + idx(1))),
      grid(IndexedSeq(n, m))(idx => noise(idx(0) * m + idx(1))),
      grid(IndexedSeq(n, m))(idx => blob(idx(0) * m + idx(1))),
      grid(IndexedSeq(n, m))(idx => missing(idx(0) * m + idx(1))),
      grid(IndexedSeq(4, 5, 4))(idx => ties3d(idx(0) * 20 + idx(1) * 4 + idx(2)))
    )

  def sameCubical(field: FiniteField)(stream: CubicalGridStream, zeroLength: Boolean): Boolean =
    import field.given
    FastCubicalHomologyEngine[field.Fp]().persistentHomology(stream, zeroLength) ==
      EagerFastCubicalReference[field.Fp]().persistentHomology(stream, zeroLength)

  "The fast cubical engine's bars and representatives equal the eager reference's" >> {
    val checks = for
      seed <- 0L until 12L
      stream <- images(seed)
      field <- Seq(f2, f3, f17)
      zeroLength <- Seq(false, true)
    yield sameCubical(field)(stream, zeroLength)
    checks.forall(identity) must beTrue
  }

  /** `Some(equal)` when both engines produced bars, `None` when both rejected the triangulation. */
  def sameAlpha(field: FiniteField)(helix: HelixDelaunay, zeroLength: Boolean): Option[Boolean] =
    import field.given
    val fast = scala.util.Try(FastAlphaHomologyEngine[field.Fp]().persistentHomology(helix, zeroLength))
    val eager = scala.util.Try(EagerFastAlphaReference[field.Fp]().persistentHomology(helix, zeroLength))
    (fast.toOption, eager.toOption) match
      case (Some(a), Some(b)) => Some(a == b)
      case (None, None)       => None // both reject the same triangulation
      case _                  => Some(false)

  "The fast alpha engine's bars and representatives equal the eager reference's" >> {
    val checks = for
      seed <- 0L until 10L
      dim <- Seq(2, 3)
      field <- Seq(f3, f17)
      zeroLength <- Seq(false, true)
    yield
      val rng = new scala.util.Random(seed * 31 + dim)
      val pts = Array.fill(8 + rng.nextInt(10))(Array.fill(dim)(rng.nextDouble()))
      sameAlpha(field)(HelixDelaunay(pts), zeroLength)
    (checks.flatten.forall(identity) must beTrue) and (checks.flatten.size must beGreaterThan(checks.size / 2))
  }

  "Every top-degree representative is a cycle over F_3" >> {
    // Equality with the reference cannot catch a bug the reference shares; closedness over a signed field can.
    import f3.given
    given Ordering[Cube] = cubeOrdering
    val checks = for
      seed <- 0L until 6L
      stream <- images(seed)
      bar <- FastCubicalHomologyEngine[f3.Fp]().persistentHomology(stream)
      if bar.dim == stream.ambientDim - 1
    yield
      Chain.from(bar.representative.boundary).isZero()
    checks.forall(identity) must beTrue
  }
