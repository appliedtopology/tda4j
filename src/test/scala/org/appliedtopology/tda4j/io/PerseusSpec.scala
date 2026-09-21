package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.barcode.{given, *}

import org.specs2.mutable
import java.io.File
import java.nio.file.Files

class PerseusSpec extends mutable.Specification:
  given Double is Field = Field.DoubleApproximated(1e-9)
  given chc: CubicalHomologyContext[Double, Double] = CubicalHomologyContext()
  import chc.{*, given}

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("perseus-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  "cubical toplex" should {
    // Same asymmetric-shape, all-distinct-value fixture as DiphaSpec's IMAGE_DATA test -- Perseus and DIPHA
    // documented the identical first-axis-fastest convention (see the class doc), so this also cross-checks
    // that both readers agree, not just that each is self-consistent.
    "read a hand-built cubical toplex file with the first declared axis fastest-varying" >> {
      val path = tempFile(".txt")
      Files.write(java.nio.file.Paths.get(path), "2\n3\n2\n0\n1\n2\n10\n11\n12\n".getBytes)
      val stream = Perseus.readCubicalToplex(path)
      (stream.topCellValue(IndexedSeq(0, 0)) must beEqualTo(0.0)) and
        (stream.topCellValue(IndexedSeq(0, 2)) must beEqualTo(2.0)) and
        (stream.topCellValue(IndexedSeq(1, 0)) must beEqualTo(10.0)) and
        (stream.topCellValue(IndexedSeq(1, 2)) must beEqualTo(12.0))
    }

    "map a -1 filtration value to +Infinity" >> {
      val path = tempFile(".txt")
      Files.write(java.nio.file.Paths.get(path), "1\n3\n0\n-1\n2\n".getBytes)
      val stream = Perseus.readCubicalToplex(path)
      stream.topCellValue(IndexedSeq(1)) must beEqualTo(Double.PositiveInfinity)
    }

    "reject a negative grid size (periodic boundary, unsupported)" >> {
      val path = tempFile(".txt")
      Files.write(java.nio.file.Paths.get(path), "1\n-3\n0\n1\n2\n".getBytes)
      Perseus.readCubicalToplex(path) must throwA[IllegalArgumentException]
    }

    "round-trip shape/values through writeCubicalToplex, -1 for +Infinity" >> {
      val shape = IndexedSeq(2, 3)
      val values = IndexedSeq(0.0, 1.0, Double.PositiveInfinity, 3.0, 4.0, 5.0)
      val path = tempFile(".txt")
      Perseus.writeCubicalToplex(path, shape, values)
      val stream = Perseus.readCubicalToplex(path)
      (stream.topCellValue(IndexedSeq(0, 2)) must beEqualTo(Double.PositiveInfinity)) and
        (stream.topCellValue(IndexedSeq(1, 0)) must beEqualTo(3.0))
    }

    "agree with Dipha's own image-data reader on the identical grid (cross-format oracle)" >> {
      val perseusPath = tempFile(".txt")
      Files.write(java.nio.file.Paths.get(perseusPath), "2\n3\n2\n0\n1\n2\n10\n11\n12\n".getBytes)
      val diphaPath = tempFile(".dipha")
      // tda4j's own convention: shape (2,3), last axis fastest -- matching what Dipha.readImageData would itself
      // hand back for the same underlying grid.
      Dipha.writeImageData(diphaPath, IndexedSeq(2, 3), IndexedSeq(0.0, 1.0, 2.0, 10.0, 11.0, 12.0))
      val fromPerseus = Perseus.readCubicalToplex(perseusPath)
      val fromDipha = Dipha.readCubicalGridStream(diphaPath)
      val coords = for i <- 0 until 2; j <- 0 until 3 yield IndexedSeq(i, j)
      coords.forall(c => fromPerseus.topCellValue(c) == fromDipha.topCellValue(c)) must beTrue
    }

    "an end-to-end persistent homology check: a missing center pixel in a 3x3 grid is a real hole" >> {
      // A 3x3 grid of pixels, all present at value 0 except the center, which is missing (-1). The 8 present
      // pixels form a ring (a "square annulus"), homotopy equivalent to a circle: this must produce an
      // ESSENTIAL dimension-1 class -- not just "doesn't crash" -- since the missing top cell can never appear
      // to fill in the hole its own boundary edges trace out at time 0. This is the actual point of mapping -1
      // to +Infinity rather than, say, 0 or a negative sentinel: only +Infinity guarantees the missing cell's
      // own 2-cell never enters the filtration at any finite threshold.
      val path = tempFile(".txt")
      val values = Seq(0, 0, 0, 0, -1, 0, 0, 0, 0) // row-major 3x3, center missing
      val lines = ("2" +: "3" +: "3" +: values.map(_.toString)).mkString("\n")
      Files.write(java.nio.file.Paths.get(path), lines.getBytes)
      val stream = Perseus.readCubicalToplex(path)
      val bars = persistentHomology(stream).diagramAt(Double.PositiveInfinity)
      bars.exists { case (dim, _, death) => dim == 1 && death.isPosInfinity } must beTrue
    }
  }

  "persistence interval output" should
    "round-trip finite and essential bars through writePersistenceIntervals/readPersistenceIntervals" >> {
      val dir = Files.createTempDirectory("perseus-spec-intervals")
      val prefix = dir.resolve("out").toString
      val bars = Seq(
        PersistenceBar[Double](0, 1.0, 4.0),
        PersistenceBar[Double](0, 2.0),
        PersistenceBar[Double](1, 3.0, 5.0)
      )
      Perseus.writePersistenceIntervals(prefix, bars)
      val dim0 = Perseus.readPersistenceIntervals(s"${prefix}_0.txt", 0)
      val dim1 = Perseus.readPersistenceIntervals(s"${prefix}_1.txt", 1)
      (dim0.toSet must beEqualTo(bars.filter(_.dim == 0).toSet)) and
        (dim1.toSet must beEqualTo(bars.filter(_.dim == 1).toSet))
    }
