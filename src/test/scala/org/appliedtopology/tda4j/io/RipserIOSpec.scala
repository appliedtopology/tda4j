package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.streams.{given, *}

import org.specs2.mutable
import java.io.File
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Paths}

/** Discriminating tests for `Ripser.scala`, per the ordering derivation in `.claude/WORKLOG-io-module.md`: every
  * fixture here uses all-distinct values specifically so a row/column or lower/upper mixup would change the result, not
  * just reorder equal entries.
  */
class RipserIOSpec extends mutable.Specification:

  private def tempFile(suffix: String): String =
    val f = File.createTempFile("ripser-io-spec", suffix)
    f.deleteOnExit()
    f.getAbsolutePath

  "point clouds" should
    "round-trip through readPointCloud/writePointCloud" >> {
      val points = Seq(Seq(1.0, 2.0), Seq(3.0, 4.0), Seq(5.0, 6.0))
      val path = tempFile(".txt")
      Ripser.writePointCloud(path, points)
      Ripser.readPointCloud(path).map(_.toSeq).toSeq must beEqualTo(points)
    }

  "lower-distance text format" should {
    "read row i (1 until n) as d(i,0),...,d(i,i-1), flat across line breaks" >> {
      val path = tempFile(".txt")
      Files.write(Paths.get(path), "1\n2,3\n".getBytes)
      val m = Ripser.readLowerDistanceMatrix(path)
      (m(1)(0) must beEqualTo(1.0)) and (m(2)(0) must beEqualTo(2.0)) and (m(2)(1) must beEqualTo(3.0))
    }

    "round-trip through writeLowerDistanceMatrix" >> {
      val m = IndexedSeq(IndexedSeq(0.0, 1.0, 2.0), IndexedSeq(1.0, 0.0, 3.0), IndexedSeq(2.0, 3.0, 0.0))
      val path = tempFile(".txt")
      Ripser.writeLowerDistanceMatrix(path, m)
      Ripser.readLowerDistanceMatrix(path).map(_.toSeq).toSeq must beEqualTo(m.map(_.toSeq))
    }
  }

  "upper-distance text format" should {
    "read row i (0 until n-1) as d(i,i+1),...,d(i,n-1) -- a DIFFERENT flat order than lower-distance" >> {
      // n=3 would NOT discriminate here: lower's pair order ({0,1},{0,2},{1,2}) and upper's ({0,1},{0,2},{1,2})
      // happen to coincide for exactly 3 points, a small-n coincidence, not evidence the two conventions agree
      // in general. n=4 (6 distinct values) is the smallest case where they provably diverge: lower's 4th pair
      // is {1,2} while upper's 4th pair is {0,3}.
      val path = tempFile(".txt")
      Files.write(Paths.get(path), "1,2,3,4,5,6".getBytes)
      val upper = Ripser.readUpperDistanceMatrix(path)
      val lower = Ripser.readLowerDistanceMatrix(path)
      (upper(0)(1) must beEqualTo(1.0)) and
        (upper(0)(2) must beEqualTo(2.0)) and
        (upper(0)(3) must beEqualTo(3.0)) and
        (upper(1)(2) must beEqualTo(4.0)) and
        (upper(1)(3) must beEqualTo(5.0)) and
        (upper(2)(3) must beEqualTo(6.0)) and
        (lower(1)(2) must beEqualTo(3.0)) and // same raw tokens, genuinely different matrix under "lower"
        (upper.map(_.toSeq).toSeq must not(beEqualTo(lower.map(_.toSeq).toSeq)))
    }

    "round-trip through writeUpperDistanceMatrix" >> {
      val m = IndexedSeq(IndexedSeq(0.0, 1.0, 2.0), IndexedSeq(1.0, 0.0, 3.0), IndexedSeq(2.0, 3.0, 0.0))
      val path = tempFile(".txt")
      Ripser.writeUpperDistanceMatrix(path, m)
      Ripser.readUpperDistanceMatrix(path).map(_.toSeq).toSeq must beEqualTo(m.map(_.toSeq))
    }
  }

  "dense distance-matrix text format (only the lower triangle is read)" should
    "read line i's first i values, ignoring anything at or past the diagonal" >> {
      val path = tempFile(".txt")
      // Line 0: nothing needed (0 values read). Line 1: "1 99" -- only "1" (the first 1 value) is read, "99"
      // (which would be the diagonal) is ignored. Line 2: "2 3 99" -- only "2 3" read.
      Files.write(Paths.get(path), "0\n1 99\n2 3 99\n".getBytes)
      val m = Ripser.readDistanceMatrix(path)
      (m(1)(0) must beEqualTo(1.0)) and (m(2)(0) must beEqualTo(2.0)) and (m(2)(1) must beEqualTo(3.0))
    }

  "binary (packed) lower-distance format" should {
    // Byte-level fixture: hand-built little-endian float32 bytes, not round-trip -- a round trip would pass
    // even with the wrong element width (write-and-read-back the same wrong thing).
    "read 3 hand-built little-endian float32 values with no header" >> {
      val path = tempFile(".bin")
      val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
      buf.putFloat(1.5f)
      buf.putFloat(2.5f)
      buf.putFloat(3.5f)
      Files.write(Paths.get(path), buf.array())
      val m = Ripser.readBinaryLowerDistanceMatrix(path)
      (m(1)(0) must beEqualTo(1.5)) and (m(2)(0) must beEqualTo(2.5)) and (m(2)(1) must beEqualTo(3.5))
    }

    "round-trip through writeBinaryLowerDistanceMatrix, at float32 precision" >> {
      val m = IndexedSeq(IndexedSeq(0.0, 1.5, 2.5), IndexedSeq(1.5, 0.0, 3.5), IndexedSeq(2.5, 3.5, 0.0))
      val path = tempFile(".bin")
      Ripser.writeBinaryLowerDistanceMatrix(path, m)
      Ripser.readBinaryLowerDistanceMatrix(path).map(_.toSeq).toSeq must beEqualTo(m.map(_.toSeq))
    }

    "agree with the text lower-distance format on the same (float32-representable) matrix" >> {
      val m = IndexedSeq(IndexedSeq(0.0, 1.5, 2.5), IndexedSeq(1.5, 0.0, 3.5), IndexedSeq(2.5, 3.5, 0.0))
      val binPath = tempFile(".bin")
      val textPath = tempFile(".txt")
      Ripser.writeBinaryLowerDistanceMatrix(binPath, m)
      Ripser.writeLowerDistanceMatrix(textPath, m)
      Ripser.readBinaryLowerDistanceMatrix(binPath).map(_.toSeq).toSeq must
        beEqualTo(Ripser.readLowerDistanceMatrix(textPath).map(_.toSeq).toSeq)
    }
  }

  "malformed triangular counts" should
    "fail loudly instead of silently flooring to a smaller n" >> {
      val path = tempFile(".txt")
      // 4 values is not n*(n-1)/2 for any integer n (n=3 -> 3 values, n=4 -> 6 values).
      Files.write(Paths.get(path), "1,2,3,4".getBytes)
      Ripser.readLowerDistanceMatrix(path) must throwA[IllegalArgumentException]
    }
