package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import SpecThrows.throwWith

/** A file in the wrong format must be refused with a message, never read as a different complex: a full matrix handed
  * to the lower-triangular reader used to come back as a 4-point metric space.
  */
class MalformedInputSpec extends Specification:
  sequential

  private def file(content: String): String =
    val path = FileAccess.tempFile("tda4j-malformed-", ".txt")
    FileAccess.writeText(path, content)
    path

  "CSV.readLowerTriangularDistanceMatrix" should {
    "read a lower triangle" >> {
      CSV.readLowerTriangularDistanceMatrix(file("1\n2,3\n")).map(_.toSeq).toSeq must beEqualTo(
        Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0))
      )
    }
    "refuse a full matrix" >> {
      CSV.readLowerTriangularDistanceMatrix(file("0,1,2\n1,0,3\n2,3,0\n")) must throwWith[IllegalArgumentException](
        "row 1 has 3 numbers, expected 1"
      )
    }
    "refuse ragged rows" >> {
      CSV.readLowerTriangularDistanceMatrix(file("1\n2,3,4\n")) must throwWith[IllegalArgumentException](
        "row 2 has 3 numbers, expected 2"
      )
    }
  }

  "CSV.readFullDistanceMatrix and readPointCloud" should {
    "refuse a matrix that is not square" >> {
      CSV.readFullDistanceMatrix(file("0,1,2\n1,0\n")) must throwWith[IllegalArgumentException](
        "row 1 has 3 numbers, expected 2"
      )
    }
    "refuse points of different dimensions" >> {
      CSV.readPointCloud(file("1,2\n3\n")) must throwWith[IllegalArgumentException]("row 2 has 1 numbers, expected 2")
    }
    "name the line and the token of something that is not a number" >> {
      CSV.readPointCloud(file("x,y\n1,2\n")) must throwWith[IllegalArgumentException]("line 1: 'x' is not a number")
    }
  }

  "Ripser.readDistanceMatrix" should {
    "ignore blank lines at the end" >> {
      Ripser.readDistanceMatrix(file("0 1 2\n1 0 3\n2 3 0\n\n\n")).map(_.toSeq).toSeq must beEqualTo(
        Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0))
      )
    }
    "refuse a line shorter than its row of the lower triangle" >> {
      Ripser.readDistanceMatrix(file("0 1 2\n1 0 3\n2\n")) must throwWith[IllegalArgumentException](
        "line 3 has 1 numbers"
      )
    }
  }

  "Perseus.readCubicalImageData" should {
    "refuse a file that ends early" >> {
      Perseus.readCubicalImageData(file("2\n3\n3\n1 2 3 4 5\n")) must throwWith[IllegalArgumentException](
        "ends before the grid is complete"
      )
    }
    "refuse values beyond the grid" >> {
      Perseus.readCubicalImageData(file("1\n3\n1 2 3 4 5\n")) must throwWith[IllegalArgumentException](
        "values beyond the 3 grid"
      )
    }
  }
