package org.appliedtopology.tda4j
package matlab

import org.specs2.mutable.Specification

/** `TDA4j.takensEmbedding`, `takensEmbeddingOfVectors` and `delayByAutocorrelation`: the library calls of the same
  * arguments, with arrays in and out.
  */
class TakensFacadeSpec extends Specification:
  private val series = Array.tabulate(60)(t => math.sin(2 * math.Pi * t / 20))

  "takensEmbedding" should {
    "be the windows of the series, delay apart, one for every stride-th start" in {
      val windows = TDA4j.takensEmbedding(Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), 3, 2, 1)
      val strided = TDA4j.takensEmbedding(Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0), 2, 1, 3)
      (windows.map(_.toSeq).toSeq must beEqualTo(Seq(Seq(0.0, 2.0, 4.0), Seq(1.0, 3.0, 5.0)))) and
        (strided.map(_.toSeq).toSeq must beEqualTo(Seq(Seq(0.0, 1.0), Seq(3.0, 4.0))))
    }

    "default the stride to 1, and say what is wrong with a window longer than the series" in {
      val windows = TDA4j.takensEmbedding(series, 2, 5)
      (windows.length must beEqualTo(55)) and
        (TDA4j.takensEmbedding(Array(1.0, 2.0), 3, 2) must throwAn[IllegalArgumentException])
    }
  }

  "takensEmbeddingOfVectors" should {
    "put the rows of a window one after the other" in {
      val rows = Array(Array(0.0, 10.0), Array(1.0, 11.0), Array(2.0, 12.0), Array(3.0, 13.0))
      TDA4j.takensEmbeddingOfVectors(rows, 2, 2).map(_.toSeq).toSeq must beEqualTo(
        Seq(Seq(0.0, 10.0, 2.0, 12.0), Seq(1.0, 11.0, 3.0, 13.0))
      )
    }
  }

  "delayByAutocorrelation" should {
    "be a quarter of the period of a sinusoid" in {
      TDA4j.delayByAutocorrelation(series, 15) must beBetween(4, 6)
    }
  }
end TakensFacadeSpec
