package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** The default persistence threshold, through the real `TDA4j` facade (NOT `FullBarcode`, which switches it off). */
class PersistenceThresholdSpec extends Specification:
  private val inf = Double.PositiveInfinity

  // Collinear points 0, 0.001, 1: H0 merges at 0.001 (diameter) and 0.999, so the connectivity scale is 0.999 and
  // the default threshold ~0.00999 -- the first merge's bar [0, 0.001) is below it, the second's [0, 0.999) is not.
  private val points = Array(Array(0.0, 0.0), Array(0.001, 0.0), Array(1.0, 0.0))

  private def rows(r: PersistenceResult): Seq[(Int, Double, Double)] =
    r.toArray().toSeq.map(a => (a(0).toInt, a(1), a(2)))

  "the default" should {
    "hide bars with persistence <= 1% of the connectivity scale, but never an essential bar" >> {
      val r = TDA4j.computeFromPoints(points)
      (r.size() must beEqualTo(2)) and
        (r.hiddenCount() must beEqualTo(1)) and
        (r.persistenceThreshold() must beCloseTo(0.00999, 1e-9)) and
        (rows(r).map(_._3).exists(_.isPosInfinity) must beTrue) and
        (rows(r).forall(row => row._3.isPosInfinity || row._3 - row._2 > r.persistenceThreshold()) must beTrue)
    }

    "apply to every entry point: points, distance matrix, relation, landmarks, cubical image" >> {
      val fromDistances = TDA4j.computeFromDistanceMatrix(Array(Array(0.0, 0.001, 1.0), Array(0.001, 0.0, 0.999), Array(1.0, 0.999, 0.0)))
      // all points as landmarks make the witness complex contractible at 0 (every finite bar is [0,0), scale 0), so
      // the DEFAULT hides nothing there -- use an explicit threshold to show the key is accepted and applied.
      val fromLandmarks =
        TDA4j.computeFromPointsAndLandmarks(points, Array(0, 1, 2), Array("maxDimension", "1", "minPersistence", "5"))
      // a 1-D-ish image: two basins joined by a high ridge, plus a 0.001-deep dimple
      val image = TDA4j.computeFromCubicalImage(Array(5), Array(0.0, 0.001, 0.0, 1.0, 0.5), Array("maxDimension", "1"))
      val relation = TDA4j.computeFromRelation(Array(Array(0.0, 0.001, 1.0), Array(0.001, 0.0, 0.999), Array(1.0, 0.999, 0.0)))
      (fromDistances.hiddenCount() must beGreaterThan(0)) and
        (fromLandmarks.size() must beEqualTo(1)) and (fromLandmarks.persistenceThreshold() must beEqualTo(5.0)) and
        (image.persistenceThreshold() must beGreaterThan(0.0)) and
        (relation.persistenceThreshold() must be_>=(0.0))
    }
  }

  "explicit settings" should {
    "minPersistence=0 and minPersistenceFraction=0 both report every bar" >> {
      val a = TDA4j.computeFromPoints(points, Array("minPersistence", "0"))
      val b = TDA4j.computeFromPoints(points, Array("minPersistenceFraction", "0"))
      (a.size() must beEqualTo(3)) and (b.size() must beEqualTo(3)) and (a.hiddenCount() must beEqualTo(0)) and
        (a.persistenceThreshold() must beEqualTo(0.0))
    }

    "an absolute minPersistence is in the barcode's own units, independent of the connectivity scale" >> {
      (TDA4j.computeFromPoints(points, Array("minPersistence", "0.0005")).size() must beEqualTo(3)) and
        (TDA4j.computeFromPoints(points, Array("minPersistence", "2")).size() must beEqualTo(1)) // essential only
    }

    "a different fraction moves the cut" >> {
      (TDA4j.computeFromPoints(points, Array("minPersistenceFraction", "0.0005")).size() must beEqualTo(3)) and
        (TDA4j.computeFromPoints(points, Array("minPersistenceFraction", "0.5")).size() must beEqualTo(2))
    }

    "reject both keys together, and negative or non-numeric values -- before computing anything" >> {
      (TDA4j.computeFromPoints(points, Array("minPersistence", "0.1", "minPersistenceFraction", "0.1")) must
        throwAn[IllegalArgumentException]) and
        (TDA4j.computeFromPoints(points, Array("minPersistence", "-1")) must throwAn[IllegalArgumentException]) and
        (TDA4j.computeFromPoints(points, Array("minPersistenceFraction", "abc")) must throwAn[IllegalArgumentException])
    }

    "be rejected by the landmark-selection entry point, which produces no barcode" >> {
      TDA4j.selectLandmarksFromPoints(points, Array("numLandmarks", "2", "minPersistence", "0")) must
        throwAn[IllegalArgumentException]
    }
  }

  "the filtered view" should {
    "keep each reported bar's OWN representative (index remapping), even when an earlier bar is hidden" >> {
      // bars: 0 hidden (persistence 0.001), 1 and 2 visible. Cycle provider tags each FULL index.
      val r = new PersistenceResult(
        Array(0, 0, 0),
        Array(0.0, 0.0, 0.0),
        Array(0.001, 1.0, inf),
        i => (Array(Array(i)), Array(i.toDouble)),
        () => throw new UnsupportedOperationException("not needed")
      ).withPersistenceThreshold(None, 0.01)
      (r.size() must beEqualTo(2)) and
        (r.cycleVertices(0).map(_.toSeq).toSeq must beEqualTo(Seq(Seq(1)))) and
        (r.cycleCoefficients(1).toSeq must beEqualTo(Seq(2.0)))
    }

    "give real bars the real representative of the matching full-barcode bar" >> {
      val full = TDA4j.computeFromPoints(points, Array("minPersistence", "0"))
      val filtered = TDA4j.computeFromPoints(points)
      def rep(r: PersistenceResult, i: Int) = (r.cycleVertices(i).map(_.toSeq).toSeq, r.cycleCoefficients(i).toSeq)
      forall(0 until filtered.size()) { i =>
        val j = (0 until full.size()).find(k =>
          full.dimension(k) == filtered.dimension(i) && full.birth(k) == filtered.birth(i) &&
            full.death(k) == filtered.death(i)
        ).get
        rep(filtered, i) must beEqualTo(rep(full, j))
      }
    }

    "not change distances or vectorizations: they always use the complete barcode" >> {
      val full = TDA4j.computeFromPoints(points, Array("minPersistence", "0"))
      val filtered = TDA4j.computeFromPoints(points)
      (filtered.bottleneckDistance(full, 0) must beEqualTo(0.0)) and
        (filtered.wassersteinDistance(full, 0) must beEqualTo(0.0)) and
        (filtered.toArrayUnfiltered().toSeq.map(_.toSeq) must beEqualTo(full.toArray().toSeq.map(_.toSeq)))
    }
  }

  "a single point (no finite bar at all)" should {
    "report its one essential bar, hiding nothing" >> {
      val r = TDA4j.computeFromPoints(Array(Array(0.0, 0.0)))
      (r.size() must beEqualTo(1)) and (r.hiddenCount() must beEqualTo(0)) and (r.persistenceThreshold() must beEqualTo(0.0))
    }
  }
