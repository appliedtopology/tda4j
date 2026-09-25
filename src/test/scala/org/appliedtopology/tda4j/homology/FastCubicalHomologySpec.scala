package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.*

import org.specs2.mutable
import org.specs2.execute.{AsResult, Result}
import org.specs2.ScalaCheck
import org.scalacheck.*

/** `FastCubicalHomologyContext` (Flash Cubical's dual-graph union-find, `.claude/DESIGN-fast-cubical-engine.md`) --
  * cross-validated against `CubicalHomologyContext` (the naive engine, this codebase's own reference oracle for cubical
  * complexes) rather than re-derived by hand for every fixture: the dual-graph construction's own correctness argument
  * (Alexander duality) is independent of the naive engine's own algorithm (general boundary-matrix reduction), so
  * agreement between the two is real evidence, not two implementations of the same idea agreeing with itself. The two
  * exact-bar-count fixtures below are reused verbatim from `CubicalStreamSpec` (already independently hand-derived and
  * pinned there), specifically so this spec doesn't re-derive those counts.
  */
class FastCubicalHomologySpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  def endpoint(e: BarcodeEndpoint[Double]): Double = e match
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity

  def fastBars[C: Field](stream: CubicalGridStream): List[(Int, Double, Double)] =
    FastCubicalHomologyContext[C]()
      .persistentHomology(stream)
      .map(b => (b.dim, endpoint(b.lower), endpoint(b.upper)))

  def naiveBars(stream: CubicalGridStream): List[(Int, Double, Double)] =
    CubicalHomologyContext[Double, Double]().persistentHomology(stream).diagramAt(Double.PositiveInfinity)

  val twoHoleFixture: CubicalGridStream =
    val elevated = Set(IndexedSeq(1, 1), IndexedSeq(3, 3))
    CubicalGridStream(IndexedSeq(5, 5), idx => if elevated(idx) then 1.0 else 0.0)

  // ---------------------------------------------------------------------------------------------------------
  // Hand-derived, exact-bar-count fixtures (reused verbatim from CubicalStreamSpec -- see that file's own header
  // comment for the general Euler-characteristic argument pinning these counts).
  // ---------------------------------------------------------------------------------------------------------

  "A constant-valued 2x2 image (9 vertices, 12 edges, 4 pixels, totalCells=25) reduces correctly" >> {
    val stream = CubicalGridStream(IndexedSeq(2, 2), _ => 5.0)
    val bars = fastBars[Double](stream)
    (HomologyFixtures.totalBarsAccountForAllCells(bars, 25) must beTrue) and
      (bars.count(_._1 == 0) must beEqualTo(9)) and
      (bars.count { case (0, _, d) => d.isInfinite; case _ => false } must beEqualTo(1)) and
      (bars.count { case (0, b, d) => b == d; case _ => false } must beEqualTo(8)) and
      (bars.count(_._1 == 1) must beEqualTo(4)) and
      (bars.forall { case (1, b, d) => b == d; case _ => true } must beTrue) and
      (bars.sorted must beEqualTo(naiveBars(stream).sorted))
  }

  "A single bright center pixel in an otherwise dark 3x3 image produces exactly one persistent H1 bar" >> {
    val stream = CubicalGridStream(IndexedSeq(3, 3), idx => if idx == IndexedSeq(1, 1) then 1.0 else 0.0)
    val bars = fastBars[Double](stream)
    (HomologyFixtures.totalBarsAccountForAllCells(bars, 49) must beTrue) and
      (bars.count(_._1 == 0) must beEqualTo(16)) and
      (bars.count { case (0, _, d) => d.isInfinite; case _ => false } must beEqualTo(1)) and
      (bars.count(_._1 == 1) must beEqualTo(9)) and
      (bars.count { case (1, b, d) => b == d; case _ => false } must beEqualTo(8)) and
      (bars.exists(_ == (1, 0.0, 1.0)) must beTrue) and
      (bars.sorted must beEqualTo(naiveBars(stream).sorted))
  }

  "Two independent single-pixel holes in a 5x5 image produce exactly two persistent H1 bars" >> {
    val bars = fastBars[Double](twoHoleFixture)
    (bars.count { case (1, 0.0, 1.0) => true; case _ => false } must beEqualTo(2)) and
      (bars.sorted must beEqualTo(naiveBars(twoHoleFixture).sorted))
  }

  // ---------------------------------------------------------------------------------------------------------
  // Regression: a PERMANENTLY missing center pixel (topValue = +Infinity, this codebase's own "Perseus missing-
  // pixel" convention -- CLAUDE.md's File I/O section, CubicalImage/PerseusSpec) is a genuinely different case
  // from the finite-valued single-hole fixture above, not just a larger birth value: it ties `birthOf` with the
  // dual graph's own `infinityId` sentinel at +Infinity, which broke the FIRST version of this engine's young/old
  // tie-break (`birthOf(ra) <= birthOf(rb)` alone let `infinityId` be picked as the YOUNG/dying side, whose chain
  // is deliberately never populated, throwing `IllegalStateException` deep in the flip computation) -- caught via
  // TDA4jSpec's own MATLAB-facing ring fixture, not this file's own (all-finite) fixtures above. Pinned here too,
  // not only at the MATLAB layer, since the bug is in this engine's own union-find, not in any facade code.
  // ---------------------------------------------------------------------------------------------------------

  val permanentlyMissingCenterFixture: CubicalGridStream =
    CubicalGridStream(IndexedSeq(3, 3), idx => if idx == IndexedSeq(1, 1) then Double.PositiveInfinity else 0.0)

  "A permanently-missing (topValue = +Infinity) center pixel produces the same essential H1 bar as a finite one, " +
    "and matches the naive engine" >> {
      val bars = FastCubicalHomologyContext[Double]().persistentHomology(permanentlyMissingCenterFixture)
      val triples = bars.map(b => (b.dim, endpoint(b.lower), endpoint(b.upper)))
      (triples.exists(_ == (1, 0.0, Double.PositiveInfinity)) must beTrue) and
        (bars.filter(_.dim == 1).forall(b => Chain.from(b.annotation.get.boundary).isZero()) must beTrue) and
        (triples.sorted must beEqualTo(naiveBars(permanentlyMissingCenterFixture).sorted))
    }

  "requires ambient dimension 2" >> {
    FastCubicalHomologyContext[Double]()
      .persistentHomology(CubicalGridStream(IndexedSeq(3), _ => 0.0)) must throwA[IllegalArgumentException]
  }

  // ---------------------------------------------------------------------------------------------------------
  // Every H1 representative is a genuine cycle (zero boundary) -- not implied by bar-value agreement with the
  // naive oracle alone, since a sign bug in the dual merge's own flip computation could still produce the right
  // birth/death VALUES from a non-cycle chain (the union-find's merge structure alone determines the barcode
  // shape; only the flip arithmetic determines whether the resulting chain is actually closed).
  // ---------------------------------------------------------------------------------------------------------

  val handFixtures: Seq[CubicalGridStream] = Seq(
    CubicalGridStream(IndexedSeq(2, 2), _ => 5.0),
    CubicalGridStream(IndexedSeq(3, 3), idx => if idx == IndexedSeq(1, 1) then 1.0 else 0.0),
    twoHoleFixture,
    permanentlyMissingCenterFixture
  )

  "every FastCubical H1 representative has zero boundary, on the hand-derived fixtures" >>
    handFixtures
      .map { stream =>
        val bars = FastCubicalHomologyContext[Double]().persistentHomology(stream)
        bars.filter(_.dim == 1).forall(b => Chain.from(b.annotation.get.boundary).isZero()) must beTrue
      }
      .reduce(_ and _)

  // ---------------------------------------------------------------------------------------------------------
  // Sign genericity over Fp(3): Double/F2-style coefficients cannot distinguish a correct orientation flip from
  // one with a dropped sign (CLAUDE.md's own "F2 hides sign errors" testing lesson) -- so re-run the same
  // fixtures over an odd prime field and check both that the barcode VALUES still agree with the Double run, and
  // that every H1 representative's boundary is STILL zero: a real, sign-sensitive check F2 provably can't make.
  // ---------------------------------------------------------------------------------------------------------

  val GF3 = new FiniteField(3)
  import GF3.given

  "agrees with the Double run over Fp(3), including genuine-cycle representatives, on the hand-derived fixtures" >>
    handFixtures
      .map { stream =>
        val doubleBars = fastBars[Double](stream)
        val f3Bars = FastCubicalHomologyContext[GF3.Fp]().persistentHomology(stream)
        val f3Triples = f3Bars.map(b => (b.dim, endpoint(b.lower), endpoint(b.upper)))
        val allCycles = f3Bars.filter(_.dim == 1).forall(b => Chain.from(b.annotation.get.boundary).isZero())
        (f3Triples.sorted must beEqualTo(doubleBars.sorted)) and (allCycles must beTrue)
      }
      .reduce(_ and _)

  // ---------------------------------------------------------------------------------------------------------
  // Random 2D grids, cross-validated against the naive engine -- reusing CubicalStreamSpec's own genTestImage
  // shape (small integer values, tie-heavy by construction, exactly the regime this codebase's filtration-
  // ordering/union-find bugs have historically hidden in), restricted to ambientDim=2, FastCubicalHomologyContext's
  // only currently-supported case. Level 4 is a sentinel for topValue = +Infinity (a permanently-missing cell,
  // ~1-in-5 per top cell) -- NOT just a larger finite value, since it ties against the dual graph's own
  // infinityId sentinel and exercises the young/old tie-break bug this file's own permanentlyMissingCenterFixture
  // regression test found by hand, across many more random configurations than that one fixture alone.
  // ---------------------------------------------------------------------------------------------------------

  case class TestImage2D(shape: IndexedSeq[Int], values: IndexedSeq[Int])

  def genTestImage2D: Gen[TestImage2D] =
    for
      shape <- Gen.listOfN(2, Gen.choose(1, 4)).map(_.toIndexedSeq)
      total = shape.product
      values <- Gen.listOfN(total, Gen.choose(0, 4)).map(_.toIndexedSeq)
    yield TestImage2D(shape, values)

  given Arbitrary[TestImage2D] = Arbitrary(genTestImage2D)

  def valueFnOf(img: TestImage2D): IndexedSeq[Int] => Double =
    idx =>
      val flat = idx.zip(img.shape).foldLeft(0) { case (acc, (i, n)) => acc * n + i }
      val level = img.values(flat)
      if level == 4 then Double.PositiveInfinity else level.toDouble

  // NOT also checked here: HomologyFixtures.totalBarsAccountForAllCells. Its "essential iff upper is infinite"
  // proxy silently miscounts once a PRIMAL cell can itself carry value +Infinity (level 4 above): a genuine
  // PAIRING between two such cells (a real 2-cell contribution, exactly like any other finite pairing) reports
  // both endpoints as the literal Double +Infinity, which the helper's `upper.isFinite` check can't distinguish
  // from a true unpaired essential class (1-cell contribution) -- found via this exact generator (shape=(3,2),
  // values=[3,4,4,4,4,4]: 22 counted vs. 35 actual cells), and confirmed NOT an engine bug by checking the SAME
  // fixture directly: this engine's own bars matched CubicalHomologyContext's bar-for-bar exactly (triples.sorted
  // == naive.sorted) on the failing case, so the helper would miscount the naive engine's own output identically.
  // The naive-agreement check below already subsumes what this invariant is meant to catch, so it stays out
  // rather than narrowing the generator to dodge a pre-existing, shared test-helper limitation.
  "matches the naive engine's barcode, and every H1 representative is a genuine cycle, on random tie-heavy 2D images" >>
    AsResult {
      prop { (img: TestImage2D) =>
        val stream = CubicalGridStream(img.shape, valueFnOf(img))
        val bars = FastCubicalHomologyContext[Double]().persistentHomology(stream)
        val triples = bars.map(b => (b.dim, endpoint(b.lower), endpoint(b.upper)))
        val allCycles = bars.filter(_.dim == 1).forall(b => Chain.from(b.annotation.get.boundary).isZero())
        allCycles && triples.sorted == naiveBars(stream).sorted
      }
    }
