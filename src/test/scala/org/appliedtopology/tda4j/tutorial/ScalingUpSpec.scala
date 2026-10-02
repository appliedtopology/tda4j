package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/scaling-up.md` and asserts every number the page quotes. */
class ScalingUpSpec extends Specification:
  sequential

  private case class Result(
    rawSizes: Map[String, Int],
    agree: Map[String, Boolean],
    barCount: Int,
    edges: (Int, Int),
    simplices: (Int, Int),
    collapsedAgrees: Boolean
  )

  private def page(): Result =
    val lab = TDAlab(2)
    import lab.{*, given}

    val metricSpace = io.CSV.readEuclideanMetricSpace("_docs/tutorials/data/noisy-circle.csv")
    def stream = streams.VietorisRips(metricSpace, maxDimension = 1)

    // Four engines on the same data. Each returns bars in its own way, so reduce them to comparable (dimension, birth, death) triples
    val naive = homology
      .SimplicialHomologyEngine[Int, CoefficientT, Double]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
    val chunks = homology
      .CellularPersistenceInChunksEngine[Simplex[Int], CoefficientT](1)
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
    val cohomology = homology
      .CellularCohomologyEngine[Simplex[Int], CoefficientT, Double]()
      .persistentCohomology(stream)
      .map(_.toTriple)
    val ripser =
      homology.PackedRipserCohomologyEngine[CoefficientT](metricSpace, 1).persistentCohomology().map(_.toTriple)

    // Drop what is not part of the answer (zero-length bars, and any dimension above the one asked for), and round the rest
    def answer(bars: Seq[(Int, Double, Double)]) =
      bars
        .filter((dim, birth, death) => dim <= 1 && (death.isInfinite || death - birth > 1e-9))
        .map((dim, birth, death) =>
          (dim, math.round(birth * 1e6), if death.isInfinite then Long.MaxValue else math.round(death * 1e6))
        )
        .sorted

    // Edge collapse: remove the edges of the Vietoris-Rips graph that cannot matter, before any triangle is built
    val collapsed = streams.EdgeCollapse.collapse(metricSpace)
    val collapsedBars = homology
      .SimplicialHomologyEngine[Int, CoefficientT, Double]()
      .persistentHomology(streams.VietorisRips(collapsed, maxDimension = 1))
      .diagramAt(Double.PositiveInfinity)

    Result(
      Map("naive" -> naive.size, "chunks" -> chunks.size, "cohomology" -> cohomology.size, "ripser" -> ripser.size),
      Map(
        "chunks" -> (answer(chunks) == answer(naive)),
        "cohomology" -> (answer(cohomology) == answer(naive)),
        "ripser" -> (answer(ripser) == answer(naive))
      ),
      answer(naive).size,
      (collapsed.stats.edgesBefore, collapsed.stats.edgesAfter),
      (stream.iterator.size, streams.VietorisRips(collapsed, maxDimension = 1).iterator.size),
      answer(collapsedBars) == answer(naive)
    )

  "scaling-up.md" should {
    lazy val r = page()
    "run four engines that return different amounts of raw output" in {
      println(r)
      r.rawSizes must beEqualTo(Map("naive" -> 23168, "chunks" -> 1544, "cohomology" -> 23168, "ripser" -> 1544))
    }
    "agree exactly on the answer: 61 bars" in
      (r.agree must beEqualTo(Map("chunks" -> true, "cohomology" -> true, "ripser" -> true)))
        .and(r.barCount must beEqualTo(61))
    "collapse 1543 edges to 321 and 24711 simplices to 1547, keeping the barcode" in
      (r.edges must beEqualTo((1543, 321)))
        .and(r.simplices must beEqualTo((24711, 1547)))
        .and(r.collapsedAgrees must beTrue)
  }
