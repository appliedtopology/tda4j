package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.{given, *}

import org.specs2.mutable
import java.io.File
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Paths}

/** Byte-level fixtures against DIPHA's own documented binary layout (`.claude/WORKLOG-io-module.md`) -- a
  * write-then-read round trip alone can't catch a self-consistently-wrong convention (wrong axis order, wrong
  * essential-class encoding), so every format here gets at least one hand-built-bytes fixture, not just a round trip.
  */
class DiphaSpec extends mutable.Specification:
  private val Magic = 8067171840L

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("dipha-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  private def writeBuf(path: String, byteSize: Int)(fill: ByteBuffer => Unit): Unit =
    val buf = ByteBuffer.allocate(byteSize).order(ByteOrder.LITTLE_ENDIAN)
    fill(buf)
    Files.write(Paths.get(path), buf.array())

  "distance matrix" should {
    "read a hand-built DISTANCE_MATRIX file (magic, type=7, n, n*n doubles row-major)" >> {
      val path = tempFile(".dipha")
      writeBuf(path, 8 + 8 + 8 + 4 * 8) { buf =>
        buf.putLong(Magic)
        buf.putLong(7L)
        buf.putLong(2L)
        buf.putDouble(0.0)
        buf.putDouble(5.0)
        buf.putDouble(5.0)
        buf.putDouble(0.0)
      }
      Dipha.readDistanceMatrix(path).map(_.toSeq).toSeq must beEqualTo(Seq(Seq(0.0, 5.0), Seq(5.0, 0.0)))
    }

    "reject a file with the wrong magic number" >> {
      val path = tempFile(".dipha")
      writeBuf(path, 24) { buf =>
        buf.putLong(123L)
        buf.putLong(7L)
        buf.putLong(1L)
      }
      Dipha.readDistanceMatrix(path) must throwA[IllegalArgumentException]
    }

    "round-trip through writeDistanceMatrix" >> {
      val m = IndexedSeq(IndexedSeq(0.0, 1.0, 2.0), IndexedSeq(1.0, 0.0, 3.0), IndexedSeq(2.0, 3.0, 0.0))
      val path = tempFile(".dipha")
      Dipha.writeDistanceMatrix(path, m)
      Dipha.readDistanceMatrix(path).map(_.toSeq).toSeq must beEqualTo(m.map(_.toSeq))
    }
  }

  "image data" should {
    // Asymmetric shape (3,2), all-distinct values -- a transposed read would still produce a valid-looking
    // grid with the right cell COUNT, just wrong values at specific coordinates, so only checking specific
    // (coordinate -> value) pairs (not just shape/size) actually discriminates this.
    "read a hand-built IMAGE_DATA file with g(1)=3 fastest, g(2)=2 slower" >> {
      val path = tempFile(".dipha")
      // x-fastest order: (i2=0: i1=0,1,2 -> 0,1,2), (i2=1: i1=0,1,2 -> 10,11,12)
      writeBuf(path, 8 + 8 + 8 + 8 + 2 * 8 + 6 * 8) { buf =>
        buf.putLong(Magic)
        buf.putLong(1L)
        buf.putLong(6L) // n
        buf.putLong(2L) // d
        buf.putLong(3L) // g(1)
        buf.putLong(2L) // g(2)
        Seq(0.0, 1.0, 2.0, 10.0, 11.0, 12.0).foreach(buf.putDouble)
      }
      val stream = Dipha.readCubicalGridStream(path)
      (stream.topCellValue(IndexedSeq(0, 0)) must beEqualTo(0.0)) and
        (stream.topCellValue(IndexedSeq(0, 2)) must beEqualTo(2.0)) and
        (stream.topCellValue(IndexedSeq(1, 0)) must beEqualTo(10.0)) and
        (stream.topCellValue(IndexedSeq(1, 2)) must beEqualTo(12.0))
    }

    "round-trip shape/values through writeImageData" >> {
      val shape = IndexedSeq(2, 3) // tda4j's own convention: last axis (3) fastest
      val values = IndexedSeq(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
      val path = tempFile(".dipha")
      Dipha.writeImageData(path, shape, values)
      val (readShape, readValues) = Dipha.readImageData(path)
      (readShape must beEqualTo(shape)) and (readValues must beEqualTo(values))
    }
  }

  "persistence diagrams" should {
    "read a hand-built PERSISTENCE_DIAGRAM file, decoding a negative dim as an essential class of dim -rawDim-1" >> {
      val path = tempFile(".dipha")
      writeBuf(path, 8 + 8 + 8 + 2 * (8 + 8 + 8)) { buf =>
        buf.putLong(Magic)
        buf.putLong(2L)
        buf.putLong(2L) // p
        buf.putLong(-1L) // essential dim 0 (== -(dim)-1 with dim=0)
        buf.putDouble(0.0) // birth
        buf.putDouble(0.0) // death, unspecified/ignored for essential
        buf.putLong(1L) // finite dim 1
        buf.putDouble(1.0)
        buf.putDouble(2.0)
      }
      val bars = Dipha.readPersistenceDiagram(path)
      bars must beEqualTo(
        Seq(
          new PersistenceBar[Double, Nothing](0, ClosedEndpoint(0.0), PositiveInfinity()),
          new PersistenceBar[Double, Nothing](1, ClosedEndpoint(1.0), OpenEndpoint(2.0))
        )
      )
    }

    "round-trip an essential dim-0 bar and a finite dim-2 bar" >> {
      val bars = Seq(
        PersistenceBar[Double](0, 3.0),
        PersistenceBar[Double](2, 1.0, 4.0)
      )
      val path = tempFile(".dipha")
      Dipha.writePersistenceDiagram(path, bars)
      Dipha.readPersistenceDiagram(path) must beEqualTo(bars)
    }
  }
