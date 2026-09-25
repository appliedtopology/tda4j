package org.appliedtopology.tda4j
package alpha

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

/** Cross-validates `AlphaComplexDQP.dtm`'s `weight(i) = -f(i)^2` power weighting against
  * `streams.DtmRipsSimplexStream(..., p = 2.0)` -- the two constructions are the SAME `p = 2` ball union (Anai et al.,
  * "DTM-based filtrations", Def. 3.1/Prop. 3.5; see `alpha.AlphaComplexDQP.dtm`'s own doc for the exact
  * correspondence), so by the persistent nerve lemma they must report the SAME number of path components at every
  * threshold -- i.e. the same H0 barcode, once alpha's `alpha = t^2` (squared-radius) units are converted to Rips's
  * `2*t` (doubled-diameter) units via `birth -> 2*sqrt(birth)`.
  *
  * '''Zero-length bars are dropped before comparing''', deliberately: a vertex the alpha complex correctly DELAYS or
  * OMITS entirely (its own restricted power cell is not yet, or never, nonempty -- see
  * `AlphaComplexDQPBuilder.compute()`'s vertex-attachment fix) still exists as an ordinary vertex in
  * `DtmRipsSimplexStream` from `t = f(x)` onward (Rips has no notion of a restricted/hidden cell at all) -- so the two
  * constructions can, and do, disagree on individual vertex/edge appearance times without disagreeing on TOPOLOGY: the
  * "extra" Rips vertex is born and merges back into the same component in the same instant (a zero-length bar), which
  * carries no persistent signal and is exactly what the nerve lemma's per-threshold component-count guarantee predicts,
  * not a discrepancy. `.claude/WORKLOG-dtm-filtrations.md` works out both fixtures below by hand, including this exact
  * phenomenon on the two-point one.
  *
  * Fixtures use HAND-PICKED `f`, not `streams.DistanceToMeasure`-derived ones: a DTM-derived `f` on a fixture small
  * enough to hand-verify tends to put every point inside its own restricted cell (no delay/omission at all), which
  * would make this cross-check pass trivially without ever exercising the vertex-attachment fix it exists to guard.
  */
class AlphaComplexDQPDtmSpec extends org.specs2.mutable.Specification:
  given Double is Field = Field.DoubleApproximated(1e-9)

  private class RawAlphaComplexStream(points: Array[Array[Double]], ac: AlphaComplexDQP) extends AlphaShapes:
    override val metricSpace = EuclideanMetricSpace(points)
    override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
      case k if k >= 0 && k < ac.sizeByDimension.length => ac.cellsOfDimension(k).iterator
    }
    override def filtrationOrdering: Ordering[Simplex[Int]] =
      FilteredSimplexOrdering[Int, Double](this)(using vertexOrdering = summon[Ordering[Int]])(using
        filtrationOrdering = summon[Ordering[Double]].reverse
      )
    override def filtrationValue: PartialFunction[Simplex[Int], Double] = {
      case c if ac.contains(c) => ac.filtrationValue(c)
    }

  private def h0(points: Array[Array[Double]], f: IndexedSeq[Double]): Set[(Double, Double)] =
    val ac =
      AlphaComplexDQP.weighted(points, f.map(fi => -fi * fi).toArray, Double.PositiveInfinity, points.head.length)
    val stream = RawAlphaComplexStream(points, ac)
    SimplicialHomologyContext[Int, Double, Double]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
      .collect { case (0, b, d) =>
        (2.0 * math.sqrt(b), if d.isInfinite then Double.PositiveInfinity else 2.0 * math.sqrt(d))
      }
      .filter { case (b, d) => d != b } // drop zero-length bars -- see class doc
      .toSet

  private def dtmRipsH0(ambient: FiniteMetricSpace[Int], f: IndexedSeq[Double]): Set[(Double, Double)] =
    val stream = DtmRipsSimplexStream(ambient, f, p = 2.0, maxFiltrationValue = Some(Double.PositiveInfinity))
    SimplicialHomologyContext[Int, Double, Double]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
      .collect { case (0, b, d) => (b, d) }
      .filter { case (b, d) => d != b }
      .toSet

  private def near(x: Double, y: Double, tol: Double): Boolean =
    if x.isInfinite || y.isInfinite then x == y else math.abs(x - y) < tol // Infinity - Infinity is NaN, not 0

  private def approxEqual(a: Set[(Double, Double)], b: Set[(Double, Double)], tol: Double = 1e-6): Boolean =
    a.size == b.size && a.forall(x => b.exists(y => near(x._1, y._1, tol) && near(x._2, y._2, tol)))

  "AlphaComplexDQP.dtm's p=2 power weighting" should {
    "agree with DtmRipsSimplexStream(p=2)'s H0, after dropping zero-length bars, on a delayed (not hidden) vertex" >> {
      // Points 0, 1 (R^1), f = (0, 2): point 1's own centre is dominated by point 0 (weight(1) = -4), so alpha
      // delays vertex 1's birth to alpha=6.25 (t=2.5) instead of Rips's own f(1)=2 -- but BOTH constructions
      // agree there is exactly ONE essential H0 bar once the zero-length "delayed vertex" artifact is dropped.
      val points = Array(Array(0.0), Array(1.0))
      val f = IndexedSeq(0.0, 2.0)
      val alphaH0 = h0(points, f)
      val ripsH0 = dtmRipsH0(EuclideanMetricSpace(points), f)
      approxEqual(alphaH0, ripsH0) must beTrue
    }

    "agree with DtmRipsSimplexStream(p=2)'s H0 on a genuinely hidden vertex" >> {
      // Points 0, 1, 2 (R^1, unit spaced), f = (0, sqrt(2), 0): point 1's power cell is EMPTY (the same
      // fixture as AlphaComplexDQPVertexAttachmentSpec's hidden-vertex case), so it never appears in alpha at
      // all, while it exists in DTM-Rips from t = 2*sqrt(2) onward as a zero-length bar. Both report the SAME
      // nontrivial finite bar [0, 2.0) (from the surviving component merging via edge (0,2)) plus one
      // essential bar -- this is the real, non-trivial cross-check (`.claude/WORKLOG-dtm-filtrations.md` works
      // the arithmetic out by hand).
      val points = Array(Array(0.0), Array(1.0), Array(2.0))
      val f = IndexedSeq(0.0, math.sqrt(2.0), 0.0)
      val alphaH0 = h0(points, f)
      val ripsH0 = dtmRipsH0(EuclideanMetricSpace(points), f)
      (alphaH0 must beEqualTo(Set((0.0, 2.0), (0.0, Double.PositiveInfinity)))) and
        approxEqual(alphaH0, ripsH0)
    }
  }
