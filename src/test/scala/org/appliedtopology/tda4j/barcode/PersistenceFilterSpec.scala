package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

class PersistenceFilterSpec extends Specification:
  private val inf = Double.PositiveInfinity

  "filtrationRange" should {
    "be (largest finite endpoint) - (smallest finite birth), ignoring infinite endpoints" >> {
      PersistenceFilter.filtrationRange(Array(0.0, 0.0, 1.0), Array(0.5, inf, 8.0)) must beEqualTo(8.0)
    }

    "be 0 when there is no finite endpoint at all (e.g. a single point)" >> {
      (PersistenceFilter.filtrationRange(Array(0.0), Array(inf)) must beEqualTo(0.0)) and
        (PersistenceFilter.filtrationRange(Array.empty[Double], Array.empty[Double]) must beEqualTo(0.0))
    }
  }

  "keptIndices" should {
    "keep EVERYTHING at threshold 0, zero-persistence bars included" >> {
      PersistenceFilter.keptIndices(Array(0.0, 1.0, 0.0), Array(0.0, 1.0, inf), 0.0).toSeq must beEqualTo(Seq(0, 1, 2))
    }

    "drop a bar whose persistence is exactly the threshold (a bar must extend BEYOND it)" >> {
      PersistenceFilter.keptIndices(Array(0.0, 0.0), Array(0.5, 0.5000001), 0.5).toSeq must beEqualTo(Seq(1))
    }

    "always keep essential bars, however large the threshold" >> {
      PersistenceFilter.keptIndices(Array(0.0, 0.0), Array(1.0, inf), 1e9).toSeq must beEqualTo(Seq(1))
    }
  }

  "threshold" should {
    "default to a fraction of the supplied scale" >> {
      PersistenceFilter.threshold(None, PersistenceFilter.DefaultFraction, 2.0, 99.0) must beCloseTo(0.02, 1e-12)
    }

    "let an absolute minPersistence win over the fraction, without ever evaluating the scale" >> {
      PersistenceFilter.threshold(Some(0.3), 0.5, throw new AssertionError("scale evaluated"), 99.0) must beEqualTo(0.3)
    }

    "not evaluate the scale for fraction 0 (it can be expensive and is pointless)" >> {
      PersistenceFilter.threshold(None, 0.0, throw new AssertionError("scale evaluated"), 99.0) must beEqualTo(0.0)
    }

    "fall back to the barcode's own range when the scale is not finite" >> {
      (PersistenceFilter.threshold(None, 0.1, inf, 5.0) must beCloseTo(0.5, 1e-12)) and
        (PersistenceFilter.threshold(None, 0.1, Double.NaN, 5.0) must beCloseTo(0.5, 1e-12))
    }

    "be 0 for a scale of 0 (a single point: nothing is hidden)" >> {
      PersistenceFilter.threshold(None, 0.01, 0.0, 0.0) must beEqualTo(0.0)
    }

    "reject negative or non-finite values" >> {
      (PersistenceFilter.threshold(Some(-1.0), 0.01, 1.0, 1.0) must throwAn[IllegalArgumentException]) and
        (PersistenceFilter.threshold(None, -0.1, 1.0, 1.0) must throwAn[IllegalArgumentException]) and
        (PersistenceFilter.threshold(Some(inf), 0.01, 1.0, 1.0) must throwAn[IllegalArgumentException]) and
        (PersistenceFilter.threshold(None, Double.NaN, 1.0, 1.0) must throwAn[IllegalArgumentException])
    }
  }

  "significant" should {
    val bars = List(
      PersistenceBar[Double, String](0, ClosedEndpoint(0.0), OpenEndpoint(0.001), Some("noise")),
      PersistenceBar[Double, String](0, ClosedEndpoint(0.0), OpenEndpoint(1.0), Some("merge")),
      PersistenceBar[Double, String](0, ClosedEndpoint(0.0), PositiveInfinity[Double](), Some("essential"))
    )

    "drop the short bar by default, preserving order and annotations" >> {
      PersistenceFilter.significant(bars).flatMap(_.annotation) must beEqualTo(List("merge", "essential"))
    }

    "keep everything when asked for no threshold" >> {
      (PersistenceFilter.significant(bars, Some(0.0)).size must beEqualTo(3)) and
        (PersistenceFilter.significant(bars, None, 0.0).size must beEqualTo(3))
    }

    "use the supplied scale, else the bars' own finite range" >> {
      // finite range of these bars is 1.0, so by default the cut is 0.01 and the 0.001 bar goes; with scale 0.05 the
      // cut is 0.0005 and it stays
      (PersistenceFilter.significant(bars).size must beEqualTo(2)) and
        (PersistenceFilter.significant(bars, scale = Some(0.05)).size must beEqualTo(3))
    }
  }
