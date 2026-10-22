package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import SpecThrows.throwWith

/** The `parse...`/`format...` pairs of the file formats work on text and bytes in memory, without a file system (a
  * browser page has none). Each example parses a literal written by hand, so a convention read the wrong way round
  * (lower against upper triangle, first axis fastest) gives the wrong numbers instead of a round trip that agrees with
  * itself.
  */
class StringFormatsSpec extends Specification:

  private def rows(m: Array[Array[Double]]): Seq[Seq[Double]] = m.toSeq.map(_.toSeq)

  "CSV" should {
    "parse a point cloud from text and format it back" >> {
      val points = CSV.parsePointCloud("0, 1\n 2 3\n\n4,5\n")
      (rows(points) must beEqualTo(Seq(Seq(0.0, 1.0), Seq(2.0, 3.0), Seq(4.0, 5.0)))) and
        (CSV.formatPointCloud(points) must beEqualTo(s"${0.0},${1.0}\n${2.0},${3.0}\n${4.0},${5.0}\n"))
    }
    "name the origin of a malformed text" >> {
      CSV.parsePointCloud("1,2\n3\n", "upload.csv") must throwWith[IllegalArgumentException](
        "upload.csv: row 2 has 1 numbers, expected 2"
      )
    }
    "read the lower triangle row by row, not the upper" >> {
      val m = CSV.parseLowerTriangularDistanceMatrix("1\n2,3\n")
      (rows(m) must beEqualTo(Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0)))) and
        (CSV.formatLowerTriangularDistanceMatrix(m) must beEqualTo(s"${1.0}\n${2.0},${3.0}\n"))
    }
    "parse and format a full matrix" >> {
      val m = CSV.parseFullDistanceMatrix("0 1\n1 0\n")
      (rows(m) must beEqualTo(Seq(Seq(0.0, 1.0), Seq(1.0, 0.0)))) and
        (CSV.formatFullDistanceMatrix(m) must beEqualTo(s"${0.0},${1.0}\n${1.0},${0.0}\n"))
    }
    "round-trip a diagram with an essential bar" >> {
      val bars = CSV.parsePersistenceDiagram("0,0.0,1.5\n1,0.5,inf\n")
      (bars.map(b => (b.dim, b.lower, b.upper)) must beEqualTo(
        Seq((0, ClosedEndpoint(0.0), OpenEndpoint(1.5)), (1, ClosedEndpoint(0.5), PositiveInfinity[Double]()))
      )) and (CSV.formatPersistenceDiagram(bars) must beEqualTo(s"0,${0.0},${1.5}\n1,${0.5},inf\n"))
    }
  }

  "Ripser" should {
    "tell lower from upper triangles" >> {
      val flat = "1 2 3 4 5 6"
      val lower = Ripser.parseLowerDistanceMatrix(flat)
      val upper = Ripser.parseUpperDistanceMatrix(flat)
      // lower: d(1,0)=1, d(2,0)=2, d(2,1)=3, d(3,0)=4, d(3,1)=5, d(3,2)=6; upper: d(0,1)=1, d(0,2)=2, d(0,3)=3, d(1,2)=4, ...
      (lower(2)(1) must beEqualTo(3.0)) and (lower(3)(0) must beEqualTo(4.0)) and
        (upper(0)(3) must beEqualTo(3.0)) and (upper(1)(2) must beEqualTo(4.0)) and
        (Ripser.formatLowerDistanceMatrix(lower) must beEqualTo(s"${1.0},${2.0},${3.0},${4.0},${5.0},${6.0}\n")) and
        (Ripser.formatUpperDistanceMatrix(upper) must beEqualTo(s"${1.0},${2.0},${3.0},${4.0},${5.0},${6.0}\n"))
    }
    "read only the strictly lower triangle of a dense matrix" >> {
      val m = Ripser.parseDistanceMatrix("0 99 99\n1 0 99\n2 3 0\n")
      rows(m) must beEqualTo(Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0)))
    }
    "round-trip the point cloud text" >> {
      Ripser.formatPointCloud(Ripser.parsePointCloud("1 2\n3 4\n")) must beEqualTo(s"${1.0},${2.0}\n${3.0},${4.0}\n")
    }
    "pack the binary format as little-endian 32-bit floats" >> {
      val m = Array(Array(0.0, 1.5, 2.0), Array(1.5, 0.0, 0.25), Array(2.0, 0.25, 0.0))
      val bytes = Ripser.formatBinaryLowerDistanceMatrix(m)
      // 1.5f = 0x3FC00000, little-endian bytes 00 00 C0 3F
      (bytes.length must beEqualTo(12)) and
        (bytes.take(4).map(_ & 0xff).toSeq must beEqualTo(Seq(0x00, 0x00, 0xc0, 0x3f))) and
        (rows(Ripser.parseBinaryLowerDistanceMatrix(bytes)) must beEqualTo(rows(m)))
    }
  }

  "Gudhi" should {
    "parse an nOFF cloud with comments and format it back" >> {
      val points = Gudhi.parseOff("# a comment\n2OFF\n3 0 0\n0 0\n1 0\n0 1\n")
      (rows(points) must beEqualTo(Seq(Seq(0.0, 0.0), Seq(1.0, 0.0), Seq(0.0, 1.0)))) and
        (Gudhi.formatOff(points) must beEqualTo(s"2OFF\n3 0 0\n${0.0} ${0.0}\n${1.0} ${0.0}\n${0.0} ${1.0}\n"))
    }
    "take the dimension from the second of four fields" >> {
      val bars = Gudhi.parsePersistenceDiagram("# diagram\n17 1 0.5 2.0\n17 0 0.0 inf\n")
      (bars.map(_.dim) must beEqualTo(Seq(1, 0))) and
        (Gudhi.formatPersistenceDiagram(bars, Some(17)) must beEqualTo(s"17 1 ${0.5} ${2.0}\n17 0 ${0.0} inf\n"))
    }
  }

  "Dipha" should {
    "write the magic number first, little-endian, and read the matrix back" >> {
      val m = Array(Array(0.0, 2.0), Array(2.0, 0.0))
      val bytes = Dipha.formatDistanceMatrix(m)
      // magic, type 7, n = 2 (three Int64), then four doubles
      (bytes.length must beEqualTo(24 + 4 * 8)) and
        (java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(0) must beEqualTo(
          8067171840L
        )) and
        (java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(8) must beEqualTo(7L)) and
        (rows(Dipha.parseDistanceMatrix(bytes)) must beEqualTo(rows(m)))
    }
    "keep DIPHA's first-axis-fastest order apart from ours" >> {
      // shape (2 rows, 3 columns) in our last-axis-fastest order; DIPHA stores the grid sizes reversed: 3, 2
      val shape = IndexedSeq(2, 3)
      val values = IndexedSeq(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
      val bytes = Dipha.formatImageData(shape, values)
      val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      val (readShape, readValues) = Dipha.parseImageData(bytes)
      (buf.getLong(24) must beEqualTo(2L)) and // dimension
        (buf.getLong(32) must beEqualTo(3L)) and (buf.getLong(40) must beEqualTo(2L)) and
        (readShape must beEqualTo(shape)) and (readValues must beEqualTo(values))
    }
    "round-trip a diagram, an essential class as a negative dimension" >> {
      val bars = Seq(PersistenceBar[Double](0, 0.0, 1.0), PersistenceBar[Double](2, 0.5))
      val bytes = Dipha.formatPersistenceDiagram(bars)
      val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      (buf.getLong(24 + 24) must beEqualTo(-3L)) and
        (Dipha.parsePersistenceDiagram(bytes).map(b => (b.dim, b.upper)) must beEqualTo(
          Seq((0, OpenEndpoint(1.0)), (2, PositiveInfinity[Double]()))
        ))
    }
    "refuse a file that is not DIPHA's" >> {
      Dipha.parseDistanceMatrix(Array.fill[Byte](40)(0)) must throwWith[IllegalArgumentException]("not a DIPHA file")
    }
  }

  "Perseus" should {
    "list the first axis fastest and read -1 as a masked cell" >> {
      val (shape, values) = Perseus.parseCubicalImageData("2\n3\n2\n1 2 3 4 -1 6\n")
      (shape must beEqualTo(IndexedSeq(2, 3))) and (values.take(4) must beEqualTo(IndexedSeq(1.0, 2.0, 3.0, 4.0))) and
        (values(4).isNaN must beTrue) and
        (Perseus.formatCubicalToplex(shape, values) must beEqualTo(
          s"2\n3\n2\n${1.0}\n${2.0}\n${3.0}\n${4.0}\n-1\n${6.0}\n"
        ))
    }
    "name the origin when the grid is cut short" >> {
      Perseus.parseCubicalImageData("2\n3\n2\n1 2\n", "toplex.txt") must throwWith[IllegalArgumentException](
        "toplex.txt: the file ends before the grid is complete"
      )
    }
    "write one text per dimension and read it back" >> {
      val bars =
        Seq(PersistenceBar[Double](0, 1.0, 3.0), PersistenceBar[Double](0, 2.0), PersistenceBar[Double](1, 4.0, 5.0))
      val texts = Perseus.formatPersistenceIntervals(bars)
      (texts must beEqualTo(Map(0 -> "1 3\n2 -1\n", 1 -> "4 5\n"))) and
        (Perseus.parsePersistenceIntervals(texts(0), 0) must beEqualTo(bars.take(2)))
    }
  }
