package org.appliedtopology.tda4j

import org.specs2.mutable.Specification
import scala.language.experimental.modularity

import SpecThrows.throwWith

/** PHAT boundary matrices (ASCII and binary) and Perseus's simplicial toplex formats. */
class PhatPerseusToplexSpec extends Specification:

  private val triangle = ExplicitStreamBuilder.fromFacets(Seq(∆(0, 1, 2)))

  "Phat.fromCells" should {
    "give the boundary matrix of a triangle, columns in filtration order, rows sorted" >> {
      val m = Phat.fromStream(triangle)
      (m.dimensions must beEqualTo(IndexedSeq(0, 0, 0, 1, 1, 1, 2))) and
        (m.columns must beEqualTo(
          IndexedSeq(
            IndexedSeq(),
            IndexedSeq(),
            IndexedSeq(),
            IndexedSeq(0L, 1L),
            IndexedSeq(0L, 2L),
            IndexedSeq(1L, 2L),
            IndexedSeq(3L, 4L, 5L)
          )
        ))
    }
    "satisfy boundary of boundary = 0 over Z/2 on a Vietoris-Rips complex" >> {
      val points = Array.tabulate(16)(i => Array(math.cos(i * 0.9), math.sin(i * 1.3)))
      val vr = VietorisRips(EuclideanMetricSpace(points), maxDimension = 2)
      val m = Phat.fromStream(vr)
      val faceSums = (0 until m.size).map { j =>
        m.columns(j)
          .foldLeft(Map.empty[Long, Int]) { (acc, row) =>
            m.columns(row.toInt).foldLeft(acc)((a, r) => a.updated(r, (a.getOrElse(r, 0) + 1) % 2))
          }
          .values
          .sum
      }
      (m.size must beGreaterThan(100)) and (faceSums.sum must beEqualTo(0)) and
        (m.columns.zipWithIndex.forall((rows, j) => rows.forall(_ < j)) must beTrue)
    }
  }

  "The PHAT matrix of a Vietoris-Rips complex" should
    "reduce, by the standard algorithm over Z/2, to the bars the Persistence verb finds at characteristic 2" >> {
      val points = Array.tabulate(14)(i => Array(math.cos(i * 0.9), math.sin(i * 1.3)))
      val t = 1.0
      val vr = VietorisRips(EuclideanMetricSpace(points), maxDimension = 2, maxFiltrationValue = t)
      val cells = vr.iterator.toIndexedSeq.sortBy(c => (vr.filtrationValue(c), c.dim))
      val m = Phat.fromCells(cells)
      val value = cells.map(vr.filtrationValue)
      val reduced = scala.collection.mutable.ArrayBuffer.empty[scala.collection.immutable.TreeSet[Long]]
      val pivotOwner = scala.collection.mutable.Map.empty[Long, Int]
      for j <- 0 until m.size do
        var col = scala.collection.immutable.TreeSet.from(m.columns(j))
        while col.nonEmpty && pivotOwner.contains(col.last) do
          val other = reduced(pivotOwner(col.last))
          col = (col -- other) ++ (other -- col)
        reduced += col
        if col.nonEmpty then pivotOwner(col.last) = j
      val paired = pivotOwner.map((row, j) => (row.toInt, j)).toSeq
      val finite =
        paired.filter((i, j) => value(i) < value(j)).groupBy((i, _) => m.dimensions(i)).view.mapValues(_.size).toMap
      val essentialColumns = (0 until m.size).filter(j => reduced(j).isEmpty && !pivotOwner.contains(j.toLong))
      val essential = essentialColumns.groupBy(m.dimensions(_)).view.mapValues(_.size).toMap
      val diagram =
        Persistence(EuclideanMetricSpace(points), maxDimension = 2, maxFiltrationValue = t, characteristic = 2)
      val expected = (0 to 2).map { d =>
        val bars = diagram.dim(d).bars
        (d, bars.count(_.upper != PositiveInfinity[Double]()), bars.count(_.upper == PositiveInfinity[Double]()))
      }
      val actual = (0 to 2).map(d => (d, finite.getOrElse(d, 0), essential.getOrElse(d, 0)))
      (expected.map(_._2).sum must beGreaterThan(5)) and (actual must beEqualTo(expected))
    }

  "Phat ASCII and binary" should {
    val m = Phat.Matrix(IndexedSeq(0, 0, 1), IndexedSeq(IndexedSeq(), IndexedSeq(), IndexedSeq(0L, 1L)))
    "write the dimension first, then the rows" >> {
      Phat.formatAscii(m) must beEqualTo("0\n0\n1 0 1\n")
    }
    "skip comments and blank lines and sort the entries of a column" >> {
      Phat.parseAscii("# a comment\n0\n\n0\n1 1 0\n") must beEqualTo(m)
    }
    "name the line of a bad token" >> {
      Phat.parseAscii("0\n1 x\n", "m.txt") must throwWith[IllegalArgumentException](
        "m.txt, line 2: 'x' is not an integer"
      )
    }
    "lay the binary form out as count, then dimension, size and rows of each column, in 64 bits" >> {
      val bytes = Phat.formatBinary(m)
      val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      (bytes.length must beEqualTo(8 + 16 + 16 + 16 + 16)) and (buf.getLong(0) must beEqualTo(3L)) and
        (buf.getLong(8 + 16 + 16) must beEqualTo(1L)) and (buf.getLong(8 + 16 + 16 + 8) must beEqualTo(2L)) and
        (Phat.parseBinary(bytes) must beEqualTo(m))
    }
    "refuse a truncated binary file" >> {
      Phat.parseBinary(Phat.formatBinary(m).dropRight(8), "m.bin") must throwWith[IllegalArgumentException]("m.bin")
    }
  }

  "Perseus simplicial toplex" should {
    "read the manual's example: a 3-simplex given by 2-coordinate vertices, born at 1" >> {
      val facets = Perseus.parseUniformToplex("3\n2\n0 0 0 1 1 0 1 1 1\n")
      facets must beEqualTo(Seq((1.0, ∆(0, 1, 2, 3))))
    }
    "use the single coordinate as the vertex and ignore birth time -1" >> {
      val facets = Perseus.parseUniformToplex("1 1\n5 7 2\n7 9 -1\n7 8 3\n")
      facets must beEqualTo(Seq((2.0, ∆(5, 7)), (3.0, ∆(7, 8))))
    }
    "read the non-uniform form: each simplex starts with its own dimension" >> {
      Perseus.parseNonUniformToplex("1\n2 1 3 5 1\n0 9 4\n1 3 4 2\n") must beEqualTo(
        Seq((1.0, ∆(1, 3, 5)), (4.0, ∆(9)), (2.0, ∆(3, 4)))
      )
    }
    "write what it reads" >> {
      val facets = Seq((1.0, ∆(1, 3, 5)), (4.0, ∆(9)), (2.0, ∆(3, 4)))
      (Perseus.formatNonUniformToplex(facets) must beEqualTo("1\n2 1 3 5 1\n0 9 4\n1 3 4 2\n")) and
        (Perseus.parseNonUniformToplex(Perseus.formatNonUniformToplex(facets)) must beEqualTo(facets)) and
        (Perseus.formatUniformToplex(Seq((1.0, ∆(5, 7)), (3.0, ∆(7, 8)))) must beEqualTo("1\n1\n5 7 1\n7 8 3\n")) and
        (Perseus.formatUniformToplex(facets) must throwWith[IllegalArgumentException]("one dimension"))
    }
    "refuse a file that stops inside a simplex" >> {
      Perseus.parseUniformToplex("2\n1\n1 2 3 1\n4 5\n", "t.txt") must throwWith[IllegalArgumentException](
        "t.txt: the file ends before"
      )
    }
    "give every face the earliest birth of the facets containing it" >> {
      val stream = Perseus.toplexStream(Perseus.parseUniformToplex("1 1\n0 1 5\n1 2 3\n"))
      (stream.filtrationValue(∆(1)) must beEqualTo(3.0)) and (stream.filtrationValue(∆(0)) must beEqualTo(5.0)) and
        (stream.filtrationValue(∆(1, 2)) must beEqualTo(3.0))
    }
  }
