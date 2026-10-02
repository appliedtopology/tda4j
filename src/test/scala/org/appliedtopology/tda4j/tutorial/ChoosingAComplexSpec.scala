package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/choosing-a-complex.md` and asserts every number the page quotes. */
class ChoosingAComplexSpec extends Specification:
  sequential

  private def page(): Map[String, (Int, (Double, Double))] =
    val lab = TDAlab(2)
    import lab.{*, given}

    val points = io.CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
    val metricSpace = streams.EuclideanMetricSpace(points)
    val engine = homology.SimplicialHomologyEngine[Int, CoefficientT, Double]()

    def summarize(stream: streams.LevelwiseSimplexStream[Int, Double]): (Int, (Double, Double)) =
      val size = stream.iterator.size
      val bars = engine.persistentHomology(stream).diagramAt(Double.PositiveInfinity)
      val loop = bars.filter(_._1 == 1).maxBy((_, birth, death) => death - birth)
      (size, (loop._2, loop._3))

    val landmarks = streams.LandmarkSelector.maxmin(metricSpace, 15).landmarks
    Map(
      "vietoris-rips" -> summarize(streams.VietorisRips(metricSpace, maxDimension = 1)),
      "cech" -> summarize(streams.Cech(metricSpace, maxDimension = 1)),
      "alpha" -> summarize(alpha.AlphaShapes(points.toSeq)),
      "sparse-rips" -> summarize(streams.SparseRips(metricSpace, epsilon = 0.5, maxDimension = 1)),
      "witness" -> summarize(streams.Witness(metricSpace, landmarks, maxDimension = 1))
    )

  "choosing-a-complex.md" should {
    lazy val r = page()
    "report the simplex counts it quotes" in {
      println(r)
      r.view.mapValues(_._1).toMap must beEqualTo(
        Map("vietoris-rips" -> 24711, "cech" -> 36050, "alpha" -> 325, "sparse-rips" -> 1730, "witness" -> 441)
      )
    }
    "find the loop in every complex" in {
      r.values.forall { (_, loop) => loop._2 - loop._1 > 0.4 } must beTrue
    }
    "give Cech and alpha the same loop, to six digits" in {
      (r("cech")._2._1 must beCloseTo(r("alpha")._2._1, 1e-6)).and(r("cech")._2._2 must beCloseTo(r("alpha")._2._2, 1e-6))
    }
    "show VR births at about twice the Cech radius, but dying at sqrt(3) against 1" in {
      (r("vietoris-rips")._2._1 / r("cech")._2._1 must beCloseTo(2.0, 0.02))
        .and(r("vietoris-rips")._2._2 must beCloseTo(1.707, 0.001))
        .and(r("cech")._2._2 must beCloseTo(0.935, 0.001))
    }
    "quote the loops of the sparse and witness complexes" in {
      (r("sparse-rips")._2._1 must beCloseTo(0.708, 0.001))
        .and(r("sparse-rips")._2._2 must beCloseTo(1.789, 0.001))
        .and(r("witness")._2._1 must beCloseTo(0.278, 0.001))
        .and(r("witness")._2._2 must beCloseTo(0.742, 0.001))
    }
  }
