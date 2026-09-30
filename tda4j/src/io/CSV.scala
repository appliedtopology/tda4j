package org.appliedtopology.tda4j

/** Loading and saving file formats used across the TDA ecosystem: this package's own `CSV` (generic delimited-text
  * point clouds, distance matrices, and persistence diagrams -- what `RipserPaperBenchmarkSpec` used to hand-roll ad
  * hoc, now routed through here instead), plus adaptors for the formats other major TDA projects actually use: Ripser's
  * point-cloud/distance-matrix/packed-binary formats (`Ripser.scala`), DIPHA's binary container format (`Dipha.scala`),
  * GUDHI's OFF point-cloud and `.pers` diagram formats (`Gudhi.scala`), and Perseus's cubical toplex format
  * (`Perseus.scala`, also what GUDHI's own cubical-complex module reads). Dionysus and JavaPlex need no dedicated
  * adaptor: both consume the same plain whitespace/comma-separated point-cloud and distance-matrix text `CSV`/`Ripser`
  * already read.
  *
  * Every format here that has a primary source (a project's own source code, not a secondhand description) was verified
  * against that source directly before being implemented -- see `.claude/WORKLOG-io-module.md` for exactly what was
  * checked and where, including two real, easy-to-get-backwards details that would have silently produced a
  * plausible-looking wrong answer rather than an error: Ripser's binary format is packed 32-bit `float`, not `double`;
  * and DIPHA's/Perseus's cubical grid axis order is the OPPOSITE of `CubicalImage.fromFlatArray`'s own convention.
  * Formats deliberately NOT implemented here (Perseus's simplicial toplex format, PHAT's boundary-matrix format) are
  * left out because no such verification was done for them, not because they're unimportant -- a wrong parser is worse
  * than a missing one.
  *
  * '''Layering''': this package is a leaf. It imports `streams` (for `EuclideanMetricSpace`/`ExplicitMetricSpace`/
  * `CubicalGridStream`) and `barcode` (for `PersistenceBar`); nothing outside `io` imports `io` back.
  */
package io

import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.{given, *}

import scala.io.Source
import java.io.PrintWriter

/** Generic delimited-text point clouds, distance matrices, and persistence diagrams -- fields split on `,` or any run
  * of whitespace, so plain-whitespace-separated files (Dionysus/JavaPlex's own convention) parse with these same
  * methods too.
  */
object CSV:
  private val delimiterRegex = "[\\s,]+"

  private def readNumericRows(path: String): IndexedSeq[IndexedSeq[Double]] =
    val src = Source.fromFile(path)
    try
      src
        .getLines()
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(line => line.split(delimiterRegex).toIndexedSeq.map(_.toDouble))
        .toIndexedSeq
    finally src.close()

  private def writeRows(path: String, rows: Iterable[Iterable[Double]]): Unit =
    val out = new PrintWriter(path)
    try rows.foreach(row => out.println(row.mkString(",")))
    finally out.close()

  /** One point per line, coordinates separated by `,` or whitespace. */
  def readPointCloud(path: String): Array[Array[Double]] =
    readNumericRows(path).map(_.toArray).toArray

  def readEuclideanMetricSpace(path: String): EuclideanMetricSpace =
    EuclideanMetricSpace(readPointCloud(path))

  def writePointCloud(path: String, points: Array[Array[Double]]): Unit =
    writeRows(path, points.toIndexedSeq.map(_.toIndexedSeq))

  /** A full `n x n` distance matrix, one row per line -- both triangles and the diagonal are read as given; this does
    * NOT check that they agree (a caller wanting that checked should build from `readLowerTriangularDistanceMatrix`
    * instead, which only ever reads one triangle).
    */
  def readFullDistanceMatrix(path: String): Array[Array[Double]] =
    readNumericRows(path).map(_.toArray).toArray

  def readExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readFullDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def writeFullDistanceMatrix(path: String, matrix: Array[Array[Double]]): Unit =
    writeRows(path, matrix.toIndexedSeq.map(_.toIndexedSeq))

  /** Row `i` (`i = 1 until n`) has exactly `i` entries, `d(i,0),...,d(i,i-1)` -- no diagonal, no upper triangle, and
    * row `0` (which would have zero entries) is omitted entirely rather than written as a blank line -- so an `n`-point
    * matrix is `n-1` physical lines, the same row layout Ripser's own text formats use, just one row per physical line
    * instead of one flat token stream spanning line breaks (see `Ripser.readLowerDistanceMatrix`, which reads the
    * identical convention).
    */
  def readLowerTriangularDistanceMatrix(path: String): Array[Array[Double]] =
    val rows = readNumericRows(path)
    DistanceMatrices.expandLowerTriangular(rows.flatten, rows.size + 1)

  def readLowerTriangularExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readLowerTriangularDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def writeLowerTriangularDistanceMatrix(path: String, matrix: Array[Array[Double]]): Unit =
    val out = new PrintWriter(path)
    try for i <- 1 until matrix.size do out.println((0 until i).map(j => matrix(i)(j)).mkString(","))
    finally out.close()

  /** `dim,birth,death` per line, no header, no comments -- `death` (or `birth`) may be `inf`/`-inf`/`infinity` (any
    * case) for an infinite endpoint. See `Gudhi.readPersistenceDiagram` for a format that also supports comments and an
    * optional coefficient-field column.
    */
  def readPersistenceDiagram(path: String): Seq[PersistenceBar[Double, Nothing]] =
    val src = Source.fromFile(path)
    try
      src
        .getLines()
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { line =>
          val fields = line.split(delimiterRegex)
          require(fields.length == 3, s"expected 3 fields (dim,birth,death), got ${fields.length} in line '$line'")
          Endpoints.toBar(fields(0).toInt, fields(1), fields(2))
        }
        .toSeq
    finally src.close()

  def writePersistenceDiagram(path: String, bars: Seq[PersistenceBar[Double, ?]]): Unit =
    val out = new PrintWriter(path)
    try
      bars.foreach { bar =>
        out.println(s"${bar.dim},${Endpoints.formatEndpoint(bar.lower)},${Endpoints.formatEndpoint(bar.upper)}")
      }
    finally out.close()
