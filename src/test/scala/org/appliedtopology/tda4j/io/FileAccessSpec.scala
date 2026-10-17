package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** The file access every platform gives the formats, the plots and the command line (`FileAccess`: `java.nio.file` on
  * the JVM, Node's `fs` on Scala.js), and the formats on top of it. The same text and the same bytes must come back on
  * both, so the checks are of the bytes a file holds, not only of a round trip.
  */
class FileAccessSpec extends Specification:
  sequential

  private def bytes(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray

  "FileAccess" should {
    "write text as UTF-8 and read it back" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".txt")
      FileAccess.writeText(path, "persistence ∞ — β₁\nsecond line\n")
      (FileAccess.readText(path) must beEqualTo("persistence ∞ — β₁\nsecond line\n"))
        .and(
          FileAccess.readBytes(path).take(11).toSeq must beEqualTo(
            bytes(0x70, 0x65, 0x72, 0x73, 0x69, 0x73, 0x74, 0x65, 0x6e, 0x63, 0x65).toSeq
          )
        )
        .and(
          FileAccess.readBytes(path).slice(12, 15).toSeq must beEqualTo(bytes(0xe2, 0x88, 0x9e).toSeq)
        ) // ∞ is e2 88 9e
    }
    "write bytes exactly, the high ones too" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".bin")
      val all = bytes((0 to 255)*)
      FileAccess.writeBytes(path, all)
      FileAccess.readBytes(path).toSeq must beEqualTo(all.toSeq)
    }
    "read only the head of a file when asked to, and all of a short one" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".txt")
      FileAccess.writeText(path, "0123456789")
      (FileAccess.readHead(path, 4).toSeq must beEqualTo(bytes(0x30, 0x31, 0x32, 0x33).toSeq))
        .and(FileAccess.readHead(path, 100).length must beEqualTo(10))
        .and(FileAccess.readHead(path, 0).length must beEqualTo(0))
    }
    "complete a writer's file when it is closed, and not before" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".txt")
      val out = FileAccess.printWriter(path)
      out.println("a,b")
      out.print("c")
      out.close()
      FileAccess.readText(path).linesIterator.toList must beEqualTo(List("a,b", "c"))
    }
    "know a file from a directory and from nothing" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".txt")
      (FileAccess.isFile(path) must beTrue)
        .and(FileAccess.isFile(path + "-missing") must beFalse)
        .and(FileAccess.isFile(FileAccess.absolute(path + "/..")) must beFalse)
    }
    "create the directories above a path" >> {
      val base = FileAccess.tempFile("tda4j-spec-", ".txt")
      val nested = base + "-dir/one/two/file.txt"
      FileAccess.createParentDirectories(nested)
      FileAccess.writeText(nested, "x")
      FileAccess.readText(nested) must beEqualTo("x")
    }
    "make a path absolute and give a file: address for it" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".html")
      (FileAccess.absolute(path) must beEqualTo(path))
        .and(FileAccess.fileUri(path) must startWith("file:///"))
        .and(FileAccess.fileUri(path) must endWith(".html"))
    }
    "say the file is missing with an IOException" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".txt") + "-missing"
      (FileAccess.readText(path) must throwA[java.io.IOException])
        .and(FileAccess.readBytes(path) must throwA[java.io.IOException])
        .and(FileAccess.readHead(path, 4) must throwA[java.io.IOException])
    }
  }

  "The file formats through FileAccess" should {
    val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))

    "round-trip a point cloud as CSV, Ripser text and OFF" >> {
      val csv = FileAccess.tempFile("tda4j-spec-", ".csv")
      val ripser = FileAccess.tempFile("tda4j-spec-", ".dat")
      val off = FileAccess.tempFile("tda4j-spec-", ".off")
      CSV.writePointCloud(csv, square)
      Ripser.writePointCloud(ripser, square)
      Gudhi.writeOff(off, square)
      (CSV.readPointCloud(csv).map(_.toSeq).toSeq must beEqualTo(square.map(_.toSeq).toSeq))
        .and(Ripser.readPointCloud(ripser).map(_.toSeq).toSeq must beEqualTo(square.map(_.toSeq).toSeq))
        .and(Gudhi.readOff(off).map(_.toSeq).toSeq must beEqualTo(square.map(_.toSeq).toSeq))
        .and(FileAccess.readText(off).linesIterator.take(2).toList must beEqualTo(List("2OFF", "4 0 0")))
    }
    "write a Ripser binary matrix as little-endian float32, the lower triangle only" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".bin")
      val d = Array(Array(0.0, 1.5, 2.0), Array(1.5, 0.0, 0.25), Array(2.0, 0.25, 0.0))
      Ripser.writeBinaryLowerDistanceMatrix(path, d)
      val written = FileAccess.readBytes(path)
      // 1.5f = 0x3fc00000, 2.0f = 0x40000000, 0.25f = 0x3e800000, little-endian after the 8-byte size header
      val floats = written.drop(written.length - 12).toSeq
      (floats must beEqualTo(bytes(0x00, 0x00, 0xc0, 0x3f, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x80, 0x3e).toSeq))
        .and(Ripser.readBinaryLowerDistanceMatrix(path).map(_.toSeq).toSeq must beEqualTo(d.map(_.toSeq).toSeq))
    }
    "write DIPHA files with the magic number first, little-endian" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".complex")
      val d = Array(Array(0.0, 1.0), Array(1.0, 0.0))
      Dipha.writeDistanceMatrix(path, d)
      val written = FileAccess.readBytes(path)
      (written.take(8).toSeq must beEqualTo(bytes(0x00, 0x46, 0xd7, 0xe0, 0x01, 0x00, 0x00, 0x00).toSeq)) // 8067171840
        .and(written.slice(8, 16).toSeq must beEqualTo(bytes(7, 0, 0, 0, 0, 0, 0, 0).toSeq)) // file type 7
        .and(Dipha.readDistanceMatrix(path).map(_.toSeq).toSeq must beEqualTo(d.map(_.toSeq).toSeq))
    }
    "round-trip persistence diagrams, the infinite bars too" >> {
      val bars = Seq(PersistenceBar[Double](0, 0.0), PersistenceBar[Double](1, 0.5, 1.5))
      val csv = FileAccess.tempFile("tda4j-spec-", ".csv")
      val pers = FileAccess.tempFile("tda4j-spec-", ".pers")
      val dipha = FileAccess.tempFile("tda4j-spec-", ".diagram")
      CSV.writePersistenceDiagram(csv, bars)
      Gudhi.writePersistenceDiagram(pers, bars)
      Dipha.writePersistenceDiagram(dipha, bars)
      (CSV.readPersistenceDiagram(csv) must beEqualTo(bars))
        .and(Gudhi.readPersistenceDiagram(pers) must beEqualTo(bars))
        .and(Dipha.readPersistenceDiagram(dipha) must beEqualTo(bars))
    }
    "round-trip a saved result through the JSON file" >> {
      val path = FileAccess.tempFile("tda4j-spec-", ".json")
      val result = matlab.TDA4j.computeFromPoints(square, Array("maxDimension", "1"))
      val saved = result.save(path)
      val loaded = matlab.TDA4j.loadResult(path)
      (saved must beEqualTo(FileAccess.absolute(path)))
        .and(FileAccess.readText(path) must startWith("{"))
        .and(loaded.toArray().map(_.toSeq).toSeq must beEqualTo(result.toArray().map(_.toSeq).toSeq))
        .and(matlab.TDA4j.loadResult(path + "-missing") must throwA[java.io.IOException])
    }
  }
