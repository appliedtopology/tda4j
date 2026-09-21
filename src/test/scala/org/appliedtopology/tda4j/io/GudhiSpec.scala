package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.{given, *}

import org.specs2.mutable
import java.io.File

class GudhiSpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("gudhi-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  "OFF point clouds" should {
    "read a plain 3D OFF file, skipping comments" >> {
      val path = tempFile(".off")
      val out = new java.io.PrintWriter(path)
      out.println("OFF")
      out.println("# a comment")
      out.println("2 0 0")
      out.println("1.0 2.0 3.0")
      out.println("4.0 5.0 6.0")
      out.close()
      Gudhi.readOff(path).map(_.toSeq).toSeq must beEqualTo(Seq(Seq(1.0, 2.0, 3.0), Seq(4.0, 5.0, 6.0)))
    }

    "read a 4-dimensional nOFF file" >> {
      val path = tempFile(".off")
      val out = new java.io.PrintWriter(path)
      out.println("4OFF")
      out.println("2 0 0")
      out.println("1.0 1.0 0.0 0.0")
      out.println("7.0 0.0 0.0 2.0")
      out.close()
      Gudhi.readOff(path).map(_.toSeq).toSeq must beEqualTo(Seq(Seq(1.0, 1.0, 0.0, 0.0), Seq(7.0, 0.0, 0.0, 2.0)))
    }

    "round-trip a 3D point cloud through writeOff" >> {
      val points = Seq(Seq(0.0, 0.0, 0.0), Seq(1.0, 2.0, 3.0), Seq(4.0, 5.0, 6.0))
      val path = tempFile(".off")
      Gudhi.writeOff(path, points)
      Gudhi.readOff(path).map(_.toSeq).toSeq must beEqualTo(points)
    }

    "agree with Csv on the same point cloud (cross-format oracle)" >> {
      val points = Seq(Seq(0.0, 0.0), Seq(3.0, 4.0), Seq(6.0, 8.0))
      val offPath = tempFile(".off")
      val csvPath = tempFile(".csv")
      Gudhi.writeOff(offPath, points)
      Csv.writePointCloud(csvPath, points)
      val fromOff = Gudhi.readEuclideanMetricSpace(offPath)
      val fromCsv = Csv.readEuclideanMetricSpace(csvPath)
      (fromOff.distance(0, 1) must beEqualTo(fromCsv.distance(0, 1))) and
        (fromOff.distance(1, 2) must beEqualTo(fromCsv.distance(1, 2)))
    }
  }

  "persistence diagrams" should {
    "read 2-, 3-, and 4-column lines, defaulting a 2-column line's dimension to 0" >> {
      val path = tempFile(".pers")
      val out = new java.io.PrintWriter(path)
      out.println("# a comment")
      out.println("2.7 3.7") // 2-column: dim defaults to 0
      out.println("2 9.6 14.0") // 3-column
      out.println("3 2 3. inf") // 4-column: field=3, dim=2, birth=3, death=inf
      out.close()
      val bars = Gudhi.readPersistenceDiagram(path)
      bars must beEqualTo(
        Seq(
          PersistenceBar[Double](0, 2.7, 3.7),
          PersistenceBar[Double](2, 9.6, 14.0),
          PersistenceBar[Double](2, 3.0)
        )
      )
    }

    "round-trip through writePersistenceDiagram" >> {
      val bars = Seq(PersistenceBar[Double](0, 1.0, 2.0), PersistenceBar[Double](1, 0.5))
      val path = tempFile(".pers")
      Gudhi.writePersistenceDiagram(path, bars)
      Gudhi.readPersistenceDiagram(path) must beEqualTo(bars)
    }
  }
