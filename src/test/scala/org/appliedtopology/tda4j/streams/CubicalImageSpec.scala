package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.specs2.mutable

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class CubicalImageSpec extends mutable.Specification:
  given Double is Field = Field.DoubleApproximated(1e-9)

  "fromFlatArray" should {
    "index row-major, matching shape (2,3)" >> {
      val shape = IndexedSeq(2, 3)
      val values = IndexedSeq(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
      val stream = CubicalImage.fromFlatArray(shape, values)
      (stream.topCellValue(IndexedSeq(0, 0)) must beEqualTo(0.0)) and
        (stream.topCellValue(IndexedSeq(0, 2)) must beEqualTo(2.0)) and
        (stream.topCellValue(IndexedSeq(1, 0)) must beEqualTo(3.0)) and
        (stream.topCellValue(IndexedSeq(1, 2)) must beEqualTo(5.0))
    }
    "reject a mismatched value count" >> {
      CubicalImage.fromFlatArray(IndexedSeq(2, 2), IndexedSeq(0.0, 1.0)) must throwA[IllegalArgumentException]
    }
    "negate values when sublevel = false, the documented superlevel trick" >> {
      val stream = CubicalImage.fromFlatArray(IndexedSeq(2), IndexedSeq(3.0, -5.0), sublevel = false)
      (stream.topCellValue(IndexedSeq(0)) must beEqualTo(-3.0)) and
        (stream.topCellValue(IndexedSeq(1)) must beEqualTo(5.0))
    }
  }

  "fromGrayscale2D and fromVoxelGrid3D" should {
    "agree with the equivalent fromFlatArray call" >> {
      val pixels = Array(Array(0.0, 1.0), Array(2.0, 3.0), Array(4.0, 5.0))
      val viaGrid2D = CubicalImage.fromGrayscale2D(pixels)
      val viaFlat = CubicalImage.fromFlatArray(IndexedSeq(3, 2), IndexedSeq(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
      (0 until 3).forall(i =>
        (0 until 2).forall(j => viaGrid2D.topCellValue(IndexedSeq(i, j)) == viaFlat.topCellValue(IndexedSeq(i, j)))
      ) must beTrue
    }
    "reject ragged rows" >> {
      CubicalImage.fromGrayscale2D(Array(Array(0.0, 1.0), Array(2.0))) must throwA[IllegalArgumentException]
    }
    "flatten a 3D voxel array consistently with fromFlatArray" >> {
      val voxels = Array.tabulate(2, 2, 2)((i, j, k) => (4 * i + 2 * j + k).toDouble)
      val viaVoxels = CubicalImage.fromVoxelGrid3D(voxels)
      val viaFlat = CubicalImage.fromFlatArray(IndexedSeq(2, 2, 2), (0 until 8).map(_.toDouble))
      (for
        i <- 0 until 2
        j <- 0 until 2
        k <- 0 until 2
      yield viaVoxels.topCellValue(IndexedSeq(i, j, k)) == viaFlat.topCellValue(IndexedSeq(i, j, k))).forall(
        identity
      ) must beTrue
    }
  }

  "fromBufferedImage" should {
    def solidPixel(r: Int, g: Int, b: Int): Int = (r << 16) | (g << 8) | b

    "read a pure grayscale pixel's luma as (approximately, given floating point) its own value" >> {
      val img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
      img.setRGB(0, 0, solidPixel(128, 128, 128))
      // 0.299+0.587+0.114 isn't exactly 1.0 in binary floating point, so the three separate multiplies-then-add
      // in `luma` don't land on exactly 128.0 -- beCloseTo, not beEqualTo.
      CubicalImage.fromBufferedImage(img).topCellValue(IndexedSeq(0, 0)) must beCloseTo(128.0, 1e-9)
    }
    "read shape as (width, height)" >> {
      val img = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
      val stream = CubicalImage.fromBufferedImage(img)
      stream.shape must beEqualTo(IndexedSeq(3, 2))
    }
    "apply the ITU-R BT.601 luma formula for a colored pixel" >> {
      val img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
      img.setRGB(0, 0, solidPixel(255, 0, 0))
      CubicalImage.fromBufferedImage(img).topCellValue(IndexedSeq(0, 0)) must beCloseTo(76.245, 1e-9)
    }
  }

  "fromFile" should {
    "round-trip a written PNG file end to end" >> {
      val img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
      img.setRGB(0, 0, 0x000000)
      img.setRGB(1, 0, 0xffffff)
      img.setRGB(0, 1, 0xffffff)
      img.setRGB(1, 1, 0x000000)
      val tmp = File.createTempFile("cubical-image-spec", ".png")
      tmp.deleteOnExit()
      ImageIO.write(img, "png", tmp)
      val stream = CubicalImage.fromFile(tmp.getAbsolutePath)
      (stream.topCellValue(IndexedSeq(0, 0)) must beEqualTo(0.0)) and
        (stream.topCellValue(IndexedSeq(1, 0)) must beEqualTo(255.0)) and
        (stream.topCellValue(IndexedSeq(0, 1)) must beEqualTo(255.0)) and
        (stream.topCellValue(IndexedSeq(1, 1)) must beEqualTo(0.0))
    }
    "reject a non-image file" >> {
      val tmp = File.createTempFile("cubical-image-spec-not-an-image", ".txt")
      tmp.deleteOnExit()
      val writer = new java.io.PrintWriter(tmp)
      writer.write("not an image")
      writer.close()
      CubicalImage.fromFile(tmp.getAbsolutePath) must throwA[IllegalArgumentException]
    }
  }

  "End-to-end: a single bright pixel loaded from an actual image reproduces the hand-verified hollow-3x3 barcode" >> {
    // Same fixture as CubicalStreamSpec's hand-derived "hollow center" test, but built by going through the real
    // BufferedImage/luma path instead of constructing CubicalGridStream's topCellValue function directly -- closes
    // the loop on the actual ask ("convert greyscale images... pixel intensity for the persistence parameter").
    val img = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)
    for
      x <- 0 until 3
      y <- 0 until 3
    do img.setRGB(x, y, if x == 1 && y == 1 then 0xffffff else 0x000000)
    val stream = CubicalImage.fromBufferedImage(img)
    given chc: CubicalHomologyContext[Double, Double] = CubicalHomologyContext()
    import chc.{*, given}
    val barcode = persistentHomology(stream).diagramAt(Double.PositiveInfinity)
    // The white pixel's own luma (birth of the killing cell) isn't exactly 255.0 in binary floating point
    // (0.299+0.587+0.114 != 1.0 exactly), so the H1 bar's death value is found and compared with a tolerance
    // rather than matched via exact tuple equality.
    val h1Bars = barcode.collect { case (1, b, d) => (b, d) }
    (barcode.count { case (0, _, d) => d.isInfinite; case _ => false } must beEqualTo(1)) and
      (h1Bars.count { case (b, d) => d - b > 1.0 } must beEqualTo(1)) and
      (h1Bars.collectFirst { case (0.0, d) if d - 0.0 > 1.0 => d } must beSome(beCloseTo(255.0, 1e-9))) and
      (barcode.count(_._1 >= 2) must beEqualTo(0))
  }
