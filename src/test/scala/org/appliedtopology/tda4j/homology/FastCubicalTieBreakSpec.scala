package org.appliedtopology.tda4j

import org.specs2.mutable

/** Two dual components born at the same value merge at a facet: either may die, with the same bars, and the fast
  * cubical engine lets the smaller one die, so that the region it walks to write the representative is the smaller one.
  * A tiling has many equal maxima, so the choice decides the cost: the cells walked grow like the picture's size when
  * the smaller region dies, and like its size times the number of tiles when the larger does (#290).
  */
class FastCubicalTieBreakSpec extends mutable.Specification:
  val f3 = FiniteField(3)
  import f3.given
  given Ordering[Cube] = cubeOrdering

  private def rows(values: Seq[Seq[Double]]): CubicalGridStream =
    val n = values.head.size
    CubicalGridStream(IndexedSeq(values.size, n), idx => values(idx(0))(idx(1)))

  // The boundary of a pixel, and its negative: a representative is a cycle up to the sign the merge gave its region.
  private def pixelBoundaries(row: Int, column: Int): Seq[Chain[Cube, f3.Fp]] =
    val terms = Cube.unitCube(IndexedSeq(row, column)).boundary[f3.Fp].toSeq
    Seq(Chain.from(terms), Chain.from(terms.map((cell, a) => (cell, summon[f3.Fp is Field].negate(a)))))

  "two equal maxima of different sizes" should {
    // A row of three pixels at 5 and a lone pixel at 5, a pixel at 3 between them, a sea at 0 around: at 3 the hole
    // of the five pixels splits in two, and the new class is born there and dies at 5 with either part.
    val image = rows(
      Seq(
        Seq.fill(8)(0.0),
        Seq(0.0, 5.0, 5.0, 5.0, 3.0, 5.0, 0.0, 0.0),
        Seq.fill(8)(0.0)
      )
    )
    val engine = FastCubicalHomologyEngine[f3.Fp]()
    val bars = engine.persistentHomology(image).filter(_.dim == 1)
    val split = bars.filter(_.lower == ClosedEndpoint(3.0))

    "give the bars of both holes" in {
      bars.map(b => (b.lower, b.upper)).toSet must beEqualTo(
        Set((ClosedEndpoint(0.0), OpenEndpoint(5.0)), (ClosedEndpoint(3.0), OpenEndpoint(5.0)))
      )
    }

    "write the representative of the split from the smaller part, the lone pixel" in {
      (split.size must beEqualTo(1)) and
        (pixelBoundaries(1, 5) must contain(split.head.annotation.get))
    }

    "walk the lone pixel, then the region of all five pixels when it dies to `∞`" in {
      engine.representativeCellsWalked must beEqualTo(1L + 5L)
    }
  }

  "a comb of equal peaks" should {
    // Peaks of 5 and saddles of 3 alternate along a row inside a sea of 0: the accumulated region meets one new peak
    // after another, each a tie. The new peak (one cell) dies every time, not the accumulated region (2k cells at the
    // k-th peak, a quadratic total).
    def comb(peaks: Int) =
      val width = 2 * peaks + 1
      rows(
        Seq(
          Seq.fill(width)(0.0),
          Seq.tabulate(width)(c =>
            if c == width - 1 then 0.0 else if c % 2 == 1 then 5.0 else if c == 0 then 0.0 else 3.0
          ),
          Seq.fill(width)(0.0)
        )
      )

    "walk a few cells per peak, however many peaks" in {
      val walked = for peaks <- Seq(15, 30, 60) yield
        val engine = FastCubicalHomologyEngine[f3.Fp]()
        val bars = engine.persistentHomology(comb(peaks)).filter(_.dim == 1)
        // Every peak but the first splits off the hole of the first: peaks - 1 bars [3, 5), and the first's [0, 5).
        (bars.size must beEqualTo(peaks)) and (engine.representativeCellsWalked must beLessThan(10L * peaks))
      walked.reduce(_ and _)
    }
  }
end FastCubicalTieBreakSpec
