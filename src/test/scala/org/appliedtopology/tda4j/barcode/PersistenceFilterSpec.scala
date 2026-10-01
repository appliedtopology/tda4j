package org.appliedtopology.tda4j
package barcode

import org.specs2.mutable.Specification

class PersistenceFilterSpec extends Specification:
  private val inf = Double.PositiveInfinity

  "connectivityScale" should {
    "be (largest finite H0 death) - (smallest H0 birth), ignoring higher dimensions" >> {
      // H0 deaths 0.5 and 2.0 -> scale 2.0, even though an H1 bar dies later at 9.0.
      PersistenceFilter.connectivityScale(
        Array(0, 0, 0, 1),
        Array(0.0, 0.0, 0.0, 1.0),
        Array(0.5, 2.0, inf, 9.0)
      ) must beEqualTo(2.0)
    }

    "subtract the smallest H0 birth, so an offset filtration (e.g. image values) has the same scale" >> {
      PersistenceFilter.connectivityScale(Array(0, 0), Array(10.0, 10.0), Array(12.0, inf)) must beEqualTo(2.0)
    }

    "fall back to the full finite range when no H0 bar is finite" >> {
      // one component from the start (a one-basin image): only essential H0, plus an H1 bar [3, 8).
      PersistenceFilter.connectivityScale(Array(0, 1), Array(1.0, 3.0), Array(inf, 8.0)) must beEqualTo(7.0)
    }

    "be 0 when there is no finite endpoint at all (e.g. a single point)" >> {
      PersistenceFilter.connectivityScale(Array(0), Array(0.0), Array(inf)) must beEqualTo(0.0)
      PersistenceFilter.connectivityScale(Array.empty[Int], Array.empty[Double], Array.empty[Double]) must beEqualTo(0.0)
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
    val dims = Array(0, 0, 0)
    val births = Array(0.0, 0.0, 0.0)
    val deaths = Array(0.001, 1.0, inf) // scale 1.0

    "default to 1% of the connectivity scale" >> {
      PersistenceFilter.threshold(dims, births, deaths) must beCloseTo(0.01, 1e-12)
    }

    "let an absolute minPersistence win over the fraction" >> {
      PersistenceFilter.threshold(dims, births, deaths, Some(0.3), 0.5) must beEqualTo(0.3)
    }

    "reject negative or non-finite values" >> {
      (PersistenceFilter.threshold(dims, births, deaths, Some(-1.0)) must throwAn[IllegalArgumentException]) and
        (PersistenceFilter.threshold(dims, births, deaths, None, -0.1) must throwAn[IllegalArgumentException]) and
        (PersistenceFilter.threshold(dims, births, deaths, Some(inf)) must throwAn[IllegalArgumentException]) and
        (PersistenceFilter.threshold(dims, births, deaths, None, Double.NaN) must throwAn[IllegalArgumentException])
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

    "agree with connectivityScale on the same bars" >> {
      PersistenceFilter.connectivityScale(bars) must beEqualTo(1.0)
    }
  }
