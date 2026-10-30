package org.appliedtopology.tda4j

import scala.language.experimental.modularity

import org.specs2.mutable.Specification

class TakensEmbeddingSpec extends Specification:

  private def rows(cloud: PointCloud): List[List[Double]] = cloud.points.map(_.toList).toList

  "TakensEmbedding" should {
    "take the windows of the series, delay samples apart" >> {
      val series = Seq(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
      (rows(TakensEmbedding(series, dimension = 3, delay = 2)) must beEqualTo(
        List(List(0.0, 2.0, 4.0), List(1.0, 3.0, 5.0))
      )) and
        (rows(TakensEmbedding(series, dimension = 2)) must beEqualTo(
          List.tabulate(5)(i => List(i.toDouble, i + 1.0))
        )) and
        (rows(TakensEmbedding(Seq.tabulate(8)(_.toDouble), dimension = 2, delay = 1, stride = 2)) must
          beEqualTo(List(List(0.0, 1.0), List(2.0, 3.0), List(4.0, 5.0), List(6.0, 7.0))))
    }

    "put the rows of a vector series one after the other" >> {
      val series = Seq(Seq(0.0, 10.0), Seq(1.0, 11.0), Seq(2.0, 12.0))
      rows(TakensEmbedding.multivariate(series, dimension = 2)) must
        beEqualTo(List(List(0.0, 10.0, 1.0, 11.0), List(1.0, 11.0, 2.0, 12.0)))
    }

    "reject a window longer than the series and empty parameters" >> {
      (TakensEmbedding(Seq(1.0, 2.0, 3.0), dimension = 3, delay = 2) must throwAn[IllegalArgumentException]) and
        (TakensEmbedding(Seq(1.0, 2.0, 3.0), dimension = 0) must throwAn[IllegalArgumentException]) and
        (TakensEmbedding(Seq(1.0, 2.0, 3.0), dimension = 2, stride = 0) must throwAn[IllegalArgumentException])
    }

    "find the loop of a periodic signal, and none in a ramp" >> {
      val sine = Seq.tabulate(200)(t => math.sin(2 * math.Pi * t / 40))
      val ramp = Seq.tabulate(200)(_ * 0.01)
      val loop = Persistence(TakensEmbedding(sine, dimension = 2, delay = 10), maxDimension = 1).dim(1).longest.get
      (loop.persistence must beGreaterThan(0.8)) and
        (Persistence(TakensEmbedding(ramp, dimension = 2, delay = 10), maxDimension = 1)
          .dim(1)
          .longerThan(0.1)
          .bars must
          beEmpty)
    }

    "suggest a quarter of the period as the delay of a sinusoid" >> {
      val sine = Seq.tabulate(400)(t => math.sin(2 * math.Pi * t / 40))
      TakensEmbedding.delayByAutocorrelation(sine, maxLag = 30) must beBetween(9, 11)
    }

    "refuse a constant series" >> {
      TakensEmbedding.delayByAutocorrelation(Seq.fill(50)(1.0), 10) must throwAn[IllegalArgumentException]
    }
  }
