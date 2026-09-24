package org.appliedtopology.tda4j
package alpha

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

/** Regression tests for the vertex-value/presence fix to `AlphaComplexDQPBuilder.compute()`: a vertex's own
  * filtration value is `-weight(x)` ONLY when x's own point lies inside its own restricted power cell V_x --
  * unconditionally assuming this (the previous behaviour) is wrong whenever some Cech-neighbour's weight
  * dominates x's own point, which mild weights (as in `AlphaComplexDQPWeightedSpec`'s `[-0.3, 0.3]` range) never
  * trigger but DTM weights do routinely. See `.claude/WORKLOG-dtm-filtrations.md` for the full derivation; these
  * two fixtures are its hand-worked oracles, independent of any DTM machinery (plain `PowerDistance.euclidean`
  * weights).
  */
class AlphaComplexDQPVertexAttachmentSpec extends org.specs2.mutable.Specification:
  "a vertex outside its own restricted power cell, but with a nonempty one," should {
    // Points 0, 1 on the line, weights (0, -4). pi_0(y) = y^2, pi_1(y) = (y-1)^2 + 4.
    // pi_1(1) = 4 > pi_0(1) = 1, so point 1 itself is NOT in V_1 -- but V_1 = [2.5, infinity) is nonempty,
    // and the restricted minimum of pi_1 over V_1 is at the bisector y = 2.5, where
    // pi_1(2.5) = pi_0(2.5) = 6.25. The OLD code reported -weight(1) = 4 here: a spurious 2.25-long H0 bar.
    "get the restricted-minimum value, not -weight(x), matching its own attaching edge exactly" >> {
      val ac = AlphaComplexDQP.weighted(Array(Array(0.0), Array(1.0)), Array(0.0, -4.0), Double.PositiveInfinity, 1)
      ac.filtrationValue(Simplex(0)) must beCloseTo(0.0, 1e-9)
      ac.filtrationValue(Simplex(1)) must beCloseTo(6.25, 1e-9)
      ac.filtrationValue(Simplex(0, 1)) must beCloseTo(6.25, 1e-9)
      // A vertex is never *later* than the edge that attaches it -- the old bug's failure mode was the reverse.
      ac.filtrationValue(Simplex(1)) must be_<=(ac.filtrationValue(Simplex(0, 1)))
    }
  }

  "a vertex with a genuinely empty power cell" should {
    // Points 0, 1, 2 on the line, weights (0, -2, 0). pi_1 is beaten everywhere: pi_0 <= pi_1 for y <= 1.5 and
    // pi_2 <= pi_1 for y >= 0.5, and [0.5, 1.5] already covers the gap -- so V_1 = {} for every real y, and
    // point 1 is a genuinely redundant site (in the regular-triangulation sense): it must not appear as a
    // 0-simplex, or anywhere else, at all.
    "be dropped from the complex entirely, not merely assigned a wrong value" >> {
      val ac =
        AlphaComplexDQP.weighted(Array(Array(0.0), Array(1.0), Array(2.0)), Array(0.0, -2.0, 0.0), Double.PositiveInfinity, 1)
      ac.contains(Simplex(1)) must beFalse
      ac.cellsOfDimension(0).toSet must beEqualTo(Set(Simplex(0), Simplex(2)))
      ac.contains(Simplex(0, 2)) must beTrue
      ac.contains(Simplex(0, 1)) must beFalse
      ac.contains(Simplex(1, 2)) must beFalse
    }
  }
