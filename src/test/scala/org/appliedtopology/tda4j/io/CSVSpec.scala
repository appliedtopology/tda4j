package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.{given, *}

import org.specs2.mutable
import java.io.File

class CsvSpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("csv-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  "point clouds" should {
    "round-trip through readPointCloud/writePointCloud" >> {
      val points = Seq(Seq(1.0, 2.0, 3.0), Seq(4.0, 5.0, 6.0))
      val path = tempFile(".csv")
      CSV.writePointCloud(path, points.map(_.toArray).toArray)
      CSV.readPointCloud(path).map(_.toSeq).toSeq must beEqualTo(points)
    }

    "build a usable EuclideanMetricSpace" >> {
      val path = tempFile(".csv")
      CSV.writePointCloud(path, Array(Array(0.0, 0.0), Array(3.0, 4.0)))
      CSV.readEuclideanMetricSpace(path).distance(0, 1) must beEqualTo(5.0)
    }
  }

  "full distance matrices" should
    "round-trip through readFullDistanceMatrix/writeFullDistanceMatrix" >> {
      val m = Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0))
      val path = tempFile(".csv")
      CSV.writeFullDistanceMatrix(path, m.map(_.toArray).toArray)
      CSV.readFullDistanceMatrix(path).map(_.toSeq).toSeq must beEqualTo(m)
    }

  "lower-triangular distance matrices" should {
    // All-distinct entries: a symmetric-looking matrix could hide a row/column swap, this can't.
    "expand row i (1 until n) as d(i,0),...,d(i,i-1), all-distinct values" >> {
      val path = tempFile(".csv")
      val out = new java.io.PrintWriter(path)
      out.println("1")
      out.println("2,3")
      out.close()
      val m = CSV.readLowerTriangularDistanceMatrix(path)
      (m(1)(0) must beEqualTo(1.0)) and
        (m(2)(0) must beEqualTo(2.0)) and
        (m(2)(1) must beEqualTo(3.0)) and
        (m(0)(1) must beEqualTo(1.0)) and // symmetrized
        (m(0)(0) must beEqualTo(0.0))
    }

    "round-trip through writeLowerTriangularDistanceMatrix" >> {
      val m = IndexedSeq(
        IndexedSeq(0.0, 1.0, 2.0),
        IndexedSeq(1.0, 0.0, 3.0),
        IndexedSeq(2.0, 3.0, 0.0)
      )
      val path = tempFile(".csv")
      CSV.writeLowerTriangularDistanceMatrix(path, m.map(_.toArray).toArray)
      CSV.readLowerTriangularDistanceMatrix(path).map(_.toSeq).toSeq must beEqualTo(m.map(_.toSeq))
    }

    "agree with Ripser's own lower-distance flat convention on the same matrix" >> {
      val m = IndexedSeq(
        IndexedSeq(0.0, 1.0, 2.0),
        IndexedSeq(1.0, 0.0, 3.0),
        IndexedSeq(2.0, 3.0, 0.0)
      )
      val csvPath = tempFile(".csv")
      val ripserPath = tempFile(".ripser")
      CSV.writeLowerTriangularDistanceMatrix(csvPath, m.map(_.toArray).toArray)
      Ripser.writeLowerDistanceMatrix(ripserPath, m.map(_.toArray).toArray)
      CSV.readLowerTriangularDistanceMatrix(csvPath).map(_.toSeq).toSeq must
        beEqualTo(Ripser.readLowerDistanceMatrix(ripserPath).map(_.toSeq).toSeq)
    }
  }

  "persistence diagrams" should
    "round-trip finite, essential, and negative-infinity-lower bars" >> {
      val path = tempFile(".csv")
      val bars = Seq(
        PersistenceBar[Double](0, 1.0, 2.0),
        PersistenceBar[Double](1, 0.5),
        new PersistenceBar[Double, Nothing](0, NegativeInfinity(), ClosedEndpoint(3.0))
      )
      CSV.writePersistenceDiagram(path, bars)
      val readBack = CSV.readPersistenceDiagram(path)
      readBack must beEqualTo(bars)
    }
