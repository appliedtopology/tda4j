package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/comparing-barcodes.md` and asserts every number the page quotes. */
class ComparingBarcodesSpec extends Specification:
  sequential

  private case class Result(
    barCounts: Map[String, Int],
    bottleneck: Map[String, Double],
    wasserstein: Map[String, Double],
    landscape: Map[String, Double],
    image: Map[String, Double]
  )

  private def page(): Result =
    val lab = TDAlab(2)
    import lab.{*, given}

    val engine = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()

    // The loops of a point cloud: the bars of dimension 1 that survive the default 1% threshold.
    def loopDiagram(file: String) =
      val metricSpace = io.CSV.readEuclideanMetricSpace(s"_docs/tutorials/data/$file")
      val stream = streams.VietorisRips(metricSpace, maxDimension = 1)
      val bars = engine.persistentHomology(stream).barcodeAt(Double.PositiveInfinity)
      barcode.PersistenceFilter.significant(bars.filter(_.dim == 1), scale = Some(metricSpace.minimumEnclosingRadius))

    val circleA = loopDiagram("noisy-circle.csv")
    val circleB = loopDiagram("noisy-circle-b.csv")
    val eight = loopDiagram("figure-eight.csv")

    val pairs = List("A-B" -> (circleA, circleB), "A-8" -> (circleA, eight), "B-8" -> (circleB, eight))

    val bottleneck = pairs.map((name, ds) => name -> barcode.BarcodeDistance.bottleneckDistance(ds._1, ds._2)).toMap
    val wasserstein = pairs.map((name, ds) => name -> barcode.BarcodeDistance.wassersteinDistance(ds._1, ds._2)).toMap

    def l2(x: Array[Array[Double]], y: Array[Array[Double]]): Double =
      math.sqrt(x.flatten.zip(y.flatten).map((p, q) => (p - q) * (p - q)).sum)

    val landscapes = Map(
      "A" -> barcode.Vectorization.landscape(circleA, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100),
      "B" -> barcode.Vectorization.landscape(circleB, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100),
      "8" -> barcode.Vectorization.landscape(eight, numLevels = 3, tMin = 0.0, tMax = 2.0, resolution = 100)
    )
    val imageOf = Map(
      "A" -> barcode.Vectorization.persistenceImage(circleA, 0.1, (0.0, 2.0), (0.0, 2.0), 40, 40, Some(1.0)),
      "B" -> barcode.Vectorization.persistenceImage(circleB, 0.1, (0.0, 2.0), (0.0, 2.0), 40, 40, Some(1.0)),
      "8" -> barcode.Vectorization.persistenceImage(eight, 0.1, (0.0, 2.0), (0.0, 2.0), 40, 40, Some(1.0))
    )
    Result(
      Map("A" -> circleA.size, "B" -> circleB.size, "8" -> eight.size),
      bottleneck,
      wasserstein,
      Map("A-B" -> l2(landscapes("A"), landscapes("B")), "A-8" -> l2(landscapes("A"), landscapes("8")), "B-8" -> l2(landscapes("B"), landscapes("8"))),
      Map("A-B" -> l2(imageOf("A"), imageOf("B")), "A-8" -> l2(imageOf("A"), imageOf("8")), "B-8" -> l2(imageOf("B"), imageOf("8")))
    )

  "comparing-barcodes.md" should {
    lazy val r = page()
    "count the loops: one in each circle, two in the figure eight" in {
      println(r)
      r.barCounts must beEqualTo(Map("A" -> 1, "B" -> 1, "8" -> 2))
    }
    "put the two circles close together and the figure eight far from both, in every measure" in {
      List(r.bottleneck, r.wasserstein, r.landscape, r.image).forall(m => m("A-B") < m("A-8") && m("A-B") < m("B-8")) must beTrue
    }
    "quote the distances" in {
      (r.bottleneck("A-B") must beCloseTo(0.040, 0.001))
        .and(r.bottleneck("A-8") must beCloseTo(0.556, 0.001))
        .and(r.bottleneck("B-8") must beCloseTo(0.518, 0.001))
        .and(r.wasserstein("A-8") must beCloseTo(0.864, 0.001))
        .and(r.bottleneck("A-8") / r.bottleneck("A-B") must beGreaterThan(10.0))
    }
  }
